package com.dsh.tapper

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * SNTP 对时客户端（RFC 5905 的客户端子集）。
 *
 * 只用 JDK 自带的 DatagramSocket，**不引入任何第三方依赖**。
 * 精度来源：NTP 的四时间戳算法（见 [TapMath.sntpOffset]）能抵消大部分网络单程延迟；
 * 典型局域网/同城服务器往返 30~100ms 时，偏差可收敛到 ±10~50ms。
 *
 * 失败时**抛异常**，由调用方决定如何呈现——绝不静默回落到设备时钟
 * （否则「北京时间」就与「本地时间」变成同一个源了）。
 */
object SntpClient {

    /** 默认授时服务器：阿里云 NTP（本机实测可达，RTT ~47ms）。 */
    private val SERVERS = listOf(
        "ntp.aliyun.com",
        "cn.pool.ntp.org",
        "ntp1.aliyun.com",
    )

    private const val NTP_PORT = 123
    private const val TIMEOUT_MS = 3000
    private const val PACKET_SIZE = 48

    /**
     * 向任一可用服务器取一次时间。
     * @return Pair(服务器 epoch 毫秒, 往返延迟毫秒)
     * @throws Exception 所有服务器都失败时抛出（含最后一次的原因）
     */
    @Throws(Exception::class)
    fun fetch(): Pair<Long, Long> {
        var last: Exception? = null
        for (host in SERVERS) {
            try {
                return query(host)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("没有可用的 NTP 服务器")
    }

    /** 向指定服务器发一次 SNTP 请求并解算。 */
    private fun query(host: String): Pair<Long, Long> {
        val address = InetAddress.getByName(host)
        val socket = DatagramSocket()
        try {
            socket.soTimeout = TIMEOUT_MS
            // LI=0, VN=4, Mode=3(client) -> 0x23
            val request = ByteArray(PACKET_SIZE)
            request[0] = 0x23

            val t1 = System.currentTimeMillis()
            socket.send(DatagramPacket(request, request.size, address, NTP_PORT))

            val buffer = ByteArray(PACKET_SIZE)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val t4 = System.currentTimeMillis()

            // 报文解析走纯函数 TapMath.parseSntpResponse（有单测覆盖字段偏移，
            // 实测曾因把 ORIGINATE 当成 t2 而解算出量级离谱的偏移）
            val p = TapMath.parseSntpResponse(buffer)

            // 服务器回包的 Mode 应为 4(server) 或 5(broadcast)
            if (p.mode != 4 && p.mode != 5) throw Exception("非法响应模式: " + p.mode)
            // Stratum 0 表示 Kiss-o-Death，不可用
            if (p.stratum == 0) throw Exception("服务器返回 Kiss-o-Death")

            val r = TapMath.sntpOffset(t1, p.t2, p.t3, t4)
            // 服务器时间 = 本地时间 + 偏移
            return Pair(t4 + r.offsetMs, r.roundTripMs)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

}
