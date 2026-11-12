package com.bayx.chat

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
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
        setContent { BayxTheme { BayxApp() } }
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
    when (val state = auth) {
        AuthState.Loading -> SplashScreen()
        AuthState.LoggedOut -> AuthFlow()
        AuthState.Offline -> OfflineScreen()
        is AuthState.LoggedIn -> MainNav(state)
    }
}

@Composable
private fun MainNav(me: AuthState.LoggedIn) {
    val nav = rememberNavController()
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
