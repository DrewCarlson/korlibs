/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2012-2018 DragonBones team and other contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.dragonbones.parser

import com.dragonbones.core.*
import com.dragonbones.model.*
import com.dragonbones.util.*
import com.soywiz.kds.*
import com.soywiz.kmem.*
import com.soywiz.korio.serialization.json.*
import com.soywiz.korma.math.*

/**
 * @private
 */
class BinaryDataParser  :  ObjectDataParser() {
	private var _binaryOffset: Int = 0
	private lateinit var _binary: MemBuffer
	private lateinit var _intArrayBuffer: Int16Buffer
	private lateinit var _frameArrayBuffer: Int16Buffer
	private lateinit var _timelineArrayBuffer: Uint16Buffer

	private fun _inRange(a: Int, min: Int, max: Int): Boolean {
		return min <= a && a <= max
	}

	private fun _decodeUTF8(data: Uint8Buffer): String {
		val EOF_byte = -1
		val EOF_code_point = -1
		val FATAL_POINT = 0xFFFD

		var pos = 0
		var result = ""
		var code_point: Int?
		var utf8_code_point: Int = 0
		var utf8_bytes_needed = 0
		var utf8_bytes_seen = 0
		var utf8_lower_boundary = 0

		while (data.size > pos) {

			val _byte = data[pos++]

			if (_byte == EOF_byte) {
				if (utf8_bytes_needed != 0) {
					code_point = FATAL_POINT
				}
				else {
					code_point = EOF_code_point
				}
			}
			else {
				if (utf8_bytes_needed == 0) {
					if (this._inRange(_byte, 0x00, 0x7F)) {
						code_point = _byte
					}
					else {
						if (this._inRange(_byte, 0xC2, 0xDF)) {
							utf8_bytes_needed = 1
							utf8_lower_boundary = 0x80
							utf8_code_point = _byte - 0xC0
						}
						else if (this._inRange(_byte, 0xE0, 0xEF)) {
							utf8_bytes_needed = 2
							utf8_lower_boundary = 0x800
							utf8_code_point = _byte - 0xE0
						}
						else if (this._inRange(_byte, 0xF0, 0xF4)) {
							utf8_bytes_needed = 3
							utf8_lower_boundary = 0x10000
							utf8_code_point = _byte - 0xF0
						}
						else {

						}
						utf8_code_point = (utf8_code_point * pow(64.0, utf8_bytes_needed.toDouble())).toInt()
						code_point = null
					}
				}
				else if (!this._inRange(_byte, 0x80, 0xBF)) {
					utf8_code_point = 0
					utf8_bytes_needed = 0
					utf8_bytes_seen = 0
					utf8_lower_boundary = 0
					pos--
					code_point = _byte
				}
				else {

					utf8_bytes_seen += 1
					utf8_code_point += ((_byte - 0x80) * pow(64.0, (utf8_bytes_needed - utf8_bytes_seen).toDouble())).toInt()

					if (utf8_bytes_seen != utf8_bytes_needed) {
						code_point = null
					}
					else {

						val cp = utf8_code_point
						val lower_boundary = utf8_lower_boundary
						utf8_code_point = 0
						utf8_bytes_needed = 0
						utf8_bytes_seen = 0
						utf8_lower_boundary = 0
						if (this._inRange(cp, lower_boundary, 0x10FFFF) && !this._inRange(cp, 0xD800, 0xDFFF)) {
							code_point = cp
						}
						else {
							code_point = _byte
						}
					}

				}
			}
			//Decode string
			if (code_point != null && code_point != EOF_code_point) {
				if (code_point <= 0xFFFF) {
					if (code_point > 0) result += code_point.toChar()
				}
				else {
					code_point -= 0x10000
					result += (0xD800 + ((code_point shr 10) and 0x3ff)).toChar()
					result += (0xDC00 + (code_point and 0x3ff)).toChar()
				}
			}
		}

		return result
	}

	private fun _parseBinaryTimeline(type: TimelineType, offset: Int, timelineData: TimelineData? = null): TimelineData {
		val timeline: TimelineData = if (timelineData != null) timelineData else BaseObject.borrowObject<TimelineData>()
		timeline.type = type
		timeline.offset = offset

		this._timeline = timeline

		val keyFrameCount = this._timelineArrayBuffer[timeline.offset + BinaryOffset.TimelineKeyFrameCount.index]
		if (keyFrameCount == 1) {
			timeline.frameIndicesOffset = -1
		}
		else {
			var frameIndicesOffset = 0
			val totalFrameCount = this._animation!!.frameCount + 1 // One more frame than animation.
			val frameIndices = this._data!!.frameIndices
			frameIndicesOffset = frameIndices.length
			frameIndices.length += totalFrameCount
			timeline.frameIndicesOffset = frameIndicesOffset

			var iK = 0
			var frameStart = 0
			var frameCount = 0
			for (i in 0 until totalFrameCount) {
				if (frameStart + frameCount <= i && iK < keyFrameCount) {
					frameStart = this._frameArrayBuffer[this._animation!!.frameOffset + this._timelineArrayBuffer[timeline.offset + BinaryOffset.TimelineFrameOffset.index + iK]].toInt()
					if (iK == keyFrameCount - 1) {
						frameCount = this._animation!!.frameCount - frameStart
					}
					else {
						frameCount = this._frameArrayBuffer[this._animation!!.frameOffset + this._timelineArrayBuffer[timeline.offset + BinaryOffset.TimelineFrameOffset.index + iK + 1]] - frameStart
					}

					iK++
				}

				frameIndices[frameIndicesOffset + i] = iK - 1
			}
		}

		this._timeline = null //

		return timeline
	}

	override fun _parseAnimation(rawData: Any?): AnimationData {
		val animation: AnimationData = BaseObject.borrowObject<AnimationData>()
		animation.blendType = DataParser._getAnimationBlendType(ObjectDataParser._getString(rawData, DataParser.BLEND_TYPE, ""))
		animation.frameCount = ObjectDataParser._getInt(rawData, DataParser.DURATION, 0)
		animation.playTimes = ObjectDataParser._getInt(rawData, DataParser.PLAY_TIMES, 1)
		animation.duration = animation.frameCount.toDouble() / this._armature!!.frameRate.toDouble() // float
		animation.fadeInTime = ObjectDataParser._getNumber(rawData, DataParser.FADE_IN_TIME, 0.0)
		animation.scale = ObjectDataParser._getNumber(rawData, DataParser.SCALE, 1.0)
		animation.name = ObjectDataParser._getString(rawData, DataParser.NAME, DataParser.DEFAULT_NAME)
		if (animation.name.isEmpty()) {
			animation.name = DataParser.DEFAULT_NAME
		}

		// Offsets.
		val offsets = rawData[DataParser.OFFSET] as  IntArrayList
		animation.frameIntOffset = offsets[0]
		animation.frameFloatOffset = offsets[1]
		animation.frameOffset = offsets[2]

		this._animation = animation

		if (DataParser.ACTION in rawData) {
			animation.actionTimeline = this._parseBinaryTimeline(TimelineType.Action, rawData[DataParser.ACTION] as Int)
		}

		if (DataParser.Z_ORDER in rawData) {
			animation.zOrderTimeline = this._parseBinaryTimeline(TimelineType.ZOrder, rawData[DataParser.Z_ORDER] as Int)
		}

		if (DataParser.BONE in rawData) {
			val rawTimeliness = rawData[DataParser.BONE]
			for (k in rawTimeliness.keys) {
				val rawTimelines = rawTimeliness[k] as  IntArrayList
				val bone = this._armature?.getBone(k) ?: continue

				for (i in 0 until rawTimelines.size step 2) {
					val timelineType = TimelineType[rawTimelines[i]]
					val timelineOffset = rawTimelines[i + 1]
					val timeline = this._parseBinaryTimeline(timelineType, timelineOffset)
					this._animation?.addBoneTimeline(bone.name, timeline)
				}
			}
		}

		if (DataParser.SLOT in rawData) {
			val rawTimeliness = rawData[DataParser.SLOT]
			for (k in rawTimeliness.keys) {
				val rawTimelines = rawTimeliness[k] as  IntArrayList
				val slot = this._armature?.getSlot(k) ?: continue

				for (i in 0 until rawTimelines.size step 2) {
					val timelineType = TimelineType[rawTimelines[i]]
					val timelineOffset = rawTimelines[i + 1]
					val timeline = this._parseBinaryTimeline(timelineType, timelineOffset)
					this._animation?.addSlotTimeline(slot.name, timeline)
				}
			}
		}

		if (DataParser.CONSTRAINT in rawData) {
			val rawTimeliness = rawData[DataParser.CONSTRAINT]
			for (k in rawTimeliness.keys) {
				val rawTimelines = rawTimeliness[k] as  IntArrayList
				val constraint = this._armature?.getConstraint(k) ?: continue

				for (i in 0 until rawTimelines.size step 2) {
					val timelineType = TimelineType[rawTimelines[i]]
					val timelineOffset = rawTimelines[i + 1]
					val timeline = this._parseBinaryTimeline(timelineType, timelineOffset)
					this._animation?.addConstraintTimeline(constraint.name, timeline)
				}
			}
		}

		if (DataParser.TIMELINE in rawData) {
			val rawTimelines = rawData[DataParser.TIMELINE] as ArrayList<Any?>
			for (rawTimeline in rawTimelines) {
				val timelineOffset = ObjectDataParser._getInt(rawTimeline, DataParser.OFFSET, 0)
				if (timelineOffset >= 0) {
					val timelineType = TimelineType[ObjectDataParser._getInt(rawTimeline, DataParser.TYPE, TimelineType.Action.id)]
					val timelineName = ObjectDataParser._getString(rawTimeline, DataParser.NAME, "")
					var timeline: TimelineData? = null

					if (timelineType == TimelineType.AnimationProgress && animation.blendType != AnimationBlendType.None) {
						timeline = BaseObject.borrowObject<AnimationTimelineData>()
						val animaitonTimeline = timeline
						animaitonTimeline.x = ObjectDataParser._getNumber(rawTimeline, DataParser.X, 0.0)
						animaitonTimeline.y = ObjectDataParser._getNumber(rawTimeline, DataParser.Y, 0.0)
					}

					timeline = this._parseBinaryTimeline(timelineType, timelineOffset, timeline)

					when (timelineType) {
						TimelineType.Action -> {
							// TODO
						}

						TimelineType.ZOrder -> {
							// TODO
						}

						TimelineType.BoneTranslate,
						TimelineType.BoneRotate,
						TimelineType.BoneScale,
						TimelineType.Surface,
						TimelineType.BoneAlpha -> {
							this._animation?.addBoneTimeline(timelineName, timeline)
						}

						TimelineType.SlotDisplay,
						TimelineType.SlotColor,
						TimelineType.SlotDeform,
						TimelineType.SlotZIndex,
						TimelineType.SlotAlpha -> {
							this._animation?.addSlotTimeline(timelineName, timeline)
						}

						TimelineType.IKConstraint -> {
							this._animation?.addConstraintTimeline(timelineName, timeline)
						}

						TimelineType.AnimationProgress,
						TimelineType.AnimationWeight,
						TimelineType.AnimationParameter -> {
							this._animation?.addAnimationTimeline(timelineName, timeline)
						}
					}
				}
			}
		}

		this._animation = null

		return animation
	}

	override fun _parseGeometry(rawData: Any?, geometry: GeometryData): Unit {
		geometry.offset = rawData[DataParser.OFFSET] as Int
		geometry.data = this._data

		val weightOffset = this._intArrayBuffer[geometry.offset + BinaryOffset.GeometryWeightOffset.index].toInt()
		if (weightOffset >= 0) {
			val weight = BaseObject.borrowObject<WeightData>()
			val vertexCount = this._intArrayBuffer[geometry.offset + BinaryOffset.GeometryVertexCount.index]
			val boneCount = this._intArrayBuffer[weightOffset + BinaryOffset.WeigthBoneCount.index]
			weight.offset = weightOffset

			for (i in 0 until boneCount) {
				val boneIndex = this._intArrayBuffer[weightOffset + BinaryOffset.WeigthBoneIndices.index + i].toInt()
				weight.addBone(this._rawBones[boneIndex])
			}

			var boneIndicesOffset = weightOffset + BinaryOffset.WeigthBoneIndices.index + boneCount
			var weightCount = 0
			for (i in 0 until vertexCount) {
				val vertexBoneCount = this._intArrayBuffer[boneIndicesOffset++]
				weightCount += vertexBoneCount
				boneIndicesOffset += vertexBoneCount
			}

			weight.count = weightCount
			geometry.weight = weight
		}
	}

	override fun _parseArray(rawData: Any?): Unit {
		val offsets = rawData[DataParser.OFFSET] as  IntArrayList
		val l1 = offsets[1]
		val l2 = offsets[3]
		val l3 = offsets[5]
		val l4 = offsets[7]
		val l5 = offsets[9]
		val l6 = offsets[11]
		val l7 = if (offsets.size > 12) offsets[13] else 0 // Color.
		val binary = this._binary
		val intArray = binary.sliceInt16Buffer(this._binaryOffset + offsets[0], l1 / Int16Buffer_BYTES_PER_ELEMENT)
		val floatArray = binary.sliceFloat32Buffer(this._binaryOffset + offsets[2], l2 / Float32Buffer_BYTES_PER_ELEMENT)
		val frameIntArray = binary.sliceInt16Buffer(this._binaryOffset + offsets[4], l3 / Int16Buffer_BYTES_PER_ELEMENT)
		val frameFloatArray = binary.sliceFloat32Buffer(this._binaryOffset + offsets[6], l4 / Float32Buffer_BYTES_PER_ELEMENT)
		val frameArray = binary.sliceInt16Buffer(this._binaryOffset + offsets[8], l5 / Int16Buffer_BYTES_PER_ELEMENT)
		val timelineArray = binary.sliceUint16Buffer(this._binaryOffset + offsets[10], l6 / Uint16Buffer_BYTES_PER_ELEMENT)
		val colorArray = if (l7 > 0) binary.sliceInt16Buffer(this._binaryOffset + offsets[12], l7 / Int16Buffer_BYTES_PER_ELEMENT) else intArray // Color.

		this._data!!.binary = this._binary
		this._data!!.intArray = intArray
		this._intArrayBuffer = intArray
		this._data!!.floatArray = floatArray
		this._data!!.frameIntArray = frameIntArray
		this._data!!.frameFloatArray = frameFloatArray
		this._data!!.frameArray = frameArray
		this._frameArrayBuffer = frameArray
		this._data!!.timelineArray = timelineArray
		this._timelineArrayBuffer = timelineArray
		this._data!!.colorArray = colorArray
	}

	override fun parseDragonBonesData(rawData: Any?, scale: Double): DragonBonesData? {
		//console.assert(rawData != null && rawData is MemBuffer, "Data error.")

		val tag = NewUint8Buffer(rawData as MemBuffer, 0, 8)
		if (tag[0] != 'D'.toInt() || tag[1] != 'B'.toInt() || tag[2] != 'D'.toInt() || tag[3] != 'T'.toInt()) {
			console.assert(false, "Nonsupport data.")
			return null
		}

		val headerLength = NewInt32Buffer(rawData, 8, 1)[0]
		val headerBytes = NewUint8Buffer(rawData, 8 + 4, headerLength)
		val headerString = this._decodeUTF8(headerBytes)
		val header = Json.parse(headerString)
		//
		this._binaryOffset = 8 + 4 + headerLength
		this._binary = rawData

		return super.parseDragonBonesData(header, scale)
	}

	companion object {
		private var _binaryDataParserInstance: BinaryDataParser? = null
		/**
		 * - Deprecated, please refer to {@link dragonBones.BaseFactory#parseDragonBonesData()}.
		 * @deprecated
		 * @language en_US
		 */
		/**
		 * - 已废弃，请参考 {@link dragonBones.BaseFactory#parseDragonBonesData()}。
		 * @deprecated
		 * @language zh_CN
		 */
		fun getInstance(): BinaryDataParser {
			if (BinaryDataParser._binaryDataParserInstance == null) {
				BinaryDataParser._binaryDataParserInstance = BinaryDataParser()
			}

			return BinaryDataParser._binaryDataParserInstance!!
		}
	}
}
