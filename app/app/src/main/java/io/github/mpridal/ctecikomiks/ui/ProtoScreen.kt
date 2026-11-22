package io.github.mpridal.ctecikomiks.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.mpridal.ctecikomiks.BuildConfig
import io.github.mpridal.ctecikomiks.CteciApp
import io.github.mpridal.ctecikomiks.update.UpdateState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mpridal.ctecikomiks.core.WordStatus
import io.github.mpridal.ctecikomiks.data.ProtoPage
import java.util.Locale

private val Ink = Color(0xFF2B2B2B)
private val Dim = Color(0xFFAAAAAA)
private val Current = Color(0xFFFFE27A)
private val Paper = Color(0xFFFFFDF7)
private val Narration = Color(0xFFFFF6D6)

@Composable
fun ProtoScreen(vm: ProtoViewModel, app: CteciApp) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        UpdateBanner(app)
        TopBar(s, vm, onSettings = { showSettings = true })
        Spacer(Modifier.padding(4.dp))
        val page = vm.page
        when {
            s.loading || page == null -> Text("Načítám…", fontSize = 28.sp)
            s.finished -> Summary(s, vm)
            else -> PageView(page, s)
        }
    }
    if (showSettings) SettingsDialog(app, onClose = { showSettings = false })
}

/** Lišta nové verze: stažená a ověřená aktualizace → instalace (potvrzuje Android). */
@Composable
private fun UpdateBanner(app: CteciApp) {
    val st by app.updater.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val d = st as? UpdateState.Downloaded ?: return
    Row(Modifier.fillMaxWidth().background(Color(0xFFE3F2E1), RoundedCornerShape(8.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nová verze ${d.version} je stažená.", fontSize = 18.sp, modifier = Modifier.weight(1f))
        if (app.updater.canInstall()) {
            Button(onClick = { app.updater.install() }) { Text("Nainstalovat") }
        } else {
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Povolit instalaci") }
        }
    }
    Spacer(Modifier.padding(4.dp))
}

/** Nastavení prototypu: verze, token pro odesílání záznamů do soukromého repa logů. */
@Composable
private fun SettingsDialog(app: CteciApp, onClose: () -> Unit) {
    val status by app.uploader.status.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Nastavení prototypu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Verze ${BuildConfig.VERSION_NAME}")
                Text("Záznamy relací se odesílají do soukromého repa ${BuildConfig.LOG_REPO}. Stav: $status")
                Text(if (app.uploader.hasToken) "Token je uložený (šifrovaně)." else "Token zatím není uložený.")
                OutlinedTextField(value = token, onValueChange = { token = it.trim() }, singleLine = true,
                    label = { Text("GitHub token (github_pat_…)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { if (token.startsWith("github_pat_") || token.startsWith("ghp_")) app.uploader.setToken(token); onClose() }) {
                Text("Uložit a odeslat")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { app.updater.checkNow(); app.uploader.uploadPending() }) { Text("Zkontrolovat teď") }
                OutlinedButton(onClick = onClose) { Text("Zavřít") }
            }
        },
    )
}

@Composable
private fun TopBar(s: UiState, vm: ProtoViewModel, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Prototyp A", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        EngineKind.entries.forEach { k ->
            FilterChip(selected = s.engine == k, onClick = { vm.selectEngine(k) }, label = { Text(k.label) })
        }
        if (s.engine == EngineKind.VOSK) {
            Text("·", fontSize = 18.sp)
            GrammarMode.entries.forEach { m ->
                FilterChip(selected = s.grammarMode == m, onClick = { vm.selectGrammar(m) }, label = { Text(m.label) })
            }
        }
        Text("Stránka ${s.pageIndex + 1}/${s.pageCount}", fontSize = 18.sp)
        Text(if (s.listening) "🎤 poslouchám" else if (!s.micAllowed) "🎤 bez povolení" else "🎤 …", fontSize = 18.sp)
        Spacer(Modifier.weight(1f))
        StarCounter(s.stars, s.rewardTick)
        OutlinedButton(onClick = onSettings) { Text("⚙", fontSize = 20.sp) }
        Button(onClick = { vm.manualAdvance() }, enabled = !s.finished) { Text("Dál ▶", fontSize = 20.sp) }
    }
    if (s.error != null) Text(s.error, color = Color(0xFFB00020), fontSize = 16.sp)
}

@Composable
private fun StarCounter(stars: Int, tick: Int) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(tick) {
        if (tick > 0) {
            scale.snapTo(1.8f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }
    Text("⭐ $stars", fontSize = 28.sp, modifier = Modifier.scale(scale.value))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageView(page: ProtoPage, s: UiState) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        page.image?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.weight(1.1f).fillMaxHeight())
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (block in page.blocks) {
                val shape = RoundedCornerShape(if (block.kind == "narration") 6.dp else 28.dp)
                FlowRow(
                    Modifier.fillMaxWidth()
                        .background(if (block.kind == "narration") Narration else Color.White, shape)
                        .border(2.5.dp, Ink, shape)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    block.words.forEachIndexed { i, w ->
                        val idx = block.firstWord + i
                        val status = s.statuses.getOrElse(idx) { WordStatus.PENDING }
                        val isCurrent = idx == s.cursor
                        Text(
                            w.text.uppercase(Locale.ROOT),
                            fontSize = 36.sp,
                            color = if (status == WordStatus.PENDING) Ink else Dim,
                            modifier = Modifier
                                .background(if (isCurrent) Current else Color.Transparent, RoundedCornerShape(8.dp))
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            Text("Slyším: ${s.heard}", fontSize = 16.sp, color = Color(0xFF666666))
            s.lastLatencyMs?.let { Text("Odezva (odhad): $it ms", fontSize = 16.sp, color = Color(0xFF666666)) }
        }
    }
}

@Composable
private fun Summary(s: UiState, vm: ProtoViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Souhrn prototypu A", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Brána: aspoň 85 % slov bez ručního posunu, odezva pod 1 s.", fontSize = 18.sp)
        for (sum in s.summaries.values) {
            val st = sum.stats
            val pct = (st.autoRatio * 100).toInt()
            val ok = st.autoRatio >= 0.85 && (sum.medianLatency ?: 0) < 1000
            Box(Modifier.border(2.dp, if (ok) Color(0xFF2E7D32) else Color(0xFFB00020), RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text(
                    "${sum.mode}: $pct % bez ručního posunu (rozpoznáno ${st.recognized}, přeskočeno ${st.skipped}, " +
                        "ručně ${st.manual} z ${st.total}) · hvězdičky ${sum.rewards} · " +
                        "odezva medián ${sum.medianLatency?.let { "$it ms" } ?: "–"} · ${if (ok) "PROŠLO" else "NEPROŠLO"}",
                    fontSize = 20.sp,
                )
            }
        }
        s.savedLog?.let { Text("Záznam: $it", fontSize = 14.sp, color = Color(0xFF666666)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.restart() }) { Text("Znovu (${s.engine.label})") }
            EngineKind.entries.filter { it != s.engine }.forEach { k ->
                OutlinedButton(onClick = { vm.selectEngine(k) }) { Text("Zkusit ${k.label}") }
            }
        }
        Spacer(Modifier.width(1.dp))
        Text("Hvězdička = správně přečtené těžké slovo. Zapište si, kolikrát přišla omylem.",
            fontSize = 16.sp, style = MaterialTheme.typography.bodyMedium)
    }
}
