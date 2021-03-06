package com.soywiz.korim.bitmap

import com.soywiz.korio.crypto.*
import com.soywiz.korio.lang.*
import kotlin.test.*

class Bitmap1Test {
	@kotlin.test.Test
	fun name() {
		val bmp = Bitmap1(4, 4)
		assertEquals(
			"""
				....
				....
				....
				....
			""".trimIndent(),
			bmp.toLines(".X").joinToString("\n")
		)

		bmp[0, 0] = 1
		bmp[1, 1] = 1
		bmp[1, 2] = 1
		bmp[3, 3] = 1

		assertEquals(
			"""
				X...
				.X..
				.X..
				...X
			""".trimIndent(),
			bmp.toLines(".X").joinToString("\n")
		)

		assertEquals(
			"21:82",
			bmp.data.hex.splitInChunks(2).joinToString(":")
		)
	}
}