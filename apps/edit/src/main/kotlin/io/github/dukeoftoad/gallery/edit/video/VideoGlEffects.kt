package io.github.dukeoftoad.gallery.edit.video

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The GL realization of the nine ADR-0012 adjustments (r42, contract
 * decisions 6-7): ONE shader + ONE uniform mapping, held by
 * [VideoAdjustProjection] in :shared:video-edit and consumed VERBATIM by
 * both realizations --- the ExoPlayer preview (ExoPlayer.setVideoEffects)
 * and the Transformer export (EditedMediaItem.Builder.setEffects) --- so
 * preview and export agree by construction: there is nothing to agree
 * about, both sides compile the same source and bind the same
 * [VideoAdjustProjection.uniforms] over the same [VideoAdjustParams].
 *
 * The parameter struct, the projection math and the shader text are pure
 * Kotlin (JVM-pinned edge by edge, including the nine-row table
 * conformance); this file is the thin media3 binding --- the ONLY place
 * the edit app compiles GL. The stage order inside the shader is the
 * photo pipeline's LutBuilder order (exposure -> brightness -> contrast
 * -> highlights/shadows -> saturation -> temperature/tint -> vignette),
 * so the same parameter VALUES mean the same colour math the photo
 * editor taught.
 *
 * The 1.11.1 effect contract this binds (verified against the
 * media3-effect 1.11.1 artifact): [GlEffect.toGlShaderProgram] creates
 * the program; [BaseGlShaderProgram] owns the output texture pool, focuses
 * the output framebuffer, clears it and THEN calls [drawFrame], which
 * only binds the INPUT texture as the sampler and draws the full-canvas
 * quad. The vertex/texcoord convention is media3's own
 * (vertex_shader_transformation_es2 with identity matrices):
 * `vTexCoord = aFramePosition.xy * 0.5 + 0.5`, the full NDC quad ---
 * the shared shader's VERTEX_SHADER_LINES encode exactly that, so no
 * flip matrix is needed. The input frame arrives as a regular
 * GL_TEXTURE_2D (media3 hands shader programs 2D intermediate textures,
 * the fragment_shader_lut_es2 sampler2D precedent).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoAdjustShaderProgram(
    private val params: VideoAdjustParams,
    useHighPrecisionColorFloats: Boolean,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents= */ useHighPrecisionColorFloats,
    /* texturePoolCapacity= */ 1,
) {

    private var programHandle: Int = 0
    private var positionLoc: Int = 0
    private var samplerLoc: Int = 0
    private var uniformLocs: Map<String, Int> = emptyMap()

    // The full-canvas quad as two triangles of (x, y) NDC pairs; the
    // shared vertex shader derives the texture coordinates (media3's own
    // TRIANGLE_STRIP bounds cover the same quad).
    private val quad: FloatBuffer = floatArrayOf(
        -1f, -1f,
        +1f, -1f,
        -1f, +1f,
        -1f, +1f,
        +1f, -1f,
        +1f, +1f,
    ).toDirectFloatBuffer()

    /** The output frame matches the input frame: no geometry on video v1. */
    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    /**
     * Draws the input frame through the shared adjustment shader into the
     * ALREADY-FOCUSED output framebuffer (the base class focuses and
     * clears it): bind the input texture as the sampler, push the
     * projection's uniforms, draw the quad. Nothing else --- the base
     * class owns every texture and framebuffer.
     */
    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        ensureProgram()
        GLES20.glUseProgram(programHandle)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        GlUtil.checkGlException(samplerLoc != -1, "the sampler uniform is missing")
        GLES20.glUniform1i(samplerLoc, 0)
        for (uniform in VideoAdjustProjection.uniforms(params)) {
            GLES20.glUniform1f(uniformLocs.getValue(uniform.name), uniform.value)
        }
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** Compiles the shared shader pair once, lazily on the GL thread. */
    private fun ensureProgram() {
        if (programHandle != 0) return
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VideoAdjustProjection.vertexShaderSource())
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, VideoAdjustProjection.fragmentShaderSource())
        val program = GLES20.glCreateProgram()
        GlUtil.checkGlException(program != 0, "glCreateProgram failed")
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        GlUtil.checkGlException(
            linked[0] == GLES20.GL_TRUE,
            "program link failed: " + GLES20.glGetProgramInfoLog(program),
        )
        programHandle = program
        positionLoc = GLES20.glGetAttribLocation(program, "aFramePosition")
        samplerLoc = GLES20.glGetUniformLocation(program, "uTexSampler")
        // the uniform mapping is pinned by the shared module's tests: the
        // shader declares exactly these names, in this order.
        uniformLocs = VideoAdjustProjection.uniforms(params).associate { uniform ->
            uniform.name to GLES20.glGetUniformLocation(program, uniform.name)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GlUtil.checkGlException(shader != 0, "glCreateShader failed")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        GlUtil.checkGlException(
            compiled[0] == GLES20.GL_TRUE,
            "shader compile failed: " + GLES20.glGetShaderInfoLog(shader),
        )
        return shader
    }

    private fun FloatArray.toDirectFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(this@toDirectFloatBuffer); position(0) }
}

/**
 * The [GlEffect] handle both realizations receive: carries the parameter
 * struct to the shader program media3 builds for it. The effect instance
 * changes identity on every parameter commit, which is what makes the
 * preview re-apply (ExoPlayer.setVideoEffects re-sends the effect list
 * to the video renderer; a fresh instance is a fresh program).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoAdjustEffect(private val params: VideoAdjustParams) : GlEffect {

    override fun toGlShaderProgram(
        context: Context,
        useHighPrecisionColorFloats: Boolean,
    ): GlShaderProgram = VideoAdjustShaderProgram(params, useHighPrecisionColorFloats)
}
