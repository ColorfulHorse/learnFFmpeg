package com.lyj.learnffmpeg

interface VideoCallBack {
    fun onState(state: Int)

    fun onError(code: Int)
}