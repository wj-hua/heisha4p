# Android 蓝牙抓包教程

用官方 App 操作背夹，同时让 Android 记录全部蓝牙收发（HCI snoop），再用 `tools/` 里的脚本解析出 ATT 帧。不需要 root。

## 1. 准备

1. 一台 Android 手机，装好官方 App（Shark Arsenal）
2. 电脑装好 `adb`，手机打开 USB 调试并连上电脑
3. 手机「开发者选项」→ 打开 **启用蓝牙 HCI 信息收集日志**（Enable Bluetooth HCI snoop log）
4. **关闭再打开一次蓝牙**，设置才会生效

## 2. 操作

1. 给背夹通电，先**不要**打开官方 App
2. 打开官方 App，连接背夹，然后进入控制页
3. 按功能逐个操作，**一次只改一个参数**，每步之间停 2~3 秒，并记下每一步做了什么和大概的时间

> 原则：两次操作之间只有一个参数不同，才能把"哪个字节变了"对应到"哪个功能"。
> 例如：静音 → 超频 → 静音；同一灯效下只改颜色；同一颜色下只改速度。

## 3. 导出日志

不同手机导出方式不一样，按顺序试：

**方式 A：`dumpsys`（小米 / 部分 Android 13 实测可用）**

```bash
adb shell dumpsys bluetooth_manager > dump.txt
python3 tools/mi_snoop.py dump.txt --att        # 只看 ATT 层
python3 tools/mi_snoop.py dump.txt --handles    # handle → UUID 映射
python3 tools/mi_snoop.py dump.txt --adv        # 扫描到的广播名
```

如果输出里有 `BTSNOOP_LOG_SUMMARY`，说明这种方式可用。这个缓冲区只有约 256 KB，操作时间一长开头就会被覆盖。需要长时间采集时，用守护脚本持续轮询并去重：

```bash
adb devices                                              # 查看序列号
python3 tools/capture_daemon.py --serial <序列号> --out capture.jsonl
# 另开终端操作手机，完成后 Ctrl-C
python3 tools/report.py capture.jsonl                    # GATT 树 + 时间线 + 帧差异分析
```

**方式 B：标准 `btsnoop_hci.log`**

```bash
adb bugreport bugreport.zip
# 解压后在 FS/data/misc/bluetooth/logs/ 下找到 btsnoop_hci.log
python3 tools/btsnoop_parse.py btsnoop_hci.log
python3 tools/btsnoop_parse.py btsnoop_hci.log --grep "05 02"   # 按十六进制过滤
```

`btsnoop_hci.log` 也可以直接用 Wireshark 打开。

## 4. 分析

1. 先从服务发现结果（`--handles`）里找出自定义服务，以及它的写特征、通知特征的 handle
2. 只看这两个 handle 的 `WriteCmd` / `WriteRq`（手机发）和 `Notification`（背夹回）
3. 对照你记录的操作步骤：
   - 反复出现、内容不变的帧 → 心跳或查询
   - 只有 1 个字节不同的帧 → 这个字节就是对应功能的参数
   - `report.py` 第 3 部分会按长度分组，自动列出变化的字节位置
4. 用 [nRF Connect](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp) 手动发**查询类**命令验证猜测，确认后再写进代码

## 5. 隐私

完整的 `dump.txt`、`bugreport.zip`、`btsnoop_hci.log` 里有你手机上**所有**蓝牙设备的信息（耳机、车机、手表的名称和 MAC 等），**不要公开上传**。分享时只贴背夹相关的 ATT 行，背夹 MAC 后三段打码。
