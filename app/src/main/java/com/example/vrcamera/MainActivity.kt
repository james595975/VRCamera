package com.example.vrcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible

class MainActivity : ComponentActivity() {

    private lateinit var textureViewLeft: TextureView
    private lateinit var textureViewRight: TextureView
    private lateinit var controlPanel: CardView
    private lateinit var tvHint: TextView
    private lateinit var btnFlip: Button

    private var camera: Camera? = null
    private var previewUseCaseLeft: Preview? = null
    private var previewUseCaseRight: Preview? = null

    private var uZoomScale: Float = 2.0f
    private var uDistortion: Float = 0.0f
    private var uIPDOffset: Float = 0.0f

    private var isFlipped: Boolean = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean -> if (isGranted) startCameraPipeline() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureViewLeft = findViewById(R.id.textureViewLeft)
        textureViewRight = findViewById(R.id.textureViewRight)
        controlPanel = findViewById(R.id.controlPanel)
        tvHint = findViewById(R.id.tvHint)
        btnFlip = findViewById(R.id.btnFlip)

        // 패널 토글 리스너
        findViewById<View>(R.id.mainRootLayout).setOnClickListener { toggleControlPanel() }
        textureViewLeft.setOnClickListener { toggleControlPanel() }
        textureViewRight.setOnClickListener { toggleControlPanel() }

        // 상하반전 버튼 리스너
        btnFlip.setOnClickListener {
            isFlipped = !isFlipped
            if (isFlipped) {
                val purpleColorList = android.content.res.ColorStateList.valueOf(Color.rgb(128, 0, 128))
                ViewCompat.setBackgroundTintList(btnFlip, purpleColorList)
                btnFlip.setTextColor(Color.WHITE)
            } else {
                val defaultDarkBlue = android.content.res.ColorStateList.valueOf(Color.rgb(34, 49, 78))
                val cyanColor = Color.rgb(0, 229, 255)
                ViewCompat.setBackgroundTintList(btnFlip, defaultDarkBlue)
                btnFlip.setTextColor(cyanColor)
            }
            applyVRTransform() // 버튼 누르면 즉시 GPU 매트릭스 변환
        }

        // 슬라이더 리스너 연동
        findViewById<SeekBar>(R.id.sbZoom).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, r: Boolean) {
                uZoomScale = 1.0f + (p / 50f)
                applyVRTransform()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {} override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<SeekBar>(R.id.sbDistortion).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, r: Boolean) {
                uDistortion = p / 100f
                applyVRTransform()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {} override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<SeekBar>(R.id.sbIpd).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, r: Boolean) {
                uIPDOffset = (p - 30) * 6f
                applyVRTransform()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {} override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        checkCameraPermission()
    }

    private fun toggleControlPanel() {
        if (controlPanel.isVisible) {
            controlPanel.animate().alpha(0f).translationY(50f).setDuration(250).withEndAction {
                controlPanel.isVisible = false
                tvHint.isVisible = true
            }.start()
        } else {
            controlPanel.isVisible = true
            tvHint.isVisible = false
            controlPanel.animate().alpha(1f).translationY(0f).setDuration(250).start()
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraPipeline()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPipeline() {
        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                setupCameraSession()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        textureViewLeft.surfaceTextureListener = listener
        textureViewRight.surfaceTextureListener = listener

        if (textureViewLeft.isAvailable && textureViewRight.isAvailable) {
            setupCameraSession()
        }
    }

    private fun setupCameraSession() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                previewUseCaseLeft = Preview.Builder().build()
                previewUseCaseRight = Preview.Builder().build()

                textureViewLeft.surfaceTexture?.let { previewUseCaseLeft?.setSurfaceProvider { request ->
                    val surface = android.view.Surface(it)
                    request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { surface.release() }
                }}

                textureViewRight.surfaceTexture?.let { previewUseCaseRight?.setSurfaceProvider { request ->
                    val surface = android.view.Surface(it)
                    request.provideSurface(surface, ContextCompat.getMainExecutor(this)) { surface.release() }
                }}

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCaseLeft, previewUseCaseRight
                )

                textureViewLeft.post { applyVRTransform() }
            } catch (exc: Exception) {
                Log.e("VR_CAMERA", "하드웨어 세션 바인딩 실패", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // 🌟 [최종 하드웨어 오버라이드] 90도 누움 해결 및 상하반전 결합 매트릭스 공식
    private fun applyVRTransform() {
        runOnUiThread {
            val leftDistortionFix = 1.0f - (uDistortion * 0.08f)
            val rightDistortionFix = 1.0f - (uDistortion * 0.08f)

            // 디지털 배율 연동
            camera?.cameraControl?.setZoomRatio(uZoomScale)

            // 뷰 크기 및 중심점 계산
            val widthL = textureViewLeft.width.toFloat()
            val heightL = textureViewLeft.height.toFloat()
            val widthR = textureViewRight.width.toFloat()
            val heightR = textureViewRight.height.toFloat()

            if (widthL == 0f || heightL == 0f) return@runOnUiThread

            // 🌟 하드웨어 그래픽 전용 Matrix 생성 (일반 View 속성이 아닌 GPU 단 제어)
            val matrixLeft = Matrix()
            val matrixRight = Matrix()

            // 1단계: 태블릿 가로 기본 센서 특성으로 인한 90도 누움 현상 강제 보정 회전
            matrixLeft.postRotate(-90f, widthL / 2f, heightL / 2f)
            matrixRight.postRotate(-90f, widthR / 2f, heightR / 2f)

            // 2단계: 사용자가 '상하반전'을 눌렀을 때 추가로 180도 더 회전 처리
            if (isFlipped) {
                matrixLeft.postRotate(180f, widthL / 2f, heightL / 2f)
                matrixRight.postRotate(180f, widthR / 2f, heightR / 2f)

                // 180도 회전 시 발생하는 좌우 반전을 스케일로 상쇄
                matrixLeft.postScale(-leftDistortionFix, 1.0f, widthL / 2f, heightL / 2f)
                matrixRight.postScale(-rightDistortionFix, 1.0f, widthR / 2f, heightR / 2f)

                // IPD 보정값 대칭 역산
                textureViewLeft.translationX = uIPDOffset
                textureViewRight.translationX = -uIPDOffset
            } else {
                // 기본 왜곡률 스케일 적용
                matrixLeft.postScale(leftDistortionFix, 1.0f, widthL / 2f, heightL / 2f)
                matrixRight.postScale(rightDistortionFix, 1.0f, widthR / 2f, heightR / 2f)

                // 기본 IPD 평행 이동
                textureViewLeft.translationX = -uIPDOffset
                textureViewRight.translationX = uIPDOffset
            }

            // 🌟 3단계: 일반 뷰 속성(rotation)을 완전히 우회하고 하드웨어 텍스처 파이프라인에 직접 행렬 주입
            textureViewLeft.setTransform(matrixLeft)
            textureViewRight.setTransform(matrixRight)
        }
    }
}