package com.xayah.core.network.io

import org.apache.commons.io.output.CountingOutputStream
import java.io.OutputStream
import java.util.Timer
import java.util.TimerTask

class CountingOutputStreamImpl(
    `out`: OutputStream,
    private val fileSize: Long,
    inline val onProgress: (written: Long, total: Long) -> Unit
) : CountingOutputStream(`out`) {
    // 取父类实际写入字节数上报：按请求长度自累计会在末尾多计一整块，进度会虚高/乱跳
    private val timer = Timer()
    private val task = object : TimerTask() {
        override fun run() {
            onProgress(byteCount, fileSize)
        }
    }

    init {
        timer.schedule(task, 0, 1000)
    }

    override fun close() {
        super.close()
        timer.cancel()
    }
}
