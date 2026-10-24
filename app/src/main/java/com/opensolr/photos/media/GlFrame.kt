package com.opensolr.photos.media

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A surface a video decoder draws into, read back as a picture through an offscreen OpenGL texture: the way that
 * works whatever format a phone's decoder writes. Made, used and released on one thread (the [handler]'s).
 */
class GlFrame(private val width: Int, private val height: Int, handler: Handler, onFrame: (Bitmap) -> Unit) {

    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val pbuffer: EGLSurface
    private val texture: Int
    private val program: Int
    private val texture2: SurfaceTexture
    val surface: Surface
    private var done = false

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "no EGL" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0)
        val config = configs[0] ?: error("no EGL config")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "EGL not current" }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        program = link(
            "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTex; varying vec2 vTex; void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTex; uniform samplerExternalOES s; void main() { gl_FragColor = texture2D(s, vTex); }",
        )
        texture2 = SurfaceTexture(texture).apply { setDefaultBufferSize(width, height) }
        surface = Surface(texture2)
        texture2.setOnFrameAvailableListener({ st ->
            if (done) return@setOnFrameAvailableListener
            runCatching { read(st) }.getOrNull()?.let { done = true; onFrame(it) }
        }, handler)
    }

    private fun read(st: SurfaceTexture): Bitmap {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        st.updateTexImage()
        val m = FloatArray(16)
        st.getTransformMatrix(m)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val tex = floats(0f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 4, GLES20.GL_FLOAT, false, 0, tex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, m, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "s"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        pixels.rewind()
        val upside = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        upside.copyPixelsFromBuffer(pixels)
        // OpenGL reads from the bottom row up
        val upright = Bitmap.createBitmap(upside, 0, 0, width, height, Matrix().apply { postScale(1f, -1f) }, false)
        if (upright !== upside) upside.recycle()
        return upright
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { texture2.release() }
        runCatching {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun floats(vararg v: Float) = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

    private fun link(vertex: String, fragment: String): Int {
        fun shader(type: Int, code: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, code); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(p)
        }
    }
}
