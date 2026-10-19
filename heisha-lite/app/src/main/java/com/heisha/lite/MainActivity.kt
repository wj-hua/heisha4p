package com.heisha.lite

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.heisha.lite.ble.BleConstants
import com.heisha.lite.ble.ConnState
import com.heisha.lite.ble.CoolerBleManager
import com.heisha.lite.ble.CoolerProtocol
import com.heisha.lite.ble.CoolerRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 轻量版主界面，布局参照原版 App 的背夹设备页：
 *   背夹图 → 模式说明 → 静音/超频/智享 切换条 → 白色信息卡片
 *
 * 只接入抓包确认过的功能：
 *   - 制冷模式（cmd 0x02：0x2D / 0x14 / 0xFA）+ 配套灯效 preset（cmd 0x05）
 *   - 背夹温度（心跳响应）、固件版本（cmd 0x04）
 *   - 灯效设置 → [LightActivity]
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ble: CoolerBleManager

    private lateinit var tvModeDesc: TextView
    private lateinit var modeBar: View
    private lateinit var modeViews: Map<Int, View>
    private lateinit var panelConnect: View
    private lateinit var panelDevice: View
    private lateinit var tvConnState: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var btnScan: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvHotTemp: TextView
    private lateinit var tvFirmware: TextView
    private lateinit var tvTimer: TextView
    private lateinit var swLowTemp: SwitchCompat
    private lateinit var swCooling: SwitchCompat

    /** 关闭制冷前的模式，重新打开时恢复它 */
    private var lastCoolingMode = BleConstants.WorkMode.SILENT
    private lateinit var tvLog: TextView

    /** 命令延迟下发 / 扫描超时 */
    private val handler = Handler(Looper.getMainLooper())

    /** 每秒刷新定时关闭倒计时 */
    private val timerTick = object : Runnable {
        override fun run() {
            renderTimer()
            handler.postDelayed(this, 1000L)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result.values.all { it }) ensurePermThenScan(autoConnect = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ble = CoolerBleManager.get(this)

        tvModeDesc = findViewById(R.id.tvModeDesc)
        modeBar = findViewById(R.id.modeBar)
        modeViews = mapOf(
            BleConstants.WorkMode.SILENT to findViewById<View>(R.id.modeSilent),
            BleConstants.WorkMode.OVERCLOCK to findViewById<View>(R.id.modeOverclock),
            BleConstants.WorkMode.SMART to findViewById<View>(R.id.modeSmart),
        )
        panelConnect = findViewById(R.id.panelConnect)
        panelDevice = findViewById(R.id.panelDevice)
        tvConnState = findViewById(R.id.tvConnState)
        devicesContainer = findViewById(R.id.devicesContainer)
        btnScan = findViewById(R.id.btnScan)
        tvTemp = findViewById(R.id.tvTemp)
        tvHotTemp = findViewById(R.id.tvHotTemp)
        tvFirmware = findViewById(R.id.tvFirmware)
        tvTimer = findViewById(R.id.tvTimer)
        swLowTemp = findViewById(R.id.swLowTemp)
        swCooling = findViewById(R.id.swCooling)
        findViewById<View>(R.id.rowCooling).setOnClickListener {
            val coolingOn = CoolerRepository.state.value.fanLevel != BleConstants.WorkMode.LIGHT_ONLY
            setMode(if (coolingOn) BleConstants.WorkMode.LIGHT_ONLY else lastCoolingMode)
        }
        tvLog = findViewById(R.id.tvLog)

        modeViews.forEach { (value, view) -> view.setOnClickListener { setMode(value) } }
        btnScan.setOnClickListener { ensurePermThenScan() }
        // 优雅断开：直接杀 App 会让背夹残留旧连接，下次连接 status=62
        findViewById<View>(R.id.btnDisconnect).setOnClickListener { ble.disconnect() }
        findViewById<View>(R.id.rowLight).setOnClickListener {
            startActivity(Intent(this, LightActivity::class.java))
        }
        findViewById<View>(R.id.rowTimer).setOnClickListener { showTimerDialog() }
        findViewById<View>(R.id.rowLowTemp).setOnClickListener { toggleLowTempProtect() }
        findViewById<View>(R.id.btnLog).setOnClickListener { tvLog.isVisible = !tvLog.isVisible }

        observe()

        // 同原版 App：打开即找背夹，扫到上次连接过的就自动连上；已连接/连接中则不打扰
        val cs = CoolerRepository.connState.value
        if (cs == ConnState.IDLE || cs == ConnState.DISCONNECTED || cs == ConnState.POWERED_OFF) {
            ensurePermThenScan(autoConnect = true)
        }
    }

    /** 切换制冷模式 */
    private fun setMode(value: Int) {
        if (CoolerRepository.connState.value != ConnState.READY) return
        // 先写进状态再等设备回查：只改界面的话，回查前到达的心跳会按旧值重绘，选中项闪回一下
        CoolerRepository.update { copy(fanLevel = value) }
        ble.send(CoolerProtocol.setCooling(value))
        // 复刻原版 App：切换模式时同步下发配套灯效 preset。
        // 设备会丢弃间隔 <100ms 的连发命令，所以错开下发
        handler.postDelayed({ ble.send(CoolerProtocol.setLightMode(MODE_LIGHT.getValue(value))) }, 200L)
        // 复刻原版 App：设置后 600ms 回查一次，确认设备接受并取回当前值
        handler.postDelayed({ ble.send(CoolerProtocol.queryCooling()) }, 600L)
    }

    private fun showMode(value: Int) {
        modeViews.forEach { (v, view) -> view.isSelected = v == value }
        tvModeDesc.text = MODE_DESC[value].orEmpty()
    }

    /** 定时关闭：选项与原版 App 一致，另加 15 分钟（协议最小单位） */
    private fun showTimerDialog() {
        if (CoolerRepository.connState.value != ConnState.READY) return
        val labels = TIMER_OPTIONS.map { timerLabel(it) }.toTypedArray()
        val checked = TIMER_OPTIONS.indexOf(CoolerRepository.state.value.shutdownUnits)
        AlertDialog.Builder(this)
            .setTitle("定时关闭")
            .setSingleChoiceItems(labels, checked) { dialog, i ->
                dialog.dismiss()
                ble.send(CoolerProtocol.setShutdownTimer(TIMER_OPTIONS[i]))
                // 复刻原版 App：设置后回查，取回设备当前的定时值
                handler.postDelayed({ ble.send(CoolerProtocol.queryShutdownTimer()) }, 300L)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 低温保护：关闭前弹出与原版 App 相同的警告 */
    private fun toggleLowTempProtect() {
        if (CoolerRepository.connState.value != ConnState.READY) return
        if (!CoolerRepository.state.value.lowTempProtect) {
            setLowTempProtect(true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("注意事项")
            .setMessage("关闭低温保护，可能导致背夹温度过低，结冰等现象，长时间会导致设备受损（重启后默认打开）")
            .setPositiveButton("仍要关闭") { _, _ -> setLowTempProtect(false) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setLowTempProtect(on: Boolean) {
        ble.send(CoolerProtocol.setLowTempProtect(on))
        // 设置 ACK 不带状态，回查取回设备实际状态
        handler.postDelayed({ ble.send(CoolerProtocol.queryLowTempProtect()) }, 300L)
    }

    private fun timerLabel(units: Int): String {
        val min = units * BleConstants.SHUTDOWN_UNIT_MIN
        return when {
            units == 0 -> "不开启"
            min < 60 -> "${min}分钟后"
            min % 60 == 0 -> "${min / 60}小时后"
            else -> "${min / 60}小时${min % 60}分钟后"
        }
    }

    /** 定时关闭行：计时中显示「29分 42秒」倒计时（同原版），否则显示「不开启」 */
    private fun renderTimer() {
        val s = CoolerRepository.state.value
        val units = s.shutdownUnits
        val left = s.shutdownDeadline - SystemClock.elapsedRealtime()
        val counting = units > 0 && left > 0
        tvTimer.text = if (counting) formatLeft(left) else timerLabel(units)
        tvTimer.setTextColor(getColor(if (counting) R.color.timer_active else R.color.text_primary))
    }

    private fun formatLeft(ms: Long): String {
        val sec = (ms + 999) / 1000
        val h = sec / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return if (h > 0) "${h}时 ${m}分 ${"%02d".format(s)}秒" else "${m}分 ${"%02d".format(s)}秒"
    }

    /** @param autoConnect 扫到上次连接的背夹时自动连接（打开 App 时用；手动点「搜索」不自动连） */
    private fun ensurePermThenScan(autoConnect: Boolean = false) {
        val need = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray()); return
        }
        if (!ble.isBluetoothEnabled()) {
            Toast.makeText(this, "请先打开蓝牙", Toast.LENGTH_SHORT).show(); return
        }
        ble.startScan(autoConnect)
        btnScan.isEnabled = false
        handler.postDelayed({
            btnScan.isEnabled = true
            // 扫描超时：停止扫描省电，状态回到空闲
            if (CoolerRepository.connState.value == ConnState.SCANNING) {
                ble.stopScan()
                CoolerRepository.setConn(ConnState.IDLE)
            }
        }, BleConstants.SCAN_TIMEOUT_MS)
    }

    private fun observe() {
        lifecycleScope.launch {
            combine(CoolerRepository.connState, CoolerRepository.state) { cs, s -> cs to s }
                .collectLatest { (cs, s) ->
                    val ready = cs == ConnState.READY
                    panelDevice.isVisible = ready
                    panelConnect.isVisible = !ready
                    modeBar.alpha = if (ready) 1f else 0.5f
                    if (ready && s.fanLevel in MODE_DESC) showMode(s.fanLevel)
                    if (s.fanLevel in modeViews) lastCoolingMode = s.fanLevel
                    swCooling.isChecked = s.fanLevel != BleConstants.WorkMode.LIGHT_ONLY
                    tvConnState.text = when (cs) {
                        ConnState.IDLE -> if (ble.found.value.isEmpty()) "未连接，点击下方搜索背夹" else "点击背夹进行连接"
                        ConnState.SCANNING -> "正在搜索背夹…"
                        ConnState.CONNECTING -> "正在连接…"
                        ConnState.DISCOVERING -> "正在读取设备信息…"
                        ConnState.DISCONNECTED -> "已断开"
                        ConnState.POWERED_OFF -> "背夹已按定时关闭。重新吸附或插电开机后，点击下方搜索"
                        ConnState.READY -> ""
                    }
                    tvTemp.text = if (s.coldEndTemp > 0) "${s.coldEndTemp}°C" else "--"
                    tvHotTemp.text = if (s.hotEndTemp > 0) "${s.hotEndTemp}°C" else "--"
                    tvFirmware.text = s.firmware.ifEmpty { "--" }
                    renderTimer()
                    swLowTemp.isChecked = s.lowTempProtect
                }
        }
        lifecycleScope.launch { ble.found.collectLatest { renderDevices(it) } }
        lifecycleScope.launch {
            ble.rawLog.collectLatest { lines ->
                tvLog.text = lines.takeLast(20).joinToString("\n")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderDevices(list: List<BluetoothDevice>) {
        devicesContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        list.forEach { d ->
            val row = inflater.inflate(R.layout.item_device, devicesContainer, false)
            row.findViewById<TextView>(R.id.tvName).text =
                runCatching { d.name }.getOrNull() ?: "(无名)"
            row.findViewById<TextView>(R.id.tvAddr).text = d.address
            row.setOnClickListener { ble.connect(d) }
            devicesContainer.addView(row)
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(timerTick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(timerTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // manager 是进程单例，只在用户真正退出界面时断开，配置变更重建不断开
        if (isFinishing) {
            ble.disconnect(); ble.close()
        }
    }

    companion object {
        /** 定时关闭可选时长（单位 15 分钟）：不开启 / 15分 / 30分 / 1h / 2h / 3h */
        private val TIMER_OPTIONS = listOf(0, 1, 2, 4, 8, 12)

        /** 模式说明文案（与原版 App 一致） */
        private val MODE_DESC = mapOf(
            BleConstants.WorkMode.SILENT to "噪音最小模式，适合玩家深夜游玩时使用",
            BleConstants.WorkMode.OVERCLOCK to "制冷功率最大档位，适合玩家在玩大型游戏时使用",
            BleConstants.WorkMode.SMART to "根据温度检测，实时调节档位，始终让玩家体验到微凉手感",
            BleConstants.WorkMode.LIGHT_ONLY to "制冷已关闭：只亮灯，风扇和 TEC 不工作。点任一模式恢复制冷",
        )

        /** 各模式配套的灯效 preset（实测原版 App 随模式一起下发） */
        private val MODE_LIGHT = mapOf(
            BleConstants.WorkMode.SILENT to BleConstants.WorkModeLight.SILENT,
            BleConstants.WorkMode.OVERCLOCK to BleConstants.WorkModeLight.OVERCLOCK,
            BleConstants.WorkMode.SMART to BleConstants.WorkModeLight.SMART,
            BleConstants.WorkMode.LIGHT_ONLY to BleConstants.WorkModeLight.LIGHT_ONLY,
        )
    }
}
