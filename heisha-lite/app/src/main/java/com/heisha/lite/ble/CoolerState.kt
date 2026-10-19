package com.heisha.lite.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 连接状态 */
enum class ConnState { IDLE, SCANNING, CONNECTING, DISCOVERING, READY, DISCONNECTED, POWERED_OFF }

/** 背夹运行状态（抓包后按实际通知帧字段补全） */
data class CoolerState(
    val connected: Boolean = false,
    val deviceName: String = "",
    val mac: String = "",
    val firmware: String = "",

    // —— 制冷 ——
    val workMode: Int = BleConstants.WorkMode.SMART,
    val fanLevel: Int = 1,
    val tecOn: Boolean = true,
    val coldEndTemp: Int = 0,      // 冷端温度（℃）
    val hotEndTemp: Int = 0,       // 散热端温度（℃），心跳第 7 字节

    // —— 灯效 ——
    val lightEffect: Int = 0,                  // 灯效模式 id（见 LightEffects，01=关闭，0=未知）
    val lightSpeedMs: Int = 0,                 // 灯效周期（毫秒）
    val lightColors: List<Int> = emptyList(),  // RGB，空 = 全彩

    // —— 定时关闭（单位 15 分钟，0 = 未开启）——
    val shutdownUnits: Int = 0,
    /** 预计关闭时刻（SystemClock.elapsedRealtime 毫秒），由设备回报的已计时秒数推算；0 = 未开启 */
    val shutdownDeadline: Long = 0L,

    // —— 保护 ——
    val lowTempProtect: Boolean = true,   // 背夹重启后默认开启
    val lowTempThreshold: Int = 0,

    // —— 磁吸版特有 ——
    val voltage: Float = 0f,
    val overclockUnlocked: Boolean = false,

    // —— 原始通知（调试用）——
    val lastRawNotify: ByteArray = ByteArray(0)
) {
    // ByteArray 需要自定义 equals/hashCode 以避免 data class 的引用比较警告
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CoolerState) return false
        return connected == other.connected && deviceName == other.deviceName &&
                mac == other.mac && firmware == other.firmware && workMode == other.workMode &&
                fanLevel == other.fanLevel && tecOn == other.tecOn && coldEndTemp == other.coldEndTemp &&
                hotEndTemp == other.hotEndTemp && lightEffect == other.lightEffect &&
                lightSpeedMs == other.lightSpeedMs && lightColors == other.lightColors &&
                lowTempProtect == other.lowTempProtect &&
                lowTempThreshold == other.lowTempThreshold && voltage == other.voltage &&
                overclockUnlocked == other.overclockUnlocked && shutdownUnits == other.shutdownUnits &&
                shutdownDeadline == other.shutdownDeadline &&
                lastRawNotify.contentEquals(other.lastRawNotify)
    }
    override fun hashCode(): Int = lastRawNotify.contentHashCode()
}

/** 全局状态持有者（轻量版不用 DI 框架，单例即可） */
object CoolerRepository {
    private val _state = MutableStateFlow(CoolerState())
    val state: StateFlow<CoolerState> = _state.asStateFlow()

    /**
     * 连接状态单独用 StateFlow 暴露。
     * ⚠️ 之前用 @Volatile var，UI 在 state 收集器里读它不会触发刷新，
     *    导致已连接但界面一直显示 CONNECTING。
     */
    private val _connState = MutableStateFlow(ConnState.IDLE)
    val connState: StateFlow<ConnState> = _connState.asStateFlow()

    /** 用接收者 lambda，调用方可直接写 copy(...) */
    fun update(transform: CoolerState.() -> CoolerState) {
        _state.value = _state.value.transform()
    }

    fun setConn(s: ConnState) { _connState.value = s }
}
