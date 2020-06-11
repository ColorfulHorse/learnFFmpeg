//
// Created by green sun on 2020/3/4.
//
#include <publisher.h>
#include <logger.h>
#include <thread>

#ifdef __cplusplus

extern "C" {
#endif

#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libyuv.h>
#include <time.h>

#ifdef __cplusplus
}
#endif

Publisher::Publisher() = default;

/**
 * 开始推流
 * @param path
 * @param width
 * @param height
 * @return
 */
int Publisher::startPublish(const char *path, int width, int height, int orientation) {
    if (initing.load() || running.load()) {
        LOGE("已经在推流");
        return -1;
    }
    this->path = path;
    this->width = orientation % 180 == 0 ? width:height;
    this->height = orientation % 180 == 0 ? height:width;
    this->orientation = orientation;
    pool = new ThreadPool(8);
    running = true;
    initing = true;
    worker = thread([=]() {
        JNIEnv *env = nullptr;
        int ret = vm->AttachCurrentThread(&env, nullptr);
        ret = initPublish(env);
        initing = false;
        if (ret == 0) {
            while (running.load()) {
                unsigned char *buffer = nullptr;
                buffer = dataPool.pop();
                if (buffer) {
                    // 解码后/压缩前的数据
                    AVFrame *frame = nullptr;
                    // 获取源图像字节大小，1byte内存对齐 yuv420P YYYYUV
                    int pic_size = av_image_get_buffer_size(codecContext->pix_fmt,
                                                            codecContext->width,
                                                            codecContext->height, 1);
                    // 创建缓冲区
                    uint8_t *pic_buf = (uint8_t *) (av_malloc(static_cast<size_t>(pic_size)));
                    // 创建编码数据
                    frame = av_frame_alloc();
                    frame->format = codecContext->pix_fmt;
                    frame->width = codecContext->width;
                    frame->height = codecContext->height;
                    // 格式化缓冲区内存
                    // dst_data: 格式化通道如rgb三通道
                    av_image_fill_arrays(frame->data, frame->linesize, pic_buf,
                                         codecContext->pix_fmt,
                                         codecContext->width, codecContext->height, 1);
                    int32_t ysize = width * height;
                    int32_t usize = (width / 2) * (height / 2);
                    const uint8_t *sy = buffer;
                    const uint8_t *su = buffer + ysize;
                    const uint8_t *sv = buffer + ysize + usize;

                    uint8_t *ty = pic_buf;
                    uint8_t *tu = pic_buf + ysize;
                    uint8_t *tv = pic_buf + ysize + usize;

                    // 旋转
                    libyuv::I420Rotate(sy, this->height, su, this->height >> 1, sv,
                                       this->height >> 1,
                                       ty, this->width, tu, this->width >> 1, tv, this->width >> 1,
                                       this->height, this->width,
                                       (libyuv::RotationMode) orientation);
                    frame->data[0] = ty;
                    frame->data[1] = tu;
                    frame->data[2] = tv;

                    chrono::system_clock::time_point start = chrono::system_clock::now();
                    encodeFrame(frame);
                    chrono::system_clock::time_point finish = chrono::system_clock::now();
                    LOGE("encode time: %lf",
                         chrono::duration_cast<chrono::duration<double, ratio<1, 1000>>>(
                                 finish - start).count());
                    delete[] buffer;
                }
            }
        }
        vm->DetachCurrentThread();
        destroyPublish();
    });
    worker.detach();
    return 0;
}

/**
 * 初始化推流配置
 * @return
 */
int Publisher::initPublish(JNIEnv *env) {
    int ret = avformat_network_init();
    AVCodec *codec = nullptr;
    // 根据输出文件名创建AVFormatContext
    avformat_alloc_output_context2(&formatContext, nullptr, "flv", path);
    // 获取编码器
    codec = avcodec_find_encoder(AV_CODEC_ID_H264);
    codecContext = avcodec_alloc_context3(codec);
    codecContext->codec_id = codec->id;
    codecContext->codec_type = AVMEDIA_TYPE_VIDEO;
    codecContext->pix_fmt = AV_PIX_FMT_YUV420P;
    codecContext->width = width;
    codecContext->height = height;
    // 码率
    codecContext->bit_rate = 144 * 1024;
    // i帧间隔
    codecContext->gop_size = 20;
    // 量化
    codecContext->qmin = 10;
    codecContext->qmax = 51;
    // 两个非B帧之间的最大B帧数
    codecContext->max_b_frames = 3;
    // 时间基 1/25 秒
    codecContext->time_base = AVRational{1, fps};
    codecContext->framerate = AVRational{fps, 1};
    // codecContext->thread_count = 4;

    AVDictionary *param = nullptr;
    av_dict_set(&param, "rotate", "90", 0);
    if (codecContext->codec_id == AV_CODEC_ID_H264) {
        // 编码速度和质量的平衡
        // "ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow", "placebo"
        av_dict_set(&param, "preset", "veryfast", 0);
        // 主要配合视频类型和视觉优化的参数
        // film：  电影、真人类型；
        // animation：  动画；
        // grain：      需要保留大量的grain时用；
        // stillimage：  静态图像编码时使用；
        // psnr：      为提高psnr做了优化的参数；
        // ssim：      为提高ssim做了优化的参数；
        // fastdecode： 可以快速解码的参数；
        // zerolatency：零延迟，用在需要非常低的延迟的情况下，比如电视电话会议的编码。
        av_dict_set(&param, "tune", "zerolatency", 0);
    }

    if (codecContext->codec_id == AV_CODEC_ID_H265) {
        av_dict_set(&param, "preset", "ultrafast", 0);
        av_dict_set(&param, "tune", "zero-latency", 0);
    }

    // 打印封装格式信息
    av_dump_format(formatContext, 0, path, 1);


    // 初始化编码器
    if (avcodec_open2(codecContext, codec, &param) < 0) {
        return -1;
    }
    //new一个流并挂到fmt_ctx名下，调用avformat_free_context时会释放该流
    stream = avformat_new_stream(formatContext, nullptr);
    if (stream == nullptr) {
        return -1;
    }
    // 复制解码配置到码流配置
    avcodec_parameters_from_context(stream->codecpar, codecContext);
    // 打开输出流
    ret = avio_open(&formatContext->pb, path, AVIO_FLAG_READ_WRITE);
    if (ret < 0) {
        char *err = av_err2str(ret);
        LOGE("打开输出流失败, err:%s", err);
        callbackError(env, PublishError::CONNECT_ERROR);
        return -1;
    }
    callbackState(env, PublishState::CONNECTED);
    LOGE("打开输出流成功");
    // 写视频文件头
    ret = avformat_write_header(formatContext, nullptr);
    if (ret == 0) {
        callbackState(env, PublishState::START);
    } else {
        callbackError(env, PublishError::UNKNOW);
        return ret;
    }
    is_publish = true;
    return ret;
}


int Publisher::destroyPublish() {
    LOGE("destroyPublish");
    index = 0;
    if (is_publish.load()) {
        int ret = encodeFrame(nullptr);
        if (ret == 0) {
            // 写文件尾
            av_write_trailer(formatContext);
        }
    }
    is_publish = false;
    running = false;
    if (pool) {
        delete pool;
        pool = nullptr;
    }

    if (pic_buf) {
        av_free(pic_buf);
        pic_buf = nullptr;
    }

    if (formatContext) {
        avio_close(formatContext->pb);
        avformat_free_context(formatContext);
        formatContext = nullptr;
    }
    return 0;
}

int Publisher::release() {
    vm = nullptr;
    return 0;
}

int Publisher::pushData(unsigned char *buffer) {
    if (is_publish.load()) {
        lock_guard<mutex> lock(pool_mutex);
        //dataPool.emplace(buffer);
        dataPool.push(buffer);
    }
    return 0;
}

int Publisher::encodeFrame(AVFrame *frame) {
    // 编码一帧数据
    int ret = avcodec_send_frame(codecContext, frame);
    if (ret != 0) {
        LOGE("encode error ", av_err2str(ret));
        return -1;
    }
    while (ret >= 0) {
        AVPacket *packet = nullptr;
        packet = av_packet_alloc();
        int pic_size = av_image_get_buffer_size(codecContext->pix_fmt,
                                                codecContext->width,
                                                codecContext->height, 1);
        av_new_packet(packet, pic_size);
        // 读编码完成的数据 某些解码器可能会消耗部分数据包而不返回任何输出，因此需要在循环中调用此函数，直到它返回EAGAIN
        ret = avcodec_receive_packet(codecContext, packet);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            // 读完
            av_packet_free(&packet);
            av_frame_free(&frame);
            return 0;
        } else if (ret < 0) {
            LOGE("ENCODE ERROR CODE %d", ret);
            av_packet_free(&packet);
            av_frame_free(&frame);
            return -1;
        }
        //frame->pts = index;
        packet->stream_index = stream->index;
        // flv mp4一般时间基为 1/1000
        // 将codec的pts转换为mux层的pts
        //packet->pts = index * (stream->time_base.den) / ((stream->time_base.num) * fps);
        int64_t pts = av_rescale_q_rnd(index, codecContext->time_base, stream->time_base,
                                       AV_ROUND_NEAR_INF);
        packet->pts = pts;
        packet->dts = packet->pts;
        // 25帧 1000/25 = 40
        packet->duration = (stream->time_base.den) / ((stream->time_base.num) * fps);
        packet->pos = -1;
        if (frame) {
            av_frame_free(&frame);
        }
        int64_t frame_index = index;
        pool->enqueue([=]() mutable {
            AVRational time_base = formatContext->streams[0]->time_base; //{ 1, 1000 };
            LOGI("Send frame index:%lld,pts:%lld,dts:%lld,duration:%lld,time_base:%d,%d,size:%d",
                 (int64_t) frame_index,
                 (int64_t) packet->pts,
                 (int64_t) packet->dts,
                 (int64_t) packet->duration,
                 time_base.num, time_base.den,
                 packet->size);
            // 将解码完的数据包写入输出
            long start = clock();
            LOGE("start write a frame");
            int code = av_interleaved_write_frame(formatContext, packet);
            long end = clock();
            LOGE("send time: %ld", end - start);
            if (code != 0) {
                LOGE("av_interleaved_write_frame failed");
            }
            av_packet_free(&packet);
        });
        index++;
    }
    return 0;
}

bool Publisher::isPublish() {
    return is_publish;
}

int Publisher::stopPublish() {
    if (running.load()) {
        running = false;
        if (worker.joinable()) {
            worker.join();
        }
    } else {
        destroyPublish();
    }
    dataPool.clear();
    return 0;
}

void Publisher::callbackState(JNIEnv *env, PublishState state) {
    if (env) {
        jclass cls = env->GetObjectClass(callback);
        jmethodID jmethodId = env->GetMethodID(cls, "onState", "(I)V");
        env->CallVoidMethod(callback, jmethodId, state);
    }
}

void Publisher::callbackError(JNIEnv *env, PublishError error) {
    if (env) {
        jclass cls = env->GetObjectClass(callback);
        jmethodID jmethodId = env->GetMethodID(cls, "onError", "(I)V");
        env->CallVoidMethod(callback, jmethodId, error);
    }
}






