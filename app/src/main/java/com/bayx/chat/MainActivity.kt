package com.bayx.chat

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
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
        content = content,
    )
}

@Composable
fun BayxApp() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "login") {
        composable("login") {
            LoginScreen(onLogin = {
                nav.navigate("chats") { popUpTo("login") { inclusive = true } }
            })
        }
        composable("chats") {
            ChatListScreen(
                onOpenChat = { name -> nav.navigate("chat/$name") },
                onProfile = { nav.navigate("profile") },
            )
        }
        composable("chat/{name}") { entry ->
            ChatScreen(
                name = entry.arguments?.getString("name").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        composable("profile") {
            ProfileScreen(
                onBack = { nav.popBackStack() },
                onLogout = {
                    nav.navigate("login") { popUpTo(nav.graph.id) { inclusive = true } }
                },
            )
        }
    }
}
