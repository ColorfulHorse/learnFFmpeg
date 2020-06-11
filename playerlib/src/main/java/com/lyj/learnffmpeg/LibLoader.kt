package com.lyj.learnffmpeg

object LibLoader {
    val list = mutableListOf<String>()

    @Synchronized
    fun loadLib(libName: String) {
        if (!list.contains(libName)) {
            list.add(libName)
            System.loadLibrary(libName)
        }
    }
}