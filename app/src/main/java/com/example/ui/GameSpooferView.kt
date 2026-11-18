package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle // <--- THÊM IMPORT NÀY VÀO
import com.example.data.DiscordAccount
import com.example.data.TaskLog
import com.example.ui.theme.*

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

    // Theo dõi trạng thái execution của spoofer service
    val spooferExecuting by com.example.network.DiscordGameTimeService.isExecuting.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cấu hình thông số Spoofer
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NaturalSageContainer),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, NaturalBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Game Time Spoofer Config",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = NaturalDarkText
                )

                OutlinedTextField(
                    value = totalHoursInput,
                    onValueChange = { totalHoursInput = it },
                    label = { Text("Tổng số giờ cày (Total Hours)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = titlesInput,
                    onValueChange = { titlesInput = it },
                    label = { Text("Số lượng game (Titles)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = batchSizeInput,
                    onValueChange = { batchSizeInput = it },
                    label = { Text("Batch Size") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dao động giờ (Wobble)", fontSize = 14.sp, color = NaturalDarkText)
                    Switch(checked = wobbleChecked, onCheckedChange = { wobbleChecked = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Chạy thử (Dry Run)", fontSize = 14.sp, color = NaturalDarkText)
                    Switch(checked = dryRunChecked, onCheckedChange = { dryRunChecked = it })
                }
            }
        }

        // Nút Start / Stop Spoofer
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
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                imageVector = if (spooferExecuting) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (spooferExecuting) "Dừng Game Spoofer" else "Chạy Game Spoofer",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        // Terminal Log Output
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Spoofer Terminal Output",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = NaturalDarkText
            )
            Text(
                text = "Clear Term",
                fontSize = 12.sp,
                color = Color(0xFFC0392B),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onClearLogs() }.padding(8.dp)
            )
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(logScrollState)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "Chưa có bản ghi log nào...",
                        color = NaturalLogGreen.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                } else {
                    logs.asReversed().forEach { log ->
                        LogRow(log)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(40.dp))
    }
}
