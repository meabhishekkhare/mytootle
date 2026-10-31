package com.mytootle.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

val ElectricBlue = Color(0xFF0A84FF)
val OledBlack = Color(0xFF000000)
val DarkCardBg = Color(0xFF121214)
val StarkWhite = Color(0xFFFFFFFF)
val MutedGrey = Color(0xFF8E8E93)
val BorderDark = Color(0xFF2C2C2E)
val AlertRed = Color(0xFFFF453A)

data class Peer(
    val identity: String,
    val host: InetAddress? = null,
    val port: Int = 49200,
    var isSaved: Boolean = false,
    var isPokedByMe: Boolean = false,
    var isPokingMe: Boolean = false,
    var proximity: String = "Immediate",
    var statusText: String = "Unseen - poke back to match",
    var statusColor: Color = ElectricBlue,
    var revertsCount: Int = 0
)

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String,
    val text: String = "",
    val type: MessageType = MessageType.TEXT,
    val timestamp: String = "10:14 PM",
    var isSeen: Boolean = false
)

enum class MessageType { TEXT, PHOTO, AUDIO, POKE, SYSTEM }

class MainActivity : ComponentActivity() {

    private lateinit var nsdManager: NsdManager
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        setContent {
            MyTootleApp(
                onStartMesh = { id -> startMeshServer(id) },
                onStopMesh = { stopMeshServer() },
                onSendPoke = { target, myId -> broadcastPoke(target, myId) }
            )
        }
    }

    private fun startMeshServer(localId: String) {
        if (isListening) return
        scope.launch {
            try {
                serverSocket = ServerSocket(49200).apply { reuseAddress = true }
                isListening = true
                while (isListening && serverSocket != null && !serverSocket!!.isClosed) {
                    val socket = serverSocket!!.accept()
                    launch { handleSocket(socket) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleSocket(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val line = reader.readLine() ?: return
            val json = JSONObject(line)
            val type = json.optString("type")
            val senderId = json.optString("from")

            runOnUiThread {
                when (type) {
                    "POKE" -> {
                        Toast.makeText(this, "$senderId poked you!", Toast.LENGTH_SHORT).show()
                    }
                    "HANDSHAKE_REQ" -> {
                        val note = json.optString("note")
                        Toast.makeText(this, "Connect request from $senderId: $note", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (_: Exception) {} finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun broadcastPoke(targetHost: String?, myId: String) {
        scope.launch {
            try {
                if (targetHost != null) {
                    val socket = Socket(targetHost, 49200)
                    val writer = PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)
                    val json = JSONObject().apply {
                        put("type", "POKE")
                        put("from", myId)
                    }
                    writer.println(json.toString())
                    writer.flush()
                    socket.close()
                }
            } catch (_: Exception) {}
        }
    }

    private fun stopMeshServer() {
        isListening = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMeshServer()
        scope.cancel()
    }
}

@Composable
fun MyTootleApp(
    onStartMesh: (String) -> Unit,
    onStopMesh: () -> Unit,
    onSendPoke: (String?, String) -> Unit
) {
    var currentScreen by remember { mutableStateOf(0) }
    var isDayMode by remember { mutableStateOf(false) }

    var connectionMode by remember { mutableStateOf("Wi-Fi Network") }
    var pincode by remember { mutableStateOf("482001") }
    var resolvedLocation by remember { mutableStateOf("Jabalpur, India (auto-filled)") }
    var phoneNumber by remember { mutableStateOf("+91 98XXXXXXXX") }
    var identity by remember { mutableStateOf("shadow_runner_01") }
    var isKeystoreActive by remember { mutableStateOf(true) }
    var isZeroCloudEnforced by remember { mutableStateOf(true) }

    var isOnline by remember { mutableStateOf(true) }
    var activeTab by remember { mutableStateOf("newtOOtle") }
    var peerForHandshake by remember { mutableStateOf<Peer?>(null) }
    var handshakeNote by remember { mutableStateOf("") }

    var activeChatPeer by remember { mutableStateOf("echo_terminal_88") }
    var isPeerSaved by remember { mutableStateOf(false) }
    var extensionCount by remember { mutableStateOf(0) }
    var remainingSeconds by remember { mutableStateOf(86400L) }
    var isLiveActive by remember { mutableStateOf(false) }
    var showLiveDisclaimer by remember { mutableStateOf(false) }
    var isDropUpOpen by remember { mutableStateOf(false) }

    val peers = remember {
        mutableStateListOf(
            Peer("echo_terminal_88", proximity = "Immediate", statusText = "Ready to connect", statusColor = ElectricBlue),
            Peer("cipher_ghost_12", proximity = "Nearby", statusText = "Seen · <24h remaining", statusColor = MutedGrey),
            Peer("vector_drifter", proximity = "In Range", statusText = "Ready to connect", statusColor = ElectricBlue),
            Peer("rogue_drifter_7", proximity = "In Range", statusText = "Ready to Delete (2+ reverts)", statusColor = AlertRed, revertsCount = 3),
            Peer("dark_beacon", proximity = "Nearby", isPokingMe = true, statusText = "Unseen - poke back to match", statusColor = ElectricBlue),
            Peer("silent_tracker", proximity = "In Range", isSaved = true, statusText = "Saved contact", statusColor = MutedGrey)
        )
    }

    val messages = remember {
        mutableStateListOf(
            ChatMessage(sender = "echo_terminal_88", text = "At north entrance near gate.", type = MessageType.TEXT),
            ChatMessage(sender = "You", text = "Sent snap.", type = MessageType.TEXT),
            ChatMessage(sender = "echo_terminal_88", type = MessageType.PHOTO, isSeen = true),
            ChatMessage(sender = "echo_terminal_88", type = MessageType.AUDIO, isSeen = false),
            ChatMessage(sender = "echo_terminal_88", text = "echo_terminal_88 POKED BACK (Mutual Match)", type = MessageType.POKE)
        )
    }

    val bgPrimary = if (isDayMode) StarkWhite else OledBlack
    val textPrimary = if (isDayMode) OledBlack else StarkWhite
    val cardBg = if (isDayMode) Color(0xFFF2F2F7) else DarkCardBg
    val outlineBorder = if (isDayMode) Color(0xFFD1D1D6) else BorderDark

    LaunchedEffect(currentScreen) {
        while (currentScreen == 2 && remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgPrimary)
    ) {
        when (currentScreen) {
            0 -> WelcomeScreen(
                bgPrimary = bgPrimary,
                textPrimary = textPrimary,
                cardBg = cardBg,
                outlineBorder = outlineBorder,
                connectionMode = connectionMode,
                onModeChange = { connectionMode = it },
                isDayMode = isDayMode,
                onDayModeToggle = { isDayMode = it },
                pincode = pincode,
                onPincodeChange = {
                    pincode = it
                    resolvedLocation = if (it.trim() == "482001") "Jabalpur, India (auto-filled)" else "India Subnet Location"
                },
                resolvedLocation = resolvedLocation,
                phoneNumber = phoneNumber,
                onPhoneChange = { phoneNumber = it },
                identity = identity,
                onIdentityChange = { identity = it },
                isKeystoreActive = isKeystoreActive,
                onKeystoreToggle = { isKeystoreActive = it },
                isZeroCloudEnforced = isZeroCloudEnforced,
                onZeroCloudToggle = { isZeroCloudEnforced = it },
                onInitialize = {
                    onStartMesh(identity)
                    currentScreen = 1
                }
            )

            1 -> RadarScreen(
                bgPrimary = bgPrimary,
                textPrimary = textPrimary,
                cardBg = cardBg,
                outlineBorder = outlineBorder,
                isOnline = isOnline,
                onToggleOnline = {
                    isOnline = it
                    if (!it) onStopMesh() else onStartMesh(identity)
                },
                activeTab = activeTab,
                onTabSelect = { activeTab = it },
                peers = peers,
                onPoke = { peer ->
                    peer.isPokedByMe = true
                    if (peer.isPokingMe || peer.identity == "echo_terminal_88") {
                        peer.isPokingMe = true
                        activeChatPeer = peer.identity
                        currentScreen = 2
                    } else {
                        onSendPoke(peer.host?.hostAddress, identity)
                    }
                },
                onConnect = { peer ->
                    peerForHandshake = peer
                    handshakeNote = ""
                }
            )

            2 -> EphemeralChatScreen(
                bgPrimary = bgPrimary,
                textPrimary = textPrimary,
                cardBg = cardBg,
                outlineBorder = outlineBorder,
                peerHandle = activeChatPeer,
                isSaved = isPeerSaved,
                onToggleSave = { isPeerSaved = !isPeerSaved },
                remainingSeconds = remainingSeconds,
                extensionCount = extensionCount,
                onExtend = {
                    if (extensionCount < 2) {
                        extensionCount++
                        remainingSeconds = 86400L
                    }
                },
                messages = messages,
                isLiveActive = isLiveActive,
                onToggleLive = {
                    isLiveActive = !isLiveActive
                    showLiveDisclaimer = isLiveActive
                },
                showLiveDisclaimer = showLiveDisclaimer,
                onDismissDisclaimer = { showLiveDisclaimer = false },
                isDropUpOpen = isDropUpOpen,
                onToggleDropUp = { isDropUpOpen = !isDropUpOpen },
                onAddMedia = { type ->
                    isDropUpOpen = false
                    messages.add(ChatMessage(sender = "You", type = type))
                },
                onBackHome = { currentScreen = 1 },
                onLogout = {
                    currentScreen = 0
                    onStopMesh()
                }
            )
        }
    }

    if (peerForHandshake != null) {
        val wordCount = if (handshakeNote.trim().isEmpty()) 0 else handshakeNote.trim().split("\\s+".toRegex()).size
        Dialog(onDismissRequest = { peerForHandshake = null }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(cardBg)
                    .border(1.dp, ElectricBlue, RoundedCornerShape(14.dp))
                    .padding(18.dp)
            ) {
                Column {
                    Text(
                        text = "Connect with ${peerForHandshake?.identity}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Write a direct handshake note (max 20 words):",
                        fontSize = 11.sp,
                        color = MutedGrey
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = handshakeNote,
                        onValueChange = { input ->
                            val words = if (input.trim().isEmpty()) 0 else input.trim().split("\\s+".toRegex()).size
                            if (words <= 20) {
                                handshakeNote = input
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = outlineBorder,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary
                        ),
                        placeholder = { Text("Hey, let's link on local subnet...", fontSize = 12.sp, color = MutedGrey) }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text("$wordCount/20 words", fontSize = 10.sp, color = if (wordCount >= 20) AlertRed else MutedGrey)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, outlineBorder, RoundedCornerShape(8.dp))
                                .clickable { peerForHandshake = null }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Cancel", fontSize = 12.sp, color = textPrimary)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(OledBlack)
                                .border(1.dp, ElectricBlue, RoundedCornerShape(8.dp))
                                .clickable {
                                    activeChatPeer = peerForHandshake!!.identity
                                    peerForHandshake = null
                                    currentScreen = 2
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Send & Open", fontSize = 12.sp, color = ElectricBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WelcomeScreen(
    bgPrimary: Color,
    textPrimary: Color,
    cardBg: Color,
    outlineBorder: Color,
    connectionMode: String,
    onModeChange: (String) -> Unit,
    isDayMode: Boolean,
    onDayModeToggle: (Boolean) -> Unit,
    pincode: String,
    onPincodeChange: (String) -> Unit,
    resolvedLocation: String,
    phoneNumber: String,
    onPhoneChange: (String) -> Unit,
    identity: String,
    onIdentityChange: (String) -> Unit,
    isKeystoreActive: Boolean,
    onKeystoreToggle: (Boolean) -> Unit,
    isZeroCloudEnforced: Boolean,
    onZeroCloudToggle: (Boolean) -> Unit,
    onInitialize: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "mytOOtle",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                    fontFamily = FontFamily.SansSerif
                )
                Box(
                    modifier = Modifier
                        .border(1.dp, outlineBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "local p2p enclave",
                        fontSize = 11.sp,
                        color = MutedGrey,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("CONNECTION MODE", fontSize = 11.sp, color = MutedGrey, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Wi-Fi Network", "Local Hotspot").forEach { mode ->
                    val isSelected = connectionMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0x1F0A84FF) else cardBg)
                            .border(1.dp, if (isSelected) ElectricBlue else outlineBorder, RoundedCornerShape(8.dp))
                            .clickable { onModeChange(mode) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isSelected) "$mode •" else mode,
                            fontSize = 12.sp,
                            color = if (isSelected) ElectricBlue else textPrimary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Screen Color", fontSize = 13.sp, color = textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isDayMode) "Day" else "Night", fontSize = 12.sp, color = MutedGrey)
                    Switch(
                        checked = isDayMode,
                        onCheckedChange = onDayModeToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = StarkWhite,
                            checkedTrackColor = ElectricBlue,
                            uncheckedThumbColor = MutedGrey,
                            uncheckedTrackColor = DarkCardBg
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("PINCODE", fontSize = 11.sp, color = MutedGrey, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = pincode,
                onValueChange = onPincodeChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = outlineBorder,
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary
                ),
                singleLine = true
            )
            Text(resolvedLocation, fontSize = 11.sp, color = MutedGrey, modifier = Modifier.padding(top = 4.dp, start = 2.dp))

            Spacer(modifier = Modifier.height(16.dp))

            Text("PHONE NUMBER", fontSize = 11.sp, color = MutedGrey, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = onPhoneChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = outlineBorder,
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("IDENTITY (MIN 12 CHARACTERS)", fontSize = 11.sp, color = MutedGrey, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = identity,
                onValueChange = onIdentityChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = outlineBorder,
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Keystore Identity: Active", fontSize = 12.sp, color = textPrimary)
                Switch(checked = isKeystoreActive, onCheckedChange = onKeystoreToggle)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Zero Cloud Storage: Enforced", fontSize = 12.sp, color = textPrimary)
                Switch(checked = isZeroCloudEnforced, onCheckedChange = onZeroCloudToggle)
            }
        }

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OledBlack)
                    .border(1.dp, ElectricBlue, RoundedCornerShape(10.dp))
                    .clickable { onInitialize() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "[ let's 💬 ]",
                    color = ElectricBlue,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "No internet connection used - safe network established.\nAct mature and care for others while using platform.",
                fontSize = 11.sp,
                color = MutedGrey,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun RadarScreen(
    bgPrimary: Color,
    textPrimary: Color,
    cardBg: Color,
    outlineBorder: Color,
    isOnline: Boolean,
    onToggleOnline: (Boolean) -> Unit,
    activeTab: String,
    onTabSelect: (String) -> Unit,
    peers: List<Peer>,
    onPoke: (Peer) -> Unit,
    onConnect: (Peer) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("mytOOtle", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = textPrimary)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x1F0A84FF))
                    .border(1.dp, if (isOnline) ElectricBlue else MutedGrey, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isOnline) "(( LIVE: ${peers.size}/15 ))" else "(( OFFLINE ))",
                    color = if (isOnline) ElectricBlue else MutedGrey,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isOnline) Color(0x1F0A84FF) else cardBg)
                    .border(1.dp, if (isOnline) ElectricBlue else outlineBorder, RoundedCornerShape(8.dp))
                    .clickable { onToggleOnline(true) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "* Open to Find",
                    fontSize = 12.sp,
                    color = if (isOnline) ElectricBlue else textPrimary
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (!isOnline) Color(0x1F8E8E93) else cardBg)
                    .border(1.dp, if (!isOnline) MutedGrey else outlineBorder, RoundedCornerShape(8.dp))
                    .clickable { onToggleOnline(false) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Offline",
                    fontSize = 12.sp,
                    color = if (!isOnline) MutedGrey else textPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(
                Triple("mytOOtle", "saved contacts", "mytOOtle"),
                Triple("mypoke", "recent pings", "mypoke"),
                Triple("newtOOtle", "highlighted", "newtOOtle")
            ).forEach { (title, subtitle, key) ->
                val isSelected = activeTab == key
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTabSelect(key) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) ElectricBlue else Color.Transparent,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (key == "newtOOtle") "* $title" else title,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) ElectricBlue else textPrimary
                        )
                    }
                    Text(subtitle, fontSize = 10.sp, color = MutedGrey)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isOnline && activeTab == "newtOOtle") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 40.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = "Discovery radios disabled in Offline Mode.\nSwitch to Open to Find to discover nearby peers.",
                    color = MutedGrey,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val filteredPeers = remember(activeTab, isOnline, peers.toList()) {
                when (activeTab) {
                    "mytOOtle" -> peers.filter { it.isSaved }
                    "mypoke" -> peers.filter { it.isPokedByMe || it.isPokingMe }
                    "newtOOtle" -> peers.filter { !it.isSaved }
                    else -> peers
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (filteredPeers.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No peers found in this category.", fontSize = 12.sp, color = MutedGrey)
                        }
                    }
                } else {
                    items(filteredPeers) { peer ->
                        PeerCard(
                            peer = peer,
                            cardBg = cardBg,
                            textPrimary = textPrimary,
                            outlineBorder = outlineBorder,
                            onPoke = { onPoke(peer) },
                            onConnect = { onConnect(peer) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PeerCard(
    peer: Peer,
    cardBg: Color,
    textPrimary: Color,
    outlineBorder: Color,
    onPoke: () -> Unit,
    onConnect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(1.dp, outlineBorder, RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.identity, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                Text("Proximity: ${peer.proximity}", fontSize = 11.sp, color = MutedGrey)
                Spacer(modifier = Modifier.height(4.dp))
                Text(peer.statusText, fontSize = 11.sp, color = peer.statusColor)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .border(1.dp, outlineBorder, RoundedCornerShape(6.dp))
                        .clickable { onConnect() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Connect", fontSize = 12.sp, color = textPrimary)
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(OledBlack)
                        .border(1.5.dp, ElectricBlue, CircleShape)
                        .clickable { onPoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("👉", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun EphemeralChatScreen(
    bgPrimary: Color,
    textPrimary: Color,
    cardBg: Color,
    outlineBorder: Color,
    peerHandle: String,
    isSaved: Boolean,
    onToggleSave: () -> Unit,
    remainingSeconds: Long,
    extensionCount: Int,
    onExtend: () -> Unit,
    messages: List<ChatMessage>,
    isLiveActive: Boolean,
    onToggleLive: () -> Unit,
    showLiveDisclaimer: Boolean,
    onDismissDisclaimer: () -> Unit,
    isDropUpOpen: Boolean,
    onToggleDropUp: () -> Unit,
    onAddMedia: (MessageType) -> Unit,
    onBackHome: () -> Unit,
    onLogout: () -> Unit
) {
    val hours = remainingSeconds / 3600
    val minutes = (remainingSeconds % 3600) / 60
    val seconds = remainingSeconds % 60
    val timeFormatted = String.format("%02d:%02d:%02d", hours, minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onBackHome() }
                ) {
                    Text("< Homepage", fontSize = 12.sp, color = MutedGrey)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, AlertRed, RoundedCornerShape(6.dp))
                        .clickable { onLogout() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Logout", fontSize = 11.sp, color = AlertRed)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(peerHandle, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(OledBlack)
                            .border(1.2.dp, ElectricBlue, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("👉", fontSize = 12.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSaved) ElectricBlue else Color(0x1F0A84FF))
                        .border(1.dp, ElectricBlue, RoundedCornerShape(16.dp))
                        .clickable { onToggleSave() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isSaved) "🤝 saved" else "🤝 save",
                        fontSize = 11.sp,
                        color = if (isSaved) StarkWhite else ElectricBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text("local socket | direct encrypted link", fontSize = 10.sp, color = MutedGrey)

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(cardBg)
                    .border(1.dp, outlineBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("$timeFormatted remaining", fontSize = 11.sp, color = AlertRed, fontWeight = FontWeight.Bold)
                        Text("automatic 24h cleanup (${2 - extensionCount} extensions left)", fontSize = 10.sp, color = MutedGrey)
                    }

                    Box(
                        modifier = Modifier
                            .border(1.dp, if (extensionCount < 2) ElectricBlue else outlineBorder, RoundedCornerShape(6.dp))
                            .clickable(enabled = extensionCount < 2) { onExtend() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (extensionCount < 2) "⟳ Extend" else "Locked",
                            fontSize = 10.sp,
                            color = if (extensionCount < 2) ElectricBlue else MutedGrey
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(msg = msg, textPrimary = textPrimary, cardBg = cardBg)
            }
        }

        if (showLiveDisclaimer) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(cardBg)
                    .border(1.dp, ElectricBlue, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Active note: These videos cannot be saved or recorded.",
                        fontSize = 11.sp,
                        color = ElectricBlue,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Close [✕]",
                        fontSize = 11.sp,
                        color = MutedGrey,
                        modifier = Modifier.clickable { onDismissDisclaimer() }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isLiveActive) Color(0x1F0A84FF) else cardBg)
                        .border(1.dp, if (isLiveActive) ElectricBlue else outlineBorder, RoundedCornerShape(16.dp))
                        .clickable { onToggleLive() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isLiveActive) "Live 🎦 Active" else "+ go live 🎦",
                        fontSize = 12.sp,
                        color = if (isLiveActive) ElectricBlue else MutedGrey,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(cardBg)
                        .border(1.dp, outlineBorder, RoundedCornerShape(24.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "[ Message... ]",
                        fontSize = 13.sp,
                        color = MutedGrey,
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(ElectricBlue)
                            .clickable { onToggleDropUp() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", color = StarkWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (isDropUpOpen) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(y = (-52).dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(DarkCardBg)
                            .border(1.dp, ElectricBlue, RoundedCornerShape(20.dp))
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(OledBlack)
                                .clickable { onAddMedia(MessageType.PHOTO) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🤳", fontSize = 16.sp)
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(OledBlack)
                                .clickable { onAddMedia(MessageType.AUDIO) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🎶", fontSize = 16.sp)
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(OledBlack)
                                .border(1.2.dp, ElectricBlue, CircleShape)
                                .clickable { onAddMedia(MessageType.POKE) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("👉", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage, textPrimary: Color, cardBg: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (msg.type) {
            MessageType.TEXT -> {
                Text(msg.sender, fontSize = 10.sp, color = MutedGrey)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(msg.text, fontSize = 13.sp, color = textPrimary)
                }
            }
            MessageType.PHOTO -> {
                Text(msg.sender, fontSize = 10.sp, color = MutedGrey)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (msg.isSeen) Color(0xFF2C2C2E) else ElectricBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🤳", fontSize = 20.sp)
                }
            }
            MessageType.AUDIO -> {
                Text(msg.sender, fontSize = 10.sp, color = MutedGrey)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (msg.isSeen) Color(0xFF2C2C2E) else ElectricBlue)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🎶", fontSize = 14.sp)
                        Text("||||||||||||||||", fontSize = 10.sp, color = StarkWhite)
                    }
                }
            }
            MessageType.POKE -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x1F0A84FF))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("👉", fontSize = 14.sp)
                    Text(msg.text, fontSize = 11.sp, color = ElectricBlue, fontWeight = FontWeight.SemiBold)
                }
            }
            MessageType.SYSTEM -> {}
        }
    }
}
