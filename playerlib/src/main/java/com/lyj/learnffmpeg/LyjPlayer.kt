package com.lyj.learnffmpeg

import android.view.Surface

class LyjPlayer {

    companion object {
        const val STATE_CONNECTED = 0
        const val STATE_START = STATE_CONNECTED + 1
        const val STATE_STOP = STATE_START + 1

        const val ERROR_CONNECT_TIMEOUT = 0
        const val ERROR_STREAM = ERROR_CONNECT_TIMEOUT + 1
        const val NONE_VIDEO_STREAM = ERROR_STREAM + 1
        const val UNKNOW = NONE_VIDEO_STREAM + 1


        init {
            LibLoader.loadLib("lyjplayer")
        }

    }

    external fun initPlayer(surface: Surface)

    external fun setVideoCallBack(callback: VideoCallBack)

    external fun startPlay(url: String): Int

    external fun stopPlay(): Int

    external fun release()
}