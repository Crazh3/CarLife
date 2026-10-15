package com.baidu.carlife.sdk

import com.baidu.carlife.protobuf.CarlifeConnectTimeSyncProto
import com.baidu.carlife.protobuf.CarlifeProtocolVersionMatchStatusProto
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.AesEncryptor
import com.baidu.carlife.sdk.util.CircularByteBuffer
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class CircularByteBufferTest {
    @Test
    fun test_multiProducer_singleConsumer() {
        val buffer = CircularByteBuffer()

        thread {
            val response = CarLifeMessage.obtain(MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS)
            response.payload(
                    CarlifeProtocolVersionMatchStatusProto.CarlifeProtocolVersionMatchStatus.newBuilder()
                            .setMatchStatus(1)
                            .build())
            for (i in 0 until 50) {
                buffer.write(response.body, 0, response.size)
                Thread.sleep(1)
            }
        }

        thread {
            val response = CarLifeMessage.obtain(MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_TIME_SYNC)
            response.payload(
                    CarlifeConnectTimeSyncProto.CarlifeConnectTimeSync.newBuilder()
                            .setTimeStamp(System.currentTimeMillis().toInt())
                            .build())
            for (i in 0 until 50) {
                buffer.write(response.body, 0, response.size)
                Thread.sleep(1)
            }
        }

        val thread = thread {
            val message = CarLifeMessage.obtain(MSG_CHANNEL_CMD)
            for (i in 0 until 100) {
                buffer.read(message.body, 0, message.commandSize)

                // read while CarLife message
                buffer.read(message.body, message.commandSize, message.payloadSize)
                //message.decode()
                System.out.println(message.toString())
            }
        }

        thread.join()
    }

    @Test
    fun test_encrypt() {
        val encrypt = AesEncryptor.withRandomKey()
        val encrypt2 = AesEncryptor.withRandomKey()
        val text = "1234567890123456"
        val bytes = encrypt.encrypt(text.toByteArray())
        val decrypt = encrypt.decrypt(bytes)
        System.out.println(String(decrypt))

    }

    @Test
    fun test_split() {
        val str = "com.baidu.carlife"
        val parts = str.split(".")
        System.out.println(parts)
    }
}