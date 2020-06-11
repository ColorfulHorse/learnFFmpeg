package com.lyj.playdemo

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.lyj.learnffmpeg.LyjPlayer
import com.lyj.learnffmpeg.VideoCallBack
import kotlinx.android.synthetic.main.activity_video_player.*
import kotlinx.android.synthetic.main.activity_video_player.view.*

class VideoPlayerActivity : AppCompatActivity() {

    companion object {

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        videoView.setOnVideoListener(object : VideoCallBack {
            override fun onState(state: Int) {
                when(state) {
                    LyjPlayer.STATE_START -> loading.visibility = View.GONE
                }
            }

            override fun onError(code: Int) {
                loading.visibility = View.GONE
                tips.visibility = View.VISIBLE
                when(code) {
                    LyjPlayer.ERROR_CONNECT_TIMEOUT -> tips.text = "连接流失败，请重试"
                    LyjPlayer.ERROR_STREAM -> tips.text = "查找流失败，请重试"
                    LyjPlayer.NONE_VIDEO_STREAM -> tips.text = "没有视频流，请重试"
                    LyjPlayer.UNKNOW -> tips.text = "未知错误，请重试"
                }
            }

        })
        start.setOnClickListener {
            videoView.startPlay("rtmp://149.28.73.52:1935/live/test")
            loading.visibility = View.VISIBLE
            tips.visibility = View.GONE
        }

        stop.setOnClickListener {
            videoView.stopPlay()
            loading.visibility = View.GONE
            tips.visibility = View.GONE
        }
    }
}
