package com.heisha.lite.ble

import android.os.SystemClock
import java.util.Locale

/**
 * BR64 / BR42 黑鲨散热背夹 帧编解码
 *
 * ★ 全部基于 2026-10-15 实机抓包（HCI snoop），非推测。
 *
 * ## 传输层
 * ATT WriteCmd(0x52) 写入 handle 0x0015（特征 0xA001）
 * Notification(0x1B)  来自 handle 0x0017（特征 0xA002）
 *
 * ## 下行帧（手机 → 背夹），写特征载荷：
 *
 *     [len] [cmd] [a1] [a2] [a3]
 *
 *   len = 载荷总字节数（含 len 自身）。短命令恒为 5。
 *   实测样例：
 *     05 02 10 00 00    查询制冷模式/档位
 *     05 02 00 00 2d    设置制冷模式/档位 = 0x2d
 *     05 04 30 00 00    查询设备名
 *     05 05 00 00 60    设置灯效模式 = 0x60
 *     05 06 00 00 00    心跳（连接期间约每秒一次）
 *     05 06 28 00 fa    设置（sub 0x28）
 *     05 08 08 00 00    信息查询
 *
 * ## 上行帧（背夹 → 手机），通知特征载荷：
 *
 *     [marker] [cmd] [a1] [a2] ... [status]
 *
 *   marker 实测：0x85 / 0x86 / 0x88 / 0x8a / 0x92 / 0xaf
 *   末字节常为 0x01（成功）/ 0xff
 *   实测样例：
 *     86 02 10 00 2d 2d                 档位查询/设置回显
 *     92 04 30 00 "BR42240428V191"      设备名（ASCII）
 *     8a 06 00 00 01 15 00 18 00 00     心跳响应
 *     85 06 28 00 ff                    sub 0x28 设置回显
 */
object CoolerProtocol {

    fun ByteArray.hex(): String =
        joinToString(" ") { String.format(Locale.US, "%02X", it) }

    fun String.unhex(): ByteArray =
        split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    // ==================================================================
    // 下行：组装命令
    // ==================================================================

    /**
     * 通用短命令组装。[args] 会被补齐/截断到 3 字节。
     * @return 完整载荷（不含 ATT 头，由 BLE 层自动加）
     */
    fun build(cmd: Int, vararg args: Int): ByteArray {
        val a = ByteArray(3)
        args.take(3).forEachIndexed { i, v -> a[i] = (v and 0xFF).toByte() }
        return byteArrayOf(
            BleConstants.FRAME_LEN_SHORT.toByte(),
            cmd.toByte(),
            a[0], a[1], a[2]
        )
    }

    // ---- 制冷模式 / 风力档位（cmd 0x02）----

    /** 查询当前模式/档位 */
    fun queryCooling() = build(
        BleConstants.Cmd.COOLING,
        BleConstants.SubCooling.QUERY, 0x00, 0x00
    )

    /**
     * 设置模式/档位。
     * 实测值：0x2d / 0x14 / 0xfa（推测对应三档，语义待最终确认）
     */
    fun setCooling(value: Int) = build(
        BleConstants.Cmd.COOLING,
        BleConstants.SubCooling.SET, 0x00, value
    )

    // ---- 设备名（cmd 0x04）----

    /** 查询设备名，响应为 ASCII 型号串，如 "BR42240428V191" */
    fun queryDeviceName() = build(
        BleConstants.Cmd.DEVICE_NAME,
        BleConstants.SUB_DEVICE_NAME, 0x00, 0x00
    )

    // ---- 灯效模式（cmd 0x05）----

    /** 设置灯效模式。实测值 0x60 / 0x06 / 0xfa */
    fun setLightMode(value: Int) = build(
        BleConstants.Cmd.LIGHT_MODE,
        0x00, 0x00, value
    )

    // ---- 心跳（cmd 0x06）----

    /** 心跳。必须周期性发送，否则背夹可能判定断开 */
    fun heartbeat() = build(
        BleConstants.Cmd.HEARTBEAT,
        BleConstants.SubHeartbeat.POLL, 0x00, 0x00
    )

    // ---- 低温保护（cmd 0x06 sub 0x28）----

    fun queryLowTempProtect() = build(
        BleConstants.Cmd.HEARTBEAT,
        BleConstants.SubHeartbeat.LOW_TEMP_PROTECT, 0x00, BleConstants.LowTempProtect.QUERY
    )

    fun setLowTempProtect(on: Boolean) = build(
        BleConstants.Cmd.HEARTBEAT,
        BleConstants.SubHeartbeat.LOW_TEMP_PROTECT, 0x00,
        if (on) BleConstants.LowTempProtect.ON else BleConstants.LowTempProtect.OFF
    )

    // ---- 定时关闭（cmd 0x08）----

    /** 查询定时关闭时长（单位见 [BleConstants.SHUTDOWN_UNIT_MIN]） */
    fun queryShutdownTimer() = build(BleConstants.Cmd.SHUTDOWN_TIMER, 0x08, 0x00, 0x00)

    /** 设置定时关闭：units × 15 分钟后关闭制冷背夹，0 = 取消 */
    fun setShutdownTimer(units: Int) = build(BleConstants.Cmd.SHUTDOWN_TIMER, 0x00, 0x00, units)

    // ---- 灯效宏帧（cmd 0x01 sub 0x20，47 字节长帧）----

    /**
     * 风扇灯效宏帧。★ 2026-10-16 实测（原版 App 灯效设置页逐项切换 + snoop）：
     *
     *   2F 01 20 00 [模式] 00 FF FF [周期 lo] [周期 hi] [颜色数] [R G B]×颜色数 …（补 0 到 47 字节）
     *   → RX 88 01 20 00 01 [模式] 00 FF
     *
     *   模式见 [LightEffects]（01 = 关闭）；周期单位毫秒，越小越快；颜色数 0 = 全彩。
     *   例：呼吸 #4DFF0C → 2F 01 20 00 03 00 FF FF DC 05 01 4D FF 0C …
     *       关闭       → 2F 01 20 00 01 00 FF FF 10 0E 00 …（原版关闭帧原样复刻）
     */
    fun lightMacro(effect: Int, speedMs: Int, colors: List<Int>): ByteArray {
        val f = ByteArray(47)
        f[0] = 0x2F; f[1] = 0x01; f[2] = 0x20; f[3] = 0x00
        f[4] = effect.toByte(); f[5] = 0x00; f[6] = 0xFF.toByte(); f[7] = 0xFF.toByte()
        f[8] = speedMs.toByte(); f[9] = (speedMs shr 8).toByte()
        f[10] = colors.size.toByte()
        colors.forEachIndexed { i, c ->
            f[11 + i * 3] = (c shr 16).toByte()
            f[12 + i * 3] = (c shr 8).toByte()
            f[13 + i * 3] = c.toByte()
        }
        return f
    }

    fun lightMacroOff() = lightMacro(LightEffects.OFF, 0x0E10, emptyList())

    /** 查询当前灯效配置 → AF 01 18 00 [模式] 00 FF FF [周期] [颜色数] [RGB]…（同宏帧布局） */
    fun queryLightConfig() = build(BleConstants.Cmd.BULK, 0x18, 0x00, 0x00)

    // ==================================================================
    // 上行：解析通知
    // ==================================================================

    data class Frame(
        val marker: Int,
        val cmd: Int,
        val args: ByteArray,
        val raw: ByteArray
    ) {
        val status: Int? get() = args.lastOrNull()?.toInt()?.and(0xFF)
        override fun equals(other: Any?) =
            other is Frame && marker == other.marker && cmd == other.cmd &&
                    args.contentEquals(other.args)
        override fun hashCode() = marker * 31 + cmd
    }

    /**
     * 解析通知载荷为 [Frame]。
     * 背夹一次通知通常只含一帧；保留列表形式以备长帧拆分。
     */
    fun parse(data: ByteArray): List<Frame> {
        if (data.size < 3) return emptyList()
        val marker = data[0].toInt() and 0xFF
        val cmd = data[1].toInt() and 0xFF
        val args = data.copyOfRange(2, data.size)
        return listOf(Frame(marker, cmd, args, data))
    }

    /** 设备名（cmd 0x04 响应中的 ASCII 串） */
    fun Frame.deviceName(): String? {
        if (cmd != BleConstants.Cmd.DEVICE_NAME) return null
        // 跳过前 2 字节（sub + 00），其余按 ASCII 解
        val s = args.drop(2).toByteArray()
        val txt = String(s, Charsets.US_ASCII).trim()
        return txt.ifEmpty { null }
    }

    /** 应用到状态模型 */
    fun applyToState(f: Frame, update: (CoolerState.() -> CoolerState) -> Unit) {
        when (f.cmd) {
            BleConstants.Cmd.DEVICE_NAME -> {
                // cmd 0x04 读回的是硬件代号/固件版本（如 BR42240428V191），原版 App 显示为「固件版本」
                f.deviceName()?.let { n -> update { copy(firmware = n) } }
            }
            BleConstants.Cmd.COOLING -> {
                // 回显格式: [sub][00][value][echo]
                if (f.args.size >= 4) {
                    val v = f.args[2].toInt() and 0xFF
                    update { copy(fanLevel = v) }
                }
            }
            BleConstants.Cmd.BULK -> {
                // 连接时查询 05 01 18 00 00 的响应 = 当前灯效配置，布局同宏帧：
                //   AF 01 18 00 [模式] 00 FF FF [周期 lo] [hi] [颜色数] [RGB]…（MTU 23 下最多 20 字节，正好装下 3 色）
                if (f.marker == BleConstants.RxMarker.MAF && f.args.size >= 9 &&
                    (f.args[0].toInt() and 0xFF) == 0x18
                ) {
                    val a = f.args.map { it.toInt() and 0xFF }
                    val count = a[8].coerceAtMost((a.size - 9) / 3)
                    val colors = (0 until count).map { i -> (a[9 + i * 3] shl 16) or (a[10 + i * 3] shl 8) or a[11 + i * 3] }
                    update { copy(lightEffect = a[2], lightSpeedMs = a[6] or (a[7] shl 8), lightColors = colors) }
                }
            }
            BleConstants.Cmd.SHUTDOWN_TIMER -> {
                // 查询响应 88 08 08 00 01 [时长] [已计时秒数 u16 小端]；设置 ACK 85 08 00 00 01 忽略
                if (f.marker == BleConstants.RxMarker.M88 && f.args.size >= 6) {
                    val units = f.args[3].toInt() and 0xFF
                    val elapsed = (f.args[4].toInt() and 0xFF) or ((f.args[5].toInt() and 0xFF) shl 8)
                    val leftMs = (units * BleConstants.SHUTDOWN_UNIT_MIN * 60L - elapsed) * 1000L
                    val deadline = if (units == 0) 0L else SystemClock.elapsedRealtime() + leftMs
                    update { copy(shutdownUnits = units, shutdownDeadline = deadline) }
                }
            }
            BleConstants.Cmd.HEARTBEAT -> {
                // 低温保护查询响应 85 06 28 00 [FF=开 / 00=关]；设置 ACK（01）不含状态，忽略
                if (f.marker == BleConstants.RxMarker.M85 && f.args.size >= 3 &&
                    (f.args[0].toInt() and 0xFF) == BleConstants.SubHeartbeat.LOW_TEMP_PROTECT
                ) {
                    when (f.args[2].toInt() and 0xFF) {
                        BleConstants.LowTempProtect.ON -> update { copy(lowTempProtect = true) }
                        BleConstants.LowTempProtect.OFF -> update { copy(lowTempProtect = false) }
                    }
                }
                // 心跳响应 8A 06 00 00 01 [冷端温度] 00 [散热端温度] 00 00，单位 ℃。
                // 冷端与原版 App「背夹温度」一致；散热端为推断（关制冷时冷端升、它降，开制冷时反之）
                if (f.marker == BleConstants.RxMarker.M8A && f.args.size >= 6) {
                    val cold = f.args[3].toInt() and 0xFF
                    val hot = f.args[5].toInt() and 0xFF
                    update { copy(coldEndTemp = cold, hotEndTemp = hot) }
                }
            }
        }
        update { copy(lastRawNotify = f.raw) }
    }

    // ==================================================================
    // 连接初始化序列（实测归纳）
    // ==================================================================

    /**
     * 连接初始化序列 —— 复刻原版 App（Shark Arsenal）实测定序：
     *
     *   1. 05 02 10 00 00   查制冷模式
     *   2. 05 01 18 00 00   查当前灯效配置（AF 01 18 …）
     *   3. 05 06 28 00 fa   查低温保护
     *   4. 05 04 30 00 00   查设备名 / 固件版本
     *   5. 05 08 08 00 00   查定时关闭
     *   之后补查制冷与灯效：冷连接后约 1.5 秒内设备不应答，开头的查询常被丢弃。
     *   心跳在序列发完后才开始（插在中间会让设备不回灯效配置）。
     *
     * 注意：必须逐条带间隔发送（见 CoolerBleManager.INIT_STEP_DELAY_MS）。
     * 一次性连发多条时设备会丢弃后续命令、什么都不回。
     */
    fun initSequence(): List<ByteArray> = listOf(
        queryCooling(),
        queryLightConfig(),
        queryLowTempProtect(),
        queryDeviceName(),
        queryShutdownTimer(),
        queryCooling(),      // 握手后再查一次制冷，此时设备已应答其他查询
        queryLightConfig()   // 同理：冷连接后约 1.5 秒内设备不应答，开头那次查询常被丢弃
    )
}
