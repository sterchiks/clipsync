package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ClipItem
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Specific palette matching the requested "Bento Grid" theme
// FEF7FF Background, 1D1B20 primary text, F3EDF7 and E8DEF8 for cards & highlights.
val BentoBackground = Color(0xFFFEF7FF)
val BentoTextPrimary = Color(0xFF1D1B20)
val BentoTextSecondary = Color(0xFF49454F)
val BentoCardBg = Color(0xFFF3EDF7)
val BentoPurpleHighlight = Color(0xFF6750A4)
val BentoPillActive = Color(0xFFE8DEF8)
val BentoLiveGreen = Color(0xFF48B14C)
val BentoAlertRed = Color(0xFFB3261E)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BentoBackground),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BentoBackground)
                            .padding(innerPadding)
                    ) {
                        DashboardScreen()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel = viewModel()) {
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val serverIp by viewModel.serverIp.collectAsState()
    val pcIp by viewModel.pcIp.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val lastReceivedText by viewModel.lastReceivedText.collectAsState()
    val clipHistory by viewModel.clipHistory.collectAsState()

    var showEditPcIpDialog by remember { mutableStateOf(false) }
    var tempIpInput by remember { mutableStateOf("") }

    // Synchronize IP text state when dialog opens
    LaunchedEffect(showEditPcIpDialog) {
        if (showEditPcIpDialog) {
            tempIpInput = pcIp
        }
    }

    if (showEditPcIpDialog) {
        AlertDialog(
            onDismissRequest = { showEditPcIpDialog = false },
            title = {
                Text(
                    "Set Linux PC IP",
                    fontWeight = FontWeight.Bold,
                    color = BentoTextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the IP address of your Linux Mint computer running ClipSync daemon.",
                        fontSize = 13.sp,
                        color = BentoTextSecondary
                    )
                    OutlinedTextField(
                        value = tempIpInput,
                        onValueChange = { tempIpInput = it },
                        placeholder = { Text("e.g. 192.168.1.15") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updatePcIp(tempIpInput)
                        showEditPcIpDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BentoPurpleHighlight)
                ) {
                    Text("Save Pair IP")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditPcIpDialog = false }) {
                    Text("Cancel", color = BentoTextSecondary)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp)
    ) {
        // 1. HEADER SECTION (Matches exact LinkBoard branding specs)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ClipSync",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = BentoTextPrimary,
                        letterSpacing = (-0.5).sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (isServiceRunning) BentoLiveGreen else BentoAlertRed,
                                    CircleShape
                                )
                        )
                        Text(
                            text = if (isServiceRunning) "Service Active • 24/7" else "Service Inactive",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoTextSecondary,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(BentoPillActive),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isServiceRunning) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "System Status Icon",
                        tint = BentoTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 2. LARGE BENTO CARD: CURRENT CLIPBOARD
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BentoCardBg),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFE6E0E9), RoundedCornerShape(28.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Clipboard Logo",
                                tint = BentoPurpleHighlight,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "CURRENT SYNCED BUFFER",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoPurpleHighlight,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Text(
                            text = "Live Sync Active",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = BentoTextSecondary
                        )
                    }

                    // Display current text block
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.5f))
                            .padding(14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = lastReceivedText.ifEmpty { "No dynamic clips synchronized yet. Execute daemon on Linux PC, or send from Phone." },
                            color = BentoTextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 20.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Bottom Row: Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.sendClipboardToPc() },
                            enabled = isServiceRunning && pcIp.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BentoPurpleHighlight,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send, 
                                contentDescription = "Send clipboard",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Push to PC",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        IconButton(
                            onClick = {
                                if (lastReceivedText.isNotEmpty()) {
                                    viewModel.copyTextToClipboard(lastReceivedText)
                                }
                            },
                            enabled = lastReceivedText.isNotEmpty(),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(BentoPillActive)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Copy local",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // 3. TWO COLUMN BENTO ROW (GRID)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // SQR 1: HOST PC STATUS CARD (Interactive)
                Card(
                    colors = CardDefaults.cardColors(containerColor = BentoCardBg),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .border(1.dp, Color(0xFFE6E0E9), RoundedCornerShape(28.dp))
                        .clickable { showEditPcIpDialog = true }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(BentoPillActive),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "Desktop Windows Logo",
                                    tint = BentoTextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = "Edit IP",
                                tint = BentoPurpleHighlight.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "HOST PC IP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoPurpleHighlight,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = pcIp.ifEmpty { "Tap to Pair" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Linux Mint 22.3\n(KDE Plasma)",
                                fontSize = 11.sp,
                                color = BentoTextSecondary,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }

                // SQR 2: LOCAL PHONE IP CARD
                Card(
                    colors = CardDefaults.cardColors(containerColor = BentoCardBg),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .border(1.dp, Color(0xFFE6E0E9), RoundedCornerShape(28.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BentoPillActive),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Local Network Logo",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                "LOCAL PHONE IP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoPurpleHighlight,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = if (serverIp == "Unknown") "WiFi disconnected" else "$serverIp",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = BentoTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Listener active\non Port 40040",
                                fontSize = 10.sp,
                                color = BentoTextSecondary,
                                lineHeight = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // 4. DISCOVERED CLIENTS SUB-SECTION (Lists auto-paired LAN targets)
        if (discoveredPeers.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BentoCardBg),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFE6E0E9), RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "DISCOVERED LAN CLIENTS (UDP PAIR)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BentoPurpleHighlight,
                            letterSpacing = 0.5.sp
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            discoveredPeers.forEach { peerIp ->
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(BentoPillActive)
                                        .clickable { viewModel.updatePcIp(peerIp) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = "Target PC",
                                        tint = BentoTextPrimary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        peerIp,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BentoTextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. QUICK TOGGLE ROW: SERVICE CONTROL SWITCH
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BentoCardBg),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFE6E0E9), RoundedCornerShape(24.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BentoPillActive),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync loop status",
                                tint = BentoTextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                "Auto-Sync Daemon",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = BentoTextPrimary
                            )
                            Text(
                                "Bidirectional 24/7 transfer active",
                                fontSize = 11.sp,
                                color = BentoTextSecondary
                            )
                        }
                    }
                    
                    Switch(
                        checked = isServiceRunning,
                        onCheckedChange = { viewModel.toggleService() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BentoPurpleHighlight,
                            uncheckedThumbColor = BentoTextSecondary,
                            uncheckedTrackColor = BentoCardBg
                        )
                    )
                }
            }
        }

        // 6. SYNC BUFFER HISTORY HEADER
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Sync History Logs",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = BentoTextPrimary
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(BentoTextPrimary)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${clipHistory.size}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                if (clipHistory.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.clearAllHistory() },
                        colors = ButtonDefaults.textButtonColors(contentColor = BentoAlertRed)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear all database trace",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear AllLogs")
                    }
                }
            }
        }

        // 7. HISTORY LIST OR BENTO EMPTY GUIDELINES STATE
        if (clipHistory.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFE6E0E9), RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = BentoCardBg.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Setup & Get Started Guidelines",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = BentoPurpleHighlight
                        )
                        BulletPoint("1. Ensure PC and Phone are on the same Wi-Fi network.")
                        BulletPoint("2. Install and launch the ClipSync utility on your Linux PC.")
                        BulletPoint("3. Copy any text on either device and see it synchronize immediately!")
                        BulletPoint("4. Disable battery saver for ClipSync to run 24/7 background listeners.")
                    }
                }
            }
        } else {
            items(clipHistory, key = { it.id }) { clip ->
                HistoryItemRow(
                    clip = clip,
                    onCopy = { viewModel.copyTextToClipboard(clip.text) },
                    onDelete = { viewModel.deleteHistoryItem(clip) }
                )
            }
        }
    }
}

@Composable
fun BulletPoint(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("• ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = BentoPurpleHighlight)
        Text(text, fontSize = 13.sp, color = BentoTextSecondary)
    }
}

@Composable
fun HistoryItemRow(
    clip: ClipItem,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss (dd.MM)", Locale.getDefault()) }
    val formattedTime = formatter.format(Date(clip.timestamp))

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BentoCardBg.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy() }
            .border(1.dp, Color(0xFFE6E0E9).copy(alpha = 0.5f), RoundedCornerShape(18.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (clip.isSent) 
                            BentoPurpleHighlight.copy(alpha = 0.12f) 
                        else 
                            BentoPillActive
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (clip.isSent) Icons.Default.Send else Icons.Default.Share,
                    contentDescription = if (clip.isSent) "Sent" else "Received",
                    tint = if (clip.isSent) BentoPurpleHighlight else BentoTextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (clip.isSent) "Sent to PC" else "Received from PC",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (clip.isSent) BentoPurpleHighlight else BentoTextPrimary
                    )
                    Text(
                        formattedTime,
                        fontSize = 10.sp,
                        color = BentoTextSecondary.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = clip.text,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    color = BentoTextPrimary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete from history log",
                    tint = BentoAlertRed.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
