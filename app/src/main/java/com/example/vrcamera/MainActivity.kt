package com.example.vrcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
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
import androidx.core.view.isVisible

class MainActivity : ComponentActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var vrRenderer: VRGLRenderer
    private lateinit var controlPanel: CardView
    private lateinit var tvHint: TextView
    private lateinit var btnFlip: Button

    private var camera: Camera? = null
    private var isFlipped: Boolean = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) { startCameraPipeline() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        controlPanel = findViewById(R.id.controlPanel)
        tvHint = findViewById(R.id.tvHint)
        btnFlip = findViewById(R.id.btnFlip)

        glSurfaceView.setEGLContextClientVersion(2)

        vrRenderer = VRGLRenderer { surfaceTexture ->
            runOnUiThread {
                setupCameraSession(surfaceTexture)
            }
        }
        glSurfaceView.setRenderer(vrRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        glSurfaceView.setOnClickListener { toggleControlPanel() }
        findViewById<View>(R.id.mainRootLayout).setOnClickListener { toggleControlPanel() }

        btnFlip.setOnClickListener {
            isFlipped = !isFlipped
            if (isFlipped) {
                btnFlip.setBackgroundColor(Color.rgb(128, 0, 128))
                vrRenderer.isFlipped = 1
            } else {
                btnFlip.setBackgroundColor(Color.rgb(34, 49, 78))
                vrRenderer.isFlipped = 0
            }
        }

        // 실제 하드웨어 스마트폰 카메라 렌즈 줌 연동 리스너 (오타 완전 수정)
        findViewById<SeekBar>(R.id.sbZoom).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val hardwareZoomRatio = 1.0f + (progress / 50f)
                camera?.cameraControl?.setZoomRatio(hardwareZoomRatio)
                Log.d("VR_ZOOM", "카메라 하드웨어 줌 비율 반영: ${hardwareZoomRatio}배")
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<SeekBar>(R.id.sbDistortion).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, r: Boolean) {
                vrRenderer.distortion = p / 100f
            }
            override fun onStartTrackingTouch(s: SeekBar?) {} override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<SeekBar>(R.id.sbIpd).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, r: Boolean) {
                vrRenderer.ipdOffset = (p - 30) * 0.005f
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
        glSurfaceView.visibility = View.GONE
        glSurfaceView.visibility = View.VISIBLE
    }

    private fun setupCameraSession(surfaceTexture: SurfaceTexture) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                surfaceTexture.setDefaultBufferSize(1920, 1080)

                val previewUseCase = Preview.Builder().build()
                previewUseCase.setSurfaceProvider { request ->
                    val surface = android.view.Surface(surfaceTexture)
                    request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {
                        surface.release()
                    }
                }

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase
                )

                camera?.cameraControl?.setZoomRatio(1.0f)

            } catch (exc: Exception) {
                Log.e("VR_CAMERA", "OpenGL 하드웨어 카메라 세션 연동 실패", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }
}