package com.flashlight.tactical

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var flashlightView: FlashlightView
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var torchOn = false
    private var strobeOn = false
    private var sosOn = false
    private var canaryOn = false
    private var emergencyOn = false
    private var screenFlashOn = false
    private var emergencyMode = 0 // 0 police, 1 fire, 2 ambulance
    private var emergencyStep = 0
    private var screenStep = 0
    private var lastTapTime = 0L
    private var brightness = 1f
    private var strobeDelay = 260L
    private val handler = Handler(Looper.getMainLooper())
    private var pulse = false
    private var sosIndex = 0
    private val sosPattern = longArrayOf(140,140,140,140,140,420,420,140,420,140,420,420,140,140,140,140,140,700)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(10, 14, 20)
        window.navigationBarColor = Color.rgb(10, 14, 20)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { null }
        flashlightView = FlashlightView()
        setContentView(flashlightView)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 21)
        }
    }

    private fun setTorch(enabled: Boolean, level: Float = 1f) {
        val id = cameraId ?: return
        try {
            if (Build.VERSION.SDK_INT >= 33 && enabled) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val max = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                // Torch strength levels range from 1 to maximum. DEFAULT_LEVEL is not the minimum.
                val strength = if (max > 1) {
                    (1f + (max - 1) * level.coerceIn(0.01f, 1f)).roundToInt().coerceIn(1, max)
                } else {
                    1
                }
                cameraManager.turnOnTorchWithStrengthLevel(id, strength)
            } else {
                cameraManager.setTorchMode(id, enabled)
            }
        } catch (_: Exception) {
            try {
                cameraManager.setTorchMode(id, enabled)
                if (enabled && Build.VERSION.SDK_INT >= 33) {
                    // If strength control fails on this device, keep the flashlight usable at full power.
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Вспышка недоступна", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopModes() {
        handler.removeCallbacksAndMessages(null)
        strobeOn = false
        sosOn = false
        canaryOn = false
        emergencyOn = false
        screenFlashOn = false
        pulse = false
        sosIndex = 0
        emergencyStep = 0
        screenStep = 0
        window.decorView.systemUiVisibility = 0
    }

    private fun togglePower() {
        if (strobeOn || sosOn || canaryOn || emergencyOn || screenFlashOn) {
            stopModes()
            torchOn = true
            setTorch(true, brightness)
        } else {
            torchOn = !torchOn
            setTorch(torchOn, brightness)
        }
        flashlightView.invalidate()
    }

    private fun toggleStrobe() {
        val choices = arrayOf(
            "Обычное мигание фонарика",
            "Канарейка — свет / стоп",
            "Полиция — красный / синий",
            "Пожарная — красные вспышки",
            "Скорая — синий / жёлтый",
            "Экранная мигалка — весь экран"
        )
        AlertDialog.Builder(this)
            .setTitle("Выбери режим мигалки")
            .setItems(choices) { _, which ->
                stopModes()
                torchOn = false
                setTorch(false)
                when (which) {
                    0 -> startTorchStrobe()
                    1 -> startCanary()
                    2 -> { emergencyMode = 0; startEmergency() }
                    3 -> { emergencyMode = 1; startEmergency() }
                    4 -> { emergencyMode = 2; startEmergency() }
                    5 -> startScreenFlash()
                }
                flashlightView.invalidate()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startTorchStrobe() {
        strobeOn = true
        torchOn = true
        pulse = true
        setTorch(true, 1f)
        scheduleStrobe()
    }

    private fun scheduleStrobe() {
        handler.postDelayed({
            if (!strobeOn) return@postDelayed
            pulse = !pulse
            setTorch(pulse, 1f)
            flashlightView.invalidate()
            scheduleStrobe()
        }, strobeDelay)
    }

    private fun startCanary() {
        canaryOn = true
        pulse = true
        setTorch(true, 1f)
        scheduleCanary()
    }

    private fun scheduleCanary() {
        handler.postDelayed({
            if (!canaryOn) return@postDelayed
            pulse = !pulse
            setTorch(pulse, 1f)
            flashlightView.invalidate()
            scheduleCanary()
        }, 350L)
    }

    private fun startEmergency() {
        emergencyOn = true
        emergencyStep = 0
        scheduleEmergency()
    }

    private fun scheduleEmergency() {
        if (!emergencyOn) return
        val pattern = when (emergencyMode) {
            1 -> longArrayOf(100, 100, 100, 350, 100, 100, 100, 650) // fire red double bursts
            2 -> longArrayOf(180, 100, 180, 350) // ambulance blue/yellow
            else -> longArrayOf(140, 100, 140, 350) // police red/blue
        }
        pulse = emergencyStep % 2 == 0
        setTorch(false)
        emergencyStep++
        flashlightView.invalidate()
        handler.postDelayed({ scheduleEmergency() }, pattern[(emergencyStep - 1) % pattern.size])
    }

    private fun startScreenFlash() {
        screenFlashOn = true
        screenStep = 0
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
             View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        scheduleScreenFlash()
    }

    private fun scheduleScreenFlash() {
        if (!screenFlashOn) return
        screenStep++
        flashlightView.invalidate()
        handler.postDelayed({ scheduleScreenFlash() }, 180L)
    }

    private fun stopScreenFlash() {
        stopModes()
        torchOn = false
        setTorch(false)
        flashlightView.invalidate()
    }

    private fun toggleSos() {
        if (sosOn) {
            stopModes()
            torchOn = false
            setTorch(false)
        } else {
            stopModes()
            sosOn = true
            torchOn = true
            runSosStep()
        }
        flashlightView.invalidate()
    }

    private fun runSosStep() {
        if (!sosOn) return
        val on = sosIndex % 2 == 0
        setTorch(on, 1f)
        val delay = sosPattern[sosIndex % sosPattern.size]
        sosIndex++
        handler.postDelayed({ runSosStep() }, delay)
    }

    private inner class FlashlightView : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var lastX = 0f
        private var dragTotal = 0f
        private var draggingHead = false
        private var powerRect = RectF()
        private var sosRect = RectF()
        private var strobeRect = RectF()
        private var headRect = RectF()
        private val bg = Color.rgb(10, 14, 20)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (screenFlashOn) {
                val colors = intArrayOf(Color.RED, Color.BLUE, Color.RED, Color.BLUE, Color.RED, Color.rgb(255, 205, 0))
                val color = when (emergencyMode) {
                    1 -> Color.RED
                    2 -> if (screenStep % 2 == 0) Color.BLUE else Color.rgb(255, 205, 0)
                    else -> if (screenStep % 2 == 0) Color.RED else Color.BLUE
                }
                canvas.drawColor(color)
                p.color = Color.WHITE
                p.textAlign = Paint.Align.CENTER
                p.textSize = w * 0.045f
                p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                canvas.drawText("ДВОЙНОЕ КАСАНИЕ — СТОП", w / 2, h * 0.92f, p)
                return
            }
            canvas.drawColor(bg)
            p.shader = LinearGradient(0f, 0f, w, h, Color.rgb(22, 31, 44), bg, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p)
            p.shader = null

            p.color = Color.rgb(207, 220, 233)
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            p.textSize = w * 0.055f
            canvas.drawText("FLASH", w / 2, h * 0.065f, p)
            p.color = Color.rgb(112, 132, 153)
            p.textSize = w * 0.027f
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText("TACTICAL TORCH", w / 2, h * 0.095f, p)

            val cx = w / 2
            val headY = h * 0.20f
            val headW = w * 0.66f
            val headH = w * 0.26f
            headRect.set(cx - headW/2, headY, cx + headW/2, headY + headH)
            // Light spill when enabled
            if (torchOn || strobeOn || sosOn) {
                val glow = Paint(Paint.ANTI_ALIAS_FLAG)
                glow.shader = RadialGradient(cx, headY + headH*0.45f, headW*0.95f,
                    intArrayOf(Color.argb(65, 180, 220, 255), Color.argb(0, 180, 220, 255)),
                    null, Shader.TileMode.CLAMP)
                canvas.drawOval(cx-headW*1.15f, headY-headH*0.7f, cx+headW*1.15f, headY+headH*2.3f, glow)
            }
            // Realistic classic flashlight body: rounded tail, narrow grip and flared head
            val bodyTop = headY + headH * 0.52f
            val bodyBottom = h * 0.725f
            val bodyPath = Path().apply {
                moveTo(cx - w*0.145f, bodyTop)
                lineTo(cx + w*0.145f, bodyTop)
                lineTo(cx + w*0.105f, bodyTop + w*0.18f)
                lineTo(cx + w*0.105f, bodyBottom - w*0.10f)
                quadTo(cx + w*0.105f, bodyBottom, cx + w*0.055f, bodyBottom)
                lineTo(cx - w*0.055f, bodyBottom)
                quadTo(cx - w*0.105f, bodyBottom, cx - w*0.105f, bodyBottom - w*0.10f)
                lineTo(cx - w*0.105f, bodyTop + w*0.18f)
                close()
            }
            p.setShadowLayer(w*0.025f, 0f, w*0.018f, Color.BLACK)
            p.shader = LinearGradient(cx-w*0.15f, 0f, cx+w*0.15f, 0f,
                intArrayOf(Color.rgb(8,11,15), Color.rgb(76,84,91), Color.rgb(30,36,42), Color.rgb(5,8,11)),
                floatArrayOf(0f, 0.24f, 0.62f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(bodyPath, p)
            p.shader = null
            p.clearShadowLayer()

            // Tail cap and grip rings
            p.shader = LinearGradient(cx-w*0.12f, 0f, cx+w*0.12f, 0f,
                intArrayOf(Color.rgb(9,12,15), Color.rgb(89,97,103), Color.rgb(19,24,29), Color.rgb(5,7,9)),
                null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(cx-w*0.12f, bodyBottom-w*0.105f, cx+w*0.12f, bodyBottom-w*0.025f, w*0.025f, w*0.025f, p)
            p.shader = null
            p.color = Color.rgb(13,17,21)
            p.strokeWidth = w*0.008f
            for (i in 0..9) {
                val gy = bodyTop + w*(0.20f + i*0.034f)
                if (gy < bodyBottom-w*0.12f) canvas.drawLine(cx-w*0.098f, gy, cx+w*0.098f, gy, p)
            }

            // Flared head and metallic bezel
            val headPath = Path().apply {
                moveTo(cx-w*0.14f, headY+headH*0.78f)
                lineTo(cx-w*0.235f, headY+headH*0.53f)
                lineTo(cx-w*0.30f, headY+headH*0.42f)
                quadTo(cx-w*0.33f, headY+headH*0.36f, cx-w*0.31f, headY+headH*0.27f)
                lineTo(cx-w*0.275f, headY+headH*0.10f)
                quadTo(cx-w*0.265f, headY+headH*0.02f, cx-w*0.20f, headY+headH*0.02f)
                lineTo(cx+w*0.20f, headY+headH*0.02f)
                quadTo(cx+w*0.265f, headY+headH*0.02f, cx+w*0.275f, headY+headH*0.10f)
                lineTo(cx+w*0.31f, headY+headH*0.27f)
                quadTo(cx+w*0.33f, headY+headH*0.36f, cx+w*0.30f, headY+headH*0.42f)
                lineTo(cx+w*0.235f, headY+headH*0.53f)
                lineTo(cx+w*0.14f, headY+headH*0.78f)
                close()
            }
            p.setShadowLayer(w*0.018f, 0f, w*0.01f, Color.BLACK)
            p.shader = LinearGradient(0f, headY, 0f, headY+headH,
                intArrayOf(Color.rgb(133,143,151), Color.rgb(37,43,48), Color.rgb(94,103,109), Color.rgb(18,23,28)),
                null, Shader.TileMode.CLAMP)
            canvas.drawPath(headPath, p)
            p.shader = null
            p.clearShadowLayer()

            // Front lens: concentric glass, silver bezel and visible LED reflector
            rect.set(cx-w*0.285f, headY+headH*0.07f, cx+w*0.285f, headY+headH*0.48f)
            p.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(Color.rgb(225,231,235), Color.rgb(78,88,97), Color.rgb(20,26,31)),
                null, Shader.TileMode.CLAMP)
            canvas.drawOval(rect, p)
            p.shader = null
            rect.inset(w*0.018f, w*0.018f)
            p.color = Color.rgb(9,13,17)
            canvas.drawOval(rect, p)
            rect.inset(w*0.018f, w*0.018f)
            p.shader = RadialGradient(cx-w*0.035f, headY+headH*0.245f, w*0.19f,
                intArrayOf(if (torchOn || strobeOn || sosOn) Color.WHITE else Color.rgb(205,214,220),
                    Color.rgb(150,161,168), Color.rgb(49,58,65), Color.rgb(14,19,24)),
                floatArrayOf(0f, 0.28f, 0.72f, 1f), Shader.TileMode.CLAMP)
            canvas.drawOval(rect, p)
            p.shader = null
            p.color = Color.rgb(30,36,41)
            p.style = Paint.Style.STROKE
            p.strokeWidth = w*0.009f
            canvas.drawOval(rect, p)
            p.style = Paint.Style.FILL
            // LED emitter in the center of the reflector
            p.color = if (torchOn || strobeOn || sosOn) Color.WHITE else Color.rgb(218,224,228)
            canvas.drawCircle(cx, headY+headH*0.275f, w*0.045f, p)
            p.color = Color.rgb(125,135,142)
            canvas.drawCircle(cx, headY+headH*0.275f, w*0.025f, p)
            p.color = Color.rgb(245,247,248)
            canvas.drawCircle(cx-w*0.008f, headY+headH*0.265f, w*0.009f, p)

            // Rubberized power switch on the grip
            val btnY = h*0.59f
            p.setShadowLayer(w*0.018f, 0f, w*0.008f, Color.BLACK)
            p.shader = LinearGradient(cx-w*0.105f, btnY-w*0.04f, cx+w*0.105f, btnY+w*0.04f,
                intArrayOf(Color.rgb(12,16,20), Color.rgb(62,71,78), Color.rgb(12,16,20)), null, Shader.TileMode.CLAMP)
            powerRect.set(cx-w*0.105f, btnY-w*0.04f, cx+w*0.105f, btnY+w*0.04f)
            canvas.drawRoundRect(powerRect, w*0.035f, w*0.035f, p)
            p.shader = null
            p.clearShadowLayer()
            p.color = if (torchOn || strobeOn || sosOn) Color.rgb(72,177,94) else Color.rgb(110,119,126)
            canvas.drawCircle(cx, btnY, w*0.022f, p)
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.textSize = w*0.022f
            p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            canvas.drawText(if (torchOn || strobeOn || sosOn) "ON" else "OFF", cx, btnY+w*0.065f, p)

            // instruction
            p.color = Color.rgb(137,157,178)
            p.textSize = w*0.031f
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText("ПОВОРАЧИВАЙ ГОЛОВКУ", cx, h*0.765f, p)
            canvas.drawText(if (strobeOn || canaryOn || emergencyOn) "СКОРОСТЬ МИГАНИЯ" else "ЯРКОСТЬ СВЕТА", cx, h*0.795f, p)
            val barW = w*0.46f
            p.color = Color.rgb(43,57,73)
            canvas.drawRoundRect(cx-barW/2, h*0.815f, cx+barW/2, h*0.824f, 10f,10f,p)
            p.color = Color.rgb(65,181,255)
            val fraction = if (strobeOn) ((300f-strobeDelay)/260f).coerceIn(0f,1f) else brightness
            canvas.drawRoundRect(cx-barW/2, h*0.815f, cx-barW/2+barW*fraction, h*0.824f, 10f,10f,p)

            // bottom controls
            val bw = w*0.36f
            val bh = h*0.075f
            val by = h*0.87f
            sosRect.set(w*0.08f, by, w*0.08f+bw, by+bh)
            strobeRect.set(w*0.56f, by, w*0.56f+bw, by+bh)
            drawControl(canvas, sosRect, "SOS", sosOn)
            drawControl(canvas, strobeRect, "МИГАЛКИ", strobeOn || canaryOn || emergencyOn || screenFlashOn)
            p.color = Color.rgb(84,100,118)
            p.textSize = w*0.024f
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText("ВРАЩАЙ ВЛЕВО / ВПРАВО", cx, h*0.975f, p)
        }

        private fun drawControl(canvas: Canvas, r: RectF, label: String, active: Boolean) {
            p.color = if (active) Color.rgb(24,104,155) else Color.rgb(24,34,47)
            canvas.drawRoundRect(r, 22f, 22f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = if (active) Color.rgb(78,190,255) else Color.rgb(63,81,101)
            canvas.drawRoundRect(r, 22f, 22f, p)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.textSize = width*0.039f
            p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            canvas.drawText(label, r.centerX(), r.centerY()+p.textSize*0.34f, p)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (screenFlashOn) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 450L) {
                            stopScreenFlash()
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                        }
                        return true
                    }
                    lastX = x
                    dragTotal = 0f
                    draggingHead = headRect.contains(x, y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingHead) {
                        val dx = x - lastX
                        dragTotal += dx
                        lastX = x
                        if (strobeOn) {
                            strobeDelay = (strobeDelay - dx*1.8f).toLong().coerceIn(40L, 520L)
                        } else if (!sosOn) {
                            brightness = (brightness + dx / (width*0.8f)).coerceIn(0.12f, 1f)
                            if (torchOn) setTorch(true, brightness)
                        }
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!draggingHead || abs(dragTotal) < 12f) {
                        when {
                            powerRect.contains(x,y) -> togglePower()
                            sosRect.contains(x,y) -> toggleSos()
                            strobeRect.contains(x,y) -> toggleStrobe()
                            headRect.contains(x,y) -> togglePower()
                        }
                    }
                    draggingHead = false
                    performClick()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Для работы вспышки нужно разрешение камеры", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        stopModes()
        setTorch(false)
        torchOn = false
        if (::flashlightView.isInitialized) flashlightView.invalidate()
    }
}
