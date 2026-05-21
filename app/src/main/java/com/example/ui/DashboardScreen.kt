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
                    onOptionChange = { enroll, claim, sound ->
                        viewModel.updateAccountOptions(enroll, claim, sound)
                    },
                    onClearLogs = { viewModel.clearLogs() }
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
        // App Title Section
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

        // Card Container setup
        Card(
            modifier = Modifier
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NaturalSageContainer),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, NaturalBorder)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
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
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Key",
                            tint = NaturalSecondaryText
                        )
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

        // Large rounded green CTA button
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
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = "Login",
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Connect Account",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
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
    onOptionChange: (Boolean, Boolean, Boolean) -> Unit,
    onClearLogs: () -> Unit
) {
    val sortedQuests = remember(quests) {
        quests.sortedWith(
            compareBy<DiscordQuest> { it.completed } // Keep native API order, just push completed tasks to the bottom
        )
    }
    var showLogoutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Flat header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AutoQuest",
                    fontSize = 24.sp,
                    color = NaturalDarkText,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Discord Automation Hub",
                    fontSize = 12.sp,
                    color = NaturalSecondaryText
                )
            }

            // Quick Refresh Button
            IconButton(
                onClick = onRefresh,
                enabled = !isExecuting,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(NaturalSageContainer)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = NaturalPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Main Layout inside LazyColumn so everything compiles fine across sizes
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Logged Account card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NaturalSageContainer),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, NaturalBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar placeholder circle
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(NaturalAccentGreen)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "Discord avatar placeholder",
                                tint = NaturalPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = account.username.ifEmpty { "Connected Account" },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = NaturalDarkText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Token: ••••••••" + account.token.takeLast(4),
                                fontSize = 12.sp,
                                color = NaturalSecondaryText
                            )
                        }

                        IconButton(onClick = { showLogoutDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "Logout",
                                tint = Color(0xFFC0392B)
                            )
                        }
                    }
                }
            }



            // 3. active / Selectable quests
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
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
                                .clickable(
                                    interactionSource = allInteraction,
                                    indication = null
                                ) { onSelectAll() }
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
                                .clickable(
                                    interactionSource = noneInteraction,
                                    indication = null
                                ) { onDeselectAll() }
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Inbox,
                                contentDescription = "Empty",
                                tint = NaturalSecondaryText,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Không tìm thấy nhiệm vụ nào",
                                fontSize = 14.sp,
                                color = NaturalDarkText,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Hãy ấn vào biểu tượng làm mới ở góc phải để tải danh sách nhiệm vụ từ Discord.",
                                fontSize = 11.sp,
                                color = NaturalSecondaryText,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp)
                            )
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

            // 4. Console log output
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Real-Time Terminal Output",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NaturalDarkText
                    )

                    Text(
                        text = "Clear Term",
                        fontSize = 12.sp,
                        color = Color(0xFFC0392B),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onClearLogs() }
                            .padding(8.dp)
                    )
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

                    // Auto scroll logs console to bottom
                    LaunchedEffect(logs.size) {
                        logScrollState.animateScrollTo(logScrollState.maxValue)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(logScrollState)
                    ) {
                        if (logs.isEmpty()) {
                            Text(
                                text = "Lớp nhập ký tự rỗng. Nhấn 'Chạy Tự Động' để bắt đầu ghi nhật ký...",
                                color = NaturalLogGreen.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        } else {
                            // Reverse order to display oldest-to-newest sequentially in the console row
                            logs.asReversed().forEach { log ->
                                LogRow(log)
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(60.dp)) }
        }

        // Sticky Bottom Controllers
        val activeScale by animateFloatAsState(
            targetValue = if (isExecuting) 0.96f else 1.0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "activeScale"
        )
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.98f,
            targetValue = 1.02f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, NaturalBorder),
            color = NaturalBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Background task running details
                if (isExecuting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = NaturalPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = NaturalPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!isExecuting) {
                        val runInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = onRunAutomation,
                            colors = ButtonDefaults.buttonColors(containerColor = NaturalPrimary, contentColor = Color.White),
                            shape = RoundedCornerShape(30.dp),
                            interactionSource = runInteraction,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .bounceClick(runInteraction)
                                .graphicsLayer(scaleX = activeScale, scaleY = activeScale)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Run")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Chạy Tự Động", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        val stopInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = onStopAutomation,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0392B), contentColor = Color.White),
                            shape = RoundedCornerShape(30.dp),
                            interactionSource = stopInteraction,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .bounceClick(stopInteraction)
                                .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                        ) {
                            Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Dừng Lại", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout Account", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to disconnect your Discord account? This will clear all logs and credentials locally.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    }
                ) {
                    Text("Logout", color = Color(0xFFC0392B))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel", color = NaturalDarkText)
                }
            },
            containerColor = NaturalBg
        )
    }
}

@Composable
fun QuestCard(
    quest: DiscordQuest,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val progressPercent = if (quest.target > 0) {
        (quest.currentProgress.toFloat() / quest.target.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progressPercent,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "animatedProgress"
    )

    // Color indicators based on state
    val statusColor = when {
        quest.completed -> Color(0xFF386B20)
        else -> NaturalPrimary
    }

    val stateLabel = when {
        quest.completed -> "Hoàn Thành"
        quest.enrolled -> "Đang Tiến Hành"
        else -> "Trống (Chờ Nhận)"
    }

    val iconSymbol = when (quest.type) {
        "WATCH_VIDEO" -> Icons.Default.VideoLibrary
        "GAME" -> Icons.Default.SportsEsports
        "STREAM" -> Icons.Default.LiveTv
        "ACTIVITY" -> Icons.Default.Campaign
        else -> Icons.Default.Star
    }

    val cardInteractionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(cardInteractionSource)
            .clickable(
                interactionSource = cardInteractionSource,
                indication = LocalIndication.current,
                enabled = !quest.completed
            ) { onToggle() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, if (isSelected && !quest.completed) NaturalPrimary else NaturalBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox for selection (Hidden if already completed)
            if (!quest.completed) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = NaturalPrimary,
                        uncheckedColor = NaturalBorder
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(NaturalAccentGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Done",
                        tint = NaturalPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            // Central icon
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(NaturalAccentGreen)
            ) {
                Icon(
                    imageVector = iconSymbol,
                    contentDescription = "Quest type",
                    tint = NaturalPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stateLabel.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "${quest.type}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NaturalSecondaryText
                    )
                }

                Text(
                    text = quest.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = NaturalDarkText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )

                // Sub details with rewards
                Text(
                    text = "Qùa tặng: ${quest.rewardName}",
                    fontSize = 12.sp,
                    color = NaturalSecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!quest.completed) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Tiến độ",
                            fontSize = 11.sp,
                            color = NaturalSecondaryText
                        )
                        Text(
                            text = "${quest.currentProgress} / ${quest.target}s",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NaturalDarkText
                        )
                    }

                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = NaturalPrimary,
                        trackColor = NaturalSageContainer
                    )
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
        "debug" -> NaturalLogGreen.copy(alpha = 0.5f)
        else -> NaturalLogGreen
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "[$formattedTime] ",
            color = NaturalLogGreen.copy(alpha = 0.4f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Text(
            text = log.text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f)
        )
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
