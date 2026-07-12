package com.androidtoolsuite.app.plugins.phigros

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidtoolsuite.app.ui.Notice
import com.androidtoolsuite.app.ui.SuiteCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

@Composable
internal fun PhigrosAdvisorScreen(plugin: PhigrosAdvisorPlugin) {
    val ui by plugin.state
    val activeDialog = ui.dialog
    if (activeDialog != null) {
        AlertDialog(
            onDismissRequest = plugin::dismissDialog,
            title = { Text(activeDialog.title) },
            text = { Text(activeDialog.message) },
            confirmButton = { TextButton(onClick = plugin::dismissDialog) { Text("确定") } },
        )
    } else {
        ui.generatedImage?.let { image ->
            GeneratedPreviewDialog(image, plugin, ui.loading)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phigros Data Studio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("本地优先的云存档分析与制图", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ui.snapshot.overall > 0.0) {
                    Text(format(ui.snapshot.overall, 4), style = MaterialTheme.typography.headlineSmall, color = Color(0xFFE2B93B), fontWeight = FontWeight.Bold)
                }
            }
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        ScrollableTabRow(selectedTabIndex = ui.page.ordinal, edgePadding = 0.dp) {
            PhigrosPage.entries.forEach { page ->
                Tab(
                    selected = ui.page == page,
                    onClick = { plugin.selectPage(page) },
                    text = { Text(page.title, maxLines = 1) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when (ui.page) {
            PhigrosPage.OVERVIEW -> OverviewPage(ui, plugin)
            PhigrosPage.SCORES -> ScoresPage(ui)
            PhigrosPage.B30 -> B30Page(ui, plugin)
            PhigrosPage.HISTORY -> HistoryPage(ui)
            PhigrosPage.CATALOG -> CatalogPage(ui, plugin)
            PhigrosPage.TOKENS -> TokensPage(ui, plugin)
        }
    }
}

@Composable
private fun OverviewPage(ui: PhigrosUiState, plugin: PhigrosAdvisorPlugin) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = plugin::fetchLatest, enabled = !ui.loading && ui.selectedTokenId != null, modifier = Modifier.weight(1f)) {
                    Text("同步云存档")
                }
                OutlinedButton(onClick = { plugin.selectPage(PhigrosPage.TOKENS) }, modifier = Modifier.weight(1f)) {
                    Text("管理令牌")
                }
            }
        }
        if (ui.save == null) {
            item {
                SuiteCard {
                    Text("开始使用", style = MaterialTheme.typography.titleLarge)
                    Text("在“令牌”页扫码获取或手动保存 SessionToken，再返回此页同步。令牌通过 Android Keystore 加密，成绩与历史仅保存在本机。")
                }
            }
        } else {
            val profile = ui.save.profile
            item { PlayerSummary(profile, ui.snapshot) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = plugin::generateB30Image, enabled = !ui.loading, modifier = Modifier.weight(1f)) { Text("生成 B30 图") }
                    OutlinedButton(onClick = plugin::generateProfileImage, enabled = !ui.loading, modifier = Modifier.weight(1f)) { Text("生成个人信息图") }
                }
            }
            item { StatisticsCard(ui.save) }
        }
    }
}

@Composable
private fun PlayerSummary(profile: PlayerProfile, snapshot: RksSnapshot) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile.playerId, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (profile.selfIntro.isNotBlank()) Text(profile.selfIntro, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("COMPUTED RKS", style = MaterialTheme.typography.labelMedium)
                    Text(format(snapshot.overall, 4), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Challenge ${profile.challengeTier} / ${profile.challengeValue}", fontWeight = FontWeight.SemiBold)
                    Text(profile.dataText, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .74f))
                }
            }
            if (profile.officialRks > 0.0 && abs(profile.officialRks - snapshot.overall) > .001) {
                Text("官方存档 RKS ${format(profile.officialRks, 4)} · 本地计算差 ${signed(snapshot.overall - profile.officialRks)}", style = MaterialTheme.typography.bodySmall)
            }
            Text("存档时间 ${profile.saveUpdatedAt.take(19).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatisticsCard(save: PhigrosSave) {
    SuiteCard {
        Text("难度统计", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("EZ", "HD", "IN", "AT").forEachIndexed { index, level ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(level, color = levelColor(level), fontWeight = FontWeight.Bold)
                    Text(save.profile.cleared.getOrElse(index) { 0 }.toString(), style = MaterialTheme.typography.titleLarge)
                    Text("FC ${save.profile.fullCombo.getOrElse(index) { 0 }} · φ ${save.profile.phi.getOrElse(index) { 0 }}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        HorizontalDivider()
        Text("有效成绩 ${save.records.count { it.score > 0 }} 条 · 有定数 ${save.records.count { it.constant > 0.0 }} 条")
    }
}

@Composable
private fun ScoresPage(ui: PhigrosUiState) {
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("ALL") }
    val records = ui.snapshot.sorted
        .asSequence()
        .filter { level == "ALL" || it.level == level }
        .filter { query.isBlank() || it.title.contains(query, true) || it.id.contains(query, true) }
        .toList()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索曲名或曲目 ID") },
                singleLine = true,
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL", "EZ", "HD", "IN", "AT").forEach { item ->
                    FilterChip(selected = level == item, onClick = { level = item }, label = { Text(item) })
                }
            }
        }
        if (records.isEmpty()) item { EmptyHint("暂无成绩，请先同步云存档") }
        items(records, key = { it.identity }) { record ->
            val rank = ui.snapshot.sorted.indexOfFirst { it.identity == record.identity } + 1
            RecordCard("#$rank", record, ui.pushTargets[record.identity])
        }
    }
}

@Composable
private fun RecordCard(rankLabel: String, record: ChartRecord, target: PushTarget?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(rankLabel, modifier = Modifier.width(44.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(record.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(record.rating, color = ratingColor(record.rating), fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ValueCell("难度", record.level, levelColor(record.level))
                ValueCell("定数", format(record.constant, 1))
                ValueCell("分数", "%07d".format(record.score))
                ValueCell("准确率", "${format(record.accuracy, 4)}%")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("推分 ACC  ${target?.label ?: "—"}", color = Color(0xFF2E7D5A), fontWeight = FontWeight.SemiBold)
                Text("单曲 RKS  ${format(record.rks, 4)}", color = Color(0xFFB28704), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ValueCell(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun B30Page(ui: PhigrosUiState, plugin: PhigrosAdvisorPlugin) {
    val entries = ui.snapshot.phi.mapIndexed { index, record -> "P${index + 1}" to record } +
        ui.snapshot.best27.mapIndexed { index, record -> "B${index + 1}" to record }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = plugin::generateB30Image, enabled = !ui.loading && ui.save != null, modifier = Modifier.weight(1f)) { Text("生成 B30 图片") }
                OutlinedButton(onClick = plugin::generateProfileImage, enabled = !ui.loading && ui.save != null, modifier = Modifier.weight(1f)) { Text("个人信息图") }
            }
        }
        item { Notice("B30 采用 P3 + B27 口径：3 个最高满分谱面与 27 个最高单曲 RKS 相加后除以 30。") }
        if (entries.isEmpty()) item { EmptyHint("暂无 B30 数据") }
        items(entries, key = { it.first + it.second.identity }) { (rank, record) ->
            RecordCard(rank, record, ui.pushTargets[record.identity])
        }
    }
}

@Composable
private fun HistoryPage(ui: PhigrosUiState) {
    var days by remember { mutableStateOf(90) }
    val cutoff = if (days == 0) Long.MIN_VALUE else System.currentTimeMillis() - days * 86_400_000L
    val events = ui.timeline.filter { it.timestamp >= cutoff }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 90, 180, 0).forEach { value ->
                    FilterChip(selected = days == value, onClick = { days = value }, label = { Text(if (value == 0) "全部" else "${value}天") })
                }
            }
        }
        item { Notice("每次同步会在本机按令牌分别记录 RKS、课题模式与谱面提升，不上传历史数据；每个节点使用该次同步时的曲库定数。") }
        if (events.isEmpty()) item { EmptyHint("当前时间范围内没有推分记录；同步一次云存档后开始记录") }
        items(events, key = { "${it.timestamp}-${it.saveTimestamp}" }) { event -> TimelineCard(event) }
    }
}

@Composable
private fun TimelineCard(event: TimelineEvent) {
    var expanded by remember(event.timestamp, event.saveTimestamp) { mutableStateOf(false) }
    val visibleChanges = if (expanded) event.changes else event.changes.take(5)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(30.dp).fillMaxHeight()) {
            Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
            Box(Modifier.width(2.dp).weight(1f).background(MaterialTheme.colorScheme.primary.copy(alpha = .28f)))
        }
        ElevatedCard(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDate(event.timestamp), fontWeight = FontWeight.Bold)
                    Text(
                        if (event.oldRks == null) "RKS ${format(event.newRks, 4)}" else "${signed(event.rksDelta)} → ${format(event.newRks, 4)}",
                        color = if (event.rksDelta >= 0) Color(0xFF24845E) else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text("Challenge ${event.challengeModeRank / 100} / ${event.challengeModeRank % 100}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                visibleChanges.forEach { change ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${change.title} [${change.level}]", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            Text(change.tag, color = Color(0xFF2D7C9B), style = MaterialTheme.typography.labelMedium)
                        }
                        val score = change.oldScore?.let { "%07d → %07d".format(it, change.newScore) }
                            ?: "%07d".format(change.newScore)
                        val accuracy = change.oldAccuracy?.let { "${format(it, 4)}% → ${format(change.newAccuracy, 4)}%" }
                            ?: "${format(change.newAccuracy, 4)}%"
                        Text(
                            "$score · ACC $accuracy",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (event.changes.size > 5) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) {
                        Text(if (expanded) "收起" else "展开全部 ${event.changes.size} 条")
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogPage(ui: PhigrosUiState, plugin: PhigrosAdvisorPlugin) {
    var query by remember { mutableStateOf("") }
    var levels by remember { mutableStateOf(setOf("IN", "AT")) }
    val charts = ui.catalog.flatMap { song ->
        listOf("EZ", "HD", "IN", "AT").mapIndexedNotNull { index, level ->
            val constant = song.constantAt(index)
            if (constant <= 0.0 || level !in levels) null else CatalogChart(song, level, constant)
        }
    }.filter { query.isBlank() || it.song.title.contains(query, true) || it.song.id.contains(query, true) }
        .groupBy { floor(it.constant).toInt() }
        .toSortedMap(compareByDescending { it })
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索曲目或 ID") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedButton(onClick = plugin::refreshCatalog, enabled = !ui.loading) { Text("更新") }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("EZ", "HD", "IN", "AT").forEach { level ->
                    FilterChip(
                        selected = level in levels,
                        onClick = { levels = if (level in levels) levels - level else levels + level },
                        label = { Text(level) },
                    )
                }
            }
        }
        if (charts.isEmpty()) item { EmptyHint("没有符合条件的谱面") }
        charts.forEach { (major, group) ->
            item(key = "major-$major") {
                Text("$major · ${group.size} 张谱面", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            items(group.sortedWith(compareByDescending<CatalogChart> { it.constant }.thenBy { it.song.title }), key = { "${it.song.id}-${it.level}" }) { chart ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(format(chart.constant, 1), modifier = Modifier.width(52.dp), color = levelColor(chart.level), fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f)) {
                            Text(chart.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(chart.song.composer.ifBlank { chart.song.id }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Text(chart.level, color = levelColor(chart.level), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TokensPage(ui: PhigrosUiState, plugin: PhigrosAdvisorPlugin) {
    var label by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(PhigrosServer.CN) }
    var editingProfile by remember { mutableStateOf<TokenProfile?>(null) }
    var editedLabel by remember { mutableStateOf("") }
    editingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { editingProfile = null },
            title = { Text("令牌设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${profile.server.label} · SessionToken 已在本机加密保存", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = editedLabel,
                        onValueChange = { editedLabel = it },
                        label = { Text("备注") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    plugin.updateTokenLabel(profile.id, editedLabel)
                    editingProfile = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingProfile = null }) { Text("取消") } },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SuiteCard {
                Text("扫码获取 SessionToken", style = MaterialTheme.typography.titleLarge)
                Text("登录过程直接连接 TapTap 与 Phigros 的 LeanCloud 服务；成功后令牌仅在本机加密保存。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhigrosServer.entries.forEach { item ->
                        FilterChip(selected = server == item, onClick = { server = item }, label = { Text(item.label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { plugin.startTapLogin(server) }, enabled = !ui.loading, modifier = Modifier.weight(1f)) { Text("获取登录二维码") }
                    OutlinedButton(onClick = plugin::openLoginLink, enabled = ui.loginRequest != null, modifier = Modifier.weight(1f)) { Text("浏览器打开") }
                }
                ui.loginQr?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), "TapTap 登录二维码", modifier = Modifier.fillMaxWidth().height(280.dp), contentScale = ContentScale.Fit)
                    Text("剩余 ${ui.loginSecondsLeft} 秒", modifier = Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            SuiteCard {
                Text("手动保存", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.filter(Char::isLetterOrDigit).take(25) },
                    label = { Text("25 位 SessionToken") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(
                    onClick = { plugin.saveToken(label, token, server); token = "" },
                    enabled = !ui.loading && SecureTokenStore.TOKEN_PATTERN.matches(token),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("加密保存并设为当前令牌") }
            }
        }
        item { Text("本地令牌 · ${ui.tokenProfiles.size}", style = MaterialTheme.typography.titleLarge) }
        if (ui.tokenProfiles.isEmpty()) item { EmptyHint("尚未保存令牌") }
        items(ui.tokenProfiles, key = TokenProfile::id) { profile ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (profile.id == ui.selectedTokenId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.label, fontWeight = FontWeight.Bold)
                            Text("${profile.server.label} · 本地加密 · 最近使用 ${formatDate(profile.lastUsedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (profile.id == ui.selectedTokenId) Text("当前", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { plugin.selectToken(profile.id) }, enabled = profile.id != ui.selectedTokenId, modifier = Modifier.weight(1f)) { Text("设为当前") }
                        OutlinedButton(
                            onClick = {
                                editedLabel = profile.label
                                editingProfile = profile
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("设置") }
                        OutlinedButton(onClick = { plugin.deleteToken(profile.id) }, modifier = Modifier.weight(1f)) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneratedPreviewDialog(image: GeneratedImage, plugin: PhigrosAdvisorPlugin, loading: Boolean) {
    Dialog(
        onDismissRequest = plugin::dismissGeneratedImage,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    if (image.kind == "b30") "B30 图片预览" else "个人信息图预览",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Image(
                    bitmap = image.bitmap.asImageBitmap(),
                    contentDescription = "生成结果预览",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentScale = ContentScale.Fit,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = plugin::dismissGeneratedImage, enabled = !loading) { Text("关闭") }
                    Button(onClick = plugin::saveGeneratedImageToGallery, enabled = !loading) { Text("保存到相册") }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp)).padding(24.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PhigrosHomeWidget(player: String, rks: Double, count: Int) {
    SuiteCard {
        Text("PHIGROS DATA STUDIO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(if (count == 0) "等待同步" else format(rks, 4), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(if (count == 0) player else "$player · $count 条成绩", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private data class CatalogChart(val song: SongInfo, val level: String, val constant: Double)

@Composable
private fun levelColor(level: String): Color = when (level) {
    "EZ" -> Color(0xFF23965C)
    "HD" -> Color(0xFF247FC4)
    "IN" -> Color(0xFFC63C54)
    "AT" -> Color(0xFF8657C5)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun ratingColor(rating: String): Color = when (rating) {
    "PHI" -> Color(0xFFD39B00)
    "FC" -> Color(0xFF0089A8)
    "V", "S" -> Color(0xFF21865D)
    else -> Color(0xFF6F7782)
}

private fun format(value: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", value)
private fun signed(value: Double): String = String.format(Locale.ROOT, "%+.4f", value)
private fun formatDate(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
