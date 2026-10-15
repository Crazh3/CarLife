package com.baidu.carlifevehicle.audio.player

import com.baidu.carlifevehicle.audio.player.source.AudioParams

class AudioTrackPositionTracker(private val params: AudioParams) {
    // 开始播放时间
    private var startTimestamp = -1L

    private var consumedSize = 0L

    fun sync(latency: Long = 20) {
        if (startTimestamp == -1L) {
            return
        }

        // 计算当前时间与当前所有数据播放完毕的时间差
        // 如果大于0，表示数据喂的太快，需要做一次延时
        // 考虑到延迟无法完全消除，留出20ms的冗余，避免供不应求导致播放卡顿
        val diffTime = calcNextTrackTimestamp() - System.currentTimeMillis()
        if (diffTime > latency) {
            // 如果时间差小于20ms，就不需要sleep了
            Thread.sleep(diffTime - latency)
        }
    }

    // 当前所有的数据播放完成时候的时间戳
    private fun calcNextTrackTimestamp(): Long {
        val duration = params.bytesToDuration(consumedSize)
        return startTimestamp + duration
    }

    fun track(frame: Int) {
        if (startTimestamp == -1L) {
            startTimestamp = System.currentTimeMillis()
        }
        else {
            val nextTrackTimestamp = calcNextTrackTimestamp()
            val delayed = System.currentTimeMillis() - nextTrackTimestamp
            if (delayed >= 1000) {
                // 如果延迟超过了1s，重新校准一下
                startTimestamp += delayed
            }
        }
        consumedSize += frame
    }
}