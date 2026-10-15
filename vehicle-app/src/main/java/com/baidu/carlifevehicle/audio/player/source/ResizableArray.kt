package com.baidu.carlifevehicle.audio.player.source

import com.baidu.carlife.sdk.util.CircularByteBuffer
import java.io.InputStream
import java.nio.ByteBuffer

class ResizableArray(initialSize: Int) {
     var array = ByteArray(initialSize)
          private set

     var size: Int = 0

     val capbility: Int get() = array.size

     fun clear() {
          size = 0
     }

     fun fill(buffer: CircularByteBuffer, timeout: Long, append: Boolean) {
          if (!append) {
               size = 0
          }

          val freeSize = capbility - size
          val readSize = buffer.read(array, size, freeSize, timeout)
          size += readSize
     }

     fun fill(input: InputStream, append: Boolean = false) {
          if (!append) {
               size = 0
          }

          val freeSize = capbility - size
          val readSize = input.read(array, size, freeSize)
          size += readSize
     }

     fun fill(buffer: ByteArray, dataSize: Int, append: Boolean = false) {
          if (!append) {
               size = 0
          }

          val expectSize = size + dataSize
          if (expectSize > array.size) {
               // 每次扩充2倍
               realloc(expectSize)
          }

          val readSize = dataSize.coerceAtMost(buffer.size)
          if (readSize > 0) {
               System.arraycopy(buffer, 0, array, size, readSize)
          }
          size += readSize
     }

     fun fill(buffer: ByteBuffer, dataSize: Int, append: Boolean = false) {
          if (!append) {
               size = 0
          }

          val expectSize = size + dataSize
          if (expectSize > array.size) {
               // 每次扩充2倍
               realloc(expectSize)
          }

          val readSize = dataSize.coerceAtMost(buffer.remaining())
          if (readSize > 0) {
               buffer.get(array, size, dataSize.coerceAtMost(buffer.remaining()))
          }
          size += readSize
     }

     private fun realloc(size: Int) {
          val newArray = ByteArray(size)
          System.arraycopy(array, 0, newArray, 0, array.size)
          array = newArray
     }
}