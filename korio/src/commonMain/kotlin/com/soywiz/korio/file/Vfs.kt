@file:Suppress("EXPERIMENTAL_FEATURE_WARNING")

package com.soywiz.korio.file

import com.soywiz.klock.*
import com.soywiz.korio.async.*
import com.soywiz.korio.error.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.stream.*
import com.soywiz.korio.util.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.reflect.*

abstract class Vfs {
	protected open val absolutePath: String = ""

	open fun getAbsolutePath(path: String) = VfsUtil.lightCombine(absolutePath, path)

	//val root = VfsFile(this, "")
	val root get() = VfsFile(this, "")

	open val supportedAttributeTypes = listOf<KClass<out Attribute>>()

	operator fun get(path: String) = root[path]

	fun file(path: String) = root[path]

	fun createExistsStat(
		path: String, isDirectory: Boolean, size: Long, device: Long = -1, inode: Long = -1, mode: Int = 511,
		owner: String = "nobody", group: String = "nobody", createTime: Long = 0L, modifiedTime: Long = createTime,
		lastAccessTime: Long = modifiedTime, extraInfo: Any? = null, id: String? = null
	) = VfsStat(
		file = file(path), exists = true, isDirectory = isDirectory, size = size, device = device, inode = inode,
		mode = mode, owner = owner, group = group, createTime = createTime, modifiedTime = modifiedTime,
		lastAccessTime = lastAccessTime, extraInfo = extraInfo, id = id
	)

	fun createNonExistsStat(path: String, extraInfo: Any? = null) = VfsStat(
		file = file(path), exists = false, isDirectory = false, size = 0L,
		device = -1L, inode = -1L, mode = 511, owner = "nobody", group = "nobody",
		createTime = 0L, modifiedTime = 0L, lastAccessTime = 0L, extraInfo = extraInfo
	)

	open suspend fun exec(
		path: String,
		cmdAndArgs: List<String>,
		handler: VfsProcessHandler = VfsProcessHandler()
	): Int = throw UnsupportedOperationException()

	open suspend fun exec(
		path: String,
		cmdAndArgs: List<String>,
		env: Map<String, String>,
		handler: VfsProcessHandler = VfsProcessHandler()
	): Int = throw UnsupportedOperationException()

	open suspend fun open(path: String, mode: VfsOpenMode): AsyncStream = throw UnsupportedOperationException()

	open suspend fun openInputStream(path: String): AsyncInputStream = open(
		path,
		VfsOpenMode.READ
	)

	open suspend fun readRange(path: String, range: LongRange): ByteArray {
		val s = open(path, VfsOpenMode.READ)
		try {
			s.position = range.start
			val readCount = min(
				Int.MAX_VALUE.toLong() - 1,
				(range.endInclusive - range.start)
			).toInt() + 1
			return s.readBytesUpTo(readCount)
		} finally {
			s.close()
		}
	}

	interface Attribute

	inline fun <reified T> Iterable<Attribute>.get(): T? = this.firstOrNull { it is T } as T?

	open suspend fun put(path: String, content: AsyncInputStream, attributes: List<Attribute> = listOf()): Long {
		return open(path, VfsOpenMode.CREATE_OR_TRUNCATE).use {
			content.copyTo(this)
		}
	}

	suspend fun put(path: String, content: ByteArray, attributes: List<Attribute> = listOf()): Long {
		return put(path, content.openAsync(), attributes)
	}

	suspend fun readChunk(path: String, offset: Long, size: Int): ByteArray {
		val s = open(path, VfsOpenMode.READ)
		if (offset != 0L) s.setPosition(offset)
		return s.readBytesUpTo(size)
	}

	suspend fun writeChunk(path: String, data: ByteArray, offset: Long, resize: Boolean) {
		val s = open(path, if (resize) VfsOpenMode.CREATE_OR_TRUNCATE else VfsOpenMode.CREATE)
		s.setPosition(offset)
		s.writeBytes(data)
	}

	open suspend fun setSize(path: String, size: Long) {
		open(path, mode = VfsOpenMode.WRITE).use { this.setLength(size) }
	}

	open suspend fun setAttributes(path: String, attributes: List<Attribute>): Unit = Unit

	open suspend fun stat(path: String): VfsStat = createNonExistsStat(path)
	open suspend fun list(path: String): SuspendingSequence<VfsFile> = listOf<VfsFile>().toAsync()
	open suspend fun mkdir(path: String, attributes: List<Attribute>): Boolean = unsupported()
	open suspend fun rmdir(path: String): Boolean = delete(path) // For compatibility
	open suspend fun delete(path: String): Boolean = unsupported()
	open suspend fun rename(src: String, dst: String): Boolean {
		file(src).copyTo(file(dst))
		delete(src)
		return true
	}

	open suspend fun watch(path: String, handler: (FileEvent) -> Unit): Closeable =
		DummyCloseable

	open suspend fun touch(path: String, time: Long, atime: Long) = Unit

	open suspend fun getUnderlyingUnscapedFile(path: String): FinalVfsFile =
		FinalVfsFile(this, path)

	abstract class Proxy : Vfs() {
		protected abstract suspend fun access(path: String): VfsFile
		protected open suspend fun VfsFile.transform(): VfsFile = file(this.path)
		//suspend protected fun transform2_f(f: VfsFile): VfsFile = transform(f)

		override suspend fun getUnderlyingUnscapedFile(path: String): FinalVfsFile = access(path).transform().toUnscaped()

		protected open suspend fun init() {
		}

		var initialized = false
		private suspend fun initOnce(): Proxy {
			if (!initialized) {
				initialized = true
				init()
			}
			return this
		}

		override suspend fun exec(
			path: String,
			cmdAndArgs: List<String>,
			env: Map<String, String>,
			handler: VfsProcessHandler
		): Int = initOnce().access(path).exec(cmdAndArgs, env, handler)

		override suspend fun open(path: String, mode: VfsOpenMode) = initOnce().access(path).open(mode)

		override suspend fun readRange(path: String, range: LongRange): ByteArray =
			initOnce().access(path).readRangeBytes(range)

		override suspend fun put(path: String, content: AsyncInputStream, attributes: List<Attribute>) =
			initOnce().access(path).put(content, *attributes.toTypedArray())

		override suspend fun setSize(path: String, size: Long): Unit = initOnce().access(path).setSize(size)
		override suspend fun stat(path: String): VfsStat = initOnce().access(path).stat().copy(file = file(path))
		override suspend fun list(path: String) =
			asyncGenerate<VfsFile> { initOnce(); for (it in access(path).list()) yield(it.transform()) }

		override suspend fun delete(path: String): Boolean = initOnce().access(path).delete()
		override suspend fun setAttributes(path: String, attributes: List<Attribute>) =
			initOnce().access(path).setAttributes(*attributes.toTypedArray())

		override suspend fun mkdir(path: String, attributes: List<Attribute>): Boolean =
			initOnce().access(path).mkdir(*attributes.toTypedArray())

		override suspend fun touch(path: String, time: Long, atime: Long): Unit =
			initOnce().access(path).touch(time, atime)

		override suspend fun rename(src: String, dst: String): Boolean {
			initOnce()
			val srcFile = access(src)
			val dstFile = access(dst)
			if (srcFile.vfs != dstFile.vfs) throw IllegalArgumentException("Can't rename between filesystems. Use copyTo instead, and remove later.")
			return srcFile.renameTo(dstFile.path)
		}

		override suspend fun watch(path: String, handler: (FileEvent) -> Unit): Closeable {
			initOnce()
			return access(path).watch { e ->
				launchImmediately(coroutineContext) {
					val f1 = e.file.transform()
					val f2 = e.other?.transform()
					handler(e.copy(file = f1, other = f2))
				}
			}
		}
	}

	open class Decorator(val parent: VfsFile) : Proxy() {
		val parentVfs = parent.vfs
		override suspend fun access(path: String): VfsFile = parentVfs[path]
	}

	data class FileEvent(val kind: Kind, val file: VfsFile, val other: VfsFile? = null) {
		enum class Kind { DELETED, MODIFIED, CREATED, RENAMED }

		override fun toString() = if (other != null) "$kind($file, $other)" else "$kind($file)"
	}

	override fun toString(): String = this::class.portableSimpleName
}

enum class VfsOpenMode(
	val cmode: String,
	val write: Boolean,
	val createIfNotExists: Boolean = false,
	val truncate: Boolean = false
) {
	READ("rb", write = false),
	WRITE("rwb", write = true, createIfNotExists = true),
	APPEND("a+b", write = true, createIfNotExists = true),
	CREATE_OR_TRUNCATE("rwb", write = true, createIfNotExists = true, truncate = true),
	CREATE("rwb", write = true, createIfNotExists = true),
	CREATE_NEW("rwb", write = true);

	companion object {
		fun fromString(str: String): VfsOpenMode {
			if ('r' in str) {
				return READ
			}
			TODO()
		}
	}
}

open class VfsProcessHandler {
	open suspend fun onOut(data: ByteArray): Unit = Unit
	open suspend fun onErr(data: ByteArray): Unit = Unit
}

class VfsProcessException(message: String) : com.soywiz.korio.IOException(message)

data class VfsStat(
	val file: VfsFile,
	val exists: Boolean,
	val isDirectory: Boolean,
	val size: Long,
	val device: Long = -1L,
	val inode: Long = -1L,
	val mode: Int = 511,
	val owner: String = "nobody",
	val group: String = "nobody",
	val createTime: Long = 0L,
	val modifiedTime: Long = createTime,
	val lastAccessTime: Long = modifiedTime,
	val extraInfo: Any? = null,
	val id: String? = null
) : Path by file

//val VfsStat.createLocalDate: LocalDateTime get() = LocalDateTime.ofEpochSecond(createTime / 1000L, ((createTime % 1_000L) * 1_000_000L).toInt(), ZoneOffset.UTC)
//val VfsStat.modifiedLocalDate: LocalDateTime get() = LocalDateTime.ofEpochSecond(modifiedTime / 1000L, ((modifiedTime % 1_000L) * 1_000_000L).toInt(), ZoneOffset.UTC)
//val VfsStat.lastAccessLocalDate: LocalDateTime get() = LocalDateTime.ofEpochSecond(lastAccessTime / 1000L, ((lastAccessTime % 1_000L) * 1_000_000L).toInt(), ZoneOffset.UTC)

//val INIT = Unit.apply { println("UTC_OFFSET: $UTC_OFFSET")  }

val VfsStat.createDate: DateTime get() = DateTime.fromUnix(createTime)
val VfsStat.modifiedDate: DateTime get() = DateTime.fromUnix(modifiedTime)
val VfsStat.lastAccessDate: DateTime get() = DateTime.fromUnix(lastAccessTime)

suspend fun ByteArray.writeToFile(path: String) = LocalVfs(path).write(this)
suspend fun ByteArray.writeToFile(file: VfsFile) = file.write(this)
