package com.lyj.learnffmpeg

interface PublishCallBack {
    fun onState(state: Int)

    fun onError(code: Int)
}