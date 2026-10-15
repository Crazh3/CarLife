package com.baidu.carlifevehicle.audio.player.source.impl

import android.content.res.AssetFileDescriptor
import android.media.AudioManager
import com.baidu.carlife.sdk.internal.audio.AudioFocusManager
import com.baidu.carlifevehicle.audio.player.source.AudioParams
import com.baidu.carlifevehicle.audio.player.source.AudioSource
import com.baidu.carlifevehicle.audio.player.source.ResizableArray
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

// 对于短小的音频，可以使用这种source，避免重复IO
class PcmMemorySource constructor(
    private val storage: ByteBuffer,
    private val params: AudioParams,
    override var streamType: Int = AudioFocusManager.STREAM_MUSIC,
    override var focusType: Int = AudioManager.AUDIOFOCUS_GAIN,
    override val allowFocusDelayed: Boolean = true,
    override val forceGrantFocus: Boolean = false): AudioSource {

    companion object {
        fun fromFile(params: AudioParams, file: File): PcmMemorySource {
            return fromStream(params, FileInputStream(file), file.length().toInt())
        }

        fun fromAsset(params: AudioParams, fd: AssetFileDescriptor): PcmMemorySource {
            return fromStream(params, fd.createInputStream(), fd.length.toInt())
        }

        fun fromStream(params: AudioParams, stream: InputStream, length: Int): PcmMemorySource {
            val storage = ByteArray(length)
            stream.use {
                var position = 0
                while (position < storage.size) {
                    var readSize = 4096.coerceAtMost(storage.size - position)
                    readSize = it.read(storage, position, readSize)
                    if (readSize <= 0) {
                        return@use
                    }
                    position += readSize
                }
            }
            params.duration = params.bytesToDuration(length.toLong())
            return PcmMemorySource(ByteBuffer.wrap(storage), params)
        }
    }

    override var tag: Any? = null

    override fun params(): AudioParams {
        storage.rewind()
        return params
    }

    override fun currentPosition(): Long {
        return params.bytesToDuration(storage.position().toLong())
    }

    override fun close() {
        storage.rewind()
    }

    override fun isClosed(): Boolean {
        return storage.remaining() == 0
    }

    override fun drainTo(buffer: ResizableArray, timeout: Long): Int {
        buffer.fill(storage, storage.remaining(), false)
        return buffer.size
    }

    override fun readFrame(buffer: ResizableArray, offset: Int, timeout: Long): Int {
        buffer.fill(storage, buffer.capbility, false)
        return if (buffer.size == 0) -1 else buffer.size
    }
}