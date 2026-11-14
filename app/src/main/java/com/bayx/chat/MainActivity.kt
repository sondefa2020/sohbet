package com.bayx.chat

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import com.bayx.chat.data.Calls
import androidx.compose.runtime.DisposableEffect
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.bayx.chat.data.Notifier
import com.bayx.chat.data.Sounds
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import com.bayx.chat.data.AuthState
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.Gate
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

// Tayfa tasarım dili: sıcak krem zemin, kum/karamel vurgu, koyu mürekkep yazı.
// (Adlar eski sürümden kalma; "Blue" artık karamel vurgu rengidir.)
val Blue = Color(0xFFB07A4E)
val BlueLight = Color(0xFFE6C9A6)
val BlueDark = Color(0xFFC79A6E)
val Ink = Color(0xFF1F1B18)
val Muted = Color(0xFF6E655C)
val SoftBlue = Color(0xFF8C7F72)
val Bg = Color(0xFFF8F4EF)
val Line = Color(0xFFECE4DA)
val Online = Color(0xFF34C759)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        // Giriş bilgileri için sistem otomatik doldurma önerilerini uygulama genelinde kapat.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        handleIntent(intent)
        setContent { BayxTheme { BayxApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("tayfa_open")?.let { Bayx.pendingOpen.value = it }
        val roomId = intent?.getStringExtra(Notifier.EXTRA_ROOM_ID) ?: return
        intent.removeExtra(Notifier.EXTRA_ROOM_ID)
        Bayx.pendingRoom.value = roomId
    }

    override fun onStart() {
        super.onStart()
        Gate.onForeground()
        Bayx.setForeground(true)
    }

    override fun onStop() {
        Gate.onBackground()
        Bayx.setForeground(false)
        super.onStop()
    }
}

@Composable
fun BayxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue,
            onPrimary = Color.White,
            background = Bg,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            onSurfaceVariant = Muted,
            outline = Line,
        ),
    ) {
        // Arayüz Türkçe olduğu için telefonun dili sağdan sola olsa bile soldan sağa çizilir.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
    }
}

@Composable
fun BayxApp() {
    val auth by Bayx.auth.collectAsState()
    val context = LocalContext.current
    val shake = remember { Animatable(0f) }

    // Arama ekranından ekran paylaşımı: Activity'den ekran yakalama izni iste.
    val screenShareLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) Calls.onScreenCaptureResult(result.data)
    }
    DisposableEffect(Unit) {
        val mpm = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
        Calls.screenShareRequester = {
            runCatching { mpm?.createScreenCaptureIntent()?.let { screenShareLauncher.launch(it) } }
        }
        onDispose { Calls.screenShareRequester = null }
    }

    // Titreşim (gelen ya da gönderilen): telefonu titret, vızıltı sesi çal ve ekranı salla.
    LaunchedEffect(Unit) {
        Bayx.nudges.collect { event ->
            Sounds.nudge()
            if (!event.mine) Toast.makeText(context, "${event.sender} sana titreşim gönderdi!", Toast.LENGTH_SHORT).show()
            repeat(6) {
                shake.animateTo(18f, tween(40))
                shake.animateTo(-18f, tween(40))
            }
            shake.animateTo(0f, tween(40))
        }
    }

    // Oturum açıkken uygulama arka plana atılsa bile çalışmaya devam etsin: kalıcı servis.
    LaunchedEffect(auth) {
        when (auth) {
            is AuthState.LoggedIn, AuthState.Offline -> {
                com.bayx.chat.data.SyncService.start(context)
                requestBatteryExemptionOnce(context)
            }
            AuthState.LoggedOut -> com.bayx.chat.data.SyncService.stop(context)
            else -> {}
        }
    }

    val accepted by Gate.onboardingAccepted.collectAsState()
    Box(Modifier.fillMaxSize().offset { IntOffset(shake.value.dp.roundToPx(), 0) }) {
        when {
            !accepted && auth !is AuthState.LoggedIn -> OnboardingScreen()
            else -> when (val state = auth) {
                AuthState.Loading -> SplashScreen()
                AuthState.LoggedOut -> AuthFlow()
                AuthState.Offline -> OfflineScreen()
                is AuthState.LoggedIn -> MainNav(state)
            }
        }
    }
    val locked by Gate.locked.collectAsState()
    if (auth is AuthState.LoggedIn) {
        EffectOverlay()
        CallOverlay()
        if (locked) LockScreen()
    }
}

@Composable
private fun MainNav(me: AuthState.LoggedIn) {
    val nav = rememberNavController()
    val context = LocalContext.current

    // Android 13+: bildirim izni (bir kez sorulur; reddedilirse uygulama yine çalışır).
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Bildirime dokunulduysa ilgili sohbeti aç.
    val pendingRoom by Bayx.pendingRoom.collectAsState()
    LaunchedEffect(pendingRoom) {
        val roomId = pendingRoom ?: return@LaunchedEffect
        Bayx.pendingRoom.value = null
        nav.navigate("chat/" + Uri.encode(roomId)) { launchSingleTop = true }
    }
    val pendingOpen by Bayx.pendingOpen.collectAsState()
    LaunchedEffect(pendingOpen) {
        if (pendingOpen == "requests") {
            Bayx.pendingOpen.value = null
            nav.navigate("requests") { launchSingleTop = true }
        }
    }

    // Modüler ayar: sunucudan duyuru ve güncelleme bilgisi (admin panelinden yönetilir).
    var config by remember { mutableStateOf<com.bayx.chat.data.Api.AppConfig?>(null) }
    var updateDismissed by remember { mutableStateOf(false) }
    var announcement by remember { mutableStateOf<String?>(null) }
    val myVersion = remember {
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        }.getOrDefault(0)
    }
    LaunchedEffect(Unit) {
        val token = Bayx.accessToken ?: return@LaunchedEffect
        val cfg = runCatching { com.bayx.chat.data.Api.getConfig(token) }.getOrNull() ?: return@LaunchedEffect
        config = cfg
        if (cfg.announcement.isNotBlank()) {
            val prefs = context.getSharedPreferences("tayfa_giris", android.content.Context.MODE_PRIVATE)
            if (prefs.getString("son_duyuru", "") != cfg.announcement) {
                announcement = cfg.announcement
                prefs.edit().putString("son_duyuru", cfg.announcement).apply()
            }
        }
    }

    NavHost(navController = nav, startDestination = "chats") {
        composable("chats") {
            ChatListScreen(
                me = me,
                onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) },
                onProfile = { nav.navigate("profile") },
                onSecret = { nav.navigate("secret") },
                onMedia = { nav.navigate("media") },
                onRequests = { nav.navigate("requests") },
                onCalls = { nav.navigate("calls") },
                onStatus = { nav.navigate("status") },
            )
        }
        composable("requests") {
            RequestsScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) },
            )
        }
        composable("secret") {
            SecretAreaScreen(
                me = me,
                onBack = { nav.popBackStack() },
                onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) },
            )
        }
        composable("media") {
            MediaHubScreen(onBack = { nav.popBackStack() })
        }
        composable("calls") {
            CallsScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) },
            )
        }
        composable("status") {
            StatusScreen(onBack = { nav.popBackStack() })
        }
        composable("chat/{id}") { entry ->
            ChatScreen(
                roomId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onGroupInfo = { roomId -> nav.navigate("group/" + Uri.encode(roomId)) },
            )
        }
        composable("group/{id}") { entry ->
            GroupInfoScreen(
                roomId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onLeft = { nav.popBackStack(route = "chats", inclusive = false) },
            )
        }
        composable("profile") {
            ProfileScreen(
                me = me,
                onBack = { nav.popBackStack() },
                onAdmin = { nav.navigate("admin") },
                onWebLink = { nav.navigate("weblink") },
            )
        }
        composable("weblink") {
            WebLinkScreen(onBack = { nav.popBackStack() })
        }
        composable("admin") {
            AdminScreen(me = me, onBack = { nav.popBackStack() })
        }
    }

    config?.let { cfg ->
        if (!updateDismissed && cfg.latestVersion > myVersion && cfg.apkUrl.isNotBlank()) {
            AlertDialog(
                onDismissRequest = { updateDismissed = true },
                title = { Text("Yeni sürüm var") },
                text = { Text("Tayfa'nın yeni bir sürümü yayınlandı. Güncellemek için indir ve kur.") },
                confirmButton = {
                    TextButton(onClick = {
                        updateDismissed = true
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(cfg.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text("Güncelle") }
                },
                dismissButton = { TextButton(onClick = { updateDismissed = true }) { Text("Sonra") } },
            )
        }
    }
    announcement?.let { text ->
        AlertDialog(
            onDismissRequest = { announcement = null },
            title = { Text("Duyuru") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { announcement = null }) { Text("Tamam") } },
        )
    }
}

/** Pil optimizasyonundan muafiyet iste (yalnızca bir kez). Böylece sistem uygulamayı öldürmez. */
private fun requestBatteryExemptionOnce(context: android.content.Context) {
    val prefs = context.getSharedPreferences("tayfa_arka_plan", android.content.Context.MODE_PRIVATE)
    if (prefs.getBoolean("pil_soruldu", false)) return
    val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
        prefs.edit().putBoolean("pil_soruldu", true).apply()
        return
    }
    prefs.edit().putBoolean("pil_soruldu", true).apply()
    runCatching {
        @android.annotation.SuppressLint("BatteryLife")
        val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:" + context.packageName)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
