package com.heisha.lite

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import com.heisha.lite.ble.CoolerBleManager
import com.heisha.lite.ble.CoolerProtocol
import com.heisha.lite.ble.CoolerRepository
import com.heisha.lite.ble.LightEffect
import com.heisha.lite.ble.LightEffects

/**
 * 灯效设置页（参照原版 App）：风扇灯效开关 + 11 种灯效模式 + 配色方案 + 三档速度 + 多色取色。
 * 任一参数变化即整帧下发（cmd 0x01 sub 0x20 宏帧，见 [CoolerProtocol.lightMacro]）。
 */
class LightActivity : AppCompatActivity() {

    private val ble by lazy { CoolerBleManager.get(this) }
    private val prefs by lazy { getSharedPreferences("light", MODE_PRIVATE) }

    private lateinit var swFan: SwitchCompat
    private lateinit var panelEffect: View
    private lateinit var tvEffect: TextView
    private lateinit var tvScheme: TextView
    private lateinit var ivSchemeArrow: View
    private lateinit var panelSpeed: View
    private lateinit var sbSpeed: SeekBar
    private lateinit var panelColor: View
    private lateinit var wheel: ColorWheelView
    private lateinit var vSwatch: View
    private lateinit var tvHex: TextView
    private lateinit var colorSlots: LinearLayout

    // 当前灯效配置；颜色为 0xRRGGBB（不含 alpha）
    private var fanOn = false
    private var effect = LightEffects.ALL.first()
    private var colorCount = 0
    private var speedLevel = LightEffect.SPEED_MID
    private val colors = DEFAULT_COLORS.toMutableList()
    private var slot = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_light)

        swFan = findViewById(R.id.swFan)
        panelEffect = findViewById(R.id.panelEffect)
        tvEffect = findViewById(R.id.tvEffect)
        tvScheme = findViewById(R.id.tvScheme)
        ivSchemeArrow = findViewById(R.id.ivSchemeArrow)
        panelSpeed = findViewById(R.id.panelSpeed)
        sbSpeed = findViewById(R.id.sbSpeed)
        panelColor = findViewById(R.id.panelColor)
        wheel = findViewById(R.id.colorWheel)
        vSwatch = findViewById(R.id.vSwatch)
        tvHex = findViewById(R.id.tvHex)
        colorSlots = findViewById(R.id.colorSlots)

        loadConfig()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.rowFan).setOnClickListener {
            fanOn = !fanOn
            if (!apply()) fanOn = !fanOn
            render()
        }
        findViewById<View>(R.id.rowEffect).setOnClickListener { pickEffect() }
        findViewById<View>(R.id.rowScheme).setOnClickListener { pickScheme() }
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                speedLevel = sb.progress
                apply()
            }
        })
        wheel.onColorChanged = { color, done ->
            colors[slot] = color and 0xFFFFFF
            showColor(colors[slot])
            renderSlots()
            // 拖动中只更新界面，抬手才下发：设备会丢弃间隔过短的连发命令
            if (done) apply()
        }

        render()
    }

    /** 优先取设备连接时回报的当前灯效；风扇灯效关闭或未知时用本地记住的上次配置 */
    private fun loadConfig() {
        val s = CoolerRepository.state.value
        val fromDevice = LightEffects.byId(s.lightEffect)
        fanOn = fromDevice != null
        effect = fromDevice
            ?: LightEffects.byId(prefs.getInt(KEY_EFFECT, 0)) ?: LightEffects.ALL.first()
        if (fromDevice != null) {
            colorCount = s.lightColors.size
            speedLevel = effect.levelOf(s.lightSpeedMs)
            s.lightColors.forEachIndexed { i, c -> colors[i] = c }
        } else {
            colorCount = prefs.getInt(KEY_COUNT, effect.schemes.first())
            speedLevel = prefs.getInt(KEY_LEVEL, LightEffect.SPEED_MID)
            colors.indices.forEach { i -> colors[i] = prefs.getInt("$KEY_COLOR$i", colors[i]) }
        }
        if (colorCount !in effect.schemes) colorCount = effect.schemes.first()
    }

    private fun pickEffect() {
        val all = LightEffects.ALL
        AlertDialog.Builder(this)
            .setTitle("灯效模式")
            .setSingleChoiceItems(all.map { it.name }.toTypedArray(), all.indexOf(effect)) { d, i ->
                d.dismiss()
                effect = all[i]
                // 同原版：换模式时配色方案回到该模式的默认（列表第一项）
                colorCount = effect.schemes.first()
                slot = 0
                apply()
                render()
            }
            .show()
    }

    private fun pickScheme() {
        val schemes = effect.schemes
        if (schemes.size < 2) return
        AlertDialog.Builder(this)
            .setTitle("配色方案")
            .setSingleChoiceItems(
                schemes.map { LightEffects.schemeName(it) }.toTypedArray(), schemes.indexOf(colorCount)
            ) { d, i ->
                d.dismiss()
                colorCount = schemes[i]
                slot = 0
                apply()
                render()
            }
            .show()
    }

    /** 按当前配置整帧下发，并同步到全局状态和本地记录 */
    private fun apply(): Boolean {
        val active = colors.take(colorCount)
        val speed = effect.speedMs(colorCount, speedLevel)
        val frame = if (fanOn) CoolerProtocol.lightMacro(effect.id, speed, active) else CoolerProtocol.lightMacroOff()
        if (!ble.send(frame)) {
            Toast.makeText(this, "背夹未连接", Toast.LENGTH_SHORT).show()
            return false
        }
        CoolerRepository.update {
            copy(lightEffect = if (fanOn) effect.id else LightEffects.OFF, lightSpeedMs = speed, lightColors = active)
        }
        prefs.edit().apply {
            putInt(KEY_EFFECT, effect.id)
            putInt(KEY_COUNT, colorCount)
            putInt(KEY_LEVEL, speedLevel)
            colors.forEachIndexed { i, c -> putInt("$KEY_COLOR$i", c) }
        }.apply()
        return true
    }

    private fun render() {
        swFan.isChecked = fanOn
        panelEffect.isVisible = fanOn
        tvEffect.text = effect.name
        tvScheme.text = LightEffects.schemeName(colorCount)
        ivSchemeArrow.isVisible = effect.schemes.size > 1
        panelSpeed.isVisible = effect.hasSpeed(colorCount)
        sbSpeed.progress = speedLevel
        panelColor.isVisible = colorCount > 0
        if (colorCount > 0) {
            slot = slot.coerceAtMost(colorCount - 1)
            wheel.setColor(OPAQUE or colors[slot])
            showColor(colors[slot])
        }
        renderSlots()
    }

    private fun showColor(rgb: Int) {
        vSwatch.backgroundTintList = ColorStateList.valueOf(OPAQUE or rgb)
        tvHex.text = hex(rgb)
    }

    /** 双色/三色时列出各颜色，点选后色轮改的就是它 */
    private fun renderSlots() {
        colorSlots.removeAllViews()
        colorSlots.isVisible = colorCount > 1
        if (colorCount < 2) return
        val inflater = LayoutInflater.from(this)
        repeat(colorCount) { i ->
            val row = inflater.inflate(R.layout.item_color_slot, colorSlots, false)
            row.findViewById<View>(R.id.vSlotSwatch).backgroundTintList = ColorStateList.valueOf(OPAQUE or colors[i])
            row.findViewById<TextView>(R.id.tvSlotHex).apply {
                text = hex(colors[i])
                setTextColor(getColor(if (i == slot) R.color.accent else R.color.text_primary))
            }
            row.setOnClickListener {
                slot = i
                render()
            }
            colorSlots.addView(row)
        }
    }

    private fun hex(rgb: Int) = String.format("#%06X", rgb and 0xFFFFFF)

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
        /** 各颜色槽的初始色：取自原版各模式的默认配色 */
        private val DEFAULT_COLORS = listOf(0xFF00DC, 0x469DFC, 0xFFD700)
        private const val KEY_EFFECT = "effect"
        private const val KEY_COUNT = "color_count"
        private const val KEY_LEVEL = "speed_level"
        private const val KEY_COLOR = "color_"
    }
}
