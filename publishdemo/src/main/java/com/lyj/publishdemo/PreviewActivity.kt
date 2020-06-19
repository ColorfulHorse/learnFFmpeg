package com.lyj.publishdemo

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.doOnLayout
import com.lyj.learnffmpeg.PublishCallBack
import com.lyj.learnffmpeg.Publisher
import kotlinx.android.synthetic.main.activity_preview.*
import kotlin.math.abs
import kotlin.system.measureTimeMillis

class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PreviewActivity"
        private const val REQUEST_PERMISSION_CODE: Int = 1
        private val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private var mCameraInfo: CameraCharacteristics? = null
    private var mCamera: CameraDevice? = null
    private var mSession: CameraCaptureSession? = null
    private var mSurfaceTexture: SurfaceTexture? = null
    // 预览用
    private var previewSurface: Surface? = null
    // 推流数据用
    private var previewDataSurface: Surface? = null
    private var previewDataSize: Size? = null
    // 预览数据imageReader
    private var mPreviewReader: ImageReader? = null
    private var previewWidth = 0
    private var previewHeight = 0
    //private lateinit var previewSize: Size
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var deviceOrientation = 0
    private lateinit var mOrientationListener: OrientationEventListener
    private val imageListener = { reader: ImageReader ->
        val image = reader.acquireNextImage()
        if (image != null) {
//            listOf(
//                image.planes[0],
//                image.planes[1],
//                image.planes[2]
//            ).forEachIndexed { index, plane ->
//                Log.e(
//                    TAG, """
//
//                    index:$index
//                    width:${image.width}
//                    height:${image.height}
//                    rowStride:${plane.rowStride}
//                    pixelStride:${plane.pixelStride}
//                    bufferSize:${plane.buffer.capacity()}
//                    """
//                )
//            }
            // y u v三通道
            val yBuffer = image.planes[0].buffer

            val uBuffer = image.planes[1].buffer
            // 两像素之间u分量的间隔，yuv420中uv为1
            val uStride = image.planes[1].pixelStride

            val vBuffer = image.planes[2].buffer

            val uvSize = image.width * image.height / 4
            // yuvi420: Y:U:V = 4:1:1 = YYYYUV
            val buffer = ByteArray(image.width * image.height * 3 / 2)
            // 每一行的实际数据长度，可能因为内存对齐大于图像width
            val rowStride = image.planes[0].rowStride
            val padding = rowStride - image.width
            var pos = 0
            // 将y buffer拼进去
            if (padding == 0) {
                pos = yBuffer.remaining()
                yBuffer.get(buffer, 0, pos)
            } else {
                var yBufferPos = 0
                for (row in 0 until image.height) {
                    yBuffer.position(yBufferPos)
                    yBuffer.get(buffer, pos, image.width)
                    // 忽略行末冗余数据，偏移到下一行的位置
                    yBufferPos += rowStride
                    pos += image.width
                }
            }

            var i = 0

            val uRemaining = uBuffer.remaining()
            while (i < uRemaining) {
                // 循环u v buffer，隔一个取一个
                buffer[pos] = uBuffer[i]
                buffer[pos+uvSize] = vBuffer[i]
                pos++
                i += uStride

                if (padding == 0) continue
                // 并跳过每一行冗余数据
                val rowLen = i % rowStride
                if (rowLen >= image.width) {
                    i += padding
                }
            }
            publisher.publishData(buffer)
            image.close()
        }
    }

    private lateinit var publisher: Publisher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        publisher = Publisher()
        preview.doOnLayout {
            previewHeight = preview.height
            previewWidth = preview.width
        }
        publisher.setPublishListener(object : PublishCallBack {
            override fun onState(state: Int) {
                when (state) {
                    Publisher.STATE_CONNECTED -> Toast.makeText(
                        this@PreviewActivity,
                        "连接服务器成功",
                        Toast.LENGTH_SHORT
                    ).show()
                    Publisher.STATE_START -> Toast.makeText(
                        this@PreviewActivity,
                        "推流成功",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onError(code: Int) {
                when (code) {
                    Publisher.CONNECT_ERROR -> Toast.makeText(
                        this@PreviewActivity,
                        "连接服务器失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        })
        start.setOnClickListener {
            // 开始推流
            mCameraInfo?.let {
                val rotation = it.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                previewDataSize?.let { size ->
                    publisher.startPublish(
                        "rtmp://149.28.73.52:1935/live/test",
                        size.width,
                        size.height,
                        rotation
                    )
                }
            }
        }

        stop.setOnClickListener {
            publisher.stopPublish()
        }

        mOrientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                Log.e("onOrientationChanged", "设备旋转角度：$orientation")
                deviceOrientation = orientation
            }
        }
        if (checkRequiredPermissions()) {
            setup()
        }
    }


//    override fun onResume() {
//        super.onResume()
//        mOrientationListener.enable()
//        if (checkRequiredPermissions()) {
//            setup()
//        }
//    }

//    override fun onPause() {
//        mOrientationListener.disable()
//        closeCamera()
//        publisher.stopPublish()
//        stopBackgroundThread()
//        super.onPause()
//    }

    override fun onDestroy() {
        super.onDestroy()
        mOrientationListener.disable()
        closeCamera()
        publisher.stopPublish()
        stopBackgroundThread()
        publisher.release()
    }

    private fun setup() {
        startBackgroundThread()
        if (preview.isAvailable) {
            mSurfaceTexture = preview.surfaceTexture
            openBackCamera()
        } else {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    mSurfaceTexture = surface
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    mSurfaceTexture = surface
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    mSurfaceTexture = null
                    return true
                }

                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    mSurfaceTexture = surface
                    openBackCamera()
                }
            }
        }
    }

    private fun startBackgroundThread() {
        val backgroundThread = HandlerThread("CameraBackground").also {
            backgroundThread = it
            it.start()
        }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {

        }
    }


    /**
     * 打开后置摄像头
     */
    @SuppressLint("MissingPermission")
    private fun openBackCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var cameraId = ""
        for (id in manager.cameraIdList) {
            val cameraInfo = manager.getCameraCharacteristics(id)
            // 支持的硬件等级
            val level = cameraInfo[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]
            val previewFormat = ImageFormat.YUV_420_888
            //if (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL || level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) {
            if (cameraInfo[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK) {
                val map = cameraInfo.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: continue
                if (!map.isOutputSupportedFor(previewFormat)) {
                    continue
                }
                cameraId = id
                mCameraInfo = cameraInfo
                break
            }
            //}
        }
        if (cameraId.isNotBlank()) {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    mCamera = camera
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }

            }, backgroundHandler)
        }
    }

    private fun closeCamera() {
        try {
            mSession?.stopRepeating()
            mSession?.close()
            mSession = null
            mCamera?.close()
            mCamera = null
            mPreviewReader?.close()
            mPreviewReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {

        }
    }


    private fun createSession() {
        mCamera?.let { camera ->
            createOutputs()
            val outputs = listOf(previewSurface, previewDataSurface)
            camera.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigureFailed(session: CameraCaptureSession) {

                    }

                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.e("onConfigured", "onConfigured==============")
                        mSession = session
                        startPreview()
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        super.onClosed(session)
                    }

                },
                backgroundHandler
            )
        }
    }

    private fun createOutputs() {
        mCameraInfo?.let { info ->
            val map = info[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            map?.let { config ->
                val previewSize =
                    getOptimalSize(
                        config.getOutputSizes(SurfaceTexture::class.java),
                        previewWidth,
                        previewHeight
                    )
                // 获取camera流的分辨率
                val previewDataSize =
                    getOptimalSize(config.getOutputSizes(SurfaceTexture::class.java), 480, 640)
                Log.e(
                    TAG,
                    "preview data width:${previewDataSize.width}  height:${previewDataSize.height}"
                )
                this.previewDataSize = previewDataSize;
                val previewReader =
                    ImageReader.newInstance(
                        previewDataSize.width,
                        previewDataSize.height,
                        ImageFormat.YUV_420_888,
                        3
                    )
                mPreviewReader = previewReader
                previewReader.setOnImageAvailableListener(imageListener, backgroundHandler)
                previewDataSurface = previewReader.surface

                // 预览surface
                mSurfaceTexture?.run {
                    Log.e("preview", "width:${previewSize.width}  height:${previewSize.height}")
                    setDefaultBufferSize(previewSize.width, previewSize.height)
                    val surface = Surface(this)
                    previewSurface = surface
                }
            }
        }
    }

    /**
     * 预览
     */
    private fun startPreview() {
        mCamera?.let { camera ->
            mSession?.let { session ->
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                previewSurface?.let {
                    builder.addTarget(it)
                }
                previewDataSurface?.let {
                    builder.addTarget(it)
                }
                // 自动对焦
                builder[CaptureRequest.CONTROL_AF_MODE] =
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                builder[CaptureRequest.CONTROL_AE_MODE] =
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                val request = builder.build()
                mSession = session
                session.setRepeatingRequest(
                    request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            timestamp: Long,
                            frameNumber: Long
                        ) {
                            super.onCaptureStarted(session, request, timestamp, frameNumber)
                        }

                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {

                        }
                    },
                    backgroundHandler
                )
            }
        }
    }


    /**
     * 获取合适的预览尺寸
     */
    private fun getOptimalSize(sizes: Array<Size>, maxWidth: Int, maxHeight: Int): Size {
        val aspectRatio = maxHeight.toFloat() / maxWidth
        val targets = mutableListOf<Pair<Int, Size>>()
        sizes.forEach { size ->
            // 比例差别
            val d = aspectRatio - size.width.toFloat() / size.height
            val ratio = aspectRatio - d
            // 宽高差别
            val delta = maxWidth - size.height + maxHeight - size.width
            val diff = abs((ratio * delta).toInt())
            targets.add(Pair(diff, size))
        }
        targets.sortBy { it.first }
        targets.forEach {
            Log.e(TAG, "first: ${it.first}, size: ${it.second}")
        }
        if (targets.isNotEmpty()) {
            return targets[0].second
        }
        return sizes[0]
    }


    private fun checkRequiredPermissions(): Boolean {
        val deniedPermissions = mutableListOf<String>()
        for (permission in REQUIRED_PERMISSIONS) {
            val res = ActivityCompat.checkSelfPermission(this, permission)
            if (res == PackageManager.PERMISSION_DENIED) {
                deniedPermissions.add(permission)
            }
        }
        if (deniedPermissions.isEmpty().not()) {
            ActivityCompat.requestPermissions(
                this,
                deniedPermissions.toTypedArray(),
                REQUEST_PERMISSION_CODE
            )
        }
        return deniedPermissions.isEmpty()
    }

    /**
     * 需要顺时针旋转多少度才能保持画面正确
     */
    fun getDisplayRotation(info: CameraCharacteristics): Int {
        // 画面旋转角度  逆时针
        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        Log.e("getDisplayRotation", "画面旋转角度：$rotation")
        // 传感器旋转角度  逆时针
        val sensorOrientation = info[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        Log.e("getDisplayRotation", "传感器旋转角度：$sensorOrientation")
        val isFront =
            info[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        return if (isFront) {
            // 前置摄像头的画面是做了镜像处理的，也就是所谓的前置镜像操作，这个情况下，
            // orientation 的值并不是实际我们要旋转的角度，我们需要取它的镜像值才是我们真正要旋转的角度，
            // 例如 orientation 为 270°，实际我们要旋转的角度是 90°。
            (360 - (sensorOrientation + rotation) % 360) % 360
        } else {
            // 目标画面180 设备逆时针90  那么实际画面处于 逆时针270
            // 目标画面逆时针90 设备逆时针270 那么实际画面处于 逆时针180
            (sensorOrientation - rotation + 360) % 360
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_PERMISSION_CODE) {
            val res = grantResults.reduce { x, y -> x + y }
            if (res == PackageManager.PERMISSION_GRANTED) {
                setup()
                //                if (lifecycle.currentState > Lifecycle.State.CREATED) {
                //                    setup()
                //                }
            }
        }
    }
}
