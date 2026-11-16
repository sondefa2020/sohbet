package com.bayx.chat

import kotlinx.coroutines.launch
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.navigation.compose.NavHost
import com.bayx.chat.data.AuthState
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.Gate
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

// Tayfa tasarım dili: sıcak krem zemin, kum/karamel vurgu, koyu mürekkep yazı.
// (Adlar eski sürümden kalma; "Blue" artık karamel vurgu rengidir.)
val Blue: Color get() = when {
    ThemeState.glass && ThemeState.dark -> Color(0xFF3DDC84)
    ThemeState.glass -> Color(0xFF1AA34A)
    ThemeState.dark -> Color(0xFFC79A6E)
    else -> Color(0xFFB07A4E)
}
val BlueLight: Color get() = if (ThemeState.glass) Color(0xFF4ADE80) else Color(0xFFE6C9A6)
val BlueDark: Color get() = if (ThemeState.glass) Color(0xFF16A34A) else Color(0xFFC79A6E)
val Ink: Color get() = if (ThemeState.dark) Color(0xFFF2ECE4) else Color(0xFF1F1B18)
val Muted: Color get() = if (ThemeState.dark) Color(0xFF9A9088) else Color(0xFF6E655C)
val SoftBlue: Color get() = if (ThemeState.dark) Color(0xFFA09589) else Color(0xFF8C7F72)
val Bg: Color get() = when {
    ThemeState.glass && ThemeState.dark -> Color(0xFF0C140F)
    ThemeState.glass -> Color(0xFFEEF7F1)
    ThemeState.dark -> Color(0xFF14110F)
    else -> Color(0xFFF8F4EF)
}
val Line: Color get() = when {
    ThemeState.glass && ThemeState.dark -> Color(0xFF22352A)
    ThemeState.glass -> Color.White.copy(alpha = 0.85f)
    ThemeState.dark -> Color(0xFF2C2622)
    else -> Color(0xFFECE4DA)
}
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
        // Gelen arama: ekranı aç ve kilit ekranının üstünde göster (arama ekranı hemen görünsün).
        if (intent?.getBooleanExtra(Notifier.EXTRA_INCOMING_CALL, false) == true || intent?.hasExtra(Notifier.EXTRA_ACCEPT_CALL) == true) {
            intent.removeExtra(Notifier.EXTRA_INCOMING_CALL)
            showOverLockScreen()
            com.bayx.chat.data.Calls.ensurePolling()
        }
        // Cevapsız aramadaki "Geri ara": doğrudan arama başlar.
        intent?.getStringExtra(Notifier.EXTRA_CALL_BACK)?.let { peer ->
            val name = intent.getStringExtra(Notifier.EXTRA_CALL_BACK_NAME).orEmpty()
            val video = intent.getBooleanExtra(Notifier.EXTRA_CALL_BACK_VIDEO, false)
            intent.removeExtra(Notifier.EXTRA_CALL_BACK)
            Notifier.cancelMissed(this, peer)
            Bayx.pendingCall.value = Triple(peer, name, video)
        }
        // Arama bildirimindeki "Cevapla": arama ekranı açılınca izinleri alıp hemen cevaplar.
        intent?.getStringExtra(Notifier.EXTRA_ACCEPT_CALL)?.let {
            intent.removeExtra(Notifier.EXTRA_ACCEPT_CALL)
            com.bayx.chat.data.Calls.autoAcceptCallId = it
            com.bayx.chat.data.Notifier.cancelCall(this)
        }
        // Kişi davet linki: tayfa://t/ad ya da https://sohbet.acioglu.tr/t/ad
        intent?.data?.let { uri ->
            val name = when {
                uri.scheme == "tayfa" && uri.host == "t" -> uri.pathSegments.firstOrNull()
                uri.scheme == "https" && uri.pathSegments.firstOrNull() == "t" -> uri.pathSegments.getOrNull(1)
                else -> null
            }
            if (!name.isNullOrBlank()) {
                Bayx.pendingInvite.value = name
                intent.data = null
            }
        }
        val roomId = intent?.getStringExtra(Notifier.EXTRA_ROOM_ID) ?: return
        intent.removeExtra(Notifier.EXTRA_ROOM_ID)
        Bayx.pendingRoom.value = roomId
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
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
    val scheme = if (ThemeState.dark) {
        darkColorScheme(
            primary = Blue,
            onPrimary = Color(0xFF14110F),
            background = Bg,
            onBackground = Ink,
            surface = CardBg,
            onSurface = Ink,
            onSurfaceVariant = Muted,
            outline = Line,
        )
    } else {
        lightColorScheme(
            primary = Blue,
            onPrimary = Color.White,
            background = Bg,
            onBackground = Ink,
            surface = CardBg,
            onSurface = Ink,
            onSurfaceVariant = Muted,
            outline = Line,
        )
    }
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !ThemeState.dark
        }
    }
    MaterialTheme(colorScheme = scheme) {
        // Arapça seçilince sağdan sola, diğer dillerde soldan sağa çizilir.
        val dir = if (L.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        // Büyük yazı (Ayarlar > Kolaylıklar): telefonun yazı boyutunun üstüne ek büyütme.
        val scale by com.bayx.chat.data.Comfort.textScale.collectAsState()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
        // Her ekranda aynı oranlar: dar ekranlarda (ya da telefonda "ekran yakınlaştırma" büyükse)
        // tüm arayüz orantılı küçülür; tasarım ~392dp genişliğe göre dengelenir. Taşma, iç içe girme olmaz.
        val fit = if (widthDp in 1 until DESIGN_WIDTH_DP) (widthDp / DESIGN_WIDTH_DP.toFloat()).coerceAtLeast(0.80f) else 1f
        // Telefonun yazı boyutu çok büyükse sınırlanır (en fazla %15); kendi "Büyük yazı" seçimi ayrıca uygulanır.
        val font = density.fontScale.coerceAtMost(1.15f) * scale
        CompositionLocalProvider(
            LocalLayoutDirection provides dir,
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density * fit, font),
            LocalAppWidth provides (widthDp / fit).toInt(),
            content = content,
        )
    }
}

/** Tasarımın dengelendiği ekran genişliği (dp). */
const val DESIGN_WIDTH_DP = 392

/** Uygulamanın gördüğü (orantılanmış) ekran genişliği, dp. */
val LocalAppWidth = androidx.compose.runtime.staticCompositionLocalOf { DESIGN_WIDTH_DP }

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
            if (!event.mine) Toast.makeText(context, tf("%s sana titreşim gönderdi!", event.sender), Toast.LENGTH_SHORT).show()
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
                is AuthState.LoggedIn -> {
                    MainNav(state)
                    RecoveryDialogs()
                }
            }
        }
    }
    // Otomatik güncelleme (giriş yapılmamışken de çalışır).
    if (auth !is AuthState.Loading) UpdatePrompt()
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
    // Cevapsız aramadan "Geri ara": izinleri al, doğrudan ara.
    val pendingCall by Bayx.pendingCall.collectAsState()
    val callToast = rememberToast()
    val askCall = rememberCallPermissions { callToast(t("Arama için mikrofon (ve görüntülü aramada kamera) izni gerekli.")) }
    LaunchedEffect(pendingCall) {
        val (peer, name, video) = pendingCall ?: return@LaunchedEffect
        Bayx.pendingCall.value = null
        askCall(video) { com.bayx.chat.data.Calls.startCall(peer, name, video) }
    }
    // Gelen aramaların tam ekran açılması için gereken izinler (bir kez sorulur).
    CallPermissionsPrompt()

    // Davet linki ya da QR ile gelen kişi: kim olduğunu göster, tek dokunuşla sohbet aç.
    val pendingInvite by Bayx.pendingInvite.collectAsState()
    pendingInvite?.let { name ->
        InviteOpenDialog(
            name = name,
            onDismiss = { Bayx.pendingInvite.value = null },
            onOpen = { roomId ->
                Bayx.pendingInvite.value = null
                nav.navigate("chat/" + Uri.encode(roomId)) { launchSingleTop = true }
            },
        )
    }
    LaunchedEffect(Unit) { com.bayx.chat.data.ReachRepo.refresh(force = true) }
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

    // Rehber eşleştirmesi (WhatsApp mantığı) ve yeni hesapta ad sorma; eski hesapta numara bağlama önerisi.
    var askName by remember { mutableStateOf(NamePrompt.pending(context)) }
    LaunchedEffect(Unit) {
        com.bayx.chat.data.PhonebookRepo.start(context)
        val token = Bayx.accessToken ?: return@LaunchedEffect
        val phone = runCatching { com.bayx.chat.data.Api.phoneMe(token) }.getOrNull()
        val prefs = context.getSharedPreferences("tayfa_giris", android.content.Context.MODE_PRIVATE)
        if (phone != null && phone.isEmpty() && !prefs.getBoolean("numara_soruldu", false)) {
            prefs.edit().putBoolean("numara_soruldu", true).apply()
            nav.navigate("phonelink") { launchSingleTop = true }
        }
    }
    if (askName) {
        TextInputDialog(
            title = t("Adın"),
            initial = "",
            maxLength = 40,
            onDismiss = {},
            onSave = { name ->
                val clean = name.trim()
                if (clean.isNotEmpty()) {
                    askName = false
                    NamePrompt.done(context)
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        com.bayx.chat.data.ProfileRepo.setDisplayName(clean)
                    }
                }
            },
        )
    }

    NavHost(navController = nav, startDestination = "chats") {
        composable("phonelink") {
            PhoneAuthScreen(linkToken = Bayx.accessToken, onLinked = { nav.popBackStack() })
        }
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
                onPerson = { uid -> nav.navigate("person/" + Uri.encode(uid)) },
                onNewChat = { nav.navigate("newchat") },
            )
        }
        composable("newchat") {
            NewChatScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) { popUpTo("newchat") { inclusive = true } } },
                onDialpad = { nav.navigate("dialpad") },
                onFindName = { nav.navigate("findname") },
                onPerson = { uid -> nav.navigate("person/" + Uri.encode(uid)) },
            )
        }
        composable("dialpad") {
            DialpadScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) { popUpTo("newchat") { inclusive = true } } },
            )
        }
        composable("findname") {
            FindByNameScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) { popUpTo("newchat") { inclusive = true } } },
                onPerson = { uid -> nav.navigate("person/" + Uri.encode(uid)) },
            )
        }
        composable("person/{id}") { entry ->
            PersonCardScreen(
                userId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) },
            )
        }
        composable("editprofile") {
            EditProfileScreen(
                me = me,
                onBack = { nav.popBackStack() },
                onPhone = { nav.navigate("phonelink") { launchSingleTop = true } },
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
            TabPage(AppTab.MEDIA, nav) { MediaHubScreen(onBack = { nav.popBackStack() }) }
        }
        composable("calls") {
            TabPage(AppTab.CALLS, nav) {
                CallsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) },
                )
            }
        }
        composable("status") {
            TabPage(AppTab.STATUS, nav) { StatusScreen(onBack = { nav.popBackStack() }) }
        }
        composable("chat/{id}") { entry ->
            val roomId = entry.arguments?.getString("id").orEmpty()
            // Kilitli sohbet: açmadan önce parmak izi / ekran kilidi sorulur.
            ChatLockGate(roomId = roomId, onCancel = { nav.popBackStack() }) {
                ChatScreen(
                    roomId = roomId,
                    onBack = { nav.popBackStack() },
                    onGroupInfo = { id -> nav.navigate("group/" + Uri.encode(id)) },
                    onPerson = { uid -> nav.navigate("person/" + Uri.encode(uid)) },
                )
            }
        }
        composable("starred") {
            StarredScreen(onBack = { nav.popBackStack() }, onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) })
        }
        composable("comfort") {
            ComfortScreen(onBack = { nav.popBackStack() })
        }
        composable("group/{id}") { entry ->
            GroupInfoScreen(
                roomId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onLeft = { nav.popBackStack(route = "chats", inclusive = false) },
            )
        }
        composable("profile") {
            TabPage(AppTab.SETTINGS, nav) {
                ProfileScreen(
                    me = me,
                    onBack = { nav.popBackStack() },
                    onAdmin = { nav.navigate("admin") },
                    onWebLink = { nav.navigate("weblink") },
                    onPhone = { nav.navigate("phonelink") { launchSingleTop = true } },
                    onComfort = { nav.navigate("comfort") },
                    onEditProfile = { nav.navigate("editprofile") },
                    onStarred = { nav.navigate("starred") },
                )
            }
        }
        composable("weblink") {
            WebLinkScreen(onBack = { nav.popBackStack() })
        }
        composable("admin") {
            AdminScreen(me = me, onBack = { nav.popBackStack() })
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


/** Ana sekme sayfası: içerik + her sayfada aynı alt menü. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TabPage(current: AppTab, nav: androidx.navigation.NavHostController, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(PageBrush)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .consumeWindowInsets(WindowInsets.navigationBars),
        ) { content() }
        Box(Modifier.navigationBarsPadding()) {
            BottomNav(current) { tab ->
                if (tab == current) return@BottomNav
                val route = when (tab) {
                    AppTab.CHATS -> null
                    AppTab.CALLS -> "calls"
                    AppTab.STATUS -> "status"
                    AppTab.MEDIA -> "media"
                    AppTab.SETTINGS -> "profile"
                }
                if (route == null) {
                    nav.popBackStack("chats", inclusive = false)
                } else {
                    nav.navigate(route) {
                        popUpTo("chats")
                        launchSingleTop = true
                    }
                }
            }
        }
    }
}
