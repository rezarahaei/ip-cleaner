package com.powerix.ipcleaners

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private val Navy = Color(0xFF102A43)
private val Navy2 = Color(0xFF183F5B)
private val Mint = Color(0xFF16C79A)
private val Amber = Color(0xFFE99A22)
private val Coral = Color(0xFFD9485F)
private val Pale = Color(0xFFF3F8FB)
private val Ink = Color(0xFF183247)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ipcleaners", Context.MODE_PRIVATE)
        setContent {
            var language by remember { mutableStateOf(prefs.getString("language", "") ?: "") }
            var showIntroWarning by remember { mutableStateOf(false) }
            var showAbout by remember { mutableStateOf(false) }
            var showMenu by remember { mutableStateOf(false) }
            var showLanguage by remember { mutableStateOf(false) }
            var showApply by remember { mutableStateOf(false) }
            var showCountryChanged by remember { mutableStateOf(false) }
            var autoSync by remember { mutableStateOf(prefs.getBoolean("autoSync", false)) }
            var oldLanguage by remember { mutableStateOf(Locale.getDefault().toLanguageTag()) }
            var oldZone by remember { mutableStateOf(TimeZone.getDefault().id) }
            var currentIp by remember { mutableStateOf<IpInfo?>(null) }
            var status by remember { mutableStateOf("") }
            var loading by remember { mutableStateOf(false) }
            var speedLoading by remember { mutableStateOf(false) }
            var speedResult by remember { mutableStateOf<SpeedResult?>(null) }
            var speedError by remember { mutableStateOf("") }
            var applyTimer by remember { mutableIntStateOf(0) }
            var applyActive by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val fa = language == "fa"

            // While foregrounded, refresh no more frequently than every two minutes.
            LaunchedEffect(language) {
                if (language.isNotBlank()) {
                    if (previousCountry.isBlank()) refreshIp({ loading = it }, { currentIp = it; status = "" }, { status = it })
                    while (isActive) {
                        delay(120_000)
                        refreshIp({ loading = it }, { info ->
                            val old = currentIp?.countryCode.orEmpty()
                            currentIp = info
                            status = ""
                            if (old.isNotBlank() && info.countryCode.isNotBlank() && old != info.countryCode) showCountryChanged = true
                        }, { status = it })
                    }
                }
            }
            LaunchedEffect(applyActive) {
                if (applyActive) {
                    applyTimer = 0
                    while (applyActive && applyTimer < 60) { delay(1000); applyTimer++ }
                }
            }

            MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Mint, background = Pale)) {
                CompositionLocalProvider(LocalLayoutDirection provides if (fa) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    if (language.isBlank()) {
                        LanguageChooser(
                            onChoose = { chosen ->
                                language = chosen
                                prefs.edit().putString("language", chosen).apply()
                                oldLanguage = Locale.getDefault().toLanguageTag()
                                oldZone = TimeZone.getDefault().id
                                showIntroWarning = true
                            }
                        )
                    } else {
                        Scaffold(containerColor = Pale, topBar = {
                            TopAppBarCustom(
                                fa = fa,
                                onMenu = { showMenu = true },
                                onRefresh = { scope.launch { refreshIp({ loading = it }, { currentIp = it; status = "" }, { status = it }) } },
                                loading = loading
                            )
                        }) { padding ->
                            Column(
                                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                EarthHero(fa)
                                if (showIntroWarning) {
                                    AlertDialog(
                                        onDismissRequest = { showIntroWarning = false },
                                        title = { Text(if (fa) "یادآوری اتصال" else "VPN reminder") },
                                        text = { Text(if (fa) "از روشن بودن VPN خود مطمئن باشید." else "Please make sure your VPN is connected.") },
                                        confirmButton = { TextButton(onClick = { showIntroWarning = false }) { Text(if (fa) "متوجه شدم" else "OK") } }
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    InfoCard(if (fa) "زبان فعلی گوشی" else "Device language", Locale.getDefault().displayName, Modifier.weight(1f), "🌐")
                                    InfoCard(if (fa) "منطقه زمانی گوشی" else "Device time zone", TimeZone.getDefault().id, Modifier.weight(1f), "🕒")
                                }
                                val vpnOn = isVpnActive(this@MainActivity)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    InfoCard(if (fa) "وضعیت VPN" else "VPN status", if (vpnOn) (if (fa) "اتصال VPN فعال است" else "VPN transport active") else (if (fa) "VPN خاموش است" else "VPN appears off"), Modifier.weight(1f), if (vpnOn) "🛡️" else "⚪")
                                    InfoCard(if (fa) "کشور IP" else "IP country", currentIp?.let { "${it.flag} ${it.country}" } ?: if (loading) (if (fa) "در حال بررسی…" else "Checking…") else "—", Modifier.weight(1f), "🌍")
                                }
                                Button(onClick = { refreshIp({ loading = it }, { currentIp = it; status = "" }, { status = it }) }, enabled = !loading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
                                    Text(if (loading) (if (fa) "در حال بررسی اتصال…" else "Checking connection…") else (if (fa) "بررسی کشور VPN" else "Check VPN country"), modifier = Modifier.padding(vertical = 4.dp))
                                }
                                currentIp?.let { IpDetailsCard(it, fa) }
                                if (status.isNotBlank()) WarningCard(status, fa)
                                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(if (fa) "هماهنگ‌سازی تنظیمات" else "Synchronize settings", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink)
                                        Text(if (fa) "کشور شناسایی‌شده: ${currentIp?.country ?: "هنوز مشخص نیست"}. اندروید ممکن است برای تغییر زبان یا منطقه زمانی، تأیید دستی بخواهد." else "Detected country: ${currentIp?.country ?: "Not available"}. Android may require manual confirmation to change language or time zone.", color = Color(0xFF5E7282), fontSize = 13.sp)
                                        Button(onClick = { showApply = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Navy)) {
                                            Text(if (fa) "تغییر زبان و زمان به کشور VPN" else "Match language & time to VPN", fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(onClick = {
                                            applyActive = false
                                            try { startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) } catch (_: Exception) { }
                                            try { startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) } catch (_: Exception) { }
                                        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                            Text(if (fa) "بازگشت / تنظیم دستی گوشی" else "Restore / open device settings")
                                        }
                                        if (applyActive) Text((if (fa) "حالت هماهنگ‌سازی فعال است · " else "Sync session active · ") + "${applyTimer}s", color = Mint, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Navy)) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (fa) "تست سرعت اینترنت" else "Internet speed test", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                            Text("⚡", fontSize = 24.sp)
                                        }
                                        Text(if (fa) "اندازه‌گیری مستقیم با نقطه تست عمومی؛ نتیجه تقریبی است و به مسیر شبکه وابسته است." else "Measured directly against a public test endpoint; results are approximate and route-dependent.", color = Color(0xFFD4E3EE), fontSize = 12.sp)
                                        Button(onClick = {
                                            speedLoading = true; speedError = ""; speedResult = null
                                            scope.launch {
                                                try { speedResult = runSpeedTest(); speedError = "" }
                                                catch (e: Exception) { speedError = e.message ?: if (fa) "تست ناموفق بود" else "Speed test failed" }
                                                speedLoading = false
                                            }
                                        }, enabled = !speedLoading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Navy)) {
                                            Text(if (speedLoading) (if (fa) "در حال اندازه‌گیری…" else "Testing…") else (if (fa) "شروع تست سرعت" else "Start speed test"), fontWeight = FontWeight.Bold)
                                        }
                                        speedResult?.let { result ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                SpeedMetric(if (fa) "دانلود" else "Download", "%.1f".format(Locale.US, result.downloadMbps), "Mbps", Modifier.weight(1f))
                                                SpeedMetric(if (fa) "آپلود" else "Upload", "%.1f".format(Locale.US, result.uploadMbps), "Mbps", Modifier.weight(1f))
                                                SpeedMetric(if (fa) "پینگ" else "Latency", "${result.latencyMs}", "ms", Modifier.weight(1f))
                                            }
                                        }
                                        if (speedError.isNotBlank()) Text(speedError, color = Color(0xFFFFB9C0), fontSize = 12.sp)
                                    }
                                }
                                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(if (fa) "IP & Network Issues" else "IP & Network Issues", fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp)
                                        Text(if (fa) "برای امتیاز IP و جزئیات مشکلاتی مانند ناهماهنگی زبان، منطقه زمانی و DNS، صفحه رسمی IPFighter را باز کنید. در این نسخه امتیاز سایت جعل یا تخمین زده نمی‌شود." else "Open the official IPFighter page for its IP score and issues such as language/time-zone mismatch and DNS. This prototype does not fabricate or estimate the site's score.", color = Color(0xFF5E7282), fontSize = 13.sp)
                                        Button(onClick = { openUrl("https://ipfighter.com/") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(if (fa) "بازکردن IPFighter" else "Open IPFighter") }
                                    }
                                }
                                Text("IP Cleaners · 1.01", color = Color(0xFF8193A0), fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                        }

                        if (showMenu) {
                            ModalNavigationDrawerDummy()
                            AlertDialog(onDismissRequest = { showMenu = false }, title = { Text(if (fa) "منو" else "Menu") }, text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = autoSync, onCheckedChange = { checked -> autoSync = checked; prefs.edit().putBoolean("autoSync", checked).apply() })
                                        Text(if (fa) "یادآوری هماهنگ‌سازی خودکار (در این نسخه نیازمند اقدام دستی است)" else "Auto-sync reminder (manual action required in this version)", fontSize = 14.sp)
                                    }
                                    TextButton(onClick = { showMenu = false; showLanguage = true }) { Text(if (fa) "انتخاب زبان / Language" else "Language / انتخاب زبان") }
                                    TextButton(onClick = { showMenu = false; showAbout = true }) { Text(if (fa) "درباره برنامه" else "About") }
                                }
                            }, confirmButton = { TextButton(onClick = { showMenu = false }) { Text(if (fa) "بستن" else "Close") } })
                        }
                        if (showLanguage) AlertDialog(onDismissRequest = { showLanguage = false }, title = { Text(if (fa) "انتخاب زبان" else "Choose language") }, text = {
                            Column {
                                TextButton(onClick = { language = "fa"; prefs.edit().putString("language", "fa").apply(); showLanguage = false }) { Text("فارسی") }
                                TextButton(onClick = { language = "en"; prefs.edit().putString("language", "en").apply(); showLanguage = false }) { Text("English") }
                            }
                        }, confirmButton = { TextButton(onClick = { showLanguage = false }) { Text(if (fa) "بستن" else "Close") } })
                        if (showAbout) AlertDialog(onDismissRequest = { showAbout = false }, title = { Text("IP Cleaners · 1.01") }, text = {
                            Text(if (fa) "وب‌سایت‌ها و برنامه‌ها ممکن است برای تحلیل اتصال، علاوه بر IP، زبان، منطقه زمانی، DNS و اطلاعات دیگری را بررسی کنند. این برنامه به شناسایی برخی ناسازگاری‌ها و تنظیم موارد قابل‌تغییر کمک می‌کند؛ اما تضمین نمی‌کند همه سرویس‌ها اتصال VPN را تشخیص ندهند. VPNهای رایگان یا نامعتبر ممکن است حریم خصوصی شما را به خطر بیندازند و تغییر زبان یا منطقه زمانی، سابقه نامطلوب IP را پاک نمی‌کند." else "Websites and apps may evaluate language, time zone, DNS and other signals in addition to IP. This app helps identify some mismatches and adjust settings where possible, but cannot guarantee that every service will fail to detect VPN use. Free or untrusted VPNs may put privacy at risk, and changing language or time zone does not clear an IP's reputation history.", fontSize = 14.sp)
                        }, confirmButton = { TextButton(onClick = { showAbout = false }) { Text(if (fa) "متوجه شدم" else "OK") } }, dismissButton = { Text("Reza paydar", color = Mint) })
                        if (showApply) AlertDialog(onDismissRequest = { showApply = false }, title = { Text(if (fa) "پیش از تغییر تنظیمات" else "Before changing settings") }, text = { Text(if (fa) "برنامه زبان و منطقه زمانی پیشنهادی را از روی موقعیت IP نمایش می‌دهد، اما اندروید ممکن است تغییر تنظیمات سیستمی را به‌صورت دستی بخواهد. ادامه می‌دهید؟" else "The app can suggest a language and time zone based on IP location, but Android may require you to change system settings manually. Continue?") }, confirmButton = { TextButton(onClick = {
                            showApply = false
                            applyActive = true
                            try { startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) } catch (_: Exception) { }
                            try { startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) } catch (_: Exception) { }
                        }) { Text(if (fa) "ادامه" else "Continue") } }, dismissButton = { TextButton(onClick = { showApply = false }) { Text(if (fa) "لغو" else "Cancel") } })
                        if (showCountryChanged) AlertDialog(onDismissRequest = { showCountryChanged = false }, title = { Text(if (fa) "کشور IP تغییر کرد" else "IP country changed") }, text = { Text(if (fa) "کشور اتصال شما تغییر کرده است. برای هماهنگ‌سازی زبان و منطقه زمانی، تنظیمات گوشی را باز کنید؟" else "Your connection country changed. Open device settings to review language and time zone?") }, confirmButton = { TextButton(onClick = { showCountryChanged = false; showApply = true }) { Text(if (fa) "بله" else "Yes") } }, dismissButton = { TextButton(onClick = { showCountryChanged = false; try { startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) } catch (_: Exception) {} }) { Text(if (fa) "خیر؛ تنظیمات گوشی" else "No; device settings") } })
                    }
                }
            }
        }
    }

    private fun openUrl(url: String) { try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {} }
}

@Composable private fun LanguageChooser(onChoose: (String) -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy, Navy2, Color(0xFF0B1828)))), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            EarthHero(false, compact = true)
            Text("زبان مورد نظر را انتخاب کنید", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onChoose("fa") }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Navy), shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)) { Text("فارسی", fontSize = 18.sp) }
                Button(onClick = { onChoose("en") }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Navy), shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)) { Text("English", fontSize = 18.sp) }
            }
            Text("IP CLEANERS · 1.01", color = Color(0xFFB5D3E5), fontSize = 12.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable private fun TopAppBarCustom(fa: Boolean, onMenu: () -> Unit, onRefresh: () -> Unit, loading: Boolean) {
    Row(Modifier.fillMaxWidth().background(Navy).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("☰", color = Color.White, fontSize = 25.sp, modifier = Modifier.padding(end = 14.dp).then(Modifier).let { androidx.compose.ui.Modifier.clickableNoRipple(onMenu) })
        Column(Modifier.weight(1f)) { Text("IP CLEANERS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, letterSpacing = 1.sp); Text(if (fa) "تحلیل اتصال و اعتبار IP" else "Connection & IP inspector", color = Color(0xFFBBD2E2), fontSize = 11.sp) }
        TextButton(onClick = onRefresh, enabled = !loading) { Text(if (fa) "نوسازی" else "Refresh", color = Mint) }
    }
}

@Composable private fun EarthHero(fa: Boolean, compact: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Navy, Navy2)), RoundedCornerShape(24.dp)).padding(if (compact) 12.dp else 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(if (compact) 110.dp else 132.dp)) {
            Box(Modifier.size(if (compact) 104.dp else 124.dp).background(Color(0xFF0B243A), CircleShape))
            Text("🌐", fontSize = if (compact) 68.sp else 78.sp)
            Text("•", color = Coral, fontSize = 30.sp, modifier = Modifier.offset(x = 30.dp, y = (-30).dp))
            Text("•", color = Coral, fontSize = 24.sp, modifier = Modifier.offset(x = (-34).dp, y = 25.dp))
        }
        Text(if (fa) "اتصال خود را هوشمندانه بررسی کنید" else "Inspect your connection intelligently", color = Color.White, fontSize = if (compact) 16.sp else 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(if (fa) "بررسی IP · موقعیت · کیفیت اتصال" else "IP · location · connection quality", color = Color(0xFFBBD2E2), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable private fun InfoCard(title: String, value: String, modifier: Modifier = Modifier, emoji: String) {
    Card(modifier = modifier, shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Text(emoji, fontSize = 16.sp); Text(title, color = Color(0xFF657C8D), fontSize = 11.sp, fontWeight = FontWeight.Medium) }
            Text(value, color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
        }
    }
}

@Composable private fun IpDetailsCard(info: IpInfo, fa: Boolean) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(if (fa) "جزئیات اتصال" else "Connection details", fontWeight = FontWeight.Bold, color = Ink, fontSize = 18.sp, modifier = Modifier.weight(1f)); Text(info.flag, fontSize = 28.sp) }
            DetailRow("IP Address", info.ip)
            DetailRow(if (fa) "کشور" else "Country", info.country)
            DetailRow(if (fa) "شهر / منطقه" else "City / region", listOf(info.city, info.region).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "—" })
            DetailRow("ISP", info.isp.ifBlank { "—" })
            DetailRow(if (fa) "کد پستی" else "ZIP / postal code", info.zip.ifBlank { "—" })
            DetailRow(if (fa) "مختصات" else "Coordinates", if (info.lat != null && info.lon != null) "${info.lat}, ${info.lon}" else "—")
            DetailRow(if (fa) "منطقه زمانی IP" else "IP time zone", info.timezone.ifBlank { "—" })
            DetailRow(if (fa) "منبع داده" else "Data source", info.source)
            Text(if (fa) "امتیاز IP و وضعیت DNS/Hosting را فقط از منبعی نمایش دهید که نتیجه واقعی آن را ارائه کند." else "IP score and DNS/hosting status should only be shown when a source provides verified results.", color = Color(0xFF6E8290), fontSize = 12.sp)
        }
    }
}

@Composable private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { Text(label, color = Color(0xFF6E8290), fontSize = 12.sp, modifier = Modifier.weight(0.9f)); Text(value, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.1f), textAlign = TextAlign.End) }
}

@Composable private fun WarningCard(text: String, fa: Boolean) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1D6))) { Text((if (fa) "هشدار: " else "Notice: ") + text, color = Color(0xFF704B0B), fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
}

@Composable private fun SpeedMetric(title: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier.background(Color(0xFF1D4058), RoundedCornerShape(14.dp)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color(0xFFD4E3EE), fontSize = 11.sp)
        Text(value, color = Mint, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        Text(unit, color = Color.White, fontSize = 10.sp)
    }
}

@Composable private fun ModalNavigationDrawerDummy() {}

private fun androidx.compose.ui.Modifier.clickableNoRipple(onClick: () -> Unit): androidx.compose.ui.Modifier = this.clickable { onClick() }

data class IpInfo(
    val ip: String = "", val country: String = "", val countryCode: String = "", val city: String = "", val region: String = "",
    val isp: String = "", val zip: String = "", val lat: Double? = null, val lon: Double? = null, val timezone: String = "", val source: String = "", val flag: String = "🌍"
)
data class SpeedResult(val downloadMbps: Double, val uploadMbps: Double, val latencyMs: Long)

private suspend fun refreshIp(setLoading: (Boolean) -> Unit, onSuccess: (IpInfo) -> Unit, onError: (String) -> Unit) {
    setLoading(true)
    try {
        val result = withContext(Dispatchers.IO) { fetchIpInfo() }
        onSuccess(result)
    } catch (e: Exception) {
        onError(e.message ?: "Could not retrieve IP details")
    } finally { setLoading(false) }
}

private fun fetchIpInfo(): IpInfo {
    val endpoints = listOf(
        "https://ipwho.is/",
        "https://ipapi.co/json/"
    )
    var lastError: Exception? = null
    for (endpoint in endpoints) {
        try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 6000; requestMethod = "GET"; setRequestProperty("User-Agent", "IPCleaners/1.01 Android") }
            try {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(body)
                val isIpWho = j.has("success") || j.has("ip") && j.has("connection")
                val ip = j.optString("ip", j.optString("query", ""))
                val country = j.optString("country", j.optString("country_name", ""))
                val cc = j.optString("country_code", j.optString("countryCode", j.optString("country", "")))
                val city = j.optString("city", "")
                val region = j.optString("region", j.optString("regionName", ""))
                val connection = j.optJSONObject("connection")
                val isp = connection?.optString("isp", "") ?: j.optString("org", j.optString("isp", ""))
                val zip = j.optString("postal", j.optString("zip", ""))
                val lat = if (j.has("latitude")) j.optDouble("latitude") else if (j.has("lat")) j.optDouble("lat") else null
                val lon = if (j.has("longitude")) j.optDouble("longitude") else if (j.has("lon")) j.optDouble("lon") else null
                val timezone = j.optJSONObject("timezone")?.optString("id", "") ?: j.optString("timezone", "")
                if (ip.isNotBlank() && country.isNotBlank()) return IpInfo(ip, country, cc, city, region, isp, zip, lat, lon, timezone, URL(endpoint).host, countryFlag(cc))
                if (isIpWho && j.optBoolean("success", true).not()) throw IllegalStateException(j.optString("message", "Lookup failed"))
            } finally { conn.disconnect() }
        } catch (e: Exception) { lastError = e }
    }
    throw IllegalStateException(lastError?.message ?: "All IP lookup services failed")
}

private fun countryFlag(countryCode: String): String {
    if (countryCode.length != 2) return "🌍"
    val base = 0x1F1E6
    return String(Character.toChars(base + (countryCode.uppercase()[0] - 'A'))) + String(Character.toChars(base + (countryCode.uppercase()[1] - 'A')))
}

private fun isVpnActive(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } catch (_: Exception) { false }
}

private suspend fun runSpeedTest(): SpeedResult = withContext(Dispatchers.IO) {
    val latencyStart = System.nanoTime()
    val pingConn = (URL("https://speed.cloudflare.com/__down?bytes=1").openConnection() as HttpURLConnection).apply { connectTimeout = 7000; readTimeout = 7000; requestMethod = "GET" }
    try { pingConn.inputStream.use { it.read() } } finally { pingConn.disconnect() }
    val latency = ((System.nanoTime() - latencyStart) / 1_000_000.0).roundToInt().toLong()

    val downloadBytes = 8_000_000
    val dStart = System.nanoTime()
    val dConn = (URL("https://speed.cloudflare.com/__down?bytes=$downloadBytes").openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 20000; requestMethod = "GET" }
    var received = 0L
    try { dConn.inputStream.use { input -> val buffer = ByteArray(32 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; received += n } } } finally { dConn.disconnect() }
    val dSeconds = ((System.nanoTime() - dStart).toDouble() / 1e9).coerceAtLeast(0.001)
    val downMbps = received * 8.0 / dSeconds / 1_000_000.0

    val uploadBytes = 2_000_000
    val payload = ByteArray(uploadBytes) { (it % 251).toByte() }
    val uStart = System.nanoTime()
    val uConn = (URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection).apply {
        connectTimeout = 10000; readTimeout = 20000; requestMethod = "POST"; doOutput = true
        setRequestProperty("Content-Type", "application/octet-stream"); setFixedLengthStreamingMode(uploadBytes)
    }
    try { uConn.outputStream.use { out -> out.write(payload) }; val code = uConn.responseCode; if (code !in 200..299) throw IllegalStateException("Upload endpoint returned HTTP $code") } finally { uConn.disconnect() }
    val uSeconds = ((System.nanoTime() - uStart).toDouble() / 1e9).coerceAtLeast(0.001)
    val upMbps = uploadBytes * 8.0 / uSeconds / 1_000_000.0
    SpeedResult(downMbps, upMbps, latency)
}
