package com.heisha.lite.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

private const val TAG = "CoolerBleManager"

/** 心跳日志折叠阈值：每这么多条心跳汇总成一行 */
private const val HB_FOLD_COUNT = 10

/** 初始化序列每条命令之间的间隔（毫秒）——设备无法处理连发命令 */
private const val INIT_STEP_DELAY_MS = 600L

/**
 * 连续写失败达到此次数即判定"链路已死"。
 *
 * 实测：飞行模式切换等场景下 Android 栈会丢失 onConnectionStateChange 回调，
 * app 停留在 READY 但底层链路已断（writeCharacteristic 全部返回 false）。
 * 心跳每秒一次写，连续 3 次失败即可确认是僵尸连接而非瞬时繁忙。
 */
private const val WRITE_FAIL_LIMIT = 3

/**
 * 单次连接尝试的超时（毫秒）。
 * 超时前没有 onConnectionStateChange 回调即视为本次尝试失败。
 */
private const val CONNECT_ATTEMPT_TIMEOUT_MS = 12_000L

/**
 * 连接失败时的最大自动重试次数。
 *
 * 实测：背夹在前一条连接被异常终止（如 App 被强行杀死）后，设备端会残留
 * 旧连接并拒绝新连接，返回 status=62（HCI "Conn Failed to Establishment"），
 * 且残留状态可持续约 6~8 分钟才自行释放。快速重试全部无效，必须以秒级退避
 * 持续重试约 7 分钟，覆盖设备端旧连接的超时释放窗口；仍失败则提示用户
 * 给背夹断电重启（可立即恢复）。
 */
private const val MAX_CONNECT_ATTEMPTS = 36

/** 重试退避间隔：第 N 次重试等待 min(N * BASE, MAX) 毫秒 */
private const val RETRY_BASE_DELAY_MS = 1_500L
private const val RETRY_MAX_DELAY_MS = 12_000L

/**
 * 请求优雅断开后，兜底 close 旧 client 的延迟。
 * disconnect() 是异步的，必须等 STATE_DISCONNECTED 回调才能 close()；
 * 背靠背调 close() 会把断开抢断，设备端收不到链路终止，从而残留旧连接
 * （下次连接 status=62 的根因之一）。
 */
private const val CLOSE_FALLBACK_MS = 1_500L

/** 定时关闭到点判定的容差（毫秒）：设备与手机计时有秒级误差 */
private const val TIMER_SHUTDOWN_TOLERANCE_MS = 5_000L

/** 记住上次成功连接的背夹，用于打开 App 时自动连接 */
private const val PREFS_NAME = "ble"
private const val KEY_LAST_DEVICE = "last_device"

/** 心跳间隔：实测原 App 约每秒一次 */
private const val HEARTBEAT_MS = 1_000L

/**
 * BR64 背夹 BLE 管理器：扫描 → 连接 → 服务发现 → 通知订阅 → 收发命令
 *
 * 这一层与原 App 的 `CoolerBr64BlePeripheralImpl` + quick_blue 对应，
 * 但直接用系统 API，无第三方依赖。
 *
 * ## 连接健壮性（2026-10-16 实机联调补充）
 *
 * - **单次连接**：连接中/已连接时忽略再次点击，防止建多个 GATT client
 *   （多 client 会互相抢占，writeCharacteristic 全部返回 false）
 * - **自动重试**：连接失败（如 status=62 背夹旧连接残留）时自动退避重试，
 *   重试进度写入日志，无需用户手动反复点
 * - **尝试超时**：单次尝试 12s 无回调即放弃重来，不会卡在 CONNECTING
 * - **优雅断开**：disconnect() 后等回调再 close()，避免设备端残留连接
 * - **进程内单例**：用 [get] 获取。实测 MainActivity 出现过两个实例，
 *   各持一个 manager：旧实例的 GATT 连接一直占着背夹（背夹被连着就停止广播），
 *   新实例扫描不到设备，表现为"连接不上"
 */
class CoolerBleManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: CoolerBleManager? = null

        fun get(context: Context): CoolerBleManager =
            instance ?: synchronized(this) {
                instance ?: CoolerBleManager(context.applicationContext).also { instance = it }
            }
    }

    private val btManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var charWrite: BluetoothGattCharacteristic? = null
    private var charNotify: BluetoothGattCharacteristic? = null

    /** 就绪后延迟发初始化查询的定时器 */
    private val readyHandler = Handler(Looper.getMainLooper())

    /** 初始化序列逐条发送的索引 */
    private var initIndex = 0

    // ------------------------------------------------------------------
    // 连接状态机（见类注释：单次连接 / 自动重试 / 尝试超时 / 优雅断开）
    // ------------------------------------------------------------------

    /** 正在连接或已连接：为 true 时忽略重复的 connect() 调用 */
    private var connecting = false

    /** 用户主动断开：为 true 时断开回调只做清理，不触发自动重连 */
    private var intentionalDisconnect = false

    /** 当前连接/重试的目标设备 */
    private var pendingDevice: BluetoothDevice? = null

    /** 第几次连接尝试（从 1 开始，连接成功后清零） */
    private var connectAttempt = 0

    /** 连续写失败计数：用于发现"回调丢失的僵尸连接"（见 WRITE_FAIL_LIMIT） */
    private var writeFailStreak = 0

    private val connectTimeout = object : Runnable {
        override fun run() {
            Log.w(TAG, "连接超时（${CONNECT_ATTEMPT_TIMEOUT_MS}ms 无回调），放弃本次尝试")
            appendLog("--- 连接超时，重试中 ---")
            // 超时的 client 不会再来回调，直接丢弃
            runCatching { gatt?.close() }
            gatt = null
            connecting = false
            scheduleRetry()
        }
    }

    private val retryTask = object : Runnable {
        override fun run() {
            pendingDevice?.let { doConnect(it) }
        }
    }

    /**
     * 逐条带间隔发送初始化序列。
     * 实测：连发多条命令时设备只处理第一条，其余静默丢弃；
     * 原版 App 的 GATT 请求队列也是串行发送的。
     */
    private fun startInitSequence() {
        readyHandler.removeCallbacks(initStep)
        initIndex = 0
        readyHandler.postDelayed(initStep, 800L)
    }

    private val initStep = object : Runnable {
        override fun run() {
            val seq = CoolerProtocol.initSequence()
            if (initIndex >= seq.size) return
            send(seq[initIndex])
            initIndex++
            if (initIndex < seq.size) {
                readyHandler.postDelayed(this, INIT_STEP_DELAY_MS)
            } else {
                // 同原版：查询序列发完才开始心跳。实测心跳插在序列中间时，
                // 设备不回 05 01 18 的灯效配置（AF 01 18 …）
                readyHandler.postDelayed(heartbeatTask, HEARTBEAT_MS)
            }
        }
    }

    /**
     * 心跳：就绪后每秒一次，链路断开（charWrite 被清空）即自动停止。
     * 放在 manager 内而不是 Activity 里，保证一条连接只有一路心跳。
     */
    private val heartbeatTask = object : Runnable {
        override fun run() {
            if (charWrite == null) return
            send(CoolerProtocol.heartbeat())
            readyHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 本次扫描要自动连接的地址（上次成功连接的背夹），null = 不自动连接 */
    private var autoConnectTarget: String? = null

    /** 扫描到的设备：地址 -> (设备, 最近一次 rssi)，按 rssi 降序暴露 */
    private val _found = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val found: StateFlow<List<BluetoothDevice>> = _found
    private val rssiMap = mutableMapOf<String, Int>()

    /** 供 UI 展示的原始通知日志（调试协议时很有用） */
    private val _rawLog = MutableStateFlow<List<String>>(emptyList())
    val rawLog: StateFlow<List<String>> = _rawLog

    /** 心跳行折叠计数（见 appendLog） */
    private var hbPending = 0

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------
    fun hasBlePermissions(): Boolean {
        val need = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return need.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    fun startScan(autoConnect: Boolean = false) {
        if (!hasBlePermissions() || !isBluetoothEnabled()) return
        stopScan()
        // 已连接时只刷新列表，不覆盖 READY 等连接状态
        if (gatt == null) CoolerRepository.setConn(ConnState.SCANNING)
        _found.value = emptyList()
        autoConnectTarget = if (autoConnect && gatt == null) prefs.getString(KEY_LAST_DEVICE, null) else null

        // 背夹被连着时不广播，扫描永远扫不到。原版 App 按 MAC 直连已配对设备，
        // 这里同样先把系统里已连接/已配对的背夹放进列表，不依赖广播也能点连接
        val connected = btManager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty().filter { isCooler(it.name) }
        (connected + adapter?.bondedDevices.orEmpty()).forEach { d -> if (isCooler(d.name)) addFound(d, -1) }
        // 已连着（如原版 App 占着）就不必等广播，直接自动连接。
        // 已配对但未连接的不算：背夹可能关机，直连会白白重试 7 分钟，交给扫描结果判断
        if (connected.any { maybeAutoConnect(it) }) return

        val scanner = adapter?.bluetoothLeScanner ?: return
        // 注意：ScanFilter.setDeviceName() 是【精确匹配】而非前缀匹配，
        // 而背夹广播名带日期/版本后缀（如 BR42240428V191），无法预先写死。
        // 因此不加名字过滤，扫到全部设备后在回调里用前缀判断。
        scanner.startScan(
            emptyList<ScanFilter>(),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            scanCb
        )
        Log.i(TAG, "开始扫描，回调内按前缀列表=${BleConstants.DEVICE_NAME_PREFIXES} 过滤")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasBlePermissions()) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val d = result.device
            val name = d.name ?: result.scanRecord?.deviceName
            Log.d(TAG, "扫描到: name=$name addr=${d.address} rssi=${result.rssi}")
            if (isCooler(name)) {
                addFound(d, result.rssi)
                maybeAutoConnect(d)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "扫描失败 errorCode=$errorCode")
            CoolerRepository.setConn(ConnState.IDLE)
        }
    }

    /** 广播名匹配（实测为 "Black Shark MagCooler 4pro"） */
    private fun isCooler(name: String?): Boolean =
        !name.isNullOrBlank() && BleConstants.DEVICE_NAME_PREFIXES.any {
            name.startsWith(it, ignoreCase = true)
        }

    /** 扫到上次连接的背夹时自动连接；只触发一次 */
    private fun maybeAutoConnect(d: BluetoothDevice): Boolean {
        if (d.address != autoConnectTarget) return false
        autoConnectTarget = null
        appendLog("--- 找到上次连接的背夹，自动连接 ---")
        connect(d)
        return true
    }

    private fun addFound(d: BluetoothDevice, rssi: Int) {
        val cur = _found.value.toMutableList()
        if (cur.none { it.address == d.address }) {
            rssiMap[d.address] = rssi
            cur += d
            _found.value = cur.sortedByDescending { rssiMap[it.address] ?: -999 }
        } else {
            rssiMap[d.address] = rssi
        }
    }

    // ------------------------------------------------------------------
    // 连接（含自动重试，见类注释）
    // ------------------------------------------------------------------

    /**
     * 连接背夹。连接中/已连接时忽略重复调用——
     * 重复点击会建立多个 GATT client 互相抢占，导致之后所有写操作返回 false。
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (connecting || gatt != null) {
            Log.i(TAG, "connect() 被忽略：连接中或已连接（防重复点击）")
            appendLog("--- 已在连接/已连接，忽略重复点击 ---")
            return
        }
        intentionalDisconnect = false
        connecting = true
        connectAttempt = 0
        doConnect(device)
    }

    /** 执行一次连接尝试（失败重试的公共入口） */
    @SuppressLint("MissingPermission")
    private fun doConnect(device: BluetoothDevice) {
        pendingDevice = device
        connectAttempt++
        stopScan()
        CoolerRepository.setConn(ConnState.CONNECTING)
        CoolerRepository.update {
            copy(connected = false, deviceName = device.name ?: "", mac = device.address)
        }
        // 清理上一个 client：残留的 client 会与新连接互相抢占
        runCatching { gatt?.close() }
        gatt = null
        charWrite = null
        charNotify = null
        writeFailStreak = 0
        readyHandler.removeCallbacks(initStep)

        // autoConnect=false 直连，背夹这类设备必须直连
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else
            device.connectGatt(context, false, gattCb)
        Log.i(TAG, "连接尝试 #$connectAttempt ${device.address}")
        if (connectAttempt > 1) appendLog("--- 第 $connectAttempt 次连接尝试 ---")
        readyHandler.postDelayed(connectTimeout, CONNECT_ATTEMPT_TIMEOUT_MS)
    }

    /**
     * 安排下一次重试。失败回调、尝试超时都会走到这里。
     * 退避间隔逐次拉长（1.5s → 12s 封顶），给设备端释放旧连接留时间。
     */
    private fun scheduleRetry() {
        val d = pendingDevice ?: return
        // 先丢弃旧 client，否则重试会堆积多个 client
        runCatching { gatt?.close() }
        gatt = null
        charWrite = null
        charNotify = null

        if (connectAttempt >= MAX_CONNECT_ATTEMPTS) {
            connecting = false
            CoolerRepository.setConn(ConnState.DISCONNECTED)
            CoolerRepository.update { copy(connected = false) }
            appendLog("--- 重试 $MAX_CONNECT_ATTEMPTS 次仍失败：背夹旧连接未释放。请给背夹断电重启（磁吸取下/拔USB 10秒），或过几分钟再点一次 ---")
            Log.w(TAG, "连接重试 $MAX_CONNECT_ATTEMPTS 次全部失败")
            return
        }
        val delay = minOf(connectAttempt * RETRY_BASE_DELAY_MS, RETRY_MAX_DELAY_MS)
        appendLog("--- ${delay}ms 后重试（$connectAttempt/$MAX_CONNECT_ATTEMPTS）---")
        readyHandler.postDelayed(retryTask, delay)
    }

    /** 断开发生在定时关闭到点前后 5 秒内，视为背夹按定时关机 */
    private fun isTimerShutdown(): Boolean {
        val s = CoolerRepository.state.value
        return s.shutdownUnits > 0 &&
            SystemClock.elapsedRealtime() >= s.shutdownDeadline - TIMER_SHUTDOWN_TOLERANCE_MS
    }

    /**
     * 链路已死但 app 没收到断开回调时的强制恢复。
     *
     * 实测：飞行模式切换等场景下 Android 栈会丢 onConnectionStateChange，
     * gatt 退化成僵尸 client——状态还显示 READY，但 writeCharacteristic
     * 全部返回 false。僵尸 client 等不到回调，只能直接 close() 丢弃，
     * 然后走普通的重连流程。
     */
    @SuppressLint("MissingPermission")
    private fun recoverFromDeadLink() {
        if (writeFailStreak < WRITE_FAIL_LIMIT) return
        Log.w(TAG, "写连续失败 $writeFailStreak 次，判定链路已死，强制重连")
        appendLog("--- 链路已死（写失败×$writeFailStreak），重新连接 ---")
        writeFailStreak = 0
        connecting = false
        intentionalDisconnect = false
        // 僵尸 client 不会回调，disconnect() 无意义，必须直接 close()
        runCatching { gatt?.close() }
        gatt = null
        charWrite = null
        charNotify = null
        CoolerRepository.update { copy(connected = false) }
        CoolerRepository.setConn(ConnState.CONNECTING)
        scheduleRetry()
    }

    /**
     * 主动断开。只请求断开，close() 等 STATE_DISCONNECTED 回调后执行——
     * 背靠背 close() 会抢断断开流程，设备端残留旧连接（status=62 根源之一）。
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        intentionalDisconnect = true
        connecting = false
        pendingDevice = null
        cancelPending()
        CoolerRepository.setConn(ConnState.DISCONNECTED)
        CoolerRepository.update { copy(connected = false) }
        appendLog("--- 主动断开连接 ---")
        runCatching { gatt?.disconnect() }
    }

    /**
     * 断开并释放 client（应用退出用）。
     * 先走优雅断开；1.5s 兜底 close()，防 STATE_DISCONNECTED 回调丢失。
     */
    @SuppressLint("MissingPermission")
    fun close() {
        val g = gatt
        intentionalDisconnect = true
        connecting = false
        pendingDevice = null
        cancelPending()
        if (g == null) return
        runCatching { g.disconnect() }
        readyHandler.postDelayed({
            runCatching { g.close() }
            if (this.gatt === g) this.gatt = null
        }, CLOSE_FALLBACK_MS)
    }

    /** 取消所有待执行的连接相关定时任务 */
    private fun cancelPending() {
        readyHandler.removeCallbacks(connectTimeout)
        readyHandler.removeCallbacks(retryTask)
        readyHandler.removeCallbacks(initStep)
        readyHandler.removeCallbacks(heartbeatTask)
    }

    // ------------------------------------------------------------------
    // GATT 回调
    // ------------------------------------------------------------------
    private val gattCb = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    readyHandler.removeCallbacks(connectTimeout)
                    connecting = false
                    connectAttempt = 0
                    writeFailStreak = 0
                    CoolerRepository.update { copy(connected = true) }
                    CoolerRepository.setConn(ConnState.DISCOVERING)
                    // 不主动 requestMtu：背夹连上约 1 秒后会自己发起 MTU 51 协商，够发 47 字节灯效帧。
                    // 实测复用已有链路时 requestMtu 会立刻拿到缓存回调、随后真正的协商才到，
                    // 期间写 CCCD 永远收不到完成回调，之后所有写都返回 false
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    charWrite = null; charNotify = null
                    val wasConnecting = connecting
                    connecting = false
                    readyHandler.removeCallbacks(connectTimeout)

                    if (intentionalDisconnect) {
                        // 主动断开：优雅关闭 client
                        intentionalDisconnect = false
                        runCatching { g.close() }
                        if (gatt === g) gatt = null
                        appendLog("--- 连接已关闭 status=$status ---")
                        return
                    }

                    if (isTimerShutdown()) {
                        // 定时关闭到点：背夹整机断电（实测蓝牙一起关，链路超时 status=8），
                        // 不是故障，重连只会白白重试 7 分钟
                        runCatching { g.close() }
                        if (gatt === g) gatt = null
                        pendingDevice = null
                        CoolerRepository.update { copy(connected = false, shutdownUnits = 0, shutdownDeadline = 0L) }
                        CoolerRepository.setConn(ConnState.POWERED_OFF)
                        appendLog("--- 定时关闭已到点，背夹已关机 status=$status ---")
                        return
                    }

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        // 连接失败（典型：status=62 背夹旧连接残留）→ 自动重试
                        appendLog("--- 连接失败 status=$status ---")
                        scheduleRetry()
                    } else if (wasConnecting) {
                        // 连接过程中被断开且无错误码：也走重试
                        appendLog("--- 连接中断 status=$status ---")
                        scheduleRetry()
                    } else {
                        // 已就绪后意外断开（设备休眠/走远）：自动重连
                        CoolerRepository.setConn(ConnState.DISCONNECTED)
                        CoolerRepository.update { copy(connected = false) }
                        appendLog("--- 意外断开 status=$status，尝试重连 ---")
                        scheduleRetry()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged mtu=$mtu")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("服务发现失败 status=$status")
                scheduleRetry()
                return
            }
            dumpServices(g)

            // 只认控制服务。另一个自定义服务 00010203-…1912 是 Telink OTA 通道，
            // 往里写控制帧可能让背夹进入升级状态，不能当备用接口
            val svc = g.getService(UUID.fromString(BleConstants.SERVICE_UUID))
            charWrite = svc?.getCharacteristic(UUID.fromString(BleConstants.CHAR_WRITE))
            charNotify = svc?.getCharacteristic(UUID.fromString(BleConstants.CHAR_NOTIFY))
            if (charWrite == null || charNotify == null) {
                appendLog("未找到控制服务或其写/通知特征，请看上面的服务清单")
                scheduleRetry()
                return
            }

            g.setCharacteristicNotification(charNotify, true)
            charNotify?.descriptors?.forEach { d ->
                if (d.uuid == UUID.fromString(BleConstants.DESCRIPTOR_CCCD)) {
                    // 只开通知（01 00），同原版。实测再补写指示（02 00）会让设备
                    // 不回 05 01 18 的灯效配置响应（AF 01 18 …），其余查询照常
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }

            CoolerRepository.setConn(ConnState.READY)
            appendLog("--- 就绪，可下发命令 ---")
            prefs.edit().putString(KEY_LAST_DEVICE, g.device.address).apply()
            // 连上后的初始化序列：复刻原版 App 定序，逐条带间隔发送
            readyHandler.removeCallbacks(heartbeatTask)
            startInitSequence()
        }

        @Deprecated("API<33 走这里")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic
        ) {
            handleNotify(c.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            Log.i(TAG, "onCharacteristicWrite: ${c.value.hex()} status=$status")
            appendLog("TX ${c.value.hex()} ${if (status == 0) "ok" else "fail($status)"}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeFailStreak = 0
            } else {
                writeFailStreak++
                recoverFromDeadLink()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int
        ) {
            Log.i(TAG, "onDescriptorWrite: ${d.uuid} value=${d.value?.hex()} status=$status")
        }
    }

    private fun handleNotify(data: ByteArray) {
        Log.i(TAG, "onCharacteristicChanged: ${data.hex()}")
        appendLog("RX ${data.hex()}")
        // 能收到通知说明链路是活的，重置僵尸连接判定
        writeFailStreak = 0
        CoolerProtocol.parse(data).forEach { f ->
            CoolerProtocol.applyToState(f) { CoolerRepository.update(it) }
        }
    }

    // ------------------------------------------------------------------
    // 下发命令
    // ------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    fun send(frame: ByteArray): Boolean {
        val c = charWrite ?: run {
            appendLog("未就绪，不能发送")
            Log.w(TAG, "send() 被调用但 charWrite=null: ${frame.hex()}")
            return false
        }
        Log.i(TAG, "send() 调用: ${frame.hex()}")
        return try {
            c.writeType = if (BleConstants.WRITE_NO_RESPONSE)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = frame
            val ok = gatt?.writeCharacteristic(c) ?: false
            Log.i(TAG, "writeCharacteristic 返回: $ok")
            if (ok) {
                writeFailStreak = 0
            } else {
                writeFailStreak++
                appendLog("writeCharacteristic 返回 false")
                recoverFromDeadLink()
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "发送失败", e)
            appendLog("发送异常 ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    private fun ByteArray.hex(): String =
        joinToString(" ") { String.format(java.util.Locale.US, "%02X", it) }

    /**
     * 追加日志。
     *
     * 心跳（TX 05 06 / RX 8A 06）每秒一对，会把控制命令冲出屏幕。
     * 因此心跳行不逐条入栈，而是计数后汇总成一行；
     * 其余行（控制命令、连接事件、GATT 树）逐条保留。
     */
    private fun appendLog(s: String) {
        val isHeartbeat = s.startsWith("TX 05 06") || s.startsWith("RX 8A 06")
        if (isHeartbeat) {
            hbPending++
            if (hbPending >= HB_FOLD_COUNT) {
                _rawLog.value = (_rawLog.value + "… 心跳收发 ×$hbPending").takeLast(300)
                hbPending = 0
            }
            return
        }
        // 非心跳行：先把折叠中的心跳补成汇总行，再写本行
        if (hbPending > 0) {
            _rawLog.value = (_rawLog.value + "… 心跳收发 ×$hbPending").takeLast(300)
            hbPending = 0
        }
        _rawLog.value = (_rawLog.value + s).takeLast(300)
    }

    /** 把 GATT 树打到日志里 —— 抓包后确认 UUID 的关键 */
    @SuppressLint("MissingPermission")
    private fun dumpServices(g: BluetoothGatt) {
        val sb = StringBuilder()
        g.services.forEach { s ->
            sb.append("SVC ${s.uuid}\n")
            s.characteristics.forEach { c ->
                val props = buildList {
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("R")
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("W")
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("Wn")
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("N")
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("I")
                }
                sb.append("   CH ${c.uuid} [${props.joinToString("")}]\n")
            }
        }
        Log.i(TAG, "GATT 树:\n$sb")
        appendLog(sb.toString())
    }
}
