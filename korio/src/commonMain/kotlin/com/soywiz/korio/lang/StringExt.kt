package com.soywiz.korio.lang

import com.soywiz.kmem.*
import com.soywiz.korio.stream.*
import com.soywiz.korio.util.*

private val formatRegex = Regex("%([-]?\\d+)?(\\w)")

fun String.format(vararg params: Any): String {
	var paramIndex = 0
	return formatRegex.replace(this) { mr ->
		val param = params[paramIndex++]
		//println("param: $param")
		val size = mr.groupValues[1]
		val type = mr.groupValues[2]
		val str = when (type) {
			"d" -> (param as Number).toLong().toString()
			"X", "x" -> {
				val res = when (param) {
					is Int -> param.toStringUnsigned(16)
					else -> (param as Number).toLong().toStringUnsigned(16)
				}
				if (type == "X") res.toUpperCase() else res.toLowerCase()
			}
			else -> "$param"
		}
		val prefix = if (size.startsWith('0')) '0' else ' '
		val asize = size.toIntOrNull()
		var str2 = str
		if (asize != null) {
			while (str2.length < asize) {
				str2 = prefix + str2
			}
		}
		str2
	}
}

fun String.splitKeep(regex: Regex): List<String> {
	val str = this
	val out = arrayListOf<String>()
	var lastPos = 0
	for (part in regex.findAll(this)) {
		val prange = part.range
		if (lastPos != prange.start) {
			out += str.substring(lastPos, prange.start)
		}
		out += str.substring(prange)
		lastPos = prange.endInclusive + 1
	}
	if (lastPos != str.length) {
		out += str.substring(lastPos)
	}
	return out
}

private val replaceNonPrintableCharactersRegex by lazy { Regex("[^ -~]") }
fun String.replaceNonPrintableCharacters(replacement: String = "?"): String {
	return this.replace(replaceNonPrintableCharactersRegex, replacement)
}

fun String.toBytez(len: Int, charset: Charset = UTF8): ByteArray {
	val out = ByteArrayBuilder()
	out.append(this.toByteArray(charset))
	while (out.size < len) out.append(0.toByte())
	return out.toByteArray()
}

fun String.toBytez(charset: Charset = UTF8): ByteArray {
	val out = ByteArrayBuilder()
	out.append(this.toByteArray(charset))
	out.append(0.toByte())
	return out.toByteArray()
}

fun String.indexOfOrNull(char: Char, startIndex: Int = 0): Int? = this.indexOf(char, startIndex).takeIf { it >= 0 }

fun String.lastIndexOfOrNull(char: Char, startIndex: Int = lastIndex): Int? =
	this.lastIndexOf(char, startIndex).takeIf { it >= 0 }

fun String.splitInChunks(size: Int): List<String> {
	val out = arrayListOf<String>()
	var pos = 0
	while (pos < this.length) {
		out += this.substring(pos, kotlin.math.min(this.length, pos + size))
		pos += size
	}
	return out
}

fun String.substr(start: Int): String = this.substr(start, this.length)

fun String.substr(start: Int, length: Int): String {
	val low = (if (start >= 0) start else this.length + start).clamp(0, this.length)
	val high = (if (length >= 0) low + length else this.length + length).clamp(0, this.length)
	return if (high >= low) this.substring(low, high) else ""
}

fun String.transform(transform: (Char) -> String): String {
	var out = ""
	for (ch in this) out += transform(ch)
	return out
}

fun String.parseInt(): Int = when {
	this.startsWith("0x", ignoreCase = true) -> this.substring(2).toLong(16).toInt()
	else -> this.toInt()
}

val String.quoted: String get() = this.quote()
