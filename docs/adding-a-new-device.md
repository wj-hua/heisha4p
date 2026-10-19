# 适配新型号

以冰封制冷背夹4Pro磁吸版（下称 4Pro）为参照，把一款新背夹接进来的步骤。

## 1. 先看是不是同一套协议

用 [nRF Connect](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp) 扫描并连接背夹（连之前先关掉官方 App，背夹被连着时会停止广播），记下：

- 广播名
- GATT 服务和特征列表（UUID + 属性）

和 4Pro 对比（[protocol-br64.md 第 2.1 节](protocol-br64.md#21-gatt-表抓包中原版-app-的服务发现结果)）：

| 4Pro | 值 |
|---|---|
| 控制服务 | `0000a0a0-3c17-d293-8e48-14fe2e4da212` |
| 写特征 | `A001`（Write Without Response） |
| 通知特征 | `A002`（Notify） |

**如果控制服务一样**，很可能是同一套帧格式。先开 `A002` 通知，再往 `A001` 发下面这些**只读查询**，看有没有响应：

| 发送 | 4Pro 的响应 | 作用 |
|---|---|---|
| `05 04 30 00 00` | `92 04 30 00` + ASCII | 读硬件代号 |
| `05 06 00 00 00` | `8A 06 00 00 01 [T1] 00 [T2] 00 00` | 心跳 + 温度 |
| `05 02 10 00 00` | `86 02 10 00 XX YY` | 查制冷模式 |

注意：
- 连上后约 1.5 秒内背夹可能不响应；每条命令之间至少隔 400 ms
- 先只发查询，确认格式一致后再试设置类命令
- ⚠️ **不要往 `00010203-…2b12`（Telink OTA）这类升级特征写任何东西**

**如果服务不一样**，或者查询没有响应，就需要抓包，见 [how-to-capture.md](how-to-capture.md)。

## 2. 已知线索

官方 App 里能找到一些 4Pro 用不上、但别的型号可能用得上的命令，详见 [protocol-br64.md 第 10.2 节](protocol-br64.md#102-未解决)：

- `05 0B 10 00 00`：低电压查询。官方 App 只对设备类型 1003（FunCooler 3Pro）和 1005（MagCooler 3Pro）发送
- `05 06 20 00 00`：显示参数查询，响应 `[6..7]` = 风扇 rpm，`[8]` = 功率 W。功率、转速界面只出现在 MagCooler 5pro、6Pro 上
- 方法名 `setFanMode`、`getSpeedList`、`setLowTecProtectionTemperature` 等，4Pro 界面没开放
- 官方 App 是 Flutter 写的，协议逻辑在 `libapp.so` 里，可以用 [Blutter](https://github.com/worawit/Blutter) 反编译后查看各型号的命令构造

## 3. 改代码

heisha-lite 目前只针对 4Pro，协议相关代码集中在 `heisha-lite/app/src/main/java/com/heisha/lite/ble/`：

| 文件 | 改什么 |
|---|---|
| `BleConstants.kt` | `DEVICE_NAME_PREFIXES`（扫描用的广播名前缀）、`SERVICE_UUID` / `CHAR_WRITE` / `CHAR_NOTIFY`、命令码和模式值 |
| `CoolerProtocol.kt` | `build()` 组帧、`parse()` / `applyToState()` 解析通知、`initSequence()` 连接后的查询序列 |
| `LightEffects.kt` | 灯效模式表 |
| `CoolerState.kt` | 新设备有新数据（功率、转速等）时加字段 |
| `CoolerBleManager.kt` | 一般不用改：扫描、连接重试、通知、心跳都是通用逻辑 |

如果新设备和 4Pro 差别很大，建议先 fork 一份单独改通，再讨论怎么合并成多设备结构。

## 4. 写文档并提交

1. 复制 `docs/protocol-br64.md` 改名为 `docs/protocol-<型号>.md`，保留同样的章节结构和可信度标记（✅ 实测 / 🔶 推断 / ❓ 未知）
2. 抓包样本只保留背夹相关的 ATT 帧，MAC 打码，放到 `capture/<型号>/`
3. 在根目录 README 的「设备支持」表里加一行
4. 提 PR；只有抓包、还没写代码的话，开一个「新设备」Issue 也非常有帮助
