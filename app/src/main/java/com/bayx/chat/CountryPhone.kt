package com.bayx.chat

import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import java.util.Locale

/** Ülke: bölge kodu (TR), telefon kodu (90), yerel adı ve bayrağı. */
data class Country(val region: String, val code: Int, val name: String) {
    val flag: String get() = flagOf(region)
}

/** Bölge kodundan bayrak (🇹🇷): Unicode bölge göstergesi harfleri. */
fun flagOf(region: String): String =
    region.uppercase(Locale.ROOT).filter { it in 'A'..'Z' }.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

object Phones {
    val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** Uygulama dilinde ülke adları, alfabetik. */
    fun countries(): List<Country> {
        val locale = Locale(L.lang.code)
        return util.supportedRegions.map { r ->
            Country(r, util.getCountryCodeForRegion(r), Locale("", r).getDisplayCountry(locale).ifBlank { r })
        }.sortedBy { it.name.lowercase(locale) }
    }

    fun country(region: String): Country {
        val r = region.uppercase(Locale.ROOT)
        return Country(r, util.getCountryCodeForRegion(r), Locale("", r).getDisplayCountry(Locale(L.lang.code)).ifBlank { r })
    }

    /** Cihazın ülkesi: SIM kartı, sonra şebeke, sonra cihaz dili; bulunamazsa Türkiye. */
    fun deviceRegion(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val candidates = listOf(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
        return candidates.firstNotNullOfOrNull { c ->
            c?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && util.getCountryCodeForRegion(it) != 0 }
        } ?: "TR"
    }

    /** Yazarken biçimlendirme (ör. 532 123 45 67): ülkenin kendi numara düzeniyle. */
    fun formatAsYouType(region: String, digits: String): String {
        val f = util.getAsYouTypeFormatter(region)
        var out = ""
        digits.forEach { out = f.inputDigit(it) }
        return out
    }

    /** Örnek cep numarası (alan için ipucu), ör. 0501 234 56 78. */
    fun example(region: String): String =
        runCatching { util.format(util.getExampleNumberForType(region, PhoneNumberType.MOBILE), PhoneNumberFormat.NATIONAL) }.getOrDefault("")

    sealed interface Check {
        data object Empty : Check
        data object Incomplete : Check
        data object Invalid : Check
        data object NotMobile : Check
        /** Geçerli: [e164] "+905321234567" biçiminde. */
        data class Valid(val e164: String, val international: String) : Check
    }

    fun check(region: String, digits: String): Check {
        if (digits.isBlank()) return Check.Empty
        val number = runCatching { util.parse(digits, region) }.getOrNull() ?: return Check.Invalid
        if (!util.isValidNumberForRegion(number, region)) {
            return if (util.isPossibleNumber(number) || digits.length < 7) Check.Incomplete else Check.Invalid
        }
        val type = util.getNumberType(number)
        if (type != PhoneNumberType.MOBILE && type != PhoneNumberType.FIXED_LINE_OR_MOBILE) return Check.NotMobile
        return Check.Valid(util.format(number, PhoneNumberFormat.E164), util.format(number, PhoneNumberFormat.INTERNATIONAL))
    }
}

/**
 * WhatsApp tarzı numara alanı: solda bayraklı ülke kodu (dokununca aranabilir ülke listesi),
 * sağda o ülkenin düzeninde biçimlenen numara ve anlık doğruluk göstergesi.
 */
@Composable
fun CountryPhoneField(
    region: String,
    onRegion: (String) -> Unit,
    digits: String,
    onDigits: (String) -> Unit,
    check: Phones.Check,
) {
    var picking by remember { mutableStateOf(false) }
    val country = remember(region) { Phones.country(region) }
    val shape = RoundedCornerShape(22.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .clip(shape)
                .background(CardBg)
                .border(1.dp, Line, shape)
                .clickable { picking = true }
                .padding(horizontal = 10.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(country.flag, fontSize = 20.sp)
            Spacer(Modifier.width(4.dp))
            Text("+${country.code}", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = t("Ülke seç"), tint = Muted)
        }
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            // Alanda yalnızca rakamlar tutulur; boşluklar sadece görünümde eklenir.
            // Böylece yazarken imleç kaymaz.
            value = digits,
            visualTransformation = remember(region) { PhoneFormatTransformation(region) },
            onValueChange = { v ->
                val clean = v.filter { it.isDigit() }.take(17)
                // Numara + ile yapıştırıldıysa ülkeyi de oradan al.
                if (v.trim().startsWith("+")) {
                    runCatching { Phones.util.parse(v, "ZZ") }.getOrNull()?.let { n ->
                        Phones.util.getRegionCodeForNumber(n)?.let { r ->
                            onRegion(r)
                            onDigits(Phones.util.getNationalSignificantNumber(n))
                            return@OutlinedTextField
                        }
                    }
                }
                onDigits(clean)
            },
            singleLine = true,
            placeholder = { Text(Phones.example(region), color = Muted, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, color = Ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            shape = shape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardBg,
                unfocusedContainerColor = CardBg,
                unfocusedBorderColor = Line,
                focusedBorderColor = Blue,
            ),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.size(8.dp))
    val (label, ok) = when (check) {
        is Phones.Check.Valid -> t("Geçerli numara") + " · " + check.international to true
        Phones.Check.NotMobile -> t("Bu bir cep numarası değil. SMS alabilen bir numara yaz.") to false
        Phones.Check.Invalid -> tf("Bu numara %s için geçerli değil.", country.name) to false
        Phones.Check.Incomplete -> t("Numarayı tamamla.") to null
        Phones.Check.Empty -> "" to null
    }
    if (label.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ok != null) {
                Icon(
                    if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = if (ok) Color(0xFF2F8F4E) else Color(0xFFD14343),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                color = when (ok) {
                    true -> Color(0xFF2F8F4E)
                    false -> Color(0xFFD14343)
                    null -> Muted
                },
                fontSize = 13.sp,
            )
        }
    }
    if (picking) {
        CountryDialog(current = region, onPick = { onRegion(it); picking = false }, onDismiss = { picking = false })
    }
}

@Composable
private fun CountryDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val all = remember { Phones.countries() }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, all) {
        val q = query.trim().lowercase()
        val digits = q.filter { it.isDigit() }
        if (q.isEmpty()) all
        else all.filter { it.name.lowercase().contains(q) || it.region.lowercase() == q || (digits.isNotEmpty() && it.code.toString().startsWith(digits)) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = CardBg) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(t("Ülke seç"), color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted) },
                    placeholder = { Text(t("Ülke adı ya da kodu"), color = Muted) },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                )
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(shown, key = { it.region }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (c.region == current) ChipBg else Color.Transparent)
                                .clickable { onPick(c.region) }
                                .padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(c.flag, fontSize = 22.sp)
                            Text(c.name, color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text("+${c.code}", color = Muted, fontSize = 15.sp)
                        }
                        HorizontalDivider(color = Line)
                    }
                }
            }
        }
    }
}


/** Numarayı yazarken biçimlendirir (ör. 544 072 27 27); imleç rakamlara göre doğru yerde kalır. */
private class PhoneFormatTransformation(private val region: String) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        val formatted = runCatching { Phones.formatAsYouType(region, raw) }.getOrDefault(raw)
        // Biçimlenmiş metin aynı rakamları aynı sırada içermiyorsa düz göster.
        if (formatted.filter { it.isDigit() } != raw) {
            return androidx.compose.ui.text.input.TransformedText(text, androidx.compose.ui.text.input.OffsetMapping.Identity)
        }
        // digitPos[i] = biçimli metinde i. rakamın konumu
        val digitPos = formatted.indices.filter { formatted[it].isDigit() }
        val mapping = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = when {
                offset <= 0 -> if (digitPos.isEmpty()) 0 else digitPos[0]
                offset >= digitPos.size -> formatted.length
                else -> digitPos[offset - 1] + 1
            }
            override fun transformedToOriginal(offset: Int): Int =
                digitPos.count { it < offset }.coerceIn(0, raw.length)
        }
        return androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(formatted), mapping)
    }
    override fun equals(other: Any?) = other is PhoneFormatTransformation && other.region == region
    override fun hashCode() = region.hashCode()
}
