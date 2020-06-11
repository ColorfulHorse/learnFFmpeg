package com.lyj.learnffmpeg

import android.os.Handler


/**
 * @author green sun
 *
 * @date 2020/3/10
 *
 * @desc
 */
class Publisher {
    private val handler = Handler()

    companion object {
        const val STATE_CONNECTED = 0
        const val STATE_START = STATE_CONNECTED + 1
        const val STATE_STOP = STATE_START + 1

        const val CONNECT_ERROR = 0

        const val UNKNOW = CONNECT_ERROR + 1

        init {
            LibLoader.loadLib("lyjplayer")
        }
    }

    init {
        initPublish()

//        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
//        val codecName = codecList.findEncoderForFormat(
//            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
//                // 帧率
//                setInteger(MediaFormat.KEY_FRAME_RATE, 25)
//            }
//        )
//        val codec = MediaCodec.createByCodecName(codecName)
    }

    external fun initPublish()

    external fun setCallBack(callback: PublishCallBack)

    external fun startPublish(path: String, width: Int, height: Int, orientation: Int): Int

    external fun stopPublish(): Int

    external fun publishData(data: ByteArray): Int

    external fun release()

    fun setPublishListener(callback: PublishCallBack) {
        setCallBack(object : PublishCallBack {
            override fun onState(state: Int) {
                handler.post { callback.onState(state) }
            }

            override fun onError(code: Int) {
                handler.post { callback.onError(code) }
            }
        })
    }
}