# heisha4p

黑鲨散热背夹 BLE 协议的逆向记录 + 一个零第三方依赖的 Android 参考实现。

目标是让更多人能**不依赖官方 App（186 MB）**控制自己的背夹，并为其他型号的适配提供方法和工具。

> ⚠️ **非官方项目**，与黑鲨（Black Shark）及其关联公司无任何关系。"Black Shark"、"黑鲨"等名称是其所有者的商标，本项目仅为说明兼容性而引用。
> 协议来自对自己购买设备的抓包分析，仅用于设备互操作。本仓库**不包含**任何原厂 APK、反编译代码或原厂图片素材。
> 往背夹写入未知命令可能导致设备异常，风险自负；尤其**不要往 OTA 特征写数据**（见协议文档 2.1）。

## 仓库内容

| 目录 | 内容 |
|---|---|
| [docs/protocol-br64.md](docs/protocol-br64.md) | ★ 冰封制冷背夹4Pro磁吸版完整 BLE 协议（GATT、帧格式、全部命令、时序、踩坑） |
| [docs/adding-a-new-device.md](docs/adding-a-new-device.md) | 适配其他型号的步骤 |
| [docs/how-to-capture.md](docs/how-to-capture.md) | Android 蓝牙抓包教程 |
| [docs/static-analysis-br64.md](docs/static-analysis-br64.md) | 官方 App 静态分析记录（型号代号、方法名、UUID 清单） |
| [heisha-lite/](heisha-lite/) | Kotlin 参考 App，只用系统原生 BLE API |
| [tools/](tools/) | 抓包日志解析脚本（Python 3，无第三方依赖） |
| [capture/att_raw.txt](capture/att_raw.txt) | 官方 App 一次会话的 ATT 帧样本 |

## 设备支持

| 设备 | BLE 广播名 | 状态 |
|---|---|---|
| 冰封制冷背夹4Pro磁吸版 | `Black Shark MagCooler 4pro` | ✅ 已支持：制冷模式、制冷开关、温度、风扇灯效、定时关闭、低温保护 |
| 其他型号 | `MagCooler 3Pro / 5pro / 6 / 6Max / 6Pro`、`Fun Cooler 4Pro` 等 | 🙋 待贡献 |

官方 App 里出现过的型号代号：`BR31 BR33 BR41 BR42 BR52 BR60 BR62 BR64 V3`。代号和商品名的对应关系还没完全确认，见 [static-analysis-br64.md](docs/static-analysis-br64.md)。

手上有其他型号？欢迎按 [adding-a-new-device.md](docs/adding-a-new-device.md) 抓包，开一个 [新设备 Issue](../../issues/new?template=new-device.md) 或直接提 PR。

## 快速开始

需要 Android 8.0+ 手机、JDK 17、Android SDK（compileSdk 34）。

```bash
cd heisha-lite
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以用 Android Studio 直接打开 `heisha-lite/` 目录。

只想在自己的项目里接入协议，看 [protocol-br64.md 第 8 节](docs/protocol-br64.md#8-复刻实现参考) 的最小代码即可。

## 许可

- 代码（`heisha-lite/`、`tools/`）：[MIT](LICENSE)
- 文档和抓包样本（`docs/`、`capture/`）：[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.zh-hans)，转载请注明出处
