package com.soywiz.korge.view.filter

import com.soywiz.korag.*
import com.soywiz.korag.shader.*
import com.soywiz.korge.render.*
import com.soywiz.korge.view.*
import com.soywiz.korge.view.effect.*
import com.soywiz.korma.*

abstract class Filter {
	companion object {
		//val u_Time = Uniform("time", VarType.Float1)
		val u_TextureSize = Uniform("effectTextureSize", VarType.Float2)
		val DEFAULT_FRAGMENT = BatchBuilder2D.buildTextureLookupFragment(premultiplied = false)

		val Program.Builder.fragmentCoords01 get() = DefaultShaders.v_Tex["xy"]
		val Program.Builder.fragmentCoords get() = fragmentCoords01 * u_TextureSize
		fun Program.Builder.tex(coords: Operand) = texture2D(DefaultShaders.u_Tex, coords / u_TextureSize)
	}

	val uniforms = AG.UniformValues(
		//EffectView.u_Time to timeHolder,
		//EffectView.u_TextureSize to textureSizeHolder
	)

	open val border: Int = 0

	var vertex: VertexShader = BatchBuilder2D.VERTEX
		set(value) {
			field = value
			program = null
		}
	var fragment: FragmentShader = EffectView.DEFAULT_FRAGMENT
		set(value) {
			field = value
			program = null
		}

	var program: Program? = null

	internal val tempMat2d = Matrix2d()
	internal val oldViewMatrix = Matrix4()

	private val textureSizeHolder = FloatArray(2)

	protected open fun updateUniforms() {
	}

	open fun render(
		ctx: RenderContext,
		matrix: Matrix2d,
		texture: Texture,
		texWidth: Int,
		texHeight: Int,
		renderColorAdd: Int,
		renderColorMulInt: Int,
		blendMode: BlendMode
	) {
		// @TODO: Precompute vertices
		textureSizeHolder[0] = texture.base.width.toFloat()
		textureSizeHolder[1] = texture.base.height.toFloat()
		updateUniforms()

		if (program == null) program = Program(vertex, fragment)

		ctx.batch.setTemporalUniforms(this.uniforms) {
			ctx.batch.drawQuad(
				texture,
				m = matrix,
				filtering = true,
				colorAdd = renderColorAdd,
				colorMulInt = renderColorMulInt,
				blendFactors = blendMode.factors,
				program = program
			)
		}
	}
}

class ComposedFilter(val filters: List<Filter>) : Filter() {
	constructor(vararg filters: Filter) : this(filters.toList())

	override val border get() = filters.sumBy { it.border }

	override fun render(
		ctx: RenderContext,
		matrix: Matrix2d,
		texture: Texture,
		texWidth: Int,
		texHeight: Int,
		renderColorAdd: Int,
		renderColorMulInt: Int,
		blendMode: BlendMode
	) {
		if (filters.isEmpty()) {
			super.render(ctx, matrix, texture, texWidth, texHeight, renderColorAdd, renderColorMulInt, blendMode)
		} else {
			renderIndex(ctx, matrix, texture, texWidth, texHeight, renderColorAdd, renderColorMulInt, blendMode, 0)
		}
	}
	private val identity = Matrix2d()

	fun renderIndex(
		ctx: RenderContext,
		matrix: Matrix2d,
		texture: Texture,
		texWidth: Int,
		texHeight: Int,
		renderColorAdd: Int,
		renderColorMulInt: Int,
		blendMode: BlendMode,
		index: Int
	) {
		val filter = filters[index]
		val isLast = index >= filters.size - 1
		if (isLast) {
			filter.render(ctx, matrix, texture, texWidth, texHeight, renderColorAdd, renderColorMulInt, blendMode)
		} else {
			// @TODO: We only need two render textures
			ctx.renderToTexture(texWidth, texHeight, {
				ctx.batch.setTemporalUniforms(this.uniforms) {
					ctx.batch.drawQuad(
						texture,
						m = identity,
						filtering = true,
						colorAdd = renderColorAdd,
						colorMulInt = renderColorMulInt,
						blendFactors = blendMode.factors,
						program = program
					)
				}
			}, { newtex ->
				renderIndex(ctx, matrix, texture, texWidth, texHeight, renderColorAdd, renderColorMulInt, blendMode, index + 1)
			})
		}
	}
}