//
// Created by hunny on 2020/5/25.
//
#include <LyjPlayer.h>
#include <logger.h>
#include <ThreadPool.h>

#ifdef __cplusplus
extern "C" {
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include "libavcodec/jni.h"
#ifdef __cplusplus
}
#endif


LyjPlayer::LyjPlayer() = default;

LyjPlayer::~LyjPlayer() {

}

int LyjPlayer::init() {
    av_jni_set_java_vm(vm, 0);
    return 0;
}

void LyjPlayer::startPlay(const char *url) {
    ThreadPool *pool = new ThreadPool(8);
    this->url = url;
    playing = true;
    if (task.joinable()) {
        task.join();
    }
    // 取流线程
    task = thread([this] {
        JNIEnv *env = nullptr;
        int ret = vm->AttachCurrentThread(&env, nullptr);
        avformat_network_init();
        formatContext = avformat_alloc_context();
        //AVInputFormat *iformat = av_find_input_format("h264");
        // 打开文件
        LOGE("正在连接");
        ret = avformat_open_input(&formatContext, this->url, nullptr, nullptr);
        if (ret < 0) {
            LOGE("打开文件失败code:%d msg:%s", ret, av_err2str(ret));
            callbackError(env, PlayError::CONNECT_TIMEOUT);
            vm->DetachCurrentThread();
            destroyPlay();
            return ret;
        }
        callbackState(env, PlayState::CONNECTED);
        LOGE("连接到流媒体成功");
        ret = avformat_find_stream_info(formatContext, nullptr);
        if (ret < 0) {
            LOGE("查找流失败 %s", av_err2str(ret));
            callbackError(env, PlayError::ERROR_STREAM);
            vm->DetachCurrentThread();
            destroyPlay();
            return ret;
        }
        int index = -1;
        for (int i = 0; i < formatContext->nb_streams; i++) {
            if (formatContext->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            LOGE("没有视频流");
            callbackError(env, PlayError::NONE_VIDEO_STREAM);
            vm->DetachCurrentThread();
            destroyPlay();
            return -1;
        }
        AVStream *videoStream = formatContext->streams[index];
        AVCodecParameters *params = videoStream->codecpar;
        LOGE("AVCodecParameters id:%d, width:%d, height%d", params->codec_id, params->width,
             params->height);
        // 查找解码器
        AVCodecID codecId = videoStream->codecpar->codec_id;
        AVCodec *codec = nullptr;
        // h264硬解
        if (codecId == AV_CODEC_ID_H264) {
            codec = avcodec_find_decoder_by_name("h264_mediacodec");
            if (codec == nullptr) {
                LOGE("can not find mediacodec");
                codec = avcodec_find_decoder(codecId);
            } else {
                LOGE("使用硬解");
            }
        }
        if (codec == nullptr) {
            LOGE("找不到解码器");
            callbackError(env, PlayError::UNKNOW);
            vm->DetachCurrentThread();
            destroyPlay();
            return -1;
        }
        codecContext = avcodec_alloc_context3(codec);
        // 复制码流配置到解码器
        avcodec_parameters_to_context(codecContext, videoStream->codecpar);
        ret = avcodec_open2(codecContext, codec, nullptr);
        if (ret < 0) {
            LOGE("初始化解码器失败:%s", av_err2str(ret));
            callbackError(env, PlayError::UNKNOW);
            vm->DetachCurrentThread();
            destroyPlay();
            return -1;
        }
        this->width = codecContext->width;
        this->height = codecContext->height;
        buffer_size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, width, height, 1);
        temp = av_frame_alloc();
        packet = av_packet_alloc();
        // 创建格式转换方式
        sws_context = sws_getContext(width, height, codecContext->pix_fmt, width,
                                     height, AV_PIX_FMT_RGBA, SWS_BICUBIC,
                                     nullptr, nullptr, nullptr);
        // 设置窗口参数
        if (ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888) < 0) {
            callbackError(env, PlayError::UNKNOW);
            vm->DetachCurrentThread();
            destroyPlay();
            LOGE("初始化播放窗口失败");
            return -1;
        }
        double fps = av_q2d(videoStream->avg_frame_rate);
        AVRational timebase = videoStream->time_base;
        // 每一帧持续时间 毫秒
        int duration = static_cast<int>(timebase.den / timebase.num / fps);
        LOGE("videoStream FPS %lf, duration %d", fps, duration);
        // 定时绘制，保持帧率
        timer.setInterval([this] {
            render();
        }, duration);
        while (playing) {
            // 读流放入队列
            ret = av_read_frame(formatContext, packet);
            if (ret < 0) {
                continue;
            }
            if (packet->stream_index == index) {
                decodeFrame();
            }
            av_packet_unref(packet);
        }
        vm->DetachCurrentThread();
        // stopPlay();
        return 0;
    });
}

// 解码
int LyjPlayer::decodeFrame() {
    int ret = avcodec_send_packet(codecContext, packet);
    if (ret == AVERROR(EAGAIN)) {
        ret = 0;
    } else if (ret < 0) {
        LOGE("avcodec_send_packet err code: %d, msg:%s", ret, av_err2str(ret));
        av_packet_free(&packet);
        vm->DetachCurrentThread();
        destroyPlay();
        return -1;
    }
    LOGE("send a packet");
    while (ret >= 0) {
        ret = avcodec_receive_frame(codecContext, temp);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            return 0;
        } else if (ret < 0) {
            LOGE("avcodec_receive_frame error %s", av_err2str(ret));
            av_packet_free(&packet);
            vm->DetachCurrentThread();
            destroyPlay();
            return -1;
        }
        AVFrame * frame = av_frame_alloc();
        uint8_t *buffer = static_cast<uint8_t *>(av_malloc(buffer_size));
        av_image_fill_arrays(frame->data, frame->linesize, buffer, AV_PIX_FMT_RGBA, width, height,
                             1);
        sws_scale(sws_context, temp->data, temp->linesize, 0, codecContext->height,
                  frame->data, frame->linesize);
        FrameData frameData = {frame, buffer};
        queue.push(frameData);
    }
    return ret;
}

// 绘制
int LyjPlayer::render() {
    int ret = 0;
    JNIEnv *env = nullptr;
    FrameData frameData = queue.pop();
    AVFrame *frame = frameData.frame;
    uint8_t *buffer = frameData.buffer;
    // 开始解码第一帧回调
    if (index == 0) {
        ret = vm->AttachCurrentThread(&env, nullptr);
        callbackState(env, PlayState::START);
    }
    index++;
    ret = ANativeWindow_lock(window, &windowBuffer, nullptr);
    if (ret < 0) {
        LOGE("cannot lock window");
    } else {
        //逐行复制
        uint8_t *bufferBits = (uint8_t *) windowBuffer.bits;
        // 四通道所以*4
        for (int h = 0; h < height; h++) {
            memcpy(bufferBits + h * windowBuffer.stride * 4,
                   buffer + h * frame->linesize[0],
                   static_cast<size_t>(frame->linesize[0]));
            // LOGI("复制%d到%d", h * windowBuffer.stride * 4, h * frame->linesize[0]);
        }
        ANativeWindow_unlockAndPost(window);
    }

    av_free(buffer);
    av_frame_free(&frame);
    if (env) {
        vm->DetachCurrentThread();
    }
    return ret;
}

void LyjPlayer::callbackState(JNIEnv *env, PlayState state) {
    if (env) {
        jclass cls = env->GetObjectClass(callback);
        jmethodID jmethodId = env->GetMethodID(cls, "onState", "(I)V");
        env->CallVoidMethod(callback, jmethodId, state);
    }
}

void LyjPlayer::callbackError(JNIEnv *env, PlayError error) {
    if (env) {
        jclass cls = env->GetObjectClass(callback);
        jmethodID jmethodId = env->GetMethodID(cls, "onError", "(I)V");
        env->CallVoidMethod(callback, jmethodId, error);
    }
}

int LyjPlayer::stopPlay() {
    LOGE("stopPlay");
    playing = false;
    if (task.joinable()) {
        task.join();
    }
    destroyPlay();
    return 0;
}

int LyjPlayer::destroyPlay() {
    LOGE("destroyPlay");
    playing = false;
    timer.stop();
    queue.clear();
    index = 0;
    if (sws_context) {
        sws_freeContext(sws_context);
        sws_context = nullptr;
    }
    if (buffer) {
        av_free(buffer);
        buffer = nullptr;
    }
    if (packet) {
        av_packet_free(&packet);
        packet = nullptr;
    }
    if (frame) {
        av_frame_free(&frame);
        frame = nullptr;
    }
    if (temp) {
        av_frame_free(&temp);
        temp = nullptr;
    }
    if (formatContext) {
        avformat_close_input(&formatContext);
        avformat_free_context(formatContext);
        formatContext = nullptr;
    }
    return 0;
}

void LyjPlayer::release() {
    stopPlay();
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
    vm = nullptr;
}

