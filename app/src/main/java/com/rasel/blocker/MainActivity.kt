package com.rasel.blocker

import android.annotation.SuppressLint
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
import android.os.BatteryManager
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
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rasel.blocker.adblock.AdBlockVpnService
import com.rasel.blocker.adblock.FilterManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rasel.blocker.adblock.AdBlockVpnService
import com.rasel.blocker.adblock.FilterManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────
// HTML Content for Web Tools
// ─────────────────────────────────────────────
const val WEB_TOOLS_HTML = """
<!DOCTYPE html><html lang="en"><head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>My Local Toolset</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script src="https://unpkg.com/pdf-lib@1.17.1/dist/pdf-lib.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
    <script src="https://unpkg.com/html5-qrcode" type="text/javascript"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/2.16.105/pdf.min.js"></script>
    <style>
        body { background-color: #f8fafc; font-family: 'Inter', sans-serif; padding: 20px; }
        .tool-card {
            background: white; border-radius: 12px; padding: 15px; margin-bottom: 15px;
            box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1); border-top: 4px solid #3b82f6;
        }
        .btn-action {
            background: #2563eb; color: white; padding: 10px; border-radius: 8px;
            width: 100%; margin-top: 10px; font-weight: bold; border: none;
        }
        input, select { width: 100%; border: 1px solid #cbd5e1; padding: 8px; border-radius: 6px; margin-bottom: 5px; margin-top:5px; }
    </style>
</head>
<body>
    <h2 style="text-align:center; font-size: 24px; font-weight: bold; margin-bottom: 20px;">Web Tools & PDF</h2>
    <div class="tool-card border-t-4 border-gray-600">
        <h3 style="font-weight:bold; font-size: 18px;">Advanced PDF Merger</h3>
        <p style="font-size:12px; color: gray;">Combine multiple PDFs (Select files from Android)</p>
        <button id="android-merge-btn" class="btn-action bg-gray-700" onclick="triggerAndroidFilePicker()">Select PDFs & Merge</button>
        <div id="merge-status" style="margin-top:10px; font-size:12px; color:blue;"></div>
    </div>
    <div class="tool-card border-t-4 border-blue-500">
        <h3 style="font-weight:bold; font-size: 18px;">Job Photo (300x300)</h3>
        <input type="file" id="job-photo-file" accept="image/*">
        <button onclick="processImage(300, 300, 'job-photo.jpg', 'job-photo-file')" class="btn-action bg-blue-600">Convert Photo</button>
    </div>
    <div class="tool-card border-t-4 border-indigo-500">
        <h3 style="font-weight:bold; font-size: 18px;">Signature (300x80)</h3>
        <input type="file" id="sign-photo-file" accept="image/*">
        <button onclick="processImage(300, 80, 'signature.jpg', 'sign-photo-file')" class="btn-action bg-indigo-600">Convert Sign</button>
    </div>
    <div class="tool-card border-t-4 border-lime-500">
        <h3 style="font-weight:bold; font-size: 18px;">Age Calculator</h3>
        <label style="font-size:12px;">Date of Birth:</label>
        <input type="date" id="age-dob">
        <button onclick="calculateAge()" class="btn-action bg-lime-600">Calculate</button>
        <div id="age-result" style="margin-top:10px; font-weight:bold; color:green;"></div>
    </div>
    <canvas id="canvas" style="display:none;"></canvas>
    <script>
        const { PDFDocument } = PDFLib;
        function triggerAndroidFilePicker() {
            if(window.AndroidBridge) {
                document.getElementById('merge-status').innerText = "Select files from Android...";
                window.AndroidBridge.selectPdfFiles();
            } else { alert("Android Bridge not found!"); }
        }
        async function performMergeFromAndroid(base64ArrayStr) {
            document.getElementById('merge-status').innerText = "Merging PDFs...";
            try {
                const base64Pdfs = JSON.parse(base64ArrayStr);
                if(base64Pdfs.length < 2) {
                     document.getElementById('merge-status').innerText = "Error: Need at least 2 PDFs"; return;
                }
                const mergedPdf = await PDFDocument.create();
                for (const base64 of base64Pdfs) {
                    const pdfBytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0));
                    const pdfDoc = await PDFDocument.load(pdfBytes);
                    const copiedPages = await mergedPdf.copyPages(pdfDoc, pdfDoc.getPageIndices());
                    copiedPages.forEach((page) => mergedPdf.addPage(page));
                }
                const mergedPdfBytes = await mergedPdf.saveAsBase64();
                document.getElementById('merge-status').innerText = "Merge Complete! Saving to device...";
                if(window.AndroidBridge) { window.AndroidBridge.saveMergedPdf(mergedPdfBytes); }
            } catch (error) { document.getElementById('merge-status').innerText = "Error: " + error; }
        }
        function processImage(targetW, targetH, filename, elementId) {
            const fileInput = document.getElementById(elementId);
            if (!fileInput.files[0]) return alert("Select an image");
            const reader = new FileReader();
            reader.onload = function(event) {
                const img = new Image();
                img.onload = function() {
                    const canvas = document.getElementById('canvas');
                    canvas.width = targetW; canvas.height = targetH;
                    const ctx = canvas.getContext('2d');
                    ctx.fillStyle = "#FFFFFF"; ctx.fillRect(0,0, targetW, targetH);
                    ctx.drawImage(img, 0, 0, targetW, targetH);
                    const base64Data = canvas.toDataURL('image/jpeg', 0.9).split(',')[1];
                    if(window.AndroidBridge) { window.AndroidBridge.saveImage(base64Data, filename); alert("Image saved to Downloads folder!"); }
                }
                img.src = event.target.result;
            }
            reader.readAsDataURL(fileInput.files[0]);
        }
        function calculateAge() {
            const dobInput = document.getElementById('age-dob').value;
            if(!dobInput) return alert("Please select Date of Birth");
            const dob = new Date(dobInput);
            const target = new Date();
            let years = target.getFullYear() - dob.getFullYear();
            let months = target.getMonth() - dob.getMonth();
            let days = target.getDate() - dob.getDate();
            if (days < 0) { months--; const prevMonth = new Date(target.getFullYear(), target.getMonth(), 0); days += prevMonth.getDate(); }
            if (months < 0) { years--; months += 12; }
            document.getElementById('age-result').innerText = years + " Years, " + months + " Months, " + days + " Days";
        }
    </script>
</body>
</html>
"""

// ─────────────────────────────────────────────
// JavaScript Interface Setup
// ─────────────────────────────────────────────
class WebAppInterface(
    private val context: Context,
    private val onSelectPdfs: () -> Unit,
    private val onSavePdf: (String) -> Unit,
    private val onSaveImage: (String, String) -> Unit
) {
    @JavascriptInterface
    fun selectPdfFiles() { onSelectPdfs() }
    @JavascriptInterface
    fun saveMergedPdf(base64Data: String) { onSavePdf(base64Data) }
    @JavascriptInterface
    fun saveImage(base64Data: String, filename: String) { onSaveImage(base64Data, filename) }
}

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

    // ── Ad Blocker VPN helpers ──────────────────────────────────────
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startAdBlockVpn()
    }
    private fun requestVpnAndStart() {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startAdBlockVpn()
    }
    private fun startAdBlockVpn() {
        val i = Intent(this, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_START }
        androidx.core.content.ContextCompat.startForegroundService(this, i)
    }
    private fun stopAdBlockVpn() {
        startService(Intent(this, AdBlockVpnService::class.java).apply { action = AdBlockVpnService.ACTION_STOP })
    }
    // ────────────────────────────────────────────────────────────────

    private var webViewRef: WebView? = null
    private val selectPdfLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty() && webViewRef != null) {
            Thread {
                try {
                    val base64List = mutableListOf<String>()
                    for (uri in uris) {
                        val inputStream = contentResolver.openInputStream(uri)
                        val bytes = inputStream?.readBytes()
                        if (bytes != null) {
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            base64List.add(base64)
                        }
                        inputStream?.close()
                    }
                    val jsonArray = org.json.JSONArray(base64List).toString()
                    runOnUiThread { webViewRef?.evaluateJavascript("performMergeFromAndroid('\$jsonArray')", null) }
                } catch (e: Exception) { e.printStackTrace() }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:\$packageName")))
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                var showWebTools by remember { mutableStateOf(false) }
                var activeAiUrl by remember { mutableStateOf<String?>(null) }
                var activeAiTitle by remember { mutableStateOf("") }
                // ── Ad Blocker state ──
                var adBlockRunning by remember { mutableStateOf(AdBlockVpnService.isRunning) }
                var youtubeBlock  by remember { mutableStateOf(true) }
                var websiteBlock  by remember { mutableStateOf(true) }
                var appAdsBlock   by remember { mutableStateOf(true) }

                Column(modifier = Modifier.fillMaxSize()) {
                    CustomStatusBar()
                    SettingsScreen(
                        prefs = prefs,
                        context = this@MainActivity,
                        onOpenWebTools = { showWebTools = true },
                        onOpenAi = { title, url ->
                            activeAiTitle = title
                            activeAiUrl = url
                        },
                        onStartService = { autoLockSeconds, opacity, size, enFloat, enVol, autoHide ->
                            saveAndBroadcast(prefs, autoLockSeconds, opacity, size, enFloat, enVol, autoHide)
                            if (Settings.canDrawOverlays(this@MainActivity)) {
                                startForegroundService(Intent(this@MainActivity, FakeLockService::class.java))
                            } else {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:\$packageName")))
                            }
                        },
                        onStopService = { stopService(Intent(this@MainActivity, FakeLockService::class.java)) },
                        onUpdateSettings = { opacity, size, autoLockSeconds, enFloat, enVol, autoHide ->
                            saveAndBroadcast(prefs, autoLockSeconds, opacity, size, enFloat, enVol, autoHide)
                        },
                        // ── Ad Blocker callbacks ──
                        adBlockRunning = adBlockRunning,
                        youtubeBlock   = youtubeBlock,
                        websiteBlock   = websiteBlock,
                        appAdsBlock    = appAdsBlock,
                        onToggleAdBlock = {
                            if (adBlockRunning) {
                                stopAdBlockVpn()
                                adBlockRunning = false
                            } else {
                                requestVpnAndStart()
                                adBlockRunning = true
                            }
                        },
                        onYoutubeToggle = { v -> youtubeBlock = v; FilterManager.enableYoutubeFilter(v) },
                        onWebsiteToggle = { v -> websiteBlock = v; FilterManager.enableWebsiteFilter(v) },
                        onAppAdsToggle  = { v -> appAdsBlock  = v; FilterManager.enableAppAdsFilter(v) }
                    )
                }

                // AI Native Fullscreen Dialog
                if (activeAiUrl != null) {
                    Dialog(
                        onDismissRequest = { activeAiUrl = null },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(modifier = Modifier.fillMaxSize(), color = ComposeColor.White) {
                            var aiWebView: WebView? = null
                            BackHandler {
                                if (aiWebView?.canGoBack() == true) aiWebView?.goBack() else activeAiUrl = null
                            }
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().background(ComposeColor(0xFF0F172A)).padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { activeAiUrl = null }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = ComposeColor.White)
                                    }
                                    Text(activeAiTitle, color = ComposeColor.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { context ->
                                        WebView(context).apply {
                                            aiWebView = this
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            webViewClient = WebViewClient()
                                            webChromeClient = WebChromeClient()
                                            loadUrl(activeAiUrl!!)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Web Tools Full Screen Dialog
                if (showWebTools) {
                    Dialog(
                        onDismissRequest = { showWebTools = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(modifier = Modifier.fillMaxSize(), color = ComposeColor.White) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().background(ComposeColor(0xFF2563eb)).padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { showWebTools = false }) {
                                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = ComposeColor.White)
                                    }
                                    Text("Web Tools & PDF", color = ComposeColor.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { context ->
                                        WebView(context).apply {
                                            webViewRef = this
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.allowFileAccess = true
                                            webChromeClient = WebChromeClient()
                                            addJavascriptInterface(WebAppInterface(
                                                context = context,
                                                onSelectPdfs = { selectPdfLauncher.launch("application/pdf") },
                                                onSavePdf = { base64Data -> saveFileToDownloads(context, base64Data, "Merged_Document.pdf", "application/pdf") },
                                                onSaveImage = { base64Data, filename -> saveFileToDownloads(context, base64Data, filename, "image/jpeg") }
                                            ), "AndroidBridge")
                                            loadDataWithBaseURL(null, WEB_TOOLS_HTML, "text/html", "UTF-8", null)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun saveFileToDownloads(context: Context, base64Data: String, fileName: String, mimeType: String) {
        try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                val outputStream = context.contentResolver.openOutputStream(uri)
                outputStream?.write(bytes)
                outputStream?.close()
                runOnUiThread { android.widget.Toast.makeText(context, "Saved to Downloads: \$fileName", android.widget.Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) {
            runOnUiThread { android.widget.Toast.makeText(context, "Failed to save file", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onResume() {
        super.onResume()
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.statusBars())
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, FakeLockService::class.java))
        }
    }

    private fun saveAndBroadcast(
        prefs: SharedPreferences, autoLockSeconds: Int, opacity: Float, size: Int,
        enFloat: Boolean, enVol: Boolean, autoHide: Boolean
    ) {
        prefs.edit()
            .putInt(KEY_AUTO_SECONDS, autoLockSeconds).putFloat(KEY_OPACITY, opacity).putInt(KEY_SIZE, size)
            .putBoolean(KEY_ENABLE_FLOAT, enFloat).putBoolean(KEY_ENABLE_VOLUME, enVol).putBoolean(KEY_AUTO_HIDE, autoHide).apply()
        sendBroadcast(Intent(ACTION_UPDATE_SETTINGS))
    }
}

// ─────────────────────────────────────────────
// Custom Status Bar Composable
// ─────────────────────────────────────────────
@Composable
fun CustomStatusBar() {
    val context = LocalContext.current
    val batteryLevel = remember { mutableIntStateOf(100) }
    val isCharging = remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                batteryLevel.intValue = (level * 100 / scale)
                isCharging.value = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    val currentTime = remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime.value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    val batteryIcon = when {
        isCharging.value -> Icons.Filled.BatteryChargingFull
        batteryLevel.intValue > 80 -> Icons.Filled.BatteryFull
        batteryLevel.intValue > 40 -> Icons.Filled.Battery6Bar
        else -> Icons.Filled.Battery2Bar
    }
    val batteryColor = if (batteryLevel.intValue <= 20) ComposeColor(0xFFFF4444) else ComposeColor.White

    Row(
        modifier = Modifier.fillMaxWidth().background(ComposeColor(0xFF0A0A0A)).statusBarsPadding().padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(currentTime.value, color = ComposeColor.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Icon(Icons.Filled.SignalCellularAlt, contentDescription = "Signal", tint = ComposeColor.White, modifier = Modifier.size(15.dp))
            Icon(Icons.Filled.Wifi, contentDescription = "WiFi", tint = ComposeColor.White, modifier = Modifier.size(15.dp))
        }
        Row(modifier = Modifier.padding(end = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("\${batteryLevel.intValue}%", color = batteryColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Icon(batteryIcon, contentDescription = "Battery", tint = batteryColor, modifier = Modifier.size(18.dp))
        }
    }
}

// ─────────────────────────────────────────────
// Compose Settings UI
// ─────────────────────────────────────────────
@Composable
fun SettingsScreen(
    prefs: SharedPreferences, context: Context,
    onOpenWebTools: () -> Unit, onOpenAi: (String, String) -> Unit,
    onStartService: (Int, Float, Int, Boolean, Boolean, Boolean) -> Unit, onStopService: () -> Unit,
    onUpdateSettings: (Float, Int, Int, Boolean, Boolean, Boolean) -> Unit,
    // ── Ad Blocker params ──
    adBlockRunning: Boolean = false,
    youtubeBlock: Boolean = true,
    websiteBlock: Boolean = true,
    appAdsBlock: Boolean = true,
    onToggleAdBlock: () -> Unit = {},
    onYoutubeToggle: (Boolean) -> Unit = {},
    onWebsiteToggle: (Boolean) -> Unit = {},
    onAppAdsToggle:  (Boolean) -> Unit = {}
) {
    var isRunning by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().background(ComposeColor(0xFF0A0A0A)).padding(20.dp).verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("🔒 FakeLock Suite", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
        
        // --- Web Tools ---
        Button(onClick = onOpenWebTools, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF2563eb))) {
            Icon(Icons.Filled.Build, contentDescription = "Tools", tint = ComposeColor.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open PDF & Web Tools", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        // --- Rasel Edu Tools ---
        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://raseledutools.github.io/"))) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF10B981))) {
            Icon(Icons.Filled.Public, contentDescription = "Website", tint = ComposeColor.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Visit Rasel Edu Tools", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        // --- Floating AI Chat Trigger ---
        Button(
            onClick = {
                if (Settings.canDrawOverlays(context)) {
                    context.startService(Intent(context, FloatingAiService::class.java))
                } else {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:\${context.packageName}")))
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF8B5CF6))
        ) {
            Icon(Icons.Filled.ChatBubbleOutline, contentDescription = "Float AI", tint = ComposeColor.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start Floating AI Chat", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        // --- Native AI Assistants Grid ---
        Text("🤖 Native AI Assistants", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White, modifier = Modifier.align(Alignment.Start))
        
        val aiTools = listOf(
            Triple("ChatGPT", "https://chatgpt.com", ComposeColor(0xFF10A37F)),
            Triple("Gemini", "https://gemini.google.com", ComposeColor(0xFF4285F4)),
            Triple("DeepSeek", "https://chat.deepseek.com", ComposeColor(0xFF0052CC)),
            Triple("Claude", "https://claude.ai", ComposeColor(0xFFD97757)),
            Triple("Qwen AI", "https://chat.qwenlm.ai", ComposeColor(0xFF6D28D9))
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().height(180.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(aiTools) { ai ->
                Card(
                    modifier = Modifier.fillMaxWidth().height(50.dp).clickable { onOpenAi(ai.first, ai.second) },
                    colors = CardDefaults.cardColors(containerColor = ai.third),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(ai.first, color = ComposeColor.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ══════════════════════════════════════════════
        // 🛡️ AD BLOCKER SECTION
        // ══════════════════════════════════════════════
        Text(
            "🛡️ Ad Blocker",
            fontSize = 18.sp, fontWeight = FontWeight.Bold,
            color = ComposeColor.White,
            modifier = Modifier.align(Alignment.Start)
        )

        // Main ON/OFF Button
        Button(
            onClick = onToggleAdBlock,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (adBlockRunning) ComposeColor(0xFFDC2626) else ComposeColor(0xFF16A34A)
            )
        ) {
            Icon(
                if (adBlockRunning) Icons.Filled.GppBad else Icons.Filled.GppGood,
                contentDescription = null, tint = ComposeColor.White, modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (adBlockRunning) "⏹  Ad Blocker বন্ধ করুন" else "▶  Ad Blocker চালু করুন",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White
            )
        }

        // Status chip
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (adBlockRunning) ComposeColor(0xFF14532D) else ComposeColor(0xFF1C1917)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (adBlockRunning) "✅ সুরক্ষিত — Ads Block হচ্ছে" else "⚠️ সুরক্ষিত নয়",
                    color = if (adBlockRunning) ComposeColor(0xFF4ADE80) else ComposeColor(0xFFFFA500),
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                )
                Text(
                    "Blocked: ${FilterManager.blockedCount}",
                    color = ComposeColor(0xFF94A3B8), fontSize = 12.sp
                )
            }
        }

        // Toggle cards
        AdBlockToggleCard(
            emoji = "📺", title = "YouTube Ads",
            subtitle = "YouTube App ও Website এর বিজ্ঞাপন বন্ধ",
            checked = youtubeBlock, onChecked = onYoutubeToggle
        )
        AdBlockToggleCard(
            emoji = "🌐", title = "Website Ads",
            subtitle = "সব ওয়েবসাইটের Ad ও Tracker বন্ধ",
            checked = websiteBlock, onChecked = onWebsiteToggle
        )
        AdBlockToggleCard(
            emoji = "📱", title = "App Ads (All Apps)",
            subtitle = "AdMob, Unity, AppLovin, Facebook Ads বন্ধ",
            checked = appAdsBlock, onChecked = onAppAdsToggle
        )

        Text(
            "ℹ️ Local VPN ব্যবহার করে DNS filter করা হয়। কোনো data device ছেড়ে যায় না।",
            color = ComposeColor(0xFF64748B), fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
    }
}

// ── Ad Blocker Toggle Card ─────────────────────────────────────
@Composable
fun AdBlockToggleCard(
    emoji: String, title: String, subtitle: String,
    checked: Boolean, onChecked: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = ComposeColor.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = ComposeColor(0xFF94A3B8), fontSize = 11.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ComposeColor.White,
                    checkedTrackColor = ComposeColor(0xFF16A34A)
                )
            )
        }
    }
}

// ─────────────────────────────────────────────
// FLOATING AI SERVICE (Chat on Top of Screen)
// ─────────────────────────────────────────────
class FloatingAiService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: RelativeLayout
    private lateinit var webView: WebView
    private lateinit var params: WindowManager.LayoutParams

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var initialWidth = 0
    private var initialHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatingView = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B")) // Dark background
            elevation = 10f
        }

        // Header for dragging and AI switching
        val headerId = View.generateViewId()
        val headerLayout = LinearLayout(this).apply {
            id = headerId
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#334155"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 15, 10, 15)
        }

        // AI Buttons in Header
        val aiLinks = mapOf(
            "🟢 ChatGPT" to "https://chatgpt.com",
            "✨ Gemini" to "https://gemini.google.com",
            "🐋 DeepSeek" to "https://chat.deepseek.com",
            "🧠 Claude" to "https://claude.ai"
        )

        for ((name, url) in aiLinks) {
            val btn = TextView(this).apply {
                text = name.split(" ")[0] // Only show emoji
                textSize = 18f
                setPadding(15, 5, 15, 5)
                setOnClickListener { webView.loadUrl(url) }
            }
            headerLayout.addView(btn)
        }

        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        headerLayout.addView(spacer)

        // Close Button
        val closeBtn = TextView(this).apply {
            text = "❌"
            textSize = 16f
            setPadding(15, 5, 15, 5)
            setOnClickListener { stopSelf() }
        }
        headerLayout.addView(closeBtn)

        // Dragging Logic on Header
        headerLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                }
            }
            true
        }

        // WebView for AI
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.0.0 Mobile Safari/537.36"
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("https://chatgpt.com")
        }

        val webParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { addRule(RelativeLayout.BELOW, headerId) }

        // Resizer Handle (Bottom Right)
        val resizer = TextView(this).apply {
            text = "↘"
            textSize = 24f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#44000000")) // Semi transparent
            setPadding(20, 20, 20, 20)
        }
        val resizerParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
        }

        // Resizing Logic
        resizer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = params.width
                    initialHeight = params.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    
                    params.width = (initialWidth + dx).coerceAtLeast(400) // Minimum width
                    params.height = (initialHeight + dy).coerceAtLeast(600) // Minimum height
                    
                    windowManager.updateViewLayout(floatingView, params)
                }
            }
            true
        }

        floatingView.addView(headerLayout, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        floatingView.addView(webView, webParams)
        floatingView.addView(resizer, resizerParams)

        // Window Manager Parameters for Floating Window
        params = WindowManager.LayoutParams(
            800, 1000, // Initial Width & Height
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        windowManager.addView(floatingView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}

// ─────────────────────────────────────────────
// FakeLock Overlay Service (Unchanged Core Logic)
// ─────────────────────────────────────────────
class FakeLockService : Service() {
    private lateinit var windowManager: WindowManager
    private var lockScreenView: android.widget.FrameLayout? = null
    // ... [Rest of your lock service code remains the same, omitted here for brevity to keep focus on AI]
    override fun onBind(intent: Intent?): IBinder? = null
}
