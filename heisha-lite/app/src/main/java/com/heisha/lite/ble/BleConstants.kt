package com.heisha.lite.ble

/**
 * BR64（冰封制冷背夹4Pro磁吸版）协议常量
 *
 * 全部取自实机抓包与联调（2026-10-15 ~ 10-18），详见仓库 `docs/protocol-br64.md`。
 */
object BleConstants {

    // ---------------------------------------------------------------
    // 1. 设备识别
    // ---------------------------------------------------------------

    /**
     * BLE 广播名匹配前缀（实测）。
     *
     * ★ 实测：扫描到的广播名是 `Black Shark MagCooler 4pro`
     *   （MAC 04:33:85:XX:XX:XX）
     *   而 cmd 0x04 读到的硬件代号是 `BR42240428V191`
     *   → 广播名用商品名，GATT 里用硬件代号，两者不同，都要认。
     *
     * 原 App 内可见的广播名（libapp.so 字符串）：
     *   "Black Shark MagCooler 4pro" / "Mag Cooler 4Pro" / "MagCooler 4Pro"
     *   "Fun Cooler 4Pro" / "Black Shark MagCooler 3Pro/5pro/6/6Pro"
     */
    val DEVICE_NAME_PREFIXES = listOf(
        "Black Shark MagCooler",
        "MagCooler",
        "Mag Cooler",
        "Fun Cooler",
        "BR"      // 兼容直接用硬件代号广播的固件
    )

    /** 原 App 内部型号常量（来自 libapp.so 字符串） */
    const val MODEL_BR64 = "BLACKSHARK_COOLER_BR64"
    const val MODEL_BR42 = "BLACKSHARK_COOLER_BR42"

    /** 扫描超时（毫秒） */
    const val SCAN_TIMEOUT_MS = 15_000L

    // ---------------------------------------------------------------
    // 2. GATT 服务与特征 UUID  ★ 实测确认（2026-10-15 抓包）
    // ---------------------------------------------------------------

    /**
     * 主控制服务。★ 实测确认（2026-10-15，我的 App 实际连上背夹后 dump 出的 GATT 树）：
     *
     *   SVC 00010203-0405-0607-0809-0a0b0c0d1912
     *      CH 00010203-0405-0607-0809-0a0b0c0d2b12 [RWn]   ← Telink OTA 通道（见 OTA_SERVICE_UUID）
     *   SVC 0000a0a0-3c17-d293-8e48-14fe2e4da212           ★ 本机使用的控制服务
     *      CH 0000a001-0000-1000-8000-00805f9b34fb [Wn]    写
     *      CH 0000a002-0000-1000-8000-00805f9b34fb [N]     通知
     *
     * 注：抓包时读写句柄为 0x0015 / 0x0017，与上面的特征一致。
     */
    const val SERVICE_UUID = "0000a0a0-3c17-d293-8e48-14fe2e4da212"

    /**
     * 写入特征。从特征声明响应读出：
     *   09 07 14 00 04 15 00 01 a0   → 声明handle 0x0014, props=0x04(Write Without Response), 值handle 0x0015, UUID 0xA001
     */
    const val CHAR_WRITE = "0000a001-0000-1000-8000-00805f9b34fb"

    /**
     * 通知特征：
     *   09 07 16 00 10 17 00 02 a0   → 声明handle 0x0016, props=0x10(Notify), 值handle 0x0017, UUID 0xA002
     */
    const val CHAR_NOTIFY = "0000a002-0000-1000-8000-00805f9b34fb"

    /** CCCD 描述符（标准 UUID，一般不用改） */
    const val DESCRIPTOR_CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /**
     * OTA 服务与数据特征（推断）：这是 Telink SDK 默认的 OTA UUID，且背夹厂商 ID 为 0x0211（Telink）。
     * 仅作识别用，⚠️ 不要往里写控制帧，可能让背夹进入升级状态。
     * 标准设备信息服务（0x180A）里只有 PnP ID，没有型号/固件版本特征，固件版本走 cmd 0x04。
     */
    const val OTA_SERVICE_UUID = "00010203-0405-0607-0809-0a0b0c0d1912"
    const val OTA_CHAR_UUID = "00010203-0405-0607-0809-0a0b0c0d2b12"

    // ---------------------------------------------------------------
    // 3. 帧格式  ★ 实测确认
    // ---------------------------------------------------------------

    /**
     * 实测帧格式（ATT WriteCmd 到 handle 0x0015 的载荷）：
     *
     *     [len] [cmd] [a1] [a2] [a3]
     *
     * len = 载荷总长度（含 len 自身）。短命令固定为 5。
     * 例：查询档位  05 02 10 00 00
     *     设置档位  05 02 00 00 2d
     *
     * 上报帧（Notification on handle 0x0017 的载荷）：
     *
     *     [marker] [cmd] [a1] [a2] ... [status]
     *
     * marker 实测取值：0x85 / 0x86 / 0x88 / 0x8a / 0x92 / 0xaf
     * 末字节常为 0x01(成功) / 0xff
     */

    /** 短命令载荷长度 */
    const val FRAME_LEN_SHORT = 5

    /** 上报帧 marker 实测值 */
    object RxMarker {
        const val M85 = 0x85
        const val M86 = 0x86
        const val M88 = 0x88
        const val M8A = 0x8A
        const val M92 = 0x92
        const val MAF = 0xAF
    }

    /**
     * 命令码（实测）
     *
     * 0x01 大数据传输（灯效宏配置，长帧 len=0x2f）
     * 0x02 制冷模式 / 风力档位   sub 0x10=查询, 0x00=设置, 值在 a3
     * 0x04 设备名查询           sub 0x30 → 返回 ASCII 型号串
     * 0x05 灯效模式             值在 a3
     * 0x06 心跳 + 状态          sub 0x00=心跳(约每秒), sub 0x28=设置
     * 0x08 定时关闭           sub 0x00=设置（a3=时长，单位 15 分钟，0=取消），sub 0x08=查询
     */
    object Cmd {
        const val BULK = 0x01          // 大数据传输
        const val COOLING = 0x02       // 制冷模式/风力档位
        const val DEVICE_NAME = 0x04   // 设备名查询
        const val LIGHT_MODE = 0x05    // 灯效模式
        const val HEARTBEAT = 0x06     // 心跳/状态
        const val SHUTDOWN_TIMER = 0x08 // 定时关闭
    }

    /** cmd 0x02 的 sub 参数 */
    object SubCooling {
        const val QUERY = 0x10
        const val SET = 0x00
    }

    /** cmd 0x04 的 sub 参数 */
    const val SUB_DEVICE_NAME = 0x30

    /**
     * 定时关闭时长单位（分钟）。★ 2026-10-16 实测（原版 App 定时关闭页 + snoop）：
     *   不开启 → 05 08 00 00 00
     *   30分钟 → 05 08 00 00 02
     *   1小时  → 05 08 00 00 04
     * 查询 05 08 08 00 00 → 88 08 08 00 01 [时长] [已计时秒数 lo] [hi]
     *   例：设 15 分钟后约 3 分钟查询 → 88 08 08 00 01 01 B3 00（已计时 179 秒）
     */
    const val SHUTDOWN_UNIT_MIN = 15

    /** cmd 0x06 的 sub 参数 */
    object SubHeartbeat {
        const val POLL = 0x00
        const val LOW_TEMP_PROTECT = 0x28
    }

    /**
     * 低温保护（cmd 0x06 sub 0x28 的 a3）。★ 2026-10-16 实测（原版 App 开关 + snoop）：
     *   开启 05 06 28 00 FF → 85 06 28 00 01（ACK）
     *   关闭 05 06 28 00 00 → 85 06 28 00 01（ACK）
     *   查询 05 06 28 00 FA → 85 06 28 00 FF（开）/ 00（关）
     * 原版连接后的「握手 2」其实就是这条查询。背夹重启后默认开启。
     */
    object LowTempProtect {
        const val ON = 0xFF
        const val OFF = 0x00
        const val QUERY = 0xFA
    }

    /**
     * 工作模式。★ 2026-10-16 实测确认（原版 App UI 切换 + HCI snoop 对照）：
     *
     * | UI 模式 | cmd 0x02 值 | 配套灯效（cmd 0x05） |
     * |---|---|---|
     * | 静音   | 0x2D | 0x60 |
     * | 超频   | 0x14 | 0x06 |
     * | 智享   | 0xFA | 0xFA |
     *
     * 注意：数值大小与制冷功率无关（超频功率最大但值是 0x14）。
     * 切换模式时原版 App 会同时下发配套灯效，复刻时建议保持一致。
     */
    object WorkMode {
        const val SILENT = 0x2D      // 静音
        const val OVERCLOCK = 0x14   // 超频（制冷功率最大档位）
        const val SMART = 0xFA       // 智享
        /**
         * 仅开启灯效：风扇/TEC 停止，只亮灯。★ 2026-10-16 实测（原版灯效页「仅开启灯效」）。
         * 切回上面任一模式即恢复制冷（实测 1 分钟内冷端 35℃ → 27℃），无需重启
         */
        const val LIGHT_ONLY = 0xFB
    }

    /** 各工作模式配套的灯效 preset（cmd 0x05，实测配对下发） */
    object WorkModeLight {
        const val SILENT = 0x60
        const val OVERCLOCK = 0x06
        const val SMART = 0xFA
        const val LIGHT_ONLY = 0xFB
    }

    // ---------------------------------------------------------------
    // 4. 连接参数
    // ---------------------------------------------------------------

    /** 连接超时（毫秒） */
    const val CONNECT_TIMEOUT_MS = 20_000L

    /** 写操作是否使用 WRITE_TYPE_DEFAULT（有响应）还是 NO_RESPONSE */
    const val WRITE_NO_RESPONSE = true

    /** 通知繁忙时用于抑制 UI 刷新 */
    const val NOTIFY_THROTTLE_MS = 100L
}
