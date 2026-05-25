package com.rasel.blocker

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "fakelock_prefs"
        const val KEY_OPACITY = "btn_opacity"
        const val KEY_SIZE = "btn_size"
        const val KEY_AUTO_SECONDS = "auto_seconds"
        const val KEY_ENABLE_FLOAT = "enable_float"
        const val KEY_ENABLE_VOLUME = "enable_volume"
        const val KEY_AUTO_HIDE = "auto_hide"
        const val ACTION_UPDATE_SETTINGS = "com.rasel.blocker.UPDATE_SETTINGS"

        fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            } else {
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                SettingsScreen(
                    prefs = prefs,
                    context = this,
                    onStartService = { autoLockSeconds, opacity, size, enFloat, enVol, autoHide ->
                        saveAndBroadcast(prefs, autoLockSeconds, opacity, size, enFloat, enVol, autoHide)
                        if (Settings.canDrawOverlays(this)) {
                            val intent = Intent(this, FakeLockService::class.java)
                            startForegroundService(intent)
                        } else {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                    },
                    onStopService = {
                        stopService(Intent(this, FakeLockService::class.java))
                    },
                    onUpdateSettings = { opacity, size, autoLockSeconds, enFloat, enVol, autoHide ->
                        saveAndBroadcast(prefs, autoLockSeconds, opacity, size, enFloat, enVol, autoHide)
                    }
                )
            }
        }
    }

    private fun saveAndBroadcast(
        prefs: SharedPreferences, autoLockSeconds: Int, opacity: Float, size: Int,
        enFloat: Boolean, enVol: Boolean, autoHide: Boolean
    ) {
        prefs.edit()
            .putInt(KEY_AUTO_SECONDS, autoLockSeconds)
            .putFloat(KEY_OPACITY, opacity)
            .putInt(KEY_SIZE, size)
            .putBoolean(KEY_ENABLE_FLOAT, enFloat)
            .putBoolean(KEY_ENABLE_VOLUME, enVol)
            .putBoolean(KEY_AUTO_HIDE, autoHide)
            .apply()

        val intent = Intent(ACTION_UPDATE_SETTINGS)
        sendBroadcast(intent)
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FakeLockService::class.java)
            startForegroundService(intent)
        }
    }
}

// ─────────────────────────────────────────────
// Compose Settings UI
// ─────────────────────────────────────────────
@Composable
fun SettingsScreen(
    prefs: SharedPreferences,
    context: Context,
    onStartService: (Int, Float, Int, Boolean, Boolean, Boolean) -> Unit,
    onStopService: () -> Unit,
    onUpdateSettings: (Float, Int, Int, Boolean, Boolean, Boolean) -> Unit
) {
    var isRunning by remember { mutableStateOf(false) }
    var selectedSeconds by remember { mutableStateOf(prefs.getInt(MainActivity.KEY_AUTO_SECONDS, 0)) }
    var opacity by remember { mutableStateOf(prefs.getFloat(MainActivity.KEY_OPACITY, 0.7f)) }
    var btnSize by remember { mutableStateOf(prefs.getInt(MainActivity.KEY_SIZE, 110)) }
    
    var enableFloat by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_ENABLE_FLOAT, true)) }
    var enableVolume by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_ENABLE_VOLUME, true)) }
    var autoHide by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_AUTO_HIDE, false)) }

    val autoLockOptions = listOf(0, 15, 30, 60, 120) // 0 means Off

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFF0A0A0A))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text("🔒 FakeLock", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
        Text("Smart Lock Screen Utility", fontSize = 13.sp, color = ComposeColor(0xFF888888))

        Spacer(modifier = Modifier.height(2.dp))

        // Smart Settings Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1A1A1A))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚙️ Smart Settings", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume Button Lock/Unlock", fontSize = 13.sp, color = ComposeColor.White)
                    Switch(checked = enableVolume, onCheckedChange = { 
                        enableVolume = it
                        onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide)
                    })
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Floating Button দেখাও", fontSize = 13.sp, color = ComposeColor.White)
                    Switch(checked = enableFloat, onCheckedChange = { 
                        enableFloat = it
                        onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide)
                    })
                }

                if (enableFloat) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Auto-Hide Button", fontSize = 13.sp, color = ComposeColor.White)
                            Text("শুধু Home Screen এ দেখাবে", fontSize = 10.sp, color = ComposeColor.Gray)
                        }
                        Switch(checked = autoHide, onCheckedChange = { 
                            if (it && !MainActivity.hasUsageStatsPermission(context)) {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } else {
                                autoHide = it
                                onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide)
                            }
                        })
                    }
                }
            }
        }

        // Sliders Card
        if (enableFloat) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1A1A1A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("🔘 Button Opacity", fontSize = 13.sp, color = ComposeColor.White)
                        Text("${(opacity * 100).toInt()}%", fontSize = 13.sp, color = ComposeColor(0xFF9C27B0))
                    }
                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it; onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide) },
                        valueRange = 0.1f..1.0f
                    )
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("📐 Button Size", fontSize = 13.sp, color = ComposeColor.White)
                        Text("${btnSize}px", fontSize = 13.sp, color = ComposeColor(0xFF6200EE))
                    }
                    Slider(
                        value = btnSize.toFloat(),
                        onValueChange = { btnSize = it.toInt(); onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide) },
                        valueRange = 60f..180f
                    )
                }
            }
        }

        // Auto Lock Time
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1A1A1A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⏱ Auto Lock সময় (Off রাখাই ভালো)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    autoLockOptions.forEach { sec ->
                        val label = if (sec == 0) "Off" else if (sec >= 60) "${sec / 60}m" else "${sec}s"
                        val selected = selectedSeconds == sec
                        Button(
                            onClick = { 
                                selectedSeconds = sec
                                onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide) 
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) ComposeColor(0xFF6200EE) else ComposeColor(0xFF2A2A2A)
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(label, fontSize = 13.sp, color = ComposeColor.White)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                if (!isRunning) { 
                    onStartService(selectedSeconds, opacity, btnSize, enableFloat, enableVolume, autoHide)
                    isRunning = true 
                } else { 
                    onStopService()
                    isRunning = false 
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!isRunning) ComposeColor(0xFF6200EE) else ComposeColor(0xFFB00020)
            )
        ) {
            Text(
                text = if (!isRunning) "▶  Service চালু করুন" else "⏹  Service বন্ধ করুন",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// ─────────────────────────────────────────────
// FakeLock Overlay Service
// ─────────────────────────────────────────────
class FakeLockService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButtonView: android.widget.FrameLayout? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    private var lockScreenView: android.widget.FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // MediaSession for perfect volume button interception
    private var mediaSession: MediaSession? = null

    // Preferences Variables
    private var autoLockSeconds = 0
    private var btnOpacity = 0.7f
    private var btnSize = 110
    private var enableFloat = true
    private var enableVolume = true
    private var autoHide = false

    private var isLocked = false
    private var lastInteractionTime = System.currentTimeMillis()
    private var lastVolumeToggleTime = 0L
    private var launcherPackage = ""

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.ACTION_UPDATE_SETTINGS) {
                loadSettings()
                applySettings()
                setupVolumeInterception() // Re-check volume setting
            }
        }
    }

    private val backgroundTaskRunnable = object : Runnable {
        override fun run() {
            if (autoLockSeconds > 0 && !isLocked) {
                val elapsed = (System.currentTimeMillis() - lastInteractionTime) / 1000
                if (elapsed >= autoLockSeconds) showLockScreen()
            }

            if (enableFloat && autoHide && !isLocked) {
                if (MainActivity.hasUsageStatsPermission(this@FakeLockService)) {
                    val fgApp = getForegroundApp(this@FakeLockService)
                    if (fgApp == launcherPackage) {
                        floatingButtonView?.visibility = View.VISIBLE
                    } else {
                        floatingButtonView?.visibility = View.GONE
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        launcherPackage = getLauncherPackageName(this)

        loadSettings()

        val filter = IntentFilter(MainActivity.ACTION_UPDATE_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updateReceiver, filter)
        }

        setupVolumeInterception()
        showFloatingButton()
        applySettings()
        handler.post(backgroundTaskRunnable)
    }

    // ─────────────────────────────────────────────
    // VOLUME BUTTON HACK (Guaranteed to work everywhere)
    // ─────────────────────────────────────────────
    private fun setupVolumeInterception() {
        if (!enableVolume) {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            return
        }

        if (mediaSession == null) {
            mediaSession = MediaSession(this, "FakeLockVolumeHack")
            val playbackState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
            mediaSession?.setPlaybackState(playbackState)
            
            // Catch all volume changes globally
            mediaSession?.setPlaybackToRemote(object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    val now = System.currentTimeMillis()
                    if (now - lastVolumeToggleTime > 500) { 
                        lastVolumeToggleTime = now
                        Handler(Looper.getMainLooper()).post {
                            if (isLocked) hideLockScreen() else showLockScreen()
                        }
                    }
                }
            })
        }
        mediaSession?.isActive = true
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        autoLockSeconds = prefs.getInt(MainActivity.KEY_AUTO_SECONDS, 0)
        btnOpacity = prefs.getFloat(MainActivity.KEY_OPACITY, 0.7f)
        btnSize = prefs.getInt(MainActivity.KEY_SIZE, 110)
        enableFloat = prefs.getBoolean(MainActivity.KEY_ENABLE_FLOAT, true)
        enableVolume = prefs.getBoolean(MainActivity.KEY_ENABLE_VOLUME, true)
        autoHide = prefs.getBoolean(MainActivity.KEY_AUTO_HIDE, false)
    }

    private fun applySettings() {
        if (!enableFloat) {
            floatingButtonView?.visibility = View.GONE
        } else if (!isLocked) {
            if (!autoHide) floatingButtonView?.visibility = View.VISIBLE
            updateFloatingButton()
        }
    }

    private fun getLauncherPackageName(context: Context): String {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName ?: ""
    }

    private fun getForegroundApp(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time)
        if (stats != null) {
            var latest: android.app.usage.UsageStats? = null
            for (usageStats in stats) {
                if (latest == null || usageStats.lastTimeUsed > latest.lastTimeUsed) {
                    latest = usageStats
                }
            }
            return latest?.packageName
        }
        return null
    }

    private fun startForegroundNotification() {
        val channelId = "fakelock_ch"
        val channel = NotificationChannel(channelId, "FakeLock", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("FakeLock চালু আছে")
            .setContentText("স্মার্ট লক ব্যাকগ্রাউন্ডে কাজ করছে")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
        startForeground(1, notif)
    }

    private fun showFloatingButton() {
        val ctx = this
        floatingButtonView = android.widget.FrameLayout(ctx).apply {
            val btn = android.widget.TextView(ctx).apply {
                text = "🔒"
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                }
                setPadding(16, 16, 16, 16)
            }
            addView(btn, android.widget.FrameLayout.LayoutParams(btnSize, btnSize))
        }

        floatingButtonParams = WindowManager.LayoutParams(
            btnSize, btnSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 30; y = 200
        }

        floatingButtonView!!.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0f; var startY = 0f
            var startRawX = 0f; var startRawY = 0f
            var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                lastInteractionTime = System.currentTimeMillis()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX; startRawY = event.rawY
                        startX = floatingButtonParams!!.x.toFloat()
                        startY = floatingButtonParams!!.y.toFloat()
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startRawX
                        val dy = event.rawY - startRawY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) isDragging = true
                        if (isDragging) {
                            floatingButtonParams!!.x = (startX - dx).toInt()
                            floatingButtonParams!!.y = (startY - dy).toInt()
                            windowManager.updateViewLayout(floatingButtonView, floatingButtonParams)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            showLockScreen()
                        }
                    }
                }
                return true
            }
        })

        windowManager.addView(floatingButtonView, floatingButtonParams)
    }

    private fun updateFloatingButton() {
        val frame = floatingButtonView ?: return
        val btn = frame.getChildAt(0) as? android.widget.TextView ?: return
        val alphaInt = (btnOpacity * 255).toInt()
        
        btn.alpha = btnOpacity
        btn.textSize = btnSize * 0.18f
        (btn.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(Color.argb(alphaInt, 20, 20, 20))

        floatingButtonParams!!.width = btnSize
        floatingButtonParams!!.height = btnSize
        frame.layoutParams.width = btnSize
        frame.layoutParams.height = btnSize
        btn.layoutParams.width = btnSize
        btn.layoutParams.height = btnSize

        try { windowManager.updateViewLayout(floatingButtonView, floatingButtonParams) }
        catch (e: Exception) { }
    }

    private var clockTextView: android.widget.TextView? = null
    private var dateTextView: android.widget.TextView? = null
    private var lastTapTime = 0L

    private val aggressiveUiFlags = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

    private fun showLockScreen() {
        if (isLocked) return
        isLocked = true
        floatingButtonView?.visibility = View.GONE

        val ctx = this
        lockScreenView = object : android.widget.FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                    return true // Block Back Button
                }
                return super.dispatchKeyEvent(event)
            }
            
            // Block all touches and handle double tap to unlock
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                if (event?.action == MotionEvent.ACTION_UP) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 400) hideLockScreen()
                    lastTapTime = now
                    lastInteractionTime = System.currentTimeMillis()
                }
                return true
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            systemUiVisibility = aggressiveUiFlags
            
            // Force re-hide if status bar is pulled
            setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    handler.postDelayed({ systemUiVisibility = aggressiveUiFlags }, 100)
                }
            }

            val center = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                clockTextView = android.widget.TextView(ctx).apply {
                    text = getCurrentTime()
                    textSize = 80f
                    // Opacity reduced (70 out of 255) for dimmer text
                    setTextColor(Color.argb(70, 255, 255, 255))
                    typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                }

                dateTextView = android.widget.TextView(ctx).apply {
                    text = getCurrentDate()
                    textSize = 16f
                    setTextColor(Color.argb(50, 255, 255, 255)) // Dimmer date
                    gravity = Gravity.CENTER
                    setPadding(0, 6, 0, 0)
                }

                val hint = android.widget.TextView(ctx).apply {
                    text = "double tap to unlock"
                    textSize = 11f
                    setTextColor(Color.argb(40, 255, 255, 255)) // Dimmer hint
                    gravity = Gravity.CENTER
                    setPadding(0, 48, 0, 0)
                }

                addView(clockTextView)
                addView(dateTextView)
                addView(hint)
            }

            addView(
                center,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )

        }

        // WindowManager Flags for 0 Brightness & Blocking Status Bar
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.OPAQUE
        ).apply {
            screenBrightness = 0.0f
            dimAmount = 1.0f
        }

        windowManager.addView(lockScreenView, params)
        handler.post(clockRunnable)
    }

    private fun hideLockScreen() {
        isLocked = false
        handler.removeCallbacks(clockRunnable)
        lockScreenView?.let { windowManager.removeView(it) }
        lockScreenView = null
        
        lastInteractionTime = System.currentTimeMillis()
        applySettings() 
    }

    private fun updateClock() {
        clockTextView?.text = getCurrentTime()
        dateTextView?.text = getCurrentDate()
    }

    private fun getCurrentTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun getCurrentDate(): String =
        SimpleDateFormat("EEEE, dd MMMM", Locale.getDefault()).format(Date())

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(updateReceiver) } catch (e: Exception) {}
        floatingButtonView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        lockScreenView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
    }
}