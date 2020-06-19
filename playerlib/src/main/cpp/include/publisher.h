//
// Created by green sun on 2020/3/4.
//

#ifndef LYJPLAYER_PUBLISHER_H
#define LYJPLAYER_PUBLISHER_H

#include <jni.h>
#include <string>
#include <LinkedBlockingQueue.h>
#include <ThreadPool.h>
#include <constant.h>

#ifdef __cplusplus
extern "C" {
#endif

#include <libavformat/avformat.h>

#ifdef __cplusplus
}

#endif

using namespace std;

class Publisher {
private:
    int initPublish(JNIEnv *env);

    int destroyPublish();

    int encodeFrame(AVFrame *frame);

    ThreadPool *pool = nullptr;
    mutex pool_mutex;
    const char *path;
    int width = 0;
    int height = 0;
    int orientation = 0;
    atomic_bool is_publish = {false};
    int fps = 25;
    int64_t index = 0;
    uint8_t *pic_buf = nullptr;
    // AVFormatContext用于解封装 flv,avi,rmvb,mp4
    AVFormatContext *formatContext = nullptr;
    AVCodecContext *codecContext = nullptr;
    AVDictionary *codec_dict = nullptr;
    // AVPacket是存储压缩编码数据相关信息的结构体
//    AVPacket *packet = nullptr;
//    // 解码后/压缩前的数据
//    AVFrame *frame = nullptr;
    AVStream *stream = nullptr;
    thread worker;

    void encodeRun();

    void callbackState(JNIEnv *env, PublishState state);

    void callbackError(JNIEnv *env, PublishError error);

public:
    JavaVM *vm = nullptr;
    jobject callback = nullptr;

    LinkedBlockingQueue<unsigned char *> dataPool;

    atomic_bool running = {false};

    atomic_bool initing = {false};

    Publisher();

    /**
     * 推流
     * @param env
     * @param obj
     * @param path 推流地址
     * @return
     */
    int startPublish(const char *path, int width, int height, int orientation);

    int stopPublish();

    int pushData(unsigned char *buffer);

    int release();

    bool isPublish();

};

#endif

