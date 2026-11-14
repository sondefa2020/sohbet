package com.bayx.chat

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

/** Sohbet arka planları (WhatsApp tarzı). Seçim cihazda saklanır, tüm sohbetlere uygulanır. */
object Wallpaper {
    data class Option(
        val id: String,
        val name: String,
        val colors: List<Color>,
        val doodle: Boolean = false,
        val doodleColor: Color = Color.White,
        val dark: Boolean = false,
    )

    val options = listOf(
        Option("krem", "Krem", listOf(Color(0xFFFCFAF7), Color(0xFFF1E9DF))),
        Option("yesil", t("Yeşil doodle"), listOf(Color(0xFFE9F3EC), Color(0xFFDDEBE0)), doodle = true, doodleColor = Color(0x1A2E8B6E)),
        Option("gun", t("Gün batımı"), listOf(Color(0xFFFCE9D6), Color(0xFFF3C9A8))),
        Option("mavi", "Okyanus", listOf(Color(0xFFE3EEF7), Color(0xFFCADEEE)), doodle = true, doodleColor = Color(0x163B6EA5)),
        Option("lila", "Lila", listOf(Color(0xFFEDE7FB), Color(0xFFDBD0F2))),
    )

    private const val PREFS = "tayfa_giris"
    val selected = MutableStateFlow("krem")

    fun init(context: Context) {
        selected.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("arkaplan", "krem") ?: "krem"
    }

    fun set(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("arkaplan", id).apply()
        selected.value = id
    }

    fun current(id: String): Option = options.firstOrNull { it.id == id } ?: options.first()
}

/** Sohbet ekranının arkasına çizilen arka plan. */
@Composable
fun ChatBackground(id: String, modifier: Modifier = Modifier) {
    val option = Wallpaper.current(id)
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(option.colors))) {
        if (option.doodle) {
            Canvas(Modifier.fillMaxSize()) {
                val step = 86f
                var row = 0
                var y = 20f
                while (y < size.height + step) {
                    var x = if (row % 2 == 0) 20f else 20f + step / 2
                    while (x < size.width + step) {
                        val r = 9f
                        // küçük baloncuk + nokta deseni
                        drawCircle(option.doodleColor, radius = r, center = Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                        drawCircle(option.doodleColor, radius = 2.5f, center = Offset(x + step / 2, y + step / 2))
                        x += step
                    }
                    y += step
                    row++
                }
            }
        }
    }
}

/** Arka plan seçme penceresi. */
@Composable
fun WallpaperPicker(context: Context, current: String, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sohbet arka planı", fontWeight = FontWeight.Bold) },
        text = {
            LazyVerticalGrid(columns = GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Wallpaper.options, key = { it.id }) { option ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { picked = option.id },
                            contentAlignment = Alignment.Center,
                        ) {
                            ChatBackground(option.id, Modifier.clip(RoundedCornerShape(14.dp)))
                            if (picked == option.id) {
                                Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF2E8B6E)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Check, t("Seçili"), tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        Text(option.name, fontSize = 11.sp, color = Color(0xFF6E655C))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { Wallpaper.set(context, picked); onDismiss() }) { Text("Uygula") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
