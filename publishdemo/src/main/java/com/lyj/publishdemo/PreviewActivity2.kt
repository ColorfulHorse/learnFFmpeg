package com.lyj.publishdemo

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.contentValuesOf
import androidx.core.view.doOnLayout
import com.lyj.learnffmpeg.Publisher
import kotlinx.android.synthetic.main.activity_preview.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import kotlin.math.abs
import kotlin.system.measureTimeMillis

class PreviewActivity2 : AppCompatActivity() {

    companion object {
        private const val TAG = "PreviewActivity"
        private const val REQUEST_PERMISSION_CODE: Int = 1
        private val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        const val IMAGE_WIDTH = 1080
        const val IMAGE_HEIGHT = 2244
        const val THUMB_WIDTH = 300
        const val THUMB_HEIGHT = 400
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
    }

    private var mCameraInfo: CameraCharacteristics? = null
    private var mCamera: CameraDevice? = null
    private var mSession: CameraCaptureSession? = null
    private var mSurfaceTexture: SurfaceTexture? = null
    // 拍照用
    private var captureSurface: Surface? = null
    // 预览用
    private var previewSurface: Surface? = null
    // 预览数据用
    private var previewDataSurface: Surface? = null
    // 预览数据imageReader
    private var mPreviewReader: ImageReader? = null
    private var mCaptureReader: ImageReader? = null
    private var previewWidth = 0
    private var previewHeight = 0
    //private lateinit var previewSize: Size
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var publishThread: HandlerThread? = null
    private var publishHandler: Handler? = null
    private var deviceOrientation = 0
    private lateinit var mOrientationListener: OrientationEventListener
    private val imageListener = { reader: ImageReader ->
        //val res = backgroundHandler?.post {
            val image = reader.acquireNextImage()
            if (image != null) {
                val time = measureTimeMillis {
                    val yChannel = image.planes[0]
                    val uChannel = image.planes[1]
                    val vChannel = image.planes[2]
                    // 内存对齐导致多余的数据填充
                    val padding = yChannel.rowStride - image.width
                    val capacity = uChannel.buffer.capacity()
                    //val uvSize = (capacity + uChannel.pixelStride - 1) / uChannel.pixelStride
                    val ySize = image.width*image.height
                    val uvSize = ySize/4
                    val yuvSize = (image.width * image.height * 3) / 2
                    val yuvBuffer = ByteArray(yuvSize)
                    var pos = 0
                    if (padding == 0) {
                        pos = ySize
                        yChannel.buffer.get(yuvBuffer, 0, ySize)
                    }else {
                        var offset = 0
                        for (row in 0 until image.height) {
                            yChannel.buffer.position(offset)
                            yChannel.buffer.get(yuvBuffer, offset, image.width)
                            offset += yChannel.rowStride
                            pos+=image.width
                        }
                    }

                    var i = 0
                    val uvLen = uChannel.buffer.remaining()
                    val stride = uChannel.pixelStride
                    while (i < uvLen) {
                        yuvBuffer[pos] = uChannel.buffer[i]
                        yuvBuffer[pos+uvSize] = vChannel.buffer[i]
                        i += stride
                        pos++
                        if (padding == 0) continue

                        val rowIndex = i % uChannel.rowStride
                        if (rowIndex >= image.width) {
                            i += padding
                        }
                    }
                    player.publishData(yuvBuffer)
                    image.close()
                }
                //Log.e(TAG, "transform time: $time =============")
            //}
        }
    }

    private lateinit var player: Publisher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        player = Publisher()
        preview.doOnLayout {
            previewHeight = preview.height
            previewWidth = preview.width
        }
        mOrientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                Log.e("onOrientationChanged", "设备旋转角度：$orientation")
                deviceOrientation = orientation
            }
        }
    }


    override fun onResume() {
        super.onResume()
        mOrientationListener.enable()
        if (checkRequiredPermissions()) {
            setup()
        }
    }

    override fun onPause() {
        mOrientationListener.disable()
        closeCamera()
        publishHandler?.post {
            player.stopPublish()
        }
        stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    private fun setup() {
        startBackgroundThread()
        if (preview.isAvailable) {
            mSurfaceTexture = preview.surfaceTexture
            openBackCamera()
        } else {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    mSurfaceTexture = surface
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    mSurfaceTexture = surface
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    mSurfaceTexture = null
                    return true
                }

                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
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

        val publishThread = HandlerThread("CameraBackground").also {
            publishThread = it
            it.start()
        }
        publishHandler = Handler(publishThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {

        }
        publishThread?.quitSafely()
        try {
            publishThread?.join()
            publishThread = null
            publishHandler = null
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
            Log.e(TAG, "cameraId: $cameraId")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.e("onOpened", "onOpened==============")
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
            mCaptureReader?.close()
            mCaptureReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {

        }
    }


    private fun createSession() {
        mCamera?.let { camera ->
            createOutputs()
            val outputs = listOf(captureSurface, previewDataSurface, previewSurface)
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
                val captureSize =
                    getOptimalSize(config.getOutputSizes(ImageFormat.JPEG),
                        IMAGE_WIDTH,
                        IMAGE_HEIGHT
                    )

                val captureReader =
                    ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 5)
                mCaptureReader = captureReader
                captureReader.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage()
                    saveImage(image)
                }, backgroundHandler)
                captureSurface = captureReader.surface

                val previewSize =
                    getOptimalSize(config.getOutputSizes(SurfaceTexture::class.java), previewWidth, previewHeight)
                // 获取camera流的分辨率
                val previewDataSize =
                    getOptimalSize(config.getOutputSizes(SurfaceTexture::class.java), 480, 640)
                Log.e(TAG, "preview data width:${previewDataSize.width}  height:${previewDataSize.height}")
                val previewReader =
                    ImageReader.newInstance(previewDataSize.width, previewDataSize.height, ImageFormat.YUV_420_888, 3)
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
                publishHandler?.post {
                    val rotation = info.get(CameraCharacteristics.SENSOR_ORIENTATION)?:0
                    player.startPublish("rtmp://149.28.73.52:1935/live/test", previewDataSize.width, previewDataSize.height, rotation)
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
                val builder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                previewSurface?.let {
                    builder.addTarget(it)
                }
                previewDataSurface?.let {
                    builder.addTarget(it)
                }
                builder[CaptureRequest.CONTROL_AF_MODE] =
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                builder[CaptureRequest.CONTROL_AE_MODE] =
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                val request = builder.build()
                mSession = session
                session.setRepeatingRequest(
                    request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                            super.onCaptureStarted(session, request, timestamp, frameNumber)
                        }

                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {

                        }
                    },
                    backgroundHandler
                )
            }
        }
    }

    /**
     * 拍照
     */
    fun capture() {
        mCamera?.let { device ->
            mSession?.let { session ->
                mCameraInfo?.let { info ->
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    // 拍照时也要将预览的surface添加，不然会导致画面丢失
                    previewSurface?.let {
                        builder.addTarget(it)
                    }
                    previewDataSurface?.let {
                        builder.addTarget(it)
                    }
                    captureSurface?.let {
                        builder.addTarget(it)
                    }
                    val orientation = getJpegOrientation(info, deviceOrientation)
                    Log.e("capture", "拍照图片旋转角度：$orientation")
                    builder[CaptureRequest.JPEG_ORIENTATION] = orientation
                    val thumbSizes = info[CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES]
                    if (thumbSizes != null) {
                        val thumbSize = getOptimalSize(thumbSizes,
                            THUMB_WIDTH,
                            THUMB_HEIGHT
                        )
                        builder[CaptureRequest.JPEG_THUMBNAIL_SIZE] = thumbSize
                    }
                    // 自动对焦
                    builder[CaptureRequest.CONTROL_AF_MODE] =
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    // 自动曝光
                    builder[CaptureRequest.CONTROL_AE_MODE] =
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    builder[CaptureRequest.JPEG_QUALITY] = 100
                    builder[CaptureRequest.JPEG_QUALITY] = 100
                    val request = builder.build()
                    val captureCallback = object : CameraCaptureSession.CaptureCallback() {

                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            super.onCaptureCompleted(session, request, result)
                            Log.e("capture", "拍照成功")
                        }

                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            super.onCaptureFailed(session, request, failure)
                        }

                    }
                    session.apply {
                        //             stopRepeating()
                        //                        abortCaptures()
                        capture(request, captureCallback, backgroundHandler)
                    }
                }
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


    /**
     * 保存图片
     */
    private fun saveImage(image: Image) {
        Executors.newSingleThreadExecutor()
            .execute {
                image.use {
                    val buffer = it.planes[0].buffer
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    val date = System.currentTimeMillis()
                    val title = "IMG_${dateFormat.format(date)}"
                    val displayName = "$title.jpeg"
                    val contentValues = contentValuesOf(
                        MediaStore.Images.Media.TITLE to title,
                        MediaStore.Images.Media.DISPLAY_NAME to displayName,
                        MediaStore.Images.Media.DATE_TAKEN to date,
                        MediaStore.Images.Media.WIDTH to it.width,
                        MediaStore.Images.Media.HEIGHT to it.height,
                        MediaStore.Images.Media.MIME_TYPE to "image/jpeg"
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val path = "DCIM/ndkstart"
                        val dir = File(path)
                        if (!dir.exists()) {
                            dir.mkdirs()
                        }
                        contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "$path/$displayName")
                    } else {
                        val path =
                            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).path}/ndkstart"
                        val dir = File(path)
                        if (!dir.exists()) {
                            dir.mkdirs()
                        }
                        contentValues.put(MediaStore.Images.Media.DATA, "$dir/$displayName")
                    }
                    val uri =
                        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let { u ->
                        contentResolver.openOutputStream(u)?.use { os ->
                            os.write(data)
                        }
                        runOnUiThread {
                            Toast.makeText(this, "拍照成功，照片已保存", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
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

    /**
     * 拍照图片应该旋转的角度
     */
    private fun getJpegOrientation(cameraCharacteristics: CameraCharacteristics, deviceOrientation: Int): Int {
        var myDeviceOrientation = deviceOrientation
        if (myDeviceOrientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return 0
        }
        val sensorOrientation =
            cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        myDeviceOrientation = (myDeviceOrientation + 45) / 90 * 90

        val facingFront =
            cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        if (facingFront) {
            // 前置 镜像
            myDeviceOrientation = -myDeviceOrientation
        }

        return (sensorOrientation + myDeviceOrientation + 360) % 360
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_PERMISSION_CODE) {
            val res = grantResults.reduce { x, y -> x + y }
            if (res == PackageManager.PERMISSION_GRANTED) {
                //                if (lifecycle.currentState > Lifecycle.State.CREATED) {
                //                    setup()
                //                }
            }
        }
    }
}
