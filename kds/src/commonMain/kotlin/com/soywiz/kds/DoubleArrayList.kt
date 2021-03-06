package com.soywiz.kds

import kotlin.math.*

class DoubleArrayList(capacity: Int = 7) : NumberArrayList(), Collection<Double> {
	var data: DoubleArray = DoubleArray(capacity); private set
	internal val capacity: Int get() = data.size
	private var length: Int = 0
	override var size: Int get() = length
		set(value) {
			ensure(value)
			this.length = value
		}

	constructor(other: DoubleArrayList) : this() {
		add(other)
	}

	constructor(vararg other: Double) : this() {
		add(other)
	}

	private fun ensure(count: Int) {
		if (length + count > data.size) {
			data = data.copyOf(max(length + count, data.size * 3))
		}
	}

	fun clear() = run { length = 0 }

	fun add(value: Double) {
		ensure(1)
		data[length++] = value
	}

	operator fun plusAssign(value: Double) = add(value)
	operator fun plusAssign(value: DoubleArray) = add(value)
	operator fun plusAssign(value: DoubleArrayList) = add(value)
	operator fun plusAssign(value: Iterable<Double>) = add(value)

	fun add(values: DoubleArray, offset: Int = 0, length: Int = values.size) {
		ensure(values.size)
		MemTools.arraycopy(values, offset, data, this.length, length)
		this.length += values.size
	}

	fun add(values: DoubleArrayList) = add(values.data, 0, values.length)
	fun add(values: Iterable<Double>) = run { for (v in values) add(v) }

	operator fun get(index: Int): Double = if (index in 0 until length) data[index] else 0.0
	operator fun set(index: Int, value: Double) = run {
		if (index >= length) {
			ensure(index + 1)
			length = index + 1
		}
		data[index] = value
	}

	override fun iterator(): Iterator<Double> = data.take(length).iterator()

	override fun contains(element: Double): Boolean {
		for (n in 0 until length) if (this.data[n] == element) return true
		return false
	}

	override fun containsAll(elements: Collection<Double>): Boolean {
		for (e in elements) if (!contains(e)) return false
		return true
	}

	@Suppress("ReplaceSizeZeroCheckWithIsEmpty")
	override fun isEmpty(): Boolean = this.size == 0

	fun indexOf(value: Double, start: Int = 0, end: Int = this.size): Int {
		for (n in start until end) if (data[n] == value) return n
		return -1
	}

	fun removeAt(index: Int): Double {
		if (index < 0 || index >= length) throw IndexOutOfBoundsException()
		val out = data[index]
		if (index < length - 1) MemTools.arraycopy(data, index + 1, data, index, length - index - 1)
		length--
		return out
	}

	override fun getDouble(index: Int): Double = this[index]
	override fun setDouble(index: Int, value: Double) = run { this[index] = value }

	fun toDoubleArray() = this.data.copyOf(length)
}

fun DoubleArrayList.binarySearch(value: Double) = data.binarySearch(value, 0, size)
fun doubleArrayListOf(vararg doubles: Double) = DoubleArrayList(*doubles)
