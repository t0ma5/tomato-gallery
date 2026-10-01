/*
 * Copyright 2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 *
 * OpenGL ES 2.0 panorama renderer adapted from ReFra's panoramaviewer
 * (https://github.com/IacobIonut01/ReFra) for a View-based Activity.
 */

package tomato.gallery.panorama

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

class PanoramaGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {
    enum class Projection { SPHERE, CYLINDER }

    private val renderer = PanoramaRenderer()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    var yaw = 0f
        private set
    var pitch = 0f
        private set
    var fov = 70f
        private set

    init {
        setEGLContextClientVersion(2)
        setPreserveEGLContextOnPause(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setBitmap(bitmap: Bitmap, projection: Projection) {
        queueEvent {
            renderer.setBitmap(bitmap, projection)
            requestRender()
        }
    }

    fun addRotation(deltaYaw: Float, deltaPitch: Float) {
        yaw = (yaw + deltaYaw) % 360f
        val maxPitch = if (renderer.projection == Projection.CYLINDER) 45f else 89f
        pitch = (pitch + deltaPitch).coerceIn(-maxPitch, maxPitch)
        requestRender()
    }

    fun setFov(value: Float) {
        fov = value.coerceIn(30f, 110f)
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                addRotation(-dx * 0.15f, -dy * 0.15f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setFov(fov / detector.scaleFactor)
            return true
        }
    }

    private inner class PanoramaRenderer : Renderer {
        var projection = Projection.SPHERE
            private set
        private var mesh: SphereGeometry.Mesh? = null
        private var program = 0
        private var textureId = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var mvpHandle = 0
        private var textureHandle = 0
        private val mvp = FloatArray(16)
        private val view = FloatArray(16)
        private val proj = FloatArray(16)
        private var pendingBitmap: Bitmap? = null
        private var retainedBitmap: Bitmap? = null
        private var pendingProjection = Projection.SPHERE

        fun setBitmap(bitmap: Bitmap, projection: Projection) {
            retainedBitmap = bitmap
            pendingBitmap = bitmap
            pendingProjection = projection
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_FRONT)
            program = buildProgram(VERTEX, FRAGMENT)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            mvpHandle = GLES20.glGetUniformLocation(program, "uMVP")
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            pendingBitmap = retainedBitmap
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val aspect = width.toFloat() / max(height, 1)
            Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 10f)
        }

        override fun onDrawFrame(gl: GL10?) {
            pendingBitmap?.let { bitmap ->
                uploadTexture(bitmap)
                projection = pendingProjection
                mesh = if (projection == Projection.SPHERE) {
                    SphereGeometry.createSphere()
                } else {
                    val aspect = bitmap.width.toFloat() / max(bitmap.height, 1)
                    val arc = min(360f, aspect * 90f)
                    SphereGeometry.createCylinder(height = 2f / max(aspect, 0.01f), arcDegrees = arc)
                }
                pendingBitmap = null
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val currentMesh = mesh ?: return
            val aspect = width.toFloat() / max(height, 1)
            Matrix.perspectiveM(proj, 0, fov, aspect, 0.1f, 10f)
            Matrix.setLookAtM(view, 0, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
            Matrix.rotateM(view, 0, pitch, 1f, 0f, 0f)
            Matrix.rotateM(view, 0, yaw, 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(textureHandle, 0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, currentMesh.vertices)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, currentMesh.texCoords)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, currentMesh.indexCount, GLES20.GL_UNSIGNED_SHORT, currentMesh.indices)
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        private fun uploadTexture(bitmap: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        }

        private fun buildProgram(vertex: String, fragment: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            return prog
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }
    }

    companion object {
        private const val VERTEX = """
            uniform mat4 uMVP;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVP * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
