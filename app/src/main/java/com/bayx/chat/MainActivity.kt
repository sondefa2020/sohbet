package com.bayx.chat

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
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

val Blue = Color(0xFF1565E0)
val BlueLight = Color(0xFF2F7FF2)
val BlueDark = Color(0xFF0F5BD8)
val Ink = Color(0xFF0F1B2D)
val Muted = Color(0xFF3C4C63)
val SoftBlue = Color(0xFF5E7494)
val Bg = Color(0xFFFAFCFF)
val Line = Color(0xFFE1E8F2)
val Online = Color(0xFF22C55E)

class MainActivity : ComponentActivity() {
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
        val roomId = intent?.getStringExtra(Notifier.EXTRA_ROOM_ID) ?: return
        intent.removeExtra(Notifier.EXTRA_ROOM_ID)
        Bayx.pendingRoom.value = roomId
    }

    override fun onStart() {
        super.onStart()
        Bayx.setForeground(true)
    }

    override fun onStop() {
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

    // Gelen titreşim: telefonu titret, ekranı kısa süre salla ve kimden geldiğini göster.
    LaunchedEffect(Unit) {
        Bayx.nudges.collect { sender ->
            runCatching {
                context.getSystemService(Vibrator::class.java)
                    ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 140, 80, 140, 80, 220), -1))
            }
            Toast.makeText(context, "$sender sana titreşim gönderdi!", Toast.LENGTH_SHORT).show()
            repeat(4) {
                shake.animateTo(16f, tween(45))
                shake.animateTo(-16f, tween(45))
            }
            shake.animateTo(0f, tween(45))
        }
    }

    Box(Modifier.fillMaxSize().offset { IntOffset(shake.value.dp.roundToPx(), 0) }) {
        when (val state = auth) {
            AuthState.Loading -> SplashScreen()
            AuthState.LoggedOut -> AuthFlow()
            AuthState.Offline -> OfflineScreen()
            is AuthState.LoggedIn -> MainNav(state)
        }
    }
    if (auth is AuthState.LoggedIn) CallOverlay()
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

    NavHost(navController = nav, startDestination = "chats") {
        composable("chats") {
            ChatListScreen(
                me = me,
                onOpenChat = { roomId -> nav.navigate("chat/" + Uri.encode(roomId)) },
                onProfile = { nav.navigate("profile") },
            )
        }
        composable("chat/{id}") { entry ->
            ChatScreen(
                roomId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        composable("profile") {
            ProfileScreen(
                me = me,
                onBack = { nav.popBackStack() },
                onAdmin = { nav.navigate("admin") },
            )
        }
        composable("admin") {
            AdminScreen(me = me, onBack = { nav.popBackStack() })
        }
    }
}
