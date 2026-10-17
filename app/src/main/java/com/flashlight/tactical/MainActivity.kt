package com.flashlight.tactical

import android.Manifest
import android.app.Activity
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
                val max = cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                val min = cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) ?: 1
                val strength = (min + (max - min) * level.coerceIn(0.1f, 1f)).roundToInt().coerceIn(1, max)
                cameraManager.turnOnTorchWithStrengthLevel(id, strength)
            } else cameraManager.setTorchMode(id, enabled)
        } catch (_: Exception) {
            try { cameraManager.setTorchMode(id, enabled) } catch (_: Exception) {
                Toast.makeText(this, "Вспышка недоступна", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopModes() {
        handler.removeCallbacksAndMessages(null)
        strobeOn = false
        sosOn = false
        pulse = false
        sosIndex = 0
    }

    private fun togglePower() {
        if (strobeOn || sosOn) {
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
        if (strobeOn) {
            stopModes()
            torchOn = false
            setTorch(false)
        } else {
            stopModes()
            strobeOn = true
            torchOn = true
            pulse = true
            setTorch(true, 1f)
            scheduleStrobe()
        }
        flashlightView.invalidate()
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
            canvas.drawColor(bg)
            val w = width.toFloat()
            val h = height.toFloat()
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
            // flashlight body
            rect.set(cx-w*0.22f, headY+headH*0.62f, cx+w*0.22f, h*0.72f)
            p.shader = LinearGradient(rect.left, 0f, rect.right, 0f,
                intArrayOf(Color.rgb(26,31,39), Color.rgb(105,119,133), Color.rgb(34,40,48), Color.rgb(12,16,22)), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, w*0.07f, w*0.07f, p)
            p.shader = null
            // handle grooves
            p.color = Color.argb(115, 4, 7, 10)
            p.strokeWidth = w*0.009f
            for (i in 0..7) {
                val y = headY + headH + w*(0.075f + i*0.047f)
                canvas.drawLine(cx-w*0.19f, y, cx+w*0.19f, y, p)
            }
            // flashlight head metallic ring
            p.shader = LinearGradient(0f, headY, 0f, headY+headH,
                intArrayOf(Color.rgb(176,190,202), Color.rgb(39,49,61), Color.rgb(114,128,141)), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(headRect, w*0.11f, w*0.11f, p)
            p.shader = null
            rect.set(cx-headW*0.39f, headY+headH*0.13f, cx+headW*0.39f, headY+headH*0.87f)
            p.color = Color.rgb(7, 12, 18)
            canvas.drawOval(rect, p)
            rect.inset(w*0.025f, w*0.025f)
            p.shader = RadialGradient(cx, headY+headH*0.5f, headW*0.33f,
                intArrayOf(if (torchOn || strobeOn || sosOn) Color.WHITE else Color.rgb(180,197,211),
                    Color.rgb(104,128,148), Color.rgb(21,29,39)), null, Shader.TileMode.CLAMP)
            canvas.drawOval(rect, p)
            p.shader = null
            // ON button on the handle
            val btnY = h*0.59f
            p.setShadowLayer(w*0.025f, 0f, w*0.012f, Color.BLACK)
            p.color = if (torchOn || strobeOn || sosOn) Color.rgb(36,150,224) else Color.rgb(25,37,50)
            powerRect.set(cx-w*0.12f, btnY-w*0.045f, cx+w*0.12f, btnY+w*0.045f)
            canvas.drawRoundRect(powerRect, w*0.045f, w*0.045f, p)
            p.clearShadowLayer()
            p.color = Color.WHITE
            p.textSize = w*0.045f
            p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            canvas.drawText(if (torchOn || strobeOn || sosOn) "ON" else "OFF", cx, btnY+w*0.016f, p)

            // instruction
            p.color = Color.rgb(137,157,178)
            p.textSize = w*0.031f
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText("ПОВОРАЧИВАЙ ГОЛОВКУ", cx, h*0.765f, p)
            canvas.drawText(if (strobeOn) "СКОРОСТЬ МИГАНИЯ" else "ЯРКОСТЬ СВЕТА", cx, h*0.795f, p)
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
            drawControl(canvas, strobeRect, "МИГАНИЕ", strobeOn)
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
