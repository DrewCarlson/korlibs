/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soywiz.korau.format.org.gragravarr.opus

import com.soywiz.klogger.*
import com.soywiz.korau.format.org.gragravarr.ogg.*
import com.soywiz.korau.format.org.gragravarr.ogg.audio.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.stream.*

/**
 * This is a wrapper around an OggFile that lets you
 * get at all the interesting bits of an Opus file.
 */
class OpusFile : OggAudioStream, OggAudioHeaders, Closeable {
	/**
	 * Returns the underlying Ogg File instance
	 * @return
	 */
	var oggFile: OggFile? = null
		private set
	private var r: OggPacketReader? = null
	private var w: OggPacketWriter? = null
	/**
	 * Returns the Ogg Stream ID
	 */
	override var sid = -1
		private set

	override var info: OpusInfo? = null
		private set
	override var tags: OpusTags? = null
		private set

	private var writtenPackets: MutableList<OpusAudioData> = arrayListOf()

	override val nextAudioPacket: OpusAudioData?
		get() {
			var p: OggPacket? = null
			var op: OpusPacket? = null
			while (true) {
				p = r!!.getNextPacketWithSid(sid) ?: break
				op = OpusPacketFactory.create(p)
				if (op is OpusAudioData) {
					return op as OpusAudioData?
				} else {
					Logger("OpusFile").error { "Skipping non audio packet $op mid audio stream" }
				}
			}
			return null
		}

	/**
	 * This is an Opus file
	 */
	override val type: OggStreamIdentifier.OggStreamType
		get() = OggStreamIdentifier.OPUS_AUDIO
	/**
	 * Opus doesn't have setup headers, so this is always null
	 */
	override val setup: OggAudioSetupHeader?
		get() = null

	/**
	 * Opens the given file for reading
	 */
	constructor(ogg: OggFile) : this(ogg.packetReader) {
		this.oggFile = ogg
	}

	/**
	 * Loads a Opus File from the given packet reader.
	 */
	constructor(r: OggPacketReader) {
		this.r = r

		var p: OggPacket? = null
		while (true) {
			p = r.getNextPacket() ?: break
			if (p!!.isBeginningOfStream && p!!.data.size > 10) {
				if (OpusPacketFactory.isOpusStream(p!!)) {
					sid = p!!.sid
					break
				}
			}
		}
		if (sid == -1) {
			throw IllegalArgumentException("Supplied File is not Opus")
		}

		// First two packets are required to be info then tags
		info = OpusPacketFactory.create(p!!) as OpusInfo
		tags = OpusPacketFactory.create(r.getNextPacketWithSid(sid)!!) as OpusTags

		// Everything else should be audio data
	}

	/**
	 * Opens for writing, based on the settings
	 * from a pre-read file. The Steam ID (SID) is
	 * automatically allocated for you.
	 */
	constructor(out: SyncOutputStream, info: OpusInfo = OpusInfo(), tags: OpusTags = OpusTags()) : this(
		out,
		-1,
		info,
		tags
	) {
	}

	/**
	 * Opens for writing, based on the settings
	 * from a pre-read file, with a specific
	 * Steam ID (SID). You should only set the SID
	 * when copying one file to another!
	 */
	constructor(out: SyncOutputStream, sid: Int, info: OpusInfo, tags: OpusTags) {
		oggFile = OggFile(out)

		if (sid > 0) {
			w = oggFile!!.getPacketWriter(sid)
			this.sid = sid
		} else {
			w = oggFile!!.packetWriter
			this.sid = w!!.sid
		}

		writtenPackets = ArrayList<OpusAudioData>()

		this.info = info
		this.tags = tags
	}

	/**
	 * Skips the audio data to the next packet with a granule
	 * of at least the given granule position.
	 * Note that skipping backwards is not currently supported!
	 */
	override fun skipToGranule(granulePosition: Long) {
		r!!.skipToGranulePosition(sid, granulePosition)
	}

	/**
	 * Buffers the given audio ready for writing
	 * out. Data won't be written out yet, you
	 * need to call [.close] to do that,
	 * because we assume you'll still be populating
	 * the Info/Comment/Setup objects
	 */
	fun writeAudioData(data: OpusAudioData) {
		writtenPackets.add(data)
	}

	/**
	 * In Reading mode, will close the underlying ogg
	 * file and free its resources.
	 * In Writing mode, will write out the Info and
	 * Tags objects, and then the audio data.
	 */
	override fun close() {
		if (r != null) {
			r = null
			oggFile!!.close()
			oggFile = null
		}
		if (w != null) {
			w!!.bufferPacket(info!!.write(), true)
			w!!.bufferPacket(tags!!.write(), false)

			var lastGranule: Long = 0
			for (vd in writtenPackets) {
				// Update the granule position as we go
				if (vd.granulePosition >= 0 && lastGranule != vd.granulePosition) {
					w!!.flush()
					lastGranule = vd.granulePosition
					w!!.setGranulePosition(lastGranule)
				}

				// Write the data, flushing if needed
				w!!.bufferPacket(vd.write())
				if (w!!.sizePendingFlush > 16384) {
					w!!.flush()
				}
			}

			w!!.close()
			w = null
			oggFile!!.close()
			oggFile = null
		}
	}
}
/**
 * Opens for writing.
 */
