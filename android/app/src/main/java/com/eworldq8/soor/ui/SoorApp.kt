package com.eworldq8.soor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.eworldq8.soor.R
import com.eworldq8.soor.engine.Finding
import com.eworldq8.soor.engine.Knowledge
import com.eworldq8.soor.engine.Lang
import com.eworldq8.soor.engine.Severity
import com.eworldq8.soor.engine.SoorEngine
import com.eworldq8.soor.engine.SoorReport
import com.eworldq8.soor.scan.DeviceInfo
import com.eworldq8.soor.scan.DeviceKind
import com.eworldq8.soor.scan.DeviceKinds
import com.eworldq8.soor.scan.LocalNet
import com.eworldq8.soor.scan.Phase
import com.eworldq8.soor.scan.ScanEvent
import com.eworldq8.soor.scan.ScanState
import com.eworldq8.soor.scan.ScanUiState
import com.eworldq8.soor.scan.ScanViewModel
import com.eworldq8.soor.scan.Words

// The screens. Everything here is stateless below SoorApp, so the same screens
// can be rendered and photographed in tests with sample data.

private fun t(ar: Boolean, a: String, e: String) = if (ar) a else e
private fun ltr(s: String) = "\u2066$s\u2069"
private fun hostPort(f: Finding) = if (f.port > 0) "${f.host}:${f.port}" else f.host
private fun nameOf(d: DeviceInfo, ar: Boolean) = d.name ?: DeviceKinds.label(d.kind, ar)
private fun firstSentence(s: String): String {
    val i = s.indexOfFirst { it == '.' || it == '؟' || it == '!' }
    return if (i in 1 until s.length - 1) s.substring(0, i + 1) else s
}
private fun portName(p: Int, ar: Boolean): String = when (p) {
    443, 8443 -> t(ar, "ويب مشفّر", "Encrypted web")
    62078 -> t(ar, "خدمة أجهزة Apple", "Apple device service")
    7000 -> "AirPlay"
    8008, 8009 -> "Chromecast"
    9100 -> t(ar, "طباعة مباشرة", "Raw printing")
    631 -> t(ar, "طباعة IPP", "IPP printing")
    548 -> t(ar, "مشاركة ملفات Apple", "Apple file sharing")
    else -> t(ar, "خدمة", "Service")
}
private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(Locale.ROOT, s / 60, s % 60)
}
private fun when_(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))
private fun eventText(e: ScanEvent, ar: Boolean): String {
    val ip = e.ip?.let { ltr(it) } ?: ""
    return when (e.kind) {
        "start" -> t(ar, "بدأ الفحص", "Scan started")
        "announce" -> t(ar, "يسأل الأجهزة أن تعلن عن نفسها", "Asking devices to announce themselves")
        "announced" -> t(ar, "أعلن $ip عن نفسه", "$ip announced itself")
        "router" -> t(ar, "يقرأ جدول المنافذ في الراوتر", "Reading the router's port table")
        "upnp-on" -> t(ar, "UPnP مفعّل في الراوتر", "UPnP is on at the router")
        "upnp-off" -> t(ar, "UPnP معطّل في الراوتر", "UPnP is off at the router")
        "found" -> t(ar, "عُثر على $ip", "Found $ip")
        "probe" -> t(ar, "يفحص منافذ $ip", "Checking the ports of $ip")
        "judge" -> t(ar, "المحرك يرتّب ما وجده", "The engine ranks what it found")
        "stop" -> t(ar, "أوقفتَ الفحص", "You stopped the scan")
        else -> e.kind
    }
}
private fun radarDot(ip: String): RadarDot {
    val o = ip.substringAfterLast('.').toIntOrNull() ?: 0
    return RadarDot(angle = (o * 137.508f) % 360f, radius = 0.3f + (o % 7) * 0.09f, color = Theme.signal)
}

sealed class Sheet {
    data object About : Sheet()
    data class OfFinding(val finding: Finding) : Sheet()
    data class OfDevice(val device: DeviceInfo) : Sheet()
}

@Composable
fun SoorApp(vm: ScanViewModel) {
    val ui by vm.ui.collectAsState()
    val lang by vm.lang.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.syncLang(context) }
    SoorScreen(
        ui = ui, lang = lang, knowledge = vm.knowledge,
        onScan = { vm.start() },
        onToggleLang = { vm.switchLang(context) },
        onShare = {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, vm.reportText()) }
            context.startActivity(Intent.createChooser(send, null))
        },
        onForget = { vm.forgetDevices() },
        onOpen = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        onStop = { vm.stop() },
        onHome = { vm.home() },
        onResults = { vm.showResults() },
    )
}

@Composable
fun SoorScreen(
    ui: ScanUiState, lang: Lang, knowledge: Knowledge,
    onScan: () -> Unit, onToggleLang: () -> Unit, onShare: () -> Unit,
    onForget: () -> Unit, onOpen: (String) -> Unit, initialSheet: Sheet? = null,
    onStop: () -> Unit = {}, onHome: () -> Unit = {}, onResults: () -> Unit = {},
) {
    val ar = lang == Lang.AR
    var sheet by remember { mutableStateOf(initialSheet) }
    // the phone's back button stops a running scan, and leaves the results for the start screen
    BackHandler(enabled = ui.state == ScanState.SCANNING || ui.state == ScanState.DONE) {
        if (ui.state == ScanState.SCANNING) onStop() else onHome()
    }
    CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Theme.bg)
            drawRect(Brush.radialGradient(listOf(Theme.navy.copy(alpha = 0.40f), Color.Transparent),
                center = Offset(size.width / 2f, 0f), radius = size.width * 1.05f))
        }) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopBar(ar, onToggleLang) { sheet = Sheet.About }
                when (ui.state) {
                    ScanState.SCANNING -> Scanning(ui, ar, onStop)
                    ScanState.DONE -> Results(ui, lang, knowledge, onScan, onShare,
                        onFinding = { sheet = Sheet.OfFinding(it) }, onDevice = { sheet = Sheet.OfDevice(it) })
                    else -> Home(ui, ar, onScan, onResults)
                }
            }
        }
        when (val s = sheet) {
            is Sheet.About -> AboutSheet(ar, onDismiss = { sheet = null }, onForget = onForget, onOpen = onOpen)
            is Sheet.OfFinding -> FindingSheet(s.finding, ui.devices.firstOrNull { it.ip == s.finding.host }, knowledge, lang) { sheet = null }
            is Sheet.OfDevice -> DeviceSheet(s.device, ui.findings.filter { it.host == s.device.ip }, knowledge, lang,
                onFinding = { sheet = Sheet.OfFinding(it) }) { sheet = null }
            null -> {}
        }
    }
}

@Composable private fun V(h: Int) = Spacer(Modifier.height(h.dp))
@Composable private fun H(w: Int) = Spacer(Modifier.width(w.dp))

@Composable
private fun TopBar(ar: Boolean, onToggleLang: () -> Unit, onAbout: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.soor_mark), null, Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)))
        H(10)
        Text(t(ar, "سُور", "Soor"), color = Theme.ink, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Spacer(Modifier.weight(1f))
        Pill(t(ar, "English", "العربية"), onToggleLang)
        H(4)
        IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, t(ar, "عن سُور", "About Soor"), tint = Theme.ink2) }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, Theme.rule, RoundedCornerShape(50))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(text, color = Theme.ink2, fontSize = 13.sp)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    Box(modifier.height(56.dp).clip(RoundedCornerShape(16.dp))
        .background(Brush.horizontalGradient(listOf(Theme.navy, Theme.navyBright)))
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Theme.rule, RoundedCornerShape(16.dp))
        .background(Theme.panel).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) { Icon(icon, null, tint = Theme.ink2, modifier = Modifier.size(20.dp)); H(8) }
        Text(text, color = Theme.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionTitle(text: String, count: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Theme.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (count != null) {
            H(8)
            Box(Modifier.clip(RoundedCornerShape(50)).background(Theme.panel2).padding(horizontal = 9.dp, vertical = 2.dp)) {
                Text("$count", color = Theme.ink2, fontSize = 12.sp, fontFamily = PlexMono)
            }
        }
    }
}

@Composable
private fun Mono(text: String, color: Color = Theme.ink2, size: TextUnit = 12.5.sp) =
    Text(ltr(text), color = color, fontSize = size, fontFamily = PlexMono)

@Composable
private fun SevChip(label: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(label, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Badge(label: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Theme.signal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Note(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Theme.navy.copy(alpha = 0.14f))
        .border(1.dp, Theme.navy.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, tint = Theme.navyLite, modifier = Modifier.size(20.dp))
        H(12)
        Text(text, color = Theme.ink2, fontSize = 13.5.sp, lineHeight = 21.sp)
    }
}

// ---- home ----

@Composable
private fun Home(ui: ScanUiState, ar: Boolean, onScan: () -> Unit, onResults: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
            Radar(Modifier.fillMaxSize(), sweeping = false)
            Image(painterResource(R.drawable.soor_mark), null,
                Modifier.size(108.dp).clip(RoundedCornerShape(30.dp)).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(30.dp)))
        }
        Text(t(ar, "افحص شبكة بيتك", "Scan your home network"), color = Theme.ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 38.sp)
        V(8)
        Text(t(ar, "اعرف كل جهاز عليها وما يفتحه من منافذ، وما هو مكشوف منها للإنترنت",
                  "See every device on it, the ports each one opens, and what is exposed to the internet"),
            color = Theme.ink2, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
        V(24)
        PrimaryButton(t(ar, "افحص شبكتي", "Scan my network"), onScan)
        V(10)
        Text(t(ar, "يعمل على جهازك، ولا يجمع أي بيانات", "Runs on your phone and collects no data"), color = Theme.ink3, fontSize = 12.5.sp)
        if (ui.state == ScanState.NO_NETWORK) { V(16); NoNetwork(ar) }
        if (ui.lastScan != null) { V(18); LastScanCard(ui, ar, onResults) }
        V(26)
        SectionTitle(t(ar, "ماذا يفحص", "What it checks"))
        FeatureRow(DeviceKind.COMPUTER, t(ar, "كل جهاز على شبكتك", "Every device on your network"), t(ar, "وما يفتحه من منافذ وخدمات", "and the ports and services it opens"))
        FeatureRow(DeviceKind.ROUTER, t(ar, "ما هو مكشوف للإنترنت", "What is exposed to the internet"), t(ar, "من جدول المنافذ الممرَّرة في الراوتر", "from the router's forwarded ports"))
        FeatureRow(DeviceKind.CAMERA, t(ar, "الكاميرات", "Cameras"), t(ar, "ويحذّرك من المكشوف منها للإنترنت", "warning you about any exposed to the internet"))
        FeatureRow(DeviceKind.UNKNOWN, t(ar, "الأجهزة الجديدة", "New devices"), t(ar, "حين يظهر على شبكتك جهاز لأول مرة", "when one appears on your network for the first time"))
        V(16)
        PrivacyNote(ar)
        V(24)
    }
}

@Composable
private fun LastScanCard(ui: ScanUiState, ar: Boolean, onResults: () -> Unit) {
    val tone = toneOf(ui.findings)
    val col = when (tone) { Tone.CRIT -> Theme.crit; Tone.HIGH -> Theme.high; Tone.MED -> Theme.med; Tone.OK -> Theme.ok }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.panel).border(1.dp, Theme.rule, RoundedCornerShape(16.dp))
        .clickable(role = Role.Button, onClick = onResults).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(col))
            H(8)
            Text(t(ar, "آخر فحص", "Last scan"), color = Theme.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Mono(when_(ui.lastScan ?: 0L), Theme.ink3)
        }
        V(6)
        Text(Words.found(ui.devices.size, ar) + t(ar, "، ", ", ") + t(ar, "${ui.findings.size} ملاحظة", "${ui.findings.size} findings"),
            color = Theme.ink2, fontSize = 13.sp)
        V(8)
        Text(t(ar, "عرض النتائج", "Show the results"), color = Theme.navyLite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FeatureRow(kind: DeviceKind, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Theme.panel)
        .border(1.dp, Theme.rule, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Theme.navy.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
            DeviceGlyph(kind, Theme.signal, Modifier.size(28.dp))
        }
        H(14)
        Column { Text(title, color = Theme.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text(sub, color = Theme.ink2, fontSize = 13.sp) }
    }
}

@Composable
private fun PrivacyNote(ar: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.navy.copy(alpha = 0.14f))
        .border(1.dp, Theme.navy.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, null, tint = Theme.signal, modifier = Modifier.size(20.dp))
        H(12)
        Text(t(ar, "لا يتصل سُور إلا بعناوين داخل شبكة بيتك، ولا حساب فيه ولا خادم، ولا يخرج بشيء عنك",
                  "Soor only connects to addresses inside your home network. No account, no server, and nothing about you leaves the phone."),
            color = Theme.ink2, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun NoNetwork(ar: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.high.copy(alpha = 0.10f))
        .border(1.dp, Theme.high.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(14.dp)) {
        Icon(Icons.Outlined.Warning, null, tint = Theme.high, modifier = Modifier.size(22.dp))
        H(12)
        Column {
            Text(t(ar, "لست على شبكة واي فاي", "You are not on Wi-Fi"), color = Theme.ink, fontWeight = FontWeight.SemiBold)
            Text(t(ar, "اتصل بشبكة الواي فاي في بيتك ثم افحص، فسُور يفحص الشبكة المتصل بها فقط.",
                      "Connect to your home Wi-Fi and scan again. Soor only scans the network you are on."),
                color = Theme.ink2, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

// ---- scanning ----

@Composable
private fun Scanning(ui: ScanUiState, ar: Boolean, onStop: () -> Unit) {
    val still = LocalStill.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (!still) LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val elapsed = now - (ui.startedAt ?: now)
    Column(Modifier.fillMaxSize()) {
    // everything scrolls except the stop button, which stays within reach
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        V(4)
        Stepper(ui.phase, ar)
        V(10)
        ui.network?.let { net ->
            Text(t(ar, "الشبكة ", "Network ") + ltr(net.prefix + ".x") + (net.gateway?.let { "  ·  " + t(ar, "الراوتر ", "Router ") + ltr(it) } ?: ""),
                color = Theme.ink3, fontSize = 12.5.sp, fontFamily = PlexMono)
        }
        V(4)
        Box(Modifier.fillMaxWidth(0.62f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            Radar(Modifier.fillMaxSize(), sweeping = true, dots = ui.liveHosts.map { radarDot(it) })
        }
        V(6)
        val title = when (ui.phase) {
            Phase.DISCOVER -> t(ar, "البحث عن الأجهزة", "Finding devices")
            Phase.PROBE -> t(ar, "فحص المنافذ", "Checking ports")
            Phase.JUDGE -> t(ar, "ترتيب النتائج", "Ranking the findings")
        }
        val sub = when (ui.phase) {
            Phase.DISCOVER -> t(ar, "يسأل كل عنوان في شبكتك هل فيه جهاز", "Asking every address on your network whether a device is there")
            Phase.PROBE -> t(ar, "يقرأ ما تعلنه خدمات كل جهاز عن نفسها", "Reading what each device's services announce about themselves")
            Phase.JUDGE -> t(ar, "يرتّب المحرك ما وجده بحسب الخطورة", "The engine ranks what it found by severity")
        }
        Text(title, color = Theme.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        V(4)
        Text(sub, color = Theme.ink2, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
        V(16)
        LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = Theme.signal, trackColor = Theme.panel2)
        V(14)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("${ui.liveHosts.size}", t(ar, "الأجهزة", "Devices"), Modifier.weight(1f))
            Stat("${ui.portsChecked}", t(ar, "المنافذ", "Ports"), Modifier.weight(1f))
            Stat(clock(elapsed), t(ar, "الوقت", "Elapsed"), Modifier.weight(1f))
        }
        V(14)
        LiveFeed(ui.events, ar)
        V(16)
    }
    Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp, top = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
            .border(1.dp, Theme.crit.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).background(Theme.crit.copy(alpha = 0.08f))
            .clickable(role = Role.Button, enabled = !ui.stopping, onClick = onStop),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Close, null, tint = Theme.crit, modifier = Modifier.size(20.dp))
            H(8)
            Text(if (ui.stopping) t(ar, "جارٍ الإيقاف، ويُحفظ ما وُجد", "Stopping, keeping what was found") else t(ar, "أوقف الفحص", "Stop the scan"),
                color = Theme.crit, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Theme.panel).border(1.dp, Theme.rule, RoundedCornerShape(16.dp)).padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ltr(value), color = Theme.signal, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = PlexMono)
        Text(label, color = Theme.ink2, fontSize = 12.sp)
    }
}

@Composable
private fun Stepper(phase: Phase, ar: Boolean) {
    val steps = listOf(Phase.DISCOVER to t(ar, "اكتشاف", "Discover"), Phase.PROBE to t(ar, "المنافذ", "Ports"), Phase.JUDGE to t(ar, "الحكم", "Verdict"))
    val current = steps.indexOfFirst { it.first == phase }
    Row(Modifier.fillMaxWidth(0.9f), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, (_, label) ->
            val done = i < current
            val active = i == current
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(26.dp).clip(CircleShape)
                    .background(if (done) Theme.signal else if (active) Theme.navy else Theme.panel2)
                    .border(1.5.dp, if (active) Theme.signal else if (done) Theme.signal else Theme.rule, CircleShape), contentAlignment = Alignment.Center) {
                    if (done) Icon(Icons.Outlined.CheckCircle, null, tint = Theme.bg, modifier = Modifier.size(16.dp))
                    else Text("${i + 1}", color = if (active) Theme.ink else Theme.ink3, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = PlexMono)
                }
                V(4)
                Text(label, color = if (active) Theme.ink else Theme.ink3, fontSize = 11.5.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
            if (i < steps.size - 1) Box(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 0.dp).padding(bottom = 18.dp).height(2.dp)
                .background(if (i < current) Theme.signal else Theme.rule))
        }
    }
}

@Composable
private fun LiveFeed(events: List<ScanEvent>, ar: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.panel).border(1.dp, Theme.rule, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Label(t(ar, "ماذا يجري الآن", "What is happening now"))
        V(6)
        val shown = events.takeLast(5)
        if (shown.isEmpty()) Text(t(ar, "يبدأ...", "Starting..."), color = Theme.ink3, fontSize = 13.sp)
        shown.forEachIndexed { i, e ->
            val last = i == shown.size - 1
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (last) Theme.signal else Theme.ink3))
                H(10)
                Text(eventText(e, ar), color = if (last) Theme.ink else Theme.ink3, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- results ----

private enum class Tone { CRIT, HIGH, MED, OK }

private fun toneOf(fs: List<Finding>): Tone = when {
    fs.any { it.exposed } -> Tone.CRIT
    fs.any { it.severity == Severity.CRITICAL || it.kind == "open-auth" || it.reason == "no-auth" } -> Tone.HIGH
    fs.any { it.severity == Severity.HIGH || it.severity == Severity.MEDIUM } -> Tone.MED
    else -> Tone.OK
}

@Composable
private fun Results(
    ui: ScanUiState, lang: Lang, knowledge: Knowledge, onScan: () -> Unit, onShare: () -> Unit,
    onFinding: (Finding) -> Unit, onDevice: (DeviceInfo) -> Unit,
) {
    val ar = lang == Lang.AR
    LazyColumn(Modifier.fillMaxSize().testTag("results"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { StatusBanner(ui, ar) }
        item { SeverityPills(ui.findings, lang) }
        if (ui.findings.isNotEmpty()) {
            item { SectionTitle(t(ar, "ما يستحق انتباهك", "Worth your attention"), ui.findings.size) }
            items(ui.findings, key = { "${it.kind}|${it.host}|${it.port}" }) { f ->
                FindingCard(f, ui.devices.firstOrNull { it.ip == f.host }, knowledge, lang) { onFinding(f) }
            }
        } else {
            item { CleanCard(ar) }
        }
        item { SectionTitle(t(ar, "أجهزة شبكتك", "Devices on your network"), ui.devices.size) }
        items(ui.devices, key = { it.ip }) { d -> DeviceRow(d, ar) { onDevice(d) } }
        if (ui.partial) item {
            Note(t(ar, "توقف الفحص قبل اكتماله، فالنتائج أعلاه عمّا وصل إليه فقط، وافحص من جديد لترى الشبكة كلها.",
                      "The scan was stopped before it finished, so the results above cover only what it reached. Scan again to see the whole network."))
        }
        if (ui.firstScan) item {
            Note(t(ar, "هذا أول فحص لهذه الشبكة، فحفظ سُور في هاتفك وحده قائمة أجهزتها ليخبرك في المرات القادمة بأي جهاز جديد يظهر عليها.",
                      "This is the first scan of this network, so Soor kept its device list on this phone only, to tell you next time about any new device."))
        }
        item { ScanDetails(ui, ar) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(t(ar, "شارك التقرير", "Share report"), Icons.Outlined.Share, onShare, Modifier.weight(1f))
                PrimaryButton(t(ar, "افحص من جديد", "Scan again"), onScan, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScanDetails(ui: ScanUiState, ar: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.panel).border(1.dp, Theme.rule, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Label(t(ar, "تفاصيل الفحص", "Scan details"))
        V(6)
        val rows = listOfNotNull(
            ui.network?.let { t(ar, "الشبكة", "Network") to ltr(it.prefix + ".x") },
            ui.network?.gateway?.let { t(ar, "الراوتر", "Router") to ltr(it) },
            t(ar, "الأجهزة", "Devices") to ltr("${ui.devices.size}"),
            t(ar, "المنافذ المفحوصة", "Ports checked") to ltr("${ui.portsChecked}"),
            ui.durationMs?.let { t(ar, "المدة", "Duration") to ltr(clock(it)) },
            ui.lastScan?.let { t(ar, "الوقت", "Time") to ltr(when_(it)) },
        )
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(k, color = Theme.ink2, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Text(v, color = Theme.ink, fontSize = 13.5.sp, fontFamily = PlexMono)
            }
        }
    }
}

@Composable
private fun StatusBanner(ui: ScanUiState, ar: Boolean) {
    val tone = toneOf(ui.findings)
    val col = when (tone) { Tone.CRIT -> Theme.crit; Tone.HIGH -> Theme.high; Tone.MED -> Theme.med; Tone.OK -> Theme.ok }
    val title = when (tone) {
        Tone.CRIT -> t(ar, "في السور ثغرة، وبيتك مرئي من الإنترنت", "There is a breach in the wall, your home is visible from the internet")
        Tone.HIGH -> t(ar, "السور سليم، لكنّ في الداخل ما يحتاج معالجة الآن", "The wall holds, but something inside needs attention now")
        Tone.MED -> t(ar, "السور سليم، وبقيت أمور تستحق المراجعة", "The wall holds, a few things are worth reviewing")
        Tone.OK -> t(ar, "السور سليم ولا شيء يستحق القلق", "The wall holds and nothing needs worrying about")
    }
    val newCount = ui.devices.count { it.isNew }
    val sub = Words.found(ui.devices.size, ar) + if (newCount > 0) t(ar, "، ", ", ") + Words.newOnes(newCount, ar) else ""
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(Brush.verticalGradient(listOf(col.copy(alpha = 0.18f), Theme.panel)))
        .border(1.dp, col.copy(alpha = 0.45f), RoundedCornerShape(20.dp)).padding(18.dp)) {
        WallStrip(tone == Tone.CRIT, if (tone == Tone.CRIT) Theme.ink.copy(alpha = 0.85f) else col.copy(alpha = 0.9f), Modifier.fillMaxWidth().height(18.dp))
        V(14)
        Text(title, color = Theme.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
        V(6)
        Text(sub, color = Theme.ink2, fontSize = 14.sp, lineHeight = 22.sp)
    }
}

@Composable
private fun SeverityPills(fs: List<Finding>, lang: Lang) {
    val counts = SoorEngine.summarise(fs)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.INFO).forEach { s ->
            val n = (counts[s] ?: 0) + if (s == Severity.INFO) (counts[Severity.LOW] ?: 0) else 0
            if (n > 0) {
                val c = Theme.severity(s)
                Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("$n  ${SoorReport.severityLabel(s, lang)}", color = c, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun CleanCard(ar: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.panel).border(1.dp, Theme.ok.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CheckCircle, null, tint = Theme.ok, modifier = Modifier.size(22.dp))
        H(12)
        Text(t(ar, "لم يجد سُور منفذًا خطرًا ولا جهازًا مكشوفًا", "No risky port and no exposed device"), color = Theme.ink, fontSize = 15.sp)
    }
}

@Composable
private fun FindingCard(f: Finding, device: DeviceInfo?, k: Knowledge, lang: Lang, onClick: () -> Unit) {
    val r = SoorReport.render(f, k, lang)
    val col = Theme.severity(f.severity)
    val ar = lang == Lang.AR
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(16.dp)).background(Theme.panel)
        .border(1.dp, Theme.rule, RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(col))
        Column(Modifier.weight(1f).padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                SevChip(r.severityLabel, col)
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Mono(hostPort(f))
                    if (device != null) Text(nameOf(device, ar), color = Theme.ink3, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            V(8)
            Text(r.title, color = Theme.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
            if (r.detail.isNotEmpty()) {
                V(4)
                Text(firstSentence(r.detail), color = Theme.ink2, fontSize = 13.5.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DeviceRow(d: DeviceInfo, ar: Boolean, onClick: () -> Unit) {
    val col = Theme.severity(d.worst)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.panel).border(1.dp, Theme.rule, RoundedCornerShape(16.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Theme.bg2).border(2.dp, col, CircleShape), contentAlignment = Alignment.Center) {
            DeviceGlyph(d.kind, Theme.ink, Modifier.size(26.dp))
        }
        H(12)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nameOf(d, ar), color = Theme.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (d.isNew) { H(8); Badge(t(ar, "جديد", "New"), Theme.signal) }
            }
            Mono(d.ip)
            if (d.ports.isNotEmpty()) Text(ltr(d.ports.joinToString(" · ")), color = Theme.ink3, fontSize = 12.sp, fontFamily = PlexMono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(t(ar, "لا منافذ مفتوحة", "No open ports"), color = Theme.ink3, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DeviceMini(d: DeviceInfo, ar: Boolean) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.bg2).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Theme.panel).border(1.5.dp, Theme.severity(d.worst), CircleShape), contentAlignment = Alignment.Center) {
            DeviceGlyph(d.kind, Theme.ink, Modifier.size(20.dp))
        }
        H(10)
        Column { Text(nameOf(d, ar), color = Theme.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium); Mono(d.ip) }
    }
}

@Composable
private fun FixBox(ar: Boolean, fix: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Theme.navy.copy(alpha = 0.22f))
        .border(1.dp, Theme.navyLite.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = Theme.ok, modifier = Modifier.size(18.dp))
            H(8)
            Text(t(ar, "الحل", "How to fix it"), color = Theme.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        V(6)
        Text(fix, color = Theme.ink, fontSize = 15.sp, lineHeight = 25.sp)
    }
}

// ---- sheets ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindingSheet(f: Finding, device: DeviceInfo?, k: Knowledge, lang: Lang, onDismiss: () -> Unit) {
    val r = SoorReport.render(f, k, lang)
    val ar = lang == Lang.AR
    val col = Theme.severity(f.severity)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Theme.panel, dragHandle = { BottomSheetDefaults.DragHandle(color = Theme.rule) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { SevChip(r.severityLabel, col); Spacer(Modifier.weight(1f)); Mono(hostPort(f)) }
            V(12)
            Text(r.title, color = Theme.ink, fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
            if (device != null) { V(12); DeviceMini(device, ar) }
            V(16)
            Label(t(ar, "ما الذي يعنيه هذا", "What this means"))
            V(4)
            Text(r.detail, color = Theme.ink2, fontSize = 15.sp, lineHeight = 25.sp)
            if (r.fix.isNotEmpty()) { V(16); FixBox(ar, r.fix) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(d: DeviceInfo, fs: List<Finding>, k: Knowledge, lang: Lang, onFinding: (Finding) -> Unit, onDismiss: () -> Unit) {
    val ar = lang == Lang.AR
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Theme.panel, dragHandle = { BottomSheetDefaults.DragHandle(color = Theme.rule) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(CircleShape).background(Theme.bg2).border(2.dp, Theme.severity(d.worst), CircleShape), contentAlignment = Alignment.Center) {
                    DeviceGlyph(d.kind, Theme.ink, Modifier.size(32.dp))
                }
                H(14)
                Column(Modifier.weight(1f)) {
                    Text(nameOf(d, ar), color = Theme.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (d.name != null) Text(DeviceKinds.label(d.kind, ar), color = Theme.ink2, fontSize = 13.sp)
                    Mono(d.ip)
                }
                if (d.isNew) Badge(t(ar, "جديد", "New"), Theme.signal)
            }
            if (d.isNew) { V(12); Note(t(ar, "لم يرَ سُور هذا الجهاز على شبكتك من قبل، فتحقّق أنك تعرفه.", "Soor has not seen this device on your network before, so check that you know it.")) }
            V(18)
            Label(t(ar, "المنافذ المفتوحة", "Open ports"))
            V(6)
            if (d.ports.isEmpty()) Text(t(ar, "لم يفتح هذا الجهاز أي منفذ من المنافذ التي يفحصها سُور.", "This device opened none of the ports Soor checks."), color = Theme.ink2, fontSize = 14.sp, lineHeight = 22.sp)
            d.ports.forEach { p ->
                val svc = k.services.firstOrNull { p in it.ports }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Theme.bg2).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(64.dp)) { Mono("$p", Theme.ink, 14.sp) }
                    Text(svc?.name?.text(lang) ?: portName(p, ar), color = Theme.ink2, fontSize = 14.sp)
                }
            }
            V(18)
            Label(t(ar, "ما وجده سُور في هذا الجهاز", "What Soor found on this device"))
            V(6)
            if (fs.isEmpty()) Text(t(ar, "لا شيء يستحق القلق في هذا الجهاز.", "Nothing to worry about on this device."), color = Theme.ink2, fontSize = 14.sp)
            fs.forEach { f ->
                val r = SoorReport.render(f, k, lang)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Theme.bg2)
                    .clickable(role = Role.Button) { onFinding(f) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SevChip(r.severityLabel, Theme.severity(f.severity)); H(10)
                    Text(r.title, color = Theme.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSheet(ar: Boolean, onDismiss: () -> Unit, onForget: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    var forgotten by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Theme.panel, dragHandle = { BottomSheetDefaults.DragHandle(color = Theme.rule) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.soor_mark), null, Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)))
                H(14)
                Column {
                    Text(t(ar, "سُور", "Soor"), color = Theme.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(t(ar, "فاحص أمان شبكة البيت", "Home network security scanner") + if (version.isNotEmpty()) "  ·  ${ltr(version)}" else "", color = Theme.ink2, fontSize = 13.sp)
                }
            }
            V(16)
            Text(t(ar, "سُور يفحص شبكة بيتك كما يفحصها مختبِر الاختراق، فيجد كل جهاز متصل بها ويقرأ ما يفتحه من منافذ، ويميّز المكشوف منها إلى الإنترنت من الآمن داخل الشبكة، ثم يرتّب ما وجده بحسب الخطورة مع خطوة إصلاح لكل اكتشاف.",
                      "Soor scans your home network the way a penetration tester would. It finds every connected device and reads the ports it leaves open, tells what is exposed to the internet from what is safe inside, then ranks what it found by severity with a fix for each."),
                color = Theme.ink2, fontSize = 15.sp, lineHeight = 25.sp)
            V(14)
            AboutBlock(Icons.Outlined.CheckCircle, t(ar, "لا يجرّب كلمات المرور", "It never tries passwords"),
                t(ar, "سُور أداة دفاعية، فلا يجرّب كلمة مرور على أي جهاز ولا يستغل ثغرة، بل يقرأ ما تعلنه الأجهزة عن نفسها.",
                      "Soor is defensive. It tries no password on any device and exploits nothing. It reads only what devices announce about themselves."))
            AboutBlock(Icons.Outlined.Lock, t(ar, "داخل شبكتك وحدها", "Inside your network only"),
                t(ar, "لا يتصل إلا بعناوين داخل شبكة بيتك، إذ في شيفرته قاعدة ترفض أي عنوان خارجها قبل الاتصال به، ولا يفحص عبر بيانات الجوال أبدًا.",
                      "It only connects to addresses inside your home network: a rule in its code refuses any other address before connecting, and it never scans over mobile data."))
            AboutBlock(Icons.Outlined.Info, t(ar, "لا يجمع بياناتك", "It collects nothing"),
                t(ar, "لا حساب فيه ولا خادم، ولا يحفظ في هاتفك إلا قائمة الأجهزة التي رآها في شبكتك ليخبرك بالجديد منها، ويمكنك مسحها من هنا.",
                      "No account and no server. The only thing it keeps on your phone is the list of devices it has seen on your network, to tell you about new ones, and you can erase it here."))
            V(4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(t(ar, "الموقع", "Website")) { onOpen("https://soor.3li.info/") }
                Pill(t(ar, "الخصوصية", "Privacy")) { onOpen("https://soor.3li.info/privacy.html") }
                Pill(t(ar, "الشيفرة", "Source")) { onOpen("https://github.com/SiteQ8/Soor") }
            }
            V(16)
            SecondaryButton(if (forgotten) t(ar, "مُسحت الأجهزة المحفوظة", "Saved devices erased") else t(ar, "امسح الأجهزة المحفوظة", "Erase saved devices"),
                Icons.Outlined.Delete, { onForget(); forgotten = true }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AboutBlock(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Theme.navy.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Theme.signal, modifier = Modifier.size(20.dp))
        }
        H(12)
        Column { Text(title, color = Theme.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text(body, color = Theme.ink2, fontSize = 13.5.sp, lineHeight = 21.sp) }
    }
}
