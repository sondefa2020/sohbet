package com.bayx.chat

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.bayx.chat.data.AuthState
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.ChatMessage
import com.bayx.chat.data.ChatSession
import com.bayx.chat.data.ChatSummary
import com.bayx.chat.data.Gate
import com.bayx.chat.data.MediaRef
import com.bayx.chat.data.MsgKind
import com.bayx.chat.data.MsgState
import com.bayx.chat.data.UserPresence
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tasarımı telefon olmadan görmek için ekran görüntüleri üretir (gerçek sunucuya bağlanmaz). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class EkranGoruntuleriTest {
    @get:Rule
    val compose = createComposeRule()

    private val me = AuthState.LoggedIn("@mehmet:sohbet.acioglu.tr", "Mehmet")
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        Gate.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun onboarding() {
        compose.setContent { BayxTheme { OnboardingScreen() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/0-onboarding.png")
    }

    @Test
    fun hosgeldin() {
        compose.setContent { BayxTheme { AuthFlow() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/1-hosgeldin.png")
    }

    @Test
    fun sohbetler() {
        Bayx.rooms.value = listOf(
            ChatSummary("!a", "Ayşe", "Akşam görüşelim mi? :)", now - 60_000, 2, true, "@ayse:x"),
            ChatSummary("!b", "Tayfa Ekibi", "Can: Fotoğrafları yükledim", now - 3_600_000, 0, false),
            ChatSummary("!c", "Can", "Sesli mesaj", now - 7_200_000, 0, true, "@can:x", lastFromMe = true),
            ChatSummary("!d", "Elif", "Harika görünüyor!", now - 90_000_000, 0, true, "@elif:x"),
        )
        Bayx.presences.value = mapOf("@ayse:x" to UserPresence("online", ""), "@can:x" to UserPresence("busy", ""))
        compose.setContent { BayxTheme { ChatListScreen(me, onOpenChat = {}, onProfile = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/2-sohbetler.png")
    }

    @Test
    fun sohbet() {
        val session = ChatSession("!a")
        session.title.value = "Ayşe"
        session.isDirect = true
        session.dmUserId.value = "@ayse:x"
        Bayx.presences.value = mapOf("@ayse:x" to UserPresence("online", ""))
        session.messages.value = listOf(
            ChatMessage("1", MsgKind.TEXT, "Merhaba! Tayfa'nın yeni tasarımını beğendin mi? :)", false, "Ayşe", now - 300_000, MsgState.NONE),
            ChatMessage("2", MsgKind.TEXT, "Harika görünüyor! (Y)", true, "Ben", now - 240_000, MsgState.READ),
            ChatMessage(
                "3", MsgKind.VOICE, "", false, "Ayşe", now - 200_000, MsgState.NONE,
                MediaRef(null, null, "audio/ogg", 150_000, 0, 0, null),
            ),
            ChatMessage("4", MsgKind.TEXT, "<3", true, "Ben", now - 100_000, MsgState.SENT),
        )
        compose.setContent { BayxTheme { ChatContent(session) {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/3-sohbet.png")
    }

    @Test
    fun profil() {
        Bayx.myStatus.value = "Kodlamaya devam 🎧"
        compose.setContent { BayxTheme { ProfileScreen(me, onBack = {}, onAdmin = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/4-profil.png")
    }
}
