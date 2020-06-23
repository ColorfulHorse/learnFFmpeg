//
// Created by green sun on 2020/3/4.
//

#ifndef LYJPLAYER_PLAYER_H
#define LYJPLAYER_PLAYER_H

#include <jni.h>
#include <string>
#include <ThreadPool.h>
#include <android/native_window.h>
#include <LinkedBlockingQueue.h>
#include <constant.h>
#include <Timer.h>


#ifdef __cplusplus
extern "C" {
#endif

#include <libavformat/avformat.h>
#include <libswscale/swscale.h>

#ifdef __cplusplus
}
#endif

using namespace std;

struct FrameData {
    AVFrame *frame;
    uint8_t *buffer;
};

class LyjPlayer {
private:
    const char *url;
    int width = 0;
    int height = 0;
    atomic_bool playing = {false};
    // AVFormatContext用于解封装 flv,avi,rmvb,mp4
    AVFormatContext *formatContext = nullptr;
    AVCodecContext *codecContext = nullptr;
    // AVPacket是存储压缩编码数据相关信息的结构体
//    AVPacket *packet = nullptr;
//    // 解码后/压缩前的数据
    int buffer_size;
    AVFrame *frame = nullptr, *temp = nullptr;
    AVPacket *packet = nullptr;
    SwsContext *sws_context = nullptr;
    uint8_t *buffer = nullptr;
    ANativeWindow_Buffer windowBuffer;
    thread task;
    // 记录帧编号
    int index;
    // 网络接收的缓冲
    LinkedBlockingQueue<FrameData> queue;
    Timer timer;

    int decodeFrame();

    int render();

    int destroyPlay();

    void callbackState(JNIEnv *env, PlayState state);

    void callbackError(JNIEnv *env, PlayError error);
public:
    JavaVM *vm = nullptr;
    jobject callback = nullptr;
    ANativeWindow *window = nullptr;

    LyjPlayer();

    int init();

    void startPlay(const char *url);

    int stopPlay();

    void release();

    virtual ~LyjPlayer();

};
#endif

