package com.heisha.lite.ble

/**
 * 风扇灯效模式（cmd 0x01 sub 0x20 宏帧第 [4] 字节）。
 * ★ 2026-10-16 原版 App 灯效设置页逐个切换 + snoop 实测。
 *
 * @param schemes 可选配色方案 = 颜色个数，0 = 全彩（顺序同原版列表）
 * @param speeds  灯效速度三档（缓慢 / 中 / 快速）对应的周期毫秒数；null = 不可调速
 * @param fixedSpeed 不可调速时下发的固定值
 */
data class LightEffect(
    val id: Int,
    val name: String,
    val schemes: List<Int>,
    val speeds: List<Int>?,
    val fixedSpeed: Int = 0,
) {
    /** 跳动的全彩方案没有速度滑块（实测），其余按 speeds 是否为空 */
    fun hasSpeed(colorCount: Int) = speeds != null && !(id == 0x0C && colorCount == 0)

    fun speedMs(colorCount: Int, level: Int): Int = when {
        hasSpeed(colorCount) -> speeds!![level]
        speeds != null -> speeds[SPEED_MID]
        else -> fixedSpeed
    }

    /** 设备回报的周期毫秒数 → 最接近的速度档位 */
    fun levelOf(speedMs: Int): Int =
        speeds?.indices?.minByOrNull { kotlin.math.abs(speeds[it] - speedMs) } ?: SPEED_MID

    companion object {
        const val SPEED_MID = 1
    }
}

object LightEffects {
    /** 风扇灯效关闭 */
    const val OFF = 0x01

    val ALL = listOf(
        LightEffect(0x02, "彩虹", listOf(0), listOf(7200, 3600, 900)),
        LightEffect(0x03, "呼吸", listOf(1), listOf(3000, 1500, 990)),
        LightEffect(0x04, "星光", listOf(1, 0), listOf(400, 300, 200)),
        LightEffect(0x05, "飞环", listOf(1), listOf(2500, 2000, 1500)),
        LightEffect(0x06, "长亮", listOf(1, 2, 3), null, fixedSpeed = 0x00FF),
        LightEffect(0x07, "追逐", listOf(2), listOf(1000, 600, 300)),
        LightEffect(0x08, "环绕", listOf(2), listOf(1500, 1000, 600)),
        LightEffect(0x09, "冲击", listOf(3), listOf(1500, 1000, 600)),
        LightEffect(0x0A, "缓冲", listOf(2, 0), listOf(2500, 2000, 1500)),
        LightEffect(0x0B, "刷新", listOf(0), null, fixedSpeed = 300),
        LightEffect(0x0C, "跳动", listOf(0, 1), listOf(2000, 1500, 1000)),
    )

    fun byId(id: Int) = ALL.firstOrNull { it.id == id }

    fun schemeName(colorCount: Int) = when (colorCount) {
        0 -> "全彩"
        1 -> "单色"
        2 -> "双色"
        else -> "三色"
    }
}
