# 黑鲨冰封制冷背夹4Pro磁吸版（BR64 / MagCooler 4Pro）协议逆向分析

> 分析对象：Shark Arsenal 官方安装包 `base.apk`（版权归原厂，本仓库不附带，可用下方 SHA-256 核对版本）
> 应用：Shark Arsenal（黑鲨工具箱）v5.6.1，包名 `com.blackshark.flutter_toolbox`
> SHA-256：`1ce3d70cba64672f6408e6ccda9d31035d6f3210b8ca1ee756f81f9a7fc66590`
> 分析方式：纯静态（REA + aapt + jadx 1.5.2 + AOT 字符串提取）。**未运行 APK，未连接设备。**
>
> ⚠️ 这份报告写于实机抓包之前，保留它是为了展示静态分析能拿到什么、拿不到什么。其中部分推测已被实测推翻（例如广播名并不是 `BR64…`、控制服务是 `0000a0a0-…` 而不是 `00010203-…`），**协议细节以 [protocol-br64.md](protocol-br64.md) 为准**。

---

## 1. 目标确认

| 项 | 值 | 来源 |
|---|---|---|
| 商品名 | 冰封制冷背夹4Pro磁吸版 | 商品页 |
| 内部代号 | **BR64** | Dart 常量 `BLACKSHARK_COOLER_BR64` |
| 展示名 | `Black Shark MagCooler 4pro` / `Mag Cooler 4Pro` / `MagCooler 4Pro` | libapp.so 字符串 |
| 联名版 | `BLACKSHARK_COOLER_BR64_F`（FanRen 版） | 同上 |
| Dart 模块 | `package:cooler_br64/`（55 个源文件） | libapp.so 字符串 |

同系列对照（用于验证代号规律）：

| 型号 | 商品 | Dart 模块 |
|---|---|---|
| BR31 / BR33 | 冰封2系列 | — |
| BR41 / BR42 | 冰封3系列 | — |
| BR52 | 冰封4 | `cooler_br52` |
| BR60 / BR62 | 冰封5/6 | `cooler_br60` / `cooler_br62` |
| **BR64** | **冰封4Pro磁吸版** | `cooler_br64` |
| V3 / V3_1 | 另产线 | — |

> 注：代号与商品代的对应关系由命名规律与 Dart 包结构推断，非厂商文档确认。

---

## 2. 代码架构（关键结论）

```
┌─────────────────────────────────────────────┐
│ Dart  UI + 业务层  package:cooler_br64/*     │  ← 55 个源文件
│   ├─ model/cooler_br64_ble_peripheral_impl   │  ← BLE 协议实现（核心）
│   ├─ model/br64_gatt_request_task            │  ← GATT 请求队列
│   ├─ model/base_bluetooth_peripheral         │  ← 背夹通用 BLE 基类
│   ├─ peripheral/br64_peripheral              │  ← 外设抽象
│   └─ peripheral/br64_ble_bean                │  ← 通知帧数据模型
├─────────────────────────────────────────────┤
│ BLE 插件层  quick_blue                       │  ← com.example.quick_blue.QuickBluePlugin
│   （另存 flutter_blue_plus、bluetooth_serial） │
├─────────────────────────────────────────────┤
│ Java 平台层                                   │
│   ├─ com.blackshark.gswellness.hardware.composer  ← Flutter 插件（戒指/健康，非背夹）
│   ├─ com.eiot.aizo.ble.*                     │  ← 通用 BLE 框架（戒指用）
│   ├─ com.blackshark.bes_ota                  │  ← BES OTA（背夹固件升级可能复用）
│   └─ com.realsil / com.airoha / com.bluetrum │  ← 耳机芯片 SDK
└─────────────────────────────────────────────┘
```

**核心结论：BR64 背夹的 BLE 协议完全在 Dart 层（AOT 编译进 `libapp.so`），Java 层只是 quick_blue 通用 BLE 通道。**

已核实的 Java 侧无任何 cooler/BR64 类（在 12695 个反编译类中检索为零）。

---

## 3. 设备识别（扫描/配对）

### 3.1 BLE 广播名规律

从 `libapp.so` 提取到的真实设备名字面量：

```
BR31220518V1      ← BR31
BR5220250324V1    ← BR52
BR6020250324V1    ← BR60
BE18211115V0      ← BE18 耳机
BlackShark BH02
BS Ring
```

**格式：`BR` + 2位型号 + 8位日期(YYYYMMDD) + `V` + 版本号**

因此 BR64 的广播名应为 `BR64` + 固件编译日期 + `V` + 版本，例如 `BR6420250324V1`。
（该字符串未出现在本 APK 中，因为 BR64 是较新加入的型号，名字段可能由固件决定而非 App 硬编码。）

### 3.2 型号常量全集

```
BLACKSHARK_COOLER_BR31 / BR33 / BR41 / BR42 / BR52 / BR60 / BR62
BLACKSHARK_COOLER_BR62_F / BR62_Y        ← FanRen / YuanShen 联名
BLACKSHARK_COOLER_BR64 / BR64_F
BLACKSHARK_COOLER_V3 / V3_1
```

扫描入口方法名：`startScanCooler`（Dart）

---

## 4. GATT 特征 UUID 清单

APK 内全部 33 个 UUID，按基段分组：

| 基段 | 短 UUID | 归属判定 |
|---|---|---|
| `0000-1000-8000-00805f9b34fb` | 180a, 180f, 2902, 2a19, 2a24, 2a26, ffe1, ffe2, a001, a002, a003 | 标准 BLE + 中科蓝讯(Bluetrum) 耳机 |
| `22ED-CCDB-79C7-71482B33C801` | ECD0, ECD1, ECD2, FCF0, FCF1, FCF2 | **Realtek 瑞昱**（耳机，com.realsil） |
| `1212-EFDE-1523-785FEABCD123` | **F530, F531, F532, F534** | 4 特征集合，**候选：某背夹型号** |
| `00010203-0405-0607-0809-0a0b0c0d` | **1912, 2b12** | 2 特征集合，**候选：某背夹型号** |
| `4871-c779-dbcc-ed22dXec0000` | d0/d1/d2 | EIoT 戒指 |
| `3c17-d293-8e48-14fe2e4da212` | a0a0 | 单一自定义 |
| `62B8-B7B9-EC38-0256EBDD759F` | 3C923A11 | 单一自定义 |
| `A9C6-F006-4827-4F4035A23554` | 6546C845 | 单一自定义 |
| `D0F0-EEB8-BDF1-45CCFA288BCD` | CFFD6BAE | 单一自定义 |
| `aaaa-4aaa-baaa-aaaaaaaaaaaa` | — | 占位/测试值 |
| `842b-4318-a644-e245f50a0b5a` | 61f76cb0 | SharedPreferences 存储文件名，非 BLE |

### ⚠️ 重要限制

**无法从静态分析确定哪组 UUID 属于 BR64。** Dart AOT 快照中字符串无常驻局部性，
"UUID 靠近某类名"的相邻检索被证明是随机噪声（已用多组样本验证）。
上表标"候选"的两组是本 APK 中唯一的"同基段多特征"自定义集合，最可能是背夹，
但需实机验证。

---

## 5. 协议操作面（已提取的方法名）

从 Dart AOT 符号表提取到的 BR64/背夹协议方法：

### 制冷控制（TEC 半导体制冷）
```
getTECCmd                  获取 TEC 控制指令
queryLowTecStatus          查询低温保护状态
queryLowTecTemperature     查询低温保护温度
getCoolerCmd               背夹通用指令
getWorkModeCmd             工作模式指令
getTableModeCmd / getTableWorkModeCmd
getSpeedMode / getOutSideSpeedMode / getSpeedListWithMode
```

### 灯效控制（RGB）
```
getLightCmd                灯光指令
getLightingModeCmd         灯效模式指令
getLightingParametersCmd   灯光参数指令
getLightColorMode / getLightColorModeName / getOutLightColorModeName
getColorIndexRgb / getColorRgbA / getColorRgbB / getRGB / getArgb
queryMacroList / queryMacroListResponse    灯效宏列表
```

### 保护机制
```
getProtectCmd / getProtectVal
Br64LowTemperatureProtection / Br64LowTecProtection
coolerBr64ProtectionMechanism（页面）
```

### 设备信息 / OTA
```
getDeviceModelCmd / getModelInfo
getFirmwareVersionCmd / readFirmware / getFirmware
OTA_SERVICE_UUID / OTA_DATA_IN_UUID / OTA_DATA_OUT_UUID
_upgradeCoolerFirmware
CoolerOta
br64_ota_manage（util/br64_ota_manage.dart）
```

### 其它
```
readLightSensor            光线传感器
get:br64_voltage_msg       电压信息（磁吸版需检测电压）
get:br64_overlock_msg      超频/解锁
cold_end_temp              冷端温度
```

### 功能模式（UI 层 l10n 键，反映产品功能）
```
coolerModeSmart / coolerModeFrozen / coolerModeExtremeCold   智能/冰封/极寒
cooler_fan_mode1..3 / cooler_light_mode1..14
br62_screen（BR62 有屏，BR64 磁吸版大概率无屏）
```

---

## 6. Dart 类清单（复刻时的对应关系）

`package:cooler_br64/` 全部 55 个源文件：

**协议核心（复刻重点）**
```
model/base_bluetooth_peripheral.dart              BLE 基类（连接/断开/通知订阅）
model/cooler_br64_ble_peripheral.dart             背夹外设抽象
model/cooler_br64_ble_peripheral_impl.dart        ★ 协议实现（命令编解码在这）
model/br64_gatt_request_task.dart                 GATT 请求队列（写/读/超时重试）
peripheral/br64_peripheral.dart                   外设门面
peripheral/br64_ble_bean.dart                     通知帧数据模型
model/cooler_br64_device_bean.dart                设备信息
model/cooler_br64_provider.dart                   状态管理(Provider)
util/br64_ota_manage.dart                         固件 OTA
```

**数据模型**
```
br64_base_light_param_bean / br64_light_standard_mode_param_bean
br64_light_temperature_mode_param_bean / br64_light_macro_mode_frame_bean
br64_lighting_macro_configuration_bean / br64_mode_frame_bean
```

**业务页面（复刻轻量版可全部省略）**
```
page/cooler_br64_main_page.dart                  主控页
page/cooler_br64_work_mode_page.dart             工作模式
page/cooler_br64_lighting_page.dart              灯效
page/cooler_br64_protection_mechanism.dart       保护机制
page/cooler_br64_time_off.dart                   定时关闭
page/cooler_br64_button_work_mode_page.dart      按键模式
page/cooler_br64_laboratory_mode_page.dart       实验室模式
view/cooler_br64_connect_dialog.dart             连接弹窗
```

---

## 7. 本 APK 的第三方依赖（轻量版需剔除）

| 类别 | 组件 |
|---|---|
| 推送/统计 | 腾讯 Bugly、TPNS(XG) 推送、友盟 analytics+ccg、Firebase Analytics |
| 华为 | HMS、ScanKit(scanplus 2.12.0.301)、AGC |
| AI | 讯飞 AIKit、SparkChain 星火大模型、IVW 语音唤醒 |
| 存储 | MMKV、SQLCipher、SharedPreferences |
| UI | uCrop、LuckPicturePicker、Syncfusion PDF、lottie |
| 网络 | dio、retrofit、okhttp |
| 其它 | WorkManager、Geolocator、webview_flutter、inappwebview |

---

## 8. 网络端点（背夹功能可能不需要）

```
toolbox-api.blackshark.com          主 API
app-cfg-api.blackshark.com          配置下发
appcfg.blackshark.com:8088          配置（明文 HTTP）
esports.blackshark.com / esportsen.blackshark.com
cloud.shb.ltd
blackshark-cn.oss-cn-shanghai.aliyuncs.com       固件/图片 OSS
bigbig-won-cn / bigwon-us / bigwon-germany / bigbigwon-jp  说明书 PDF
```

轻量版若只做本地 BLE 控制，**可完全去掉网络层**（除非要 OTA 升级固件）。

---

## 9. 尚未获取的关键信息（诚实说明）

以下内容**无法通过当前可用的静态手段获取**：

1. **BR64 的确切 GATT 服务/特征 UUID** — 需实机验证（见第 4 节限制）
2. **命令帧的字节格式** — 帧头、长度域、命令码、校验算法（`getTECCmd` 等方法体在 AOT 代码段，字符串工具无法还原）
3. **通知帧解析逻辑** — `br64_ble_bean.dart` 的字段偏移与编码
4. **配对/绑定流程** — 是否需要特定握手或 Token
5. **OTA 帧格式** — `OTA_SERVICE_UUID` 等常量的具体值

### 原因
BR64 协议是 Dart 源码，被 AOT 编译为 ARM64 机器码存入 `libapp.so`。
本机未安装 Dart AOT 反编译器（Blutter/darter 等），Ghidra/Hopper/IDA 三个引擎也均未配置，
因此只能提取字符串与符号名，无法还原字节级协议。

---

## 10. 复刻轻量版 APK 的两条路线

### 路线 A：实机抓包（推荐，100% 准确，1~2 天）

需要用户提供一台 **冰封制冷背夹4Pro磁吸版** 实物。

步骤：
1. Android 手机装 BLE 抓包工具（nRF Connect / BLETest / 自研抓包 App）
2. 同时运行官方 Shark Arsenal + 抓包工具，记录：
   - 广播名 / 广播数据 / 连接参数
   - GATT 服务与特征树（含属性：read/write/notify）
   - 每一次写操作的完整字节流
   - 每一次通知的完整字节流
3. 按操作反推帧格式（改一个参数看哪个字节变化）
4. 用 Kotlin + `androidx.bluetooth` / `AndroidBluetoothLe` 复刻

产出：完整的协议规范文档 + 可运行的轻量版 APK

### 路线 B：静态深挖（无需设备，成功率约 60%）

在本机构建 **Blutter**（Dart AOT 快照解析器）：
```bash
brew install cmake llvm   # 需用户授权安装
git clone https://github.com/worawit/Blutter
cd Blutter && bash build.sh
./blutter /tmp/apk_lib/lib/arm64-v8a/libapp.so out
```
Blutter 能输出全部 Dart 类/函数/字段签名，配合 `objdump` 反汇编
`CoolerBr64BlePeripheralImpl::getTECCmd` 等方法，可还原命令字节表。

风险：AOT 反汇编可读性差，帧格式仍需人工推断；Dart 3.x 新快照格式可能不被支持。

---

## 11. 当前已确认可复用的技术要点

| 要点 | 值 |
|---|---|
| 目标设备 | 广播名以 `BR64` 开头，格式 `BR64+YYYYMMDD+V<n>` |
| 传输层 | 标准 BLE GATT，quick_blue 通道（即原生 Android BLE API） |
| 协议层 | 自定义二进制帧，含 TEC/灯效/保护/OTA 四类命令 |
| 设备侧常量 | `BLACKSHARK_COOLER_BR64` |
| 加密 | PointyCastle(AES/CBC/ECB/PKCS7/RSA)、libRtkAesJni.so、SQLCipher |
| 磁吸版特性 | 电压检测（`br64_voltage_msg`）、需磁吸吸附确认 |

---

*文档生成于静态分析，所有"候选/推断"项均需实机验证。分析过程未运行目标 APK，未连接任何设备。*
