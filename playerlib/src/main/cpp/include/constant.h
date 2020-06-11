//
// Created by hunny on 2020/6/9.
//

#ifndef LEARNFFMPEG_CONSTANT_H
#define LEARNFFMPEG_CONSTANT_H

enum class PlayState {
    CONNECTED,
    START,
    STOP
};

enum class PlayError {
    CONNECT_TIMEOUT,
    ERROR_STREAM,
    NONE_VIDEO_STREAM,
    UNKNOW
};

enum class PublishState {
    CONNECTED,
    START,
    STOP
};

enum class PublishError {
    CONNECT_ERROR,
    UNKNOW
};

#endif //LEARNFFMPEG_CONSTANT_H
