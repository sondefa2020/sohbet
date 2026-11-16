package com.bayx.chat

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.bayx.chat.data.AuthState
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.PresenceRepo
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
        PresenceRepo.presences.value = mapOf("@ayse:x" to UserPresence("online", ""), "@can:x" to UserPresence("busy", ""))
        compose.setContent { BayxTheme { ChatListScreen(me, onOpenChat = {}, onProfile = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/2-sohbetler.png")
    }

    @Test
    fun sohbet() {
        val session = ChatSession("!a")
        session.title.value = "Ayşe"
        session.isDirect = true
        session.dmUserId.value = "@ayse:x"
        PresenceRepo.presences.value = mapOf("@ayse:x" to UserPresence("online", ""))
        session.messages.value = listOf(
            ChatMessage("1", MsgKind.TEXT, "Merhaba! Tayfa'nın yeni tasarımını beğendin mi? :)", false, "Ayşe", now - 300_000, MsgState.NONE),
            ChatMessage("2", MsgKind.TEXT, "Harika görünüyor! (Y)", true, "Ben", now - 240_000, MsgState.READ),
            ChatMessage(
                "3", MsgKind.VOICE, "", false, "Ayşe", now - 200_000, MsgState.NONE,
                MediaRef(null, null, "audio/ogg", 150_000, 0, 0, null),
            ),
            ChatMessage("4", MsgKind.TEXT, "<3", true, "Ben", now - 100_000, MsgState.SENT),
        )
        compose.setContent { BayxTheme { ChatContent(session, onBack = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/3-sohbet.png")
    }

    @Test
    fun profil() {
        PresenceRepo.myStatus.value = "Kodlamaya devam 🎧"
        compose.setContent { BayxTheme { ProfileScreen(me, onBack = {}, onAdmin = {}, onWebLink = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/4-profil.png")
    }

    private fun call(phase: com.bayx.chat.data.CallPhase, video: Boolean = false, quality: Int = 3) =
        com.bayx.chat.data.CallState(
            callId = "c1", peerId = "@ayse:x", peerName = "Ayşe Yılmaz", video = video, phase = phase,
            startedAt = now - 125_000, fromMe = true, ringing = true, quality = quality,
        )

    @Test
    fun aramaSuruyor() {
        com.bayx.chat.data.Calls.state.value = call(com.bayx.chat.data.CallPhase.ACTIVE, quality = 2)
        compose.mainClock.autoAdvance = false
        compose.setContent { BayxTheme { CallOverlay() } }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/5-arama-suruyor.png")
        com.bayx.chat.data.Calls.state.value = null
    }

    @Test
    fun aramaGeliyor() {
        com.bayx.chat.data.Calls.state.value = call(com.bayx.chat.data.CallPhase.INCOMING).copy(fromMe = false)
        compose.mainClock.autoAdvance = false
        compose.setContent { BayxTheme { CallOverlay() } }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/6-arama-geliyor.png")
        com.bayx.chat.data.Calls.state.value = null
    }

    @Test
    fun aramaKucuk() {
        com.bayx.chat.data.Calls.state.value = call(com.bayx.chat.data.CallPhase.ACTIVE)
        CallUiState.minimized = true
        compose.mainClock.autoAdvance = false
        compose.setContent { BayxTheme { androidx.compose.foundation.layout.Box { ChatListScreen(me, onOpenChat = {}, onProfile = {}); CallOverlay() } } }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/7-arama-kucuk.png")
        CallUiState.minimized = false
        com.bayx.chat.data.Calls.state.value = null
    }

    @Test
    fun aramalar() {
        val h = 3_600_000L
        com.bayx.chat.data.CallLog.entries.value = listOf(
            com.bayx.chat.data.CallEntry("@ayse:x", "Ayşe Yılmaz", true, true, false, now - 600_000, 754),
            com.bayx.chat.data.CallEntry("@can:x", "Can Demir", false, false, false, now - 2 * h),
            com.bayx.chat.data.CallEntry("@can:x", "Can Demir", false, false, false, now - 2 * h - 60_000),
            com.bayx.chat.data.CallEntry("@elif:x", "Elif", false, true, true, now - 26 * h, 3_900),
            com.bayx.chat.data.CallEntry("@ayse:x", "Ayşe Yılmaz", false, true, false, now - 50 * h, 42),
            com.bayx.chat.data.CallEntry("@mert:x", "Mert", true, false, true, now - 80 * h),
        )
        compose.setContent { BayxTheme { CallsScreen(onBack = {}, onOpenChat = {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/8-aramalar.png")
    }
}
