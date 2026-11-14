package com.toadgallery.editor.video

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
 * The GL realization of the nine ADR-0012 adjustments: ONE shader + ONE uniform mapping held by
 * [VideoAdjustProjection] and consumed verbatim by both the ExoPlayer preview and the Transformer
 * export, so preview and export agree by construction. The parameter struct, projection math and
 * shader text are pure Kotlin (JVM-pinned); this file is the thin media3 binding --- the ONLY
 * place the edit app compiles GL. The shader's stage order is the photo pipeline's LutBuilder
 * order, so the same parameter VALUES mean the same colour math.
 *
 * media3 1.11.1 contract: [BaseGlShaderProgram] owns the output texture pool, focuses and clears
 * the output framebuffer, then calls [drawFrame] (bind the input texture, draw the full-canvas
 * NDC quad). The vertex/texcoord convention (`vTexCoord = aFramePosition.xy * 0.5 + 0.5`) is
 * encoded in the shared VERTEX_SHADER_LINES --- no flip matrix. The input frame is a regular
 * GL_TEXTURE_2D.
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

    // Full-canvas quad as two triangles of (x, y) NDC pairs; the shared vertex
    // shader derives the texture coordinates.
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
     * Draws the input frame through the shared shader into the ALREADY-FOCUSED output
     * framebuffer (the base class focuses/clears it); the base class owns every texture
     * and framebuffer.
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
        // The uniform mapping is pinned by the shared module's tests: the shader declares exactly these names.
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
 * The [GlEffect] handle both realizations receive: carries the parameter struct to the shader
 * program media3 builds. The instance changes identity on every parameter commit, which is
 * what makes the preview re-apply (a fresh instance is a fresh program).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoAdjustEffect(private val params: VideoAdjustParams) : GlEffect {

    override fun toGlShaderProgram(
        context: Context,
        useHighPrecisionColorFloats: Boolean,
    ): GlShaderProgram = VideoAdjustShaderProgram(params, useHighPrecisionColorFloats)
}

/**
 * The GL realization of the shared look: the parsed `.cube` uploaded as ONE 2D texture --- the
 * RED-MAJOR unwrap, x = r + g*size, y = b, size^2 x size texels --- sampled trilinearly by the
 * look stage's shader lines ([VideoAdjustProjection.LOOK_STAGE_LINES], the same arithmetic
 * [VideoLutSampler.sampleCube] pins on the JVM). The shader is the composed source with a
 * neutral parameter set: this effect OWNS the look stage, [VideoAdjustEffect] owns the nine
 * sliders, and the two chain in media3's effects list (adjustments, then the look over the
 * graded pixels).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoLookShaderProgram(
    private val look: VideoLutLook,
    useHighPrecisionColorFloats: Boolean,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents= */ useHighPrecisionColorFloats,
    /* texturePoolCapacity= */ 1,
) {

    private var programHandle: Int = 0
    private var positionLoc: Int = 0
    private var samplerLoc: Int = 0
    private var lookSamplerLoc: Int = 0
    private var uniformLocs: Map<String, Int> = emptyMap()
    private var lookTexId: Int = 0

    // The full-canvas quad, same as the adjust program's.
    private val quad: FloatBuffer = floatArrayOf(
        -1f, -1f,
        +1f, -1f,
        -1f, +1f,
        -1f, +1f,
        +1f, -1f,
        +1f, +1f,
    ).toDirectFloatBuffer()

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        ensureProgram()
        GLES20.glUseProgram(programHandle)
        // The frame texture on unit 0...
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        GLES20.glUniform1i(samplerLoc, 0)
        // ...the look's unwrapped cube on unit 1 (LINEAR filtering does the r/g/b axis
        // interpolation's first half; the shader's trilinear math rides on top).
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lookTexId)
        GLES20.glUniform1i(lookSamplerLoc, 1)
        val neutral = VideoAdjustParams()
        for (uniform in VideoAdjustProjection.uniforms(neutral)) {
            GLES20.glUniform1f(uniformLocs.getValue(uniform.name), uniform.value)
        }
        for (uniform in VideoAdjustProjection.lookUniforms(look)) {
            GLES20.glUniform1f(uniformLocs.getValue(uniform.name), uniform.value)
        }
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Compiles the composed shader and uploads the unwrapped cube as one RGBA 2D texture:
     * size^2 wide (r + g*size), size tall (b), row-major --- the layout the look stage's
     * UV math walks.
     */
    private fun ensureProgram() {
        if (programHandle != 0) return
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VideoAdjustProjection.vertexShaderSource())
        val fragment = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            VideoAdjustProjection.fragmentShaderWithLookStage(),
        )
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
        lookSamplerLoc = GLES20.glGetUniformLocation(program, "uLookSampler")
        uniformLocs = (
            VideoAdjustProjection.uniforms(VideoAdjustParams()) +
                VideoAdjustProjection.lookUniforms(look)
            ).associate { uniform ->
                uniform.name to GLES20.glGetUniformLocation(program, uniform.name)
            }
        // The cube unwrap: size^2 x size RGBA bytes.
        val size = look.size
        val width = size * size
        val height = size
        val pixels = java.nio.ByteBuffer.allocateDirect(width * height * 4)
            .order(ByteOrder.nativeOrder())
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    // x = r + g*size, y = b --- the red-major .cube order.
                    val base = (r + g * size + b * size * size) * 3
                    val x = r + g * size
                    // The b-axis half-texel offset rides the UV math; the
                    // texture itself stores the RAW samples.
                    val idx = (x + b * width) * 4
                    pixels.put(idx, f255(look.data[base]))
                    pixels.put(idx + 1, f255(look.data[base + 1]))
                    pixels.put(idx + 2, f255(look.data[base + 2]))
                    pixels.put(idx + 3, 0xFF.toByte())
                }
            }
        }
        pixels.position(0)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        lookTexId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lookTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            pixels,
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun f255(v: Double): Byte = (v * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()

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

/** The look's [GlEffect] handle (the VideoAdjustEffect shape). */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoLookEffect(private val look: VideoLutLook) : GlEffect {

    override fun toGlShaderProgram(
        context: Context,
        useHighPrecisionColorFloats: Boolean,
    ): GlShaderProgram = VideoLookShaderProgram(look, useHighPrecisionColorFloats)
}
