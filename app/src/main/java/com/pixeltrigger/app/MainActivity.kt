package com.pixeltrigger.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var overlayStatus: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var leftStartButton: Button
    private lateinit var rightStartButton: Button

    private val shoulderPrefs by lazy {
        getSharedPreferences(ShoulderCaptureService.PREFS_NAME, MODE_PRIVATE)
    }

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val combinedProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ShoulderCaptureService::class.java).apply {
                    action = ShoulderCaptureService.ACTION_START
                },
            )
            ContextCompat.startForegroundService(
                this,
                Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                },
            )
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> updateReadiness() }
    private val binderListener = Shizuku.OnBinderReceivedListener { updateReadiness() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { updateReadiness() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        setContentView(buildContent())
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateReadiness()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 13, 19))
            setPadding(dp(12), dp(14), dp(12), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "PixelTrigger V5"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, matchWrap(dp(44)))
        root.addView(TextView(this).apply {
            text = "محركان مستقلان • التقاط واحد • مركز تحكم عائم واحد"
            textSize = 12f
            setTextColor(Color.rgb(176, 176, 195))
            gravity = Gravity.CENTER
        }, matchWrap(dp(32)))

        val requirements = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        overlayStatus = requirement(requirements, "الظهور فوق التطبيقات") { requestOverlayPermission() }
        shizukuStatus = requirement(requirements, "Shizuku — ADB / UID 2000") { requestShizukuPermission() }
        root.addView(requirements, matchWrap(dp(116)))

        val start = Button(this).apply {
            text = "بدء PixelTrigger V5"
            textSize = 17f
            isAllCaps = false
            setOnClickListener { beginAllMonitoring() }
        }
        leftStartButton = start
        rightStartButton = start
        root.addView(start, matchWrap(dp(56)))

        val halves = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(8), 0, 0)
        }
        halves.addView(verticalScroll(buildShoulderColumn()), LinearLayout.LayoutParams(0, 0, 1f).apply {
            height = LinearLayout.LayoutParams.MATCH_PARENT
            marginEnd = dp(5)
        })
        halves.addView(verticalScroll(buildRightColumn()), LinearLayout.LayoutParams(0, 0, 1f).apply {
            height = LinearLayout.LayoutParams.MATCH_PARENT
            marginStart = dp(5)
        })
        root.addView(halves, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildShoulderColumn(): LinearLayout = cardColumn().apply {
        addView(title("Shoulder R / L", Color.rgb(255, 112, 136)))
        addView(body("نفس DetectionEngine وPixelSampler الخاصين بـV4 تمامًا. الاختلاف الوحيد عند FIRE: تشغيل R أو L وترك GameSpace ينفذ إعداد المستخدم."))
        addView(pressCard("R", "r"))
        addView(pressCard("L", "l"))
        addView(positionCard())
    }

    private fun buildRightColumn(): LinearLayout = cardColumn().apply {
        addView(title("PixelProbe V4", Color.rgb(166, 145, 255)))
        addView(body("المحرك الحالي محفوظ: المجموعات، التسليح، إعادة التسليح، دوائر 0.3mm ومسار Shizuku السريع للضغطة."))
        addView(innerCard().apply {
            addView(title("واجهة اللعب", Color.WHITE))
            addView(body("بعد البدء يظهر زر عائم واحد P5. منه تتحكم في PixelProbe وR/L معًا. النافذة نفسها قابلة للسحب والحفظ."))
        })
    }

    private fun pressCard(label: String, prefix: String): LinearLayout {
        val holder = innerCard()
        holder.addView(title("زر $label", Color.WHITE))
        val durationText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(215, 215, 225))
        }
        val seek = SeekBar(this).apply {
            max = 4
            progress = shoulderPrefs.getInt("shoulder_${prefix}_seconds", 1).coerceIn(1, 5) - 1
        }
        val holdSwitch = Switch(this).apply {
            text = "ضغط مستمر"
            setTextColor(Color.WHITE)
            isChecked = shoulderPrefs.getBoolean("shoulder_${prefix}_hold", false)
        }
        fun refresh() {
            val seconds = seek.progress + 1
            durationText.text = if (holdSwitch.isChecked) "$seconds ثانية" else "Flash"
            seek.isEnabled = holdSwitch.isChecked
        }
        holdSwitch.setOnCheckedChangeListener { _, checked ->
            shoulderPrefs.edit().putBoolean("shoulder_${prefix}_hold", checked).apply(); refresh()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                shoulderPrefs.edit().putInt("shoulder_${prefix}_seconds", progress + 1).apply(); refresh()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        holder.addView(holdSwitch, matchWrap(dp(44)))
        holder.addView(durationText, matchWrap(dp(28)))
        holder.addView(seek, matchWrap(dp(42)))
        refresh()
        return holder
    }

    private fun positionCard(): LinearLayout = innerCard().apply {
        addView(title("دوائر R / L", Color.WHITE))
        addView(body("3 دوائر R و3 دوائر L، كل واحدة 0.3 mm. المواضع تحفظ تلقائيًا كنسب من الشاشة."))
        addView(Button(this@MainActivity).apply {
            text = "تعديل R"
            isAllCaps = false
            setOnClickListener { sendShoulderAction(ShoulderCaptureService.ACTION_EDIT_R) }
        }, matchWrap(dp(46)))
        addView(Button(this@MainActivity).apply {
            text = "تعديل L"
            isAllCaps = false
            setOnClickListener { sendShoulderAction(ShoulderCaptureService.ACTION_EDIT_L) }
        }, matchWrap(dp(46)))
        addView(Button(this@MainActivity).apply {
            text = "✓ حفظ"
            isAllCaps = false
            setOnClickListener { sendShoulderAction(ShoulderCaptureService.ACTION_DONE_EDIT) }
        }, matchWrap(dp(46)))
    }

    private fun cardColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(9), dp(8), dp(9))
        background = roundedBackground(Color.rgb(23, 23, 32), Color.rgb(64, 64, 80))
    }

    private fun innerCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = roundedBackground(Color.rgb(31, 31, 42), Color.rgb(74, 74, 94))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun title(value: String, color: Int) = TextView(this).apply {
        text = value
        textSize = 17f
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(3), dp(5), dp(3), dp(5))
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Color.rgb(190, 190, 205))
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(6), dp(4), dp(8))
    }

    private fun verticalScroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun requirement(root: LinearLayout, label: String, action: () -> Unit): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val button = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
        val status = TextView(this).apply { text = "مطلوب"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.rgb(255, 150, 150)) }
        row.addView(button, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(status, LinearLayout.LayoutParams(dp(105), dp(50)))
        root.addView(row, matchWrap(dp(54)))
        return status
    }

    private fun updateReadiness() {
        val overlayReady = Settings.canDrawOverlays(this)
        overlayStatus.text = if (overlayReady) "جاهز" else "مطلوب"
        overlayStatus.setTextColor(if (overlayReady) Color.rgb(90, 230, 145) else Color.rgb(255, 140, 140))
        val shizukuText = when {
            !Shizuku.pingBinder() -> "شغّل Shizuku"
            runCatching { Shizuku.getUid() }.getOrDefault(-1) != 2000 -> "Root مرفوض"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "امنح الإذن"
            else -> "جاهز"
        }
        shizukuStatus.text = shizukuText
        shizukuStatus.setTextColor(if (shizukuText == "جاهز") Color.rgb(90, 230, 145) else Color.rgb(255, 170, 95))
        val ready = overlayReady && shizukuText == "جاهز"
        leftStartButton.isEnabled = ready
        rightStartButton.isEnabled = ready
    }

    private fun beginAllMonitoring() {
        if (!prerequisitesReady()) return
        combinedProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun prerequisitesReady(): Boolean {
        if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return false }
        if (!Shizuku.pingBinder()) { Toast.makeText(this, "شغّل Shizuku أولًا", Toast.LENGTH_SHORT).show(); return false }
        if (runCatching { Shizuku.getUid() }.getOrDefault(-1) != 2000) { Toast.makeText(this, "PixelTrigger يستخدم Shizuku shell UID 2000 فقط", Toast.LENGTH_LONG).show(); return false }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { requestShizukuPermission(); return false }
        return true
    }

    private fun sendShoulderAction(action: String) {
        runCatching { startService(Intent(this, ShoulderCaptureService::class.java).apply { this.action = action }) }
            .onFailure { Toast.makeText(this, "ابدأ PixelTrigger V5 أولًا", Toast.LENGTH_SHORT).show() }
    }

    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) { Toast.makeText(this, "افتح Shizuku وشغّله عبر Wireless debugging/ADB", Toast.LENGTH_LONG).show(); return }
        if (runCatching { Shizuku.getUid() }.getOrDefault(-1) != 2000) { Toast.makeText(this, "Root غير مسموح", Toast.LENGTH_LONG).show(); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(4101)
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 201)
        }
    }

    private fun roundedBackground(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(14).toFloat()
    }

    private fun matchWrap(height: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
