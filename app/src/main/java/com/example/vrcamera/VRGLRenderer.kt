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

class VRGLRenderer(private val onSurfaceCreatedCallback: (SurfaceTexture) -> Unit) : GLSurfaceView.Renderer {

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var program = 0

    // 실시간 제어 파라미터 (uZoomScale은 하드웨어 줌을 사용하므로 1.0f로 고정)
    var distortion = 0.15f
    var zoomScale = 1.0f
    var ipdOffset = 0.00f
    var isFlipped = 0

    private val mSTMatrix = FloatArray(16)
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTEX_DATA.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().put(VERTEX_DATA)

    init { vertexBuffer.position(0) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        onSurfaceCreatedCallback(surfaceTexture!!)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        surfaceTexture?.apply {
            updateTexImage()
            getTransformMatrix(mSTMatrix)
        }

        GLES20.glUseProgram(program)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, mSTMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDistortion"), distortion)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uZoomScale"), zoomScale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uIPDOffset"), ipdOffset)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uIsFlipped"), isFlipped)

        val ph = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(ph)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(ph, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        val th = GLES20.glGetAttribLocation(program, "aTextureCoord")
        GLES20.glEnableVertexAttribArray(th)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(th, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        // 기본 90도 회전 배치를 유지하는 하드웨어 정점 데이터
        private val VERTEX_DATA = floatArrayOf(
            // X,     Y,     U,     V
            -1.0f,  1.0f,  1.0f,  0.0f,
            -1.0f, -1.0f,  0.0f,  0.0f,
            1.0f,  1.0f,  1.0f,  1.0f,
            1.0f, -1.0f,  0.0f,  1.0f
        )

        private const val VERTEX_SHADER_CODE = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """

        // 🌟 [보정 완료] 프래그먼트 셰이더 초입에서 X축을 강제로 반전시켜 좌우 반전 문제를 완벽히 해결했습니다.
        private const val FRAGMENT_SHADER_CODE = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            
            uniform float uDistortion; 
            uniform float uZoomScale;   
            uniform float uIPDOffset;   
            uniform int uIsFlipped; 

            void main() {
                vec2 readyCoord = vTextureCoord;
                
                // 🌟 [좌우 반전 원천 제거] 픽셀을 거울 모드가 아닌 실제 정방향 시야로 뒤집습니다.
                readyCoord.x = 1.0 - readyCoord.x;

                // 버튼 누를 시 상하반전 처리
                if (uIsFlipped == 1) {
                    readyCoord.y = 1.0 - readyCoord.y;
                }

                bool isRightEye = readyCoord.x > 0.5;
                vec2 uv;
                
                if (!isRightEye) {
                    uv = vec2((readyCoord.x * 2.0) - 0.5 + uIPDOffset, (readyCoord.y - 0.5));
                } else {
                    uv = vec2(((readyCoord.x - 0.5) * 2.0) - 0.5 - uIPDOffset, (readyCoord.y - 0.5));
                }

                float r2 = uv.x * uv.x + uv.y * uv.y;
                vec2 distortedUV = uv * (1.0 + uDistortion * r2 + uDistortion * r2 * r2);

                distortedUV *= uZoomScale;
                distortedUV += 0.5;

                if (distortedUV.x < 0.0 || distortedUV.x > 1.0 || distortedUV.y < 0.0 || distortedUV.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    vec2 finalTexCoord;
                    if (!isRightEye) {
                        finalTexCoord = vec2(distortedUV.x * 0.5, distortedUV.y);
                    } else {
                        finalTexCoord = vec2((distortedUV.x * 0.5) + 0.5, distortedUV.y);
                    }
                    gl_FragColor = texture2D(sTexture, finalTexCoord);
                }
            }
        """
    }
}