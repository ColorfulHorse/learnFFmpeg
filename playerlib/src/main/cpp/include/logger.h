//
// Created by green sun on 2020/3/8.
//

#ifndef LYJPLAYER_LOGGER_H
#define LYJPLAYER_LOGGER_H

#ifdef ANDROID

#include <android/log.h>
#include <libavutil/time.h>

#define LOG_TAG    "LyjPlayer"
#define LOGE(format, ...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, format, ##__VA_ARGS__)
#define LOGI(format, ...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, format, ##__VA_ARGS__)
#else
#define LOGE(format, ...)  printf(LOG_TAG format "\n", ##__VA_ARGS__)
#define LOGI(format, ...)  printf(LOG_TAG format "\n", ##__VA_ARGS__)
#endif

#endif //LYJPLAYER_LOGGER_H
