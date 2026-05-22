package com.example.vrcamera

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VRGLRenderer(private val onSurfaceTextureCreated: (SurfaceTexture) -> Unit) : GLSurfaceView.Renderer {

    private var mTextureId = -1
    private var mSurfaceTexture: SurfaceTexture? = null
    private var mUpdateSurface = false

    @Volatile var distortion: Float = 0.22f
    @Volatile var ipdOffset: Float = 0.0f
    @Volatile var isFlipped: Int = 0

    private var mProgram = 0
    private var mPositionHandle = 0
    private var mTextureCoordinateHandle = 0
    private var mDistortionHandle = 0
    private var mIPDOffsetHandle = 0
    private var mIsFlippedHandle = 0
    private var mTexMatrixHandle = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var textureBuffer: FloatBuffer

    // 🌟 CameraX의 90도 회전값을 온전히 흡수할 매트릭스 배열
    private val mSTMatrix = FloatArray(16)

    // 기본 정점 (화면 전체 레이아웃)
    private val vertices = floatArrayOf(
        -1.0f, -1.0f,  1.0f, -1.0f, -1.0f,  1.0f,  1.0f,  1.0f
    )

    // 🌟 CameraX 표준 외부 텍스처 매핑 좌표 (0과 1의 기본 스탠다드 형태)
    private val textureCoordinates = floatArrayOf(
        0.0f, 1.0f,  0.0f, 0.0f,  1.0f, 1.0f,  1.0f, 0.0f
    )

    // 🌟 [회전 해결 핵심 1] 버텍스 셰이더에서 변환 행렬을 정확히 곱해 90도를 바로잡습니다.
    private val vertexShaderCode = """
        attribute vec4 position;
        attribute vec4 inputTextureCoordinate;
        varying vec2 vTextureCoord;
        uniform mat4 uTexMatrix;
        void main() {
            gl_Position = position;
            // 하드웨어 센서 회전(90도) 매트릭스를 텍스처 좌표에 적용
            vTextureCoord = (uTexMatrix * inputTextureCoordinate).xy;
        }
    """.trimIndent()

    // 🌟 [회전 해결 핵심 2] 프래그먼트 셰이더
    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        
        uniform float uDistortion;
        uniform float uIPDOffset;
        uniform float uIsFlipped;

        void main() {
            vec2 uv = vTextureCoord;

            // 1. 상하반전 제어
            if (uIsFlipped == 1.0) {
                uv.y = 1.0 - uv.y;
            }

            // 2. [가장 중요] 좌우 어느 쪽 눈이든 '완전히 동일한 원본 좌표(0.0 ~ 1.0)'를 바라보도록 축 분리
            bool isRightEye = uv.x > 0.5;
            vec2 st;

            if (!isRightEye) {
                // 왼쪽 구역(0.0~0.5)을 원본의 0.0~1.0 영역으로 맵핑
                st = vec2(uv.x * 2.0, uv.y);
            } else {
                // 오른쪽 구역(0.5~1.0)도 원본의 완전히 동일한 0.0~1.0 영역으로 복제 매핑
                st = vec2((uv.x - 0.5) * 2.0, uv.y);
            }

            // 3. 양안 간격(IPD) 미세 조정을 중앙 기준점 변경으로 안전하게 처리
            if (!isRightEye) {
                st.x += uIPDOffset;
            } else {
                st.x -= uIPDOffset;
            }

            // 4. 배럴 왜곡을 적용하기 위해 왜곡 중심점을 각 눈의 정중앙(0.5, 0.5)으로 고정
            st -= 0.5;
            float r2 = st.x * st.x + st.y * st.y;
            vec2 distortedST = st * (1.0 + uDistortion * r2 + uDistortion * r2 * r2);
            distortedST += 0.5; // 왜곡 후 다시 표준 좌표계로 복원

            // 5. 시야 확보 및 외곽 암막 안전장치
            if (distortedST.x < 0.0 || distortedST.x > 1.0 || distortedST.y < 0.0 || distortedST.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); // 렌즈 범위를 벗어나면 깔끔하게 암막 처리
            } else {
                gl_FragColor = texture2D(sTexture, distortedST); // 완벽하게 일치하는 좌우 화면 출력
            }
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices)
        vertexBuffer.position(0)
        textureBuffer = ByteBuffer.allocateDirect(textureCoordinates.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureCoordinates)
        textureBuffer.position(0)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        mProgram = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }

        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "position")
        mTextureCoordinateHandle = GLES20.glGetAttribLocation(mProgram, "inputTextureCoordinate")
        mDistortionHandle = GLES20.glGetUniformLocation(mProgram, "uDistortion")
        mIPDOffsetHandle = GLES20.glGetUniformLocation(mProgram, "uIPDOffset")
        mIsFlippedHandle = GLES20.glGetUniformLocation(mProgram, "uIsFlipped")
        mTexMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uTexMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        mTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())

        mSurfaceTexture = SurfaceTexture(mTextureId).apply {
            setOnFrameAvailableListener {
                synchronized(this@VRGLRenderer) {
                    mUpdateSurface = true
                }
            }
        }

        mSurfaceTexture?.let { onSurfaceTextureCreated(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        synchronized(this) {
            if (mUpdateSurface) {
                mSurfaceTexture?.updateTexImage()
                // 🌟 [가장 중요] CameraX 가 준 90도 회전용 행렬 정보를 매 프레임 실시간으로 빼옵니다.
                mSurfaceTexture?.getTransformMatrix(mSTMatrix)
                mUpdateSurface = false
            }
        }

        GLES20.glUseProgram(mProgram)

        // 🌟 매트릭스를 연동 핸들에 바인딩하여 버텍스 셰이더로 토스합니다.
        GLES20.glUniformMatrix4fv(mTexMatrixHandle, 1, false, mSTMatrix, 0)

        GLES20.glUniform1f(mDistortionHandle, distortion)
        GLES20.glUniform1f(mIPDOffsetHandle, ipdOffset)
        GLES20.glUniform1f(mIsFlippedHandle, isFlipped.toFloat())

        GLES20.glEnableVertexAttribArray(mPositionHandle)
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        GLES20.glEnableVertexAttribArray(mTextureCoordinateHandle)
        GLES20.glVertexAttribPointer(mTextureCoordinateHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(mPositionHandle)
        GLES20.glDisableVertexAttribArray(mTextureCoordinateHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).apply {
            GLES20.glShaderSource(this, shaderCode)
            GLES20.glCompileShader(this)
        }
    }
}