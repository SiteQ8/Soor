package info.eworldq8.soor.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.CompositionLocalProvider
import info.eworldq8.soor.R
import info.eworldq8.soor.engine.*
import info.eworldq8.soor.scan.ScanState
import info.eworldq8.soor.scan.ScanViewModel

// The Soor look: Kuwait navy (#0033A0) with a warm gold signal, on a deep field.

object Theme {
    val navy = Color(0xFF0033A0)
    val navyLite = Color(0xFF4C70BC)
    val signal = Color(0xFFE8A13A)
    val bg = Color(0xFF05060E)
    val panel = Color(0xFF0F1730)
    val ink = Color(0xFFEAF0FB)
    val ink2 = Color(0xFFA7B4D4)
    val rule = Color(0xFF23305A)
    val ok = Color(0xFF52C48D)

    fun severity(s: Severity): Color = when (s) {
        Severity.CRITICAL -> Color(0xFFFF6B5B)
        Severity.HIGH -> Color(0xFFF0904A)
        Severity.MEDIUM -> Color(0xFFE8C34A)
        else -> Color(0xFF6FA8E8)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoorApp(vm: ScanViewModel) {
    val ui by vm.ui.collectAsState()
    val lang by vm.lang.collectAsState()
    val ar = lang == Lang.AR
    var showAbout by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Finding?>(null) }
    val context = LocalContext.current

    CompositionLocalProvider(
        LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Scaffold(
            containerColor = Theme.bg,
            topBar = {
                TopAppBar(
                    title = {},
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Theme.bg),
                    navigationIcon = {
                        TextButton(onClick = { vm.toggleLang() }) {
                            Text(if (ar) "English" else "العربية", color = Theme.ink2, fontSize = 14.sp)
                        }
                    },
                    actions = {
                        TextButton(onClick = { showAbout = true }) {
                            Text("ⓘ", color = Theme.ink2, fontSize = 20.sp)
                        }
                    }
                )
            }
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Header(ar)
                Spacer(Modifier.height(18.dp))
                ScanCard(vm, ui.state, ui.progress, ui.progressText, ui.deviceCount, ar)

                if (ui.state == ScanState.NO_NETWORK) {
                    Spacer(Modifier.height(16.dp)); NoNetwork(ar)
                }

                if (ui.state == ScanState.DONE) {
                    Spacer(Modifier.height(16.dp))
                    SummaryRow(vm.summary(), lang)
                    Spacer(Modifier.height(12.dp))
                    if (ui.findings.isEmpty()) {
                        CleanCard(ar)
                    } else {
                        ui.findings.forEach { f ->
                            FindingCard(f, vm.knowledge, lang) { selected = f }
                            Spacer(Modifier.height(10.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, vm.reportText())
                                }
                                context.startActivity(Intent.createChooser(send, null))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (ar) "شارك التقرير" else "Share report", color = Theme.navyLite) }
                    }
                }

                Spacer(Modifier.height(18.dp))
                PrivacyNote(ar)
                Spacer(Modifier.height(24.dp))
            }
        }

        if (showAbout) AboutSheet(ar) { showAbout = false }
        selected?.let { f -> FindingSheet(f, vm.knowledge, lang) { selected = null } }
    }
}

@Composable
private fun Header(ar: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(84.dp)
        )
        Text("سُور", color = Theme.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            if (ar) "فاحص أمان شبكة البيت" else "Home network security scanner",
            color = Theme.ink2, fontSize = 14.sp
        )
    }
}

@Composable
private fun ScanCard(
    vm: ScanViewModel, state: ScanState, progress: Float,
    progressText: String, deviceCount: Int, ar: Boolean
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(Theme.panel)
            .border(1.dp, Theme.rule, RoundedCornerShape(16.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state == ScanState.SCANNING) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = Theme.navy, trackColor = Theme.rule
            )
            Spacer(Modifier.height(10.dp))
            Text(progressText, color = Theme.ink2, fontSize = 13.sp)
        } else {
            Button(
                onClick = { vm.start() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Theme.navy),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (state == ScanState.DONE) (if (ar) "افحص من جديد" else "Scan again")
                    else (if (ar) "افحص شبكتي" else "Scan my network"),
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            if (state == ScanState.DONE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (ar) "فُحص $deviceCount جهازًا على شبكتك"
                    else "$deviceCount devices scanned on your network",
                    color = Theme.ink2, fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun NoNetwork(ar: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.panel).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(if (ar) "لا توجد شبكة واي فاي" else "No Wi-Fi network",
            color = Theme.ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (ar) "اتصل بشبكة الواي فاي في بيتك ثم افحص، فسُور يفحص الشبكة المتصل بها فقط."
            else "Connect to your home Wi-Fi, then scan. Soor scans only the network you are on.",
            color = Theme.ink2, fontSize = 13.sp, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SummaryRow(counts: Map<Severity, Int>, lang: Lang) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        listOf(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.INFO).forEach { s ->
            val c = counts[s] ?: 0
            if (c > 0) {
                val col = Theme.severity(s)
                Box(
                    Modifier.padding(end = 8.dp)
                        .border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text("$c ${SoorReport.severityLabel(s, lang)}", color = col, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CleanCard(ar: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Theme.panel)
            .border(1.dp, Theme.ok.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(if (ar) "لا مخاطر ظاهرة" else "No visible risks",
                color = Theme.ink, fontWeight = FontWeight.SemiBold)
            Text(
                if (ar) "لم يُعثر على منفذ خطر أو جهاز مكشوف." else "No risky ports or exposed devices found.",
                color = Theme.ink2, fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun FindingCard(f: Finding, k: Knowledge, lang: Lang, onClick: () -> Unit) {
    val r = SoorReport.render(f, k, lang)
    val col = Theme.severity(f.severity)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.panel)
            .border(1.dp, Theme.rule, RoundedCornerShape(12.dp)).clickable { onClick() }
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(col))
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.clip(RoundedCornerShape(5.dp)).background(col.copy(alpha = 0.16f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text(r.severityLabel, color = col, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("${f.host}:${f.port}", color = Theme.ink2, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(r.title, color = Theme.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(r.detail, color = Theme.ink2, fontSize = 13.sp, maxLines = 2)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindingSheet(f: Finding, k: Knowledge, lang: Lang, onDismiss: () -> Unit) {
    val r = SoorReport.render(f, k, lang)
    val col = Theme.severity(f.severity)
    val ar = lang == Lang.AR
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Theme.panel) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(r.severityLabel, color = col, fontWeight = FontWeight.SemiBold)
                Text("${f.host}:${f.port}", color = Theme.ink2, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(r.title, color = Theme.ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(r.detail, color = Theme.ink)
            if (r.fix.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Theme.navy.copy(alpha = 0.18f)).padding(14.dp)
                ) {
                    Text(if (ar) "الحل" else "How to fix",
                        color = Theme.navyLite, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(r.fix, color = Theme.ink)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSheet(ar: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Theme.panel) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text(if (ar) "عن سُور" else "About Soor",
                color = Theme.ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                if (ar) "سُور يفحص شبكة بيتك كما يفحصها المختبِر، فيجد كل جهاز متصل بها ويقرأ ما يفتحه من منافذ، ويميّز المكشوف منها إلى الإنترنت من الآمن داخل الشبكة، ثم يرتّب ما وجده بحسب الخطورة مع خطوة إصلاح لكل اكتشاف، ومن ذلك التحذير من الكاميرات المكشوفة."
                else "Soor scans your home network the way a tester would. It finds every connected device and reads the ports it leaves open, tells what is exposed to the internet from what is safe inside, then ranks what it found by severity with a fix for each, cameras among them.",
                color = Theme.ink2
            )
            Spacer(Modifier.height(16.dp))
            AboutBlock(
                if (ar) "لا يجرّب كلمات المرور" else "It never tries passwords",
                if (ar) "سُور أداة دفاعية، فلا يجرّب كلمة مرور على أي جهاز ولا يستغل ثغرة، بل يطرق الأبواب ويقرأ ما تعلنه الأجهزة عن نفسها."
                else "Soor is a defensive tool. It tries no password on any device and exploits nothing. It knocks on doors and reads only what devices announce about themselves."
            )
            AboutBlock(
                if (ar) "لا يجمع بياناتك" else "It collects nothing",
                if (ar) "لا يطلب هذا التطبيق إذن الإنترنت أصلًا، فلا يستطيع أن يرسل شيئًا حتى لو أراد، ومعرفته كلها مشحونة داخله."
                else "This app does not even request internet permission, so it cannot send anything even if it wanted to, and all its knowledge ships inside it."
            )
            AboutBlock(
                if (ar) "شبكتك وحدها" else "Your network only",
                if (ar) "يفحص الشبكة المتصل بها الهاتف ولا يتجاوز عناوينها الخاصة."
                else "It scans the network the phone is on and does not go beyond its private addresses."
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutBlock(title: String, body: String) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, color = Theme.ink, fontWeight = FontWeight.SemiBold)
        Text(body, color = Theme.ink2, fontSize = 13.sp)
    }
}

@Composable
private fun PrivacyNote(ar: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Theme.navy.copy(alpha = 0.12f)).padding(14.dp)
    ) {
        Text(
            if (ar) "يعمل على جهازك بالكامل، فلا حساب ولا خادم ولا جمع بيانات."
            else "Runs entirely on your device. No account, no server, no data collection.",
            color = Theme.ink2, fontSize = 12.sp
        )
    }
}
