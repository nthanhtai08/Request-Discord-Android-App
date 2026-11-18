package com.example.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DiscordAccount
import com.example.data.DiscordQuest
import com.example.data.TaskLog
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val account by viewModel.currentAccount.collectAsStateWithLifecycle()
    val isExecuting by viewModel.isExecuting.collectAsStateWithLifecycle()
    val quests by viewModel.activeQuests.collectAsStateWithLifecycle()
    val selectedQuestIds by viewModel.selectedQuestIds.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val statusText by viewModel.currentStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NaturalBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AnimatedContent(
            targetState = account,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
            },
            label = "ScreenTransition"
        ) { activeAccount ->
            if (activeAccount == null) {
                TokenSetupView(
                    onVerifyClick = { inputToken ->
                        viewModel.saveTokenAndInit(inputToken) { success ->
                            if (success) {
                                Toast.makeText(context, "Logged in successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to connect to Discord API.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            } else {
                QuestDashboardView(
                    account = activeAccount,
                    quests = quests,
                    selectedQuestIds = selectedQuestIds,
                    logs = logs,
                    isExecuting = isExecuting,
                    statusText = statusText,
                    onToggleSelect = { viewModel.toggleQuestSelection(it) },
                    onSelectAll = { viewModel.selectAll(quests) },
                    onDeselectAll = { viewModel.deselectAll() },
                    onRunAutomation = { viewModel.startAutomation(context) },
                    onStopAutomation = { viewModel.stopAutomation(context) },
                    onLogout = { viewModel.logout() },
                    onRefresh = { viewModel.refreshQuests(activeAccount.token) },
                    onClearLogs = { viewModel.clearLogs() },
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun TokenSetupView(onVerifyClick: (String) -> Unit) {
    var rawToken by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "AutoQuest",
                fontSize = 32.sp,
                color = NaturalDarkText,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "Discord Automation Hub",
                fontSize = 14.sp,
                color = NaturalSecondaryText,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NaturalSageContainer),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, NaturalBorder)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "DISCORD TOKEN",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NaturalSecondaryText,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )

                OutlinedTextField(
                    value = rawToken,
                    onValueChange = { rawToken = it },
                    placeholder = { Text("Paste discord token here", color = NaturalSecondaryText) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = NaturalBg,
                        unfocusedContainerColor = NaturalBg,
                        focusedBorderColor = NaturalBorder,
                        unfocusedBorderColor = NaturalBorder,
                        focusedTextColor = NaturalDarkText,
                        unfocusedTextColor = NaturalDarkText
                    ),
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Key, contentDescription = "Key", tint = NaturalSecondaryText)
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle visibility",
                                tint = NaturalSecondaryText
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Chúng tôi lưu trữ Token hoàn toàn cục bộ trên điện thoại của bạn và không bao giờ chia sẻ nó lên máy chủ bên thứ ba nào.",
                    fontSize = 11.sp,
                    color = NaturalSecondaryText,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = { if (rawToken.trim().isNotEmpty()) onVerifyClick(rawToken.trim()) },
            enabled = rawToken.trim().isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = NaturalPrimary,
                contentColor = Color.White,
                disabledContainerColor = NaturalBorder,
                disabledContentColor = NaturalSecondaryText
            ),
            shape = RoundedCornerShape(30.dp),
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Icon(imageVector = Icons.Default.Key, contentDescription = "Login", modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Connect Account", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun QuestDashboardView(
    account: DiscordAccount,
    quests: List<DiscordQuest>,
    selectedQuestIds: Set<String>,
    logs: List<TaskLog>,
    isExecuting: Boolean,
    statusText: String,
    onToggleSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onRunAutomation: () -> Unit,
    onStopAutomation: () -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    onClearLogs: () -> Unit,
    viewModel: MainViewModel
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Quests, 1: Game Spoofer
    var showLogoutDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "AutoQuest Hub", fontSize = 24.sp, color = NaturalDarkText, fontWeight = FontWeight.Bold)
                Text(text = account.username.ifEmpty { "Connected Account" }, fontSize = 12.sp, color = NaturalSecondaryText)
            }

            if (selectedTab == 0) {
                IconButton(
                    onClick = onRefresh,
                    enabled = !isExecuting,
                    modifier = Modifier.clip(CircleShape).background(NaturalSageContainer)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh", tint = NaturalPrimary, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            IconButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.clip(CircleShape).background(NaturalSageContainer)
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout", tint = Color(0xFFC0392B))
            }
        }

        // Tabs Row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = NaturalBg,
            contentColor = NaturalPrimary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Quest Automation", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Game Spoofer", fontWeight = FontWeight.Bold) }
            )
        }

        // Body content based on active tab
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (selectedTab == 0) {
                QuestAutomationTabContent(
                    quests = quests,
                    selectedQuestIds = selectedQuestIds,
                    logs = logs,
                    isExecuting = isExecuting,
                    statusText = statusText,
                    onToggleSelect = onToggleSelect,
                    onSelectAll = onSelectAll,
                    onDeselectAll = onDeselectAll,
                    onRunAutomation = onRunAutomation,
                    onStopAutomation = onStopAutomation,
                    onClearLogs = onClearLogs
                )
            } else {
                GameSpooferView(
                    account = account,
                    logs = logs,
                    onClearLogs = onClearLogs,
                    viewModel = viewModel
                )
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout Account", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to disconnect your Discord account?") },
            confirmButton = {
                TextButton(onClick = { showLogoutDialog = false; onLogout() }) {
                    Text("Logout", color = Color(0xFFC0392B))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel", color = NaturalDarkText) }
            },
            containerColor = NaturalBg
        )
    }
}

@Composable
fun QuestAutomationTabContent(
    quests: List<DiscordQuest>,
    selectedQuestIds: Set<String>,
    logs: List<TaskLog>,
    isExecuting: Boolean,
    statusText: String,
    onToggleSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onRunAutomation: () -> Unit,
    onStopAutomation: () -> Unit,
    onClearLogs: () -> Unit
) {
    val sortedQuests = remember(quests) {
        quests.sortedBy { it.completed }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Quests Checklist (${sortedQuests.count { !it.completed }})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NaturalDarkText
                    )

                    Row {
                        val allInteraction = remember { MutableInteractionSource() }
                        Text(
                            text = "All",
                            fontSize = 12.sp,
                            color = NaturalPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .bounceClick(allInteraction)
                                .clickable(interactionSource = allInteraction, indication = null) { onSelectAll() }
                                .padding(8.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val noneInteraction = remember { MutableInteractionSource() }
                        Text(
                            text = "None",
                            fontSize = 12.sp,
                            color = NaturalSecondaryText,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .bounceClick(noneInteraction)
                                .clickable(interactionSource = noneInteraction, indication = null) { onDeselectAll() }
                                .padding(8.dp)
                        )
                    }
                }
            }

            if (sortedQuests.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, NaturalBorder)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(imageVector = Icons.Default.Inbox, contentDescription = null, tint = NaturalSecondaryText, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "Không tìm thấy nhiệm vụ nào", fontSize = 14.sp, color = NaturalDarkText, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            } else {
                items(sortedQuests, key = { it.id }) { quest ->
                    QuestCard(
                        quest = quest,
                        isSelected = selectedQuestIds.contains(quest.id),
                        onToggle = { onToggleSelect(quest.id) }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Real-Time Terminal Output", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NaturalDarkText)
                    Text(text = "Clear Term", fontSize = 12.sp, color = Color(0xFFC0392B), fontWeight = FontWeight.Medium, modifier = Modifier.clickable { onClearLogs() }.padding(8.dp))
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(NaturalDarkSoot)
                        .padding(12.dp)
                ) {
                    val logScrollState = rememberScrollState()
                    LaunchedEffect(logs.size) {
                        logScrollState.animateScrollTo(logScrollState.maxValue)
                    }
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(logScrollState)) {
                        if (logs.isEmpty()) {
                            Text(text = "Chờ khởi chạy automation...", color = NaturalLogGreen.copy(alpha = 0.5f), fontSize = 11.sp)
                        } else {
                            logs.asReversed().forEach { log -> LogRow(log) }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(40.dp)) }
        }

        // Bottom Bar Automation
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, NaturalBorder),
            color = NaturalBg
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (isExecuting) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NaturalPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = statusText, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = NaturalPrimary, maxLines = 1)
                    }
                }

                Button(
                    onClick = { if (!isExecuting) onRunAutomation() else onStopAutomation() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isExecuting) Color(0xFFC0392B) else NaturalPrimary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(30.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(imageVector = if (isExecuting) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = if (isExecuting) "Dừng Lại" else "Chạy Tự Động", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun GameSpooferView(
    account: DiscordAccount,
    logs: List<TaskLog>,
    onClearLogs: () -> Unit,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    var totalHoursInput by remember { mutableStateOf("6000") }
    var titlesInput by remember { mutableStateOf("250") }
    var batchSizeInput by remember { mutableStateOf("25") }
    var wobbleChecked by remember { mutableStateOf(false) }
    var dryRunChecked by remember { mutableStateOf(false) }

    val spooferExecuting by com.example.network.DiscordGameTimeService.isExecuting.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NaturalSageContainer),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, NaturalBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Game Time Spoofer Config", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NaturalDarkText)

                OutlinedTextField(value = totalHoursInput, onValueChange = { totalHoursInput = it }, label = { Text("Tổng số giờ cày") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = titlesInput, onValueChange = { titlesInput = it }, label = { Text("Số lượng game") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = batchSizeInput, onValueChange = { batchSizeInput = it }, label = { Text("Batch Size") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Dao động giờ (Wobble)", fontSize = 14.sp, color = NaturalDarkText)
                    Switch(checked = wobbleChecked, onCheckedChange = { wobbleChecked = it })
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Chạy thử (Dry Run)", fontSize = 14.sp, color = NaturalDarkText)
                    Switch(checked = dryRunChecked, onCheckedChange = { dryRunChecked = it })
                }
            }
        }

        Button(
            onClick = {
                if (!spooferExecuting) {
                    val hours = totalHoursInput.toDoubleOrNull() ?: 6000.0
                    val titles = titlesInput.toIntOrNull() ?: 250
                    val batch = batchSizeInput.toIntOrNull() ?: 25
                    viewModel.startSpoofing(context, hours, titles, batch, 450L, wobbleChecked, dryRunChecked)
                } else {
                    viewModel.stopSpoofing(context)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (spooferExecuting) Color(0xFFC0392B) else NaturalPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(30.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Icon(imageVector = if (spooferExecuting) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = if (spooferExecuting) "Dừng Game Spoofer" else "Chạy Game Spoofer", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "Spoofer Terminal Output", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NaturalDarkText)
            Text(text = "Clear Term", fontSize = 12.sp, color = Color(0xFFC0392B), fontWeight = FontWeight.Medium, modifier = Modifier.clickable { onClearLogs() }.padding(8.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(NaturalDarkSoot)
                .padding(12.dp)
        ) {
            val logScrollState = rememberScrollState()
            LaunchedEffect(logs.size) {
                logScrollState.animateScrollTo(logScrollState.maxValue)
            }
            Column(modifier = Modifier.fillMaxSize().verticalScroll(logScrollState)) {
                if (logs.isEmpty()) {
                    Text(text = "Chưa có bản ghi log nào...", color = NaturalLogGreen.copy(alpha = 0.5f), fontSize = 11.sp)
                } else {
                    logs.asReversed().forEach { log -> LogRow(log) }
                }
            }
        }
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun QuestCard(quest: DiscordQuest, isSelected: Boolean, onToggle: () -> Unit) {
    val progressPercent = if (quest.target > 0) (quest.currentProgress.toFloat() / quest.target.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(targetValue = progressPercent, label = "progress")

    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !quest.completed) { onToggle() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, if (isSelected && !quest.completed) NaturalPrimary else NaturalBorder)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!quest.completed) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(NaturalAccentGreen), contentAlignment = Alignment.Center) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = NaturalPrimary, modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(text = quest.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NaturalDarkText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = "Quà tặng: ${quest.rewardName}", fontSize = 12.sp, color = NaturalSecondaryText, maxLines = 1)
                
                if (!quest.completed) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = NaturalPrimary, trackColor = NaturalSageContainer)
                }
            }
        }
    }
}

@Composable
fun LogRow(log: TaskLog) {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val formattedTime = sdf.format(Date(log.timestamp))
    val color = when (log.type) {
        "success" -> Color(0xFF4CAF50)
        "warn" -> Color(0xFFFFBF00)
        "err" -> Color(0xFFF44336)
        else -> NaturalLogGreen
    }

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(text = "[$formattedTime] ", color = NaturalLogGreen.copy(alpha = 0.4f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text(text = log.text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Modifier.bounceClick(interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "bounceClick"
    )
    return this.graphicsLayer(scaleX = scale, scaleY = scale)
}
