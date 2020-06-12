package com.lyj.learnffmpeg

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.RelativeLayout
import kotlinx.android.synthetic.main.video_view.view.*
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class VideoView : RelativeLayout, SurfaceHolder.Callback {
    private var created = false
    private val player: LyjPlayer = LyjPlayer()

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    init {
        View.inflate(context, R.layout.video_view, this)
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {

    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {

    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        player.setSurface(holder.surface)
    }

    fun startPlay(url: String) {
        player.startPlay(url)
    }

    fun stopPlay() {
        player.stopPlay()
    }

    fun release() {
        player.release()
    }

    fun setOnVideoListener(callBack: VideoCallBack) {
        player.setVideoCallBack(object : VideoCallBack {
            override fun onState(state: Int) {
                handler.post { callBack.onState(state) }
            }

            override fun onError(code: Int) {
                handler.post { callBack.onError(code) }
            }
        })
    }
}