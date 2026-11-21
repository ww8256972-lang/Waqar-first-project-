package com.example.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.BuildConfig
import com.example.ui.designsystem.GlassCard
import com.example.ui.designsystem.JetBrainsMono
import com.example.ui.designsystem.LiquidPill
import com.example.ui.designsystem.ShimmerButton
import com.example.ui.designsystem.WciColors
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Production Agora RTC Live Video Classroom Engine (`AgoraLiveClassManager.kt`).
 *
 * Fixes the Live Video Class black screen bug by:
 * 1. Keeping `BindScreenCapturePolicy(UNLOCKED_STUDY_FRIENDLY)` active so video surfaces are never blacked by FLAG_SECURE.
 * 2. Reading `BuildConfig.AGORA_APP_ID` / `BuildConfig.AGORA_TEMP_TOKEN` safely (via Secrets / .env).
 * 3. Requesting `CAMERA` + `RECORD_AUDIO` runtime permissions before initializing Agora `RtcEngine`.
 * 4. Attaching `TextureView` canvases strictly on the Android UI thread with `LifecycleEventObserver`
 *    handling `ON_PAUSE`, `ON_RESUME`, and `ON_DESTROY` cleanup without memory leaks.
 */
object AgoraLiveClassManager {

    private const val DEFAULT_APP_ID_PLACEHOLDER = "AGORA_APP_ID_DEFAULT_VALUE"
    private const val DEFAULT_TOKEN_PLACEHOLDER = "AGORA_TEMP_TOKEN_DEFAULT_VALUE"

    fun resolvedAgoraAppId(): String =
        BuildConfig.AGORA_APP_ID.trim().takeIf {
            it.isNotEmpty() && it != DEFAULT_APP_ID_PLACEHOLDER
        }.orEmpty()

    fun resolvedAgoraToken(): String? =
        BuildConfig.AGORA_TEMP_TOKEN.trim().takeIf {
            it.isNotEmpty() && it != DEFAULT_TOKEN_PLACEHOLDER
        }

    fun isConfigured(): Boolean = resolvedAgoraAppId().isNotEmpty()

    fun createEngineSafely(
        context: Context,
        eventHandler: IRtcEngineEventHandler
    ): Result<RtcEngine> = runCatching {
        val appId = resolvedAgoraAppId()
        require(appId.isNotBlank()) {
            "Agora App ID (BuildConfig.AGORA_APP_ID) is not configured."
        }
        val config = RtcEngineConfig().apply {
            mContext = context.applicationContext ?: context
            mAppId = appId
            mEventHandler = eventHandler
        }
        RtcEngine.create(config).apply {
            enableAudio()
            enableVideo()
            setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            setVideoEncoderConfiguration(
                VideoEncoderConfiguration(
                    VideoEncoderConfiguration.VD_640x360,
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_24,
                    VideoEncoderConfiguration.STANDARD_BITRATE,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
                )
            )
        }
    }
}

@Composable
fun AgoraLiveVideoClassroomRoom(
    channelId: String,
    sessionTitle: String,
    hostName: String,
    isHostRole: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    // Ensure window never activates FLAG_SECURE blackout over live video surfaces
    BindScreenCapturePolicy(ScreenCapturePolicy.UNLOCKED_STUDY_FRIENDLY)

    var hasAvPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    var isJoined by remember(channelId) { mutableStateOf(false) }
    var isConnecting by remember(channelId) { mutableStateOf(false) }
    var connectionErrorMessage by remember(channelId) { mutableStateOf<String?>(null) }
    var isAudioMuted by remember { mutableStateOf(false) }
    var isVideoMuted by remember { mutableStateOf(false) }
    var remoteUid by remember(channelId) { mutableStateOf<Int?>(null) }
    var statusText by remember(channelId) {
        mutableStateOf("Ready to join Live Video Room ($channelId)")
    }

    var rtcEngine by remember(channelId) { mutableStateOf<RtcEngine?>(null) }
    val localTextureView = remember(channelId) { TextureView(context) }
    val remoteTextureView = remember(channelId) { TextureView(context) }

    val rtcEventHandler = remember(channelId) {
        object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                mainExecutor.execute {
                    isJoined = true
                    isConnecting = false
                    connectionErrorMessage = null
                    statusText = "Connected Live • Channel: ${channel ?: channelId} (UID $uid)"
                }
            }

            override fun onUserJoined(uid: Int, elapsed: Int) {
                mainExecutor.execute {
                    remoteUid = uid
                    rtcEngine?.setupRemoteVideo(
                        VideoCanvas(remoteTextureView, VideoCanvas.RENDER_MODE_HIDDEN, uid)
                    )
                    statusText = "Instructor/Peer Live Video Active (UID $uid)"
                }
            }

            override fun onUserOffline(uid: Int, reason: Int) {
                mainExecutor.execute {
                    if (remoteUid == uid) {
                        remoteUid = null
                        rtcEngine?.setupRemoteVideo(
                            VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, uid)
                        )
                    }
                    statusText = "Remote participant left ($reason)"
                }
            }

            override fun onError(err: Int) {
                mainExecutor.execute {
                    isJoined = false
                    isConnecting = false
                    connectionErrorMessage = "Agora RTC connection error (Code $err). Verify network or token configuration."
                    statusText = "Connection failed (Agora Error $err) • Channel: $channelId"
                }
            }
        }
    }

    fun startOrJoinChannel() {
        if (!hasAvPermissions) return
        isJoined = false
        isConnecting = true
        connectionErrorMessage = null
        isVideoMuted = false
        statusText = "Connecting to Agora RTC Channel ($channelId)…"

        val engineResult = rtcEngine?.let { Result.success(it) }
            ?: AgoraLiveClassManager.createEngineSafely(context, rtcEventHandler)
                .onSuccess { rtcEngine = it }

        val engine = engineResult.getOrElse { err ->
            isJoined = false
            isConnecting = false
            connectionErrorMessage = err.localizedMessage ?: "Unable to initialize Agora RTC Engine."
            statusText = "Engine initialization failed: ${connectionErrorMessage.orEmpty()}"
            return
        }

        runCatching {
            val clientRole = if (isHostRole) Constants.CLIENT_ROLE_BROADCASTER else Constants.CLIENT_ROLE_AUDIENCE
            engine.setClientRole(clientRole)
            engine.setupLocalVideo(VideoCanvas(localTextureView, VideoCanvas.RENDER_MODE_HIDDEN, 0))
            engine.startPreview()
            engine.muteLocalAudioStream(isAudioMuted)
            engine.muteLocalVideoStream(isVideoMuted)

            val token = AgoraLiveClassManager.resolvedAgoraToken()
            val options = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                clientRoleType = clientRole
                autoSubscribeAudio = true
                autoSubscribeVideo = true
                publishCameraTrack = !isVideoMuted
                publishMicrophoneTrack = !isAudioMuted
            }
            val joinCode = engine.joinChannel(token, channelId.ifBlank { "wci_live_class" }, 0, options)
            if (joinCode < 0) {
                isJoined = false
                isConnecting = false
                connectionErrorMessage = "Agora joinChannel returned error code $joinCode."
                statusText = "Failed to join channel ($joinCode)"
            }
        }.onFailure { err ->
            isJoined = false
            isConnecting = false
            connectionErrorMessage = err.localizedMessage ?: "Failed to join Agora channel."
            statusText = "Connection error: ${connectionErrorMessage.orEmpty()}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.CAMERA] == true &&
            perms[Manifest.permission.RECORD_AUDIO] == true
        hasAvPermissions = granted
        if (granted) {
            startOrJoinChannel()
        } else {
            isJoined = false
            isConnecting = false
            connectionErrorMessage = "Camera and Microphone permissions are required for live video classes."
            statusText = "Permissions denied for Live Video Classroom"
        }
    }

    fun leaveRoom() {
        runCatching {
            rtcEngine?.stopPreview()
            rtcEngine?.leaveChannel()
        }
        isJoined = false
        isConnecting = false
        remoteUid = null
        statusText = "Left live video room"
    }

    DisposableEffect(lifecycleOwner, channelId) {
        val observer = LifecycleEventObserver { _, event ->
            val engine = rtcEngine ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    runCatching {
                        engine.muteLocalAudioStream(true)
                        engine.muteLocalVideoStream(true)
                        engine.stopPreview()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    runCatching {
                        if (isJoined && !isVideoMuted) {
                            engine.startPreview()
                        }
                        engine.muteLocalAudioStream(isAudioMuted)
                        engine.muteLocalVideoStream(isVideoMuted)
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    runCatching {
                        engine.stopPreview()
                        engine.leaveChannel()
                    }
                    rtcEngine = null
                    runCatching { RtcEngine.destroy() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                rtcEngine?.stopPreview()
                rtcEngine?.leaveChannel()
            }
            rtcEngine = null
            runCatching { RtcEngine.destroy() }
        }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        glowColor = WciColors.Danger,
        strongSurface = true
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LIVE VIDEO CLASSROOM • $sessionTitle",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
                Text(
                    text = "Host: $hostName • Channel: $channelId",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMono,
                        color = WciColors.Info
                    )
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        color = when {
                            isJoined -> WciColors.Success.copy(alpha = 0.2f)
                            connectionErrorMessage != null -> WciColors.Danger.copy(alpha = 0.2f)
                            else -> WciColors.Warning.copy(alpha = 0.2f)
                        },
                        shape = RoundedCornerShape(999.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = when {
                            isJoined -> WciColors.Success
                            connectionErrorMessage != null -> WciColors.Danger
                            else -> WciColors.Warning
                        },
                        shape = RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = when {
                        isJoined -> "LIVE"
                        isConnecting -> "CONNECTING"
                        connectionErrorMessage != null -> "ERROR"
                        else -> "STANDBY"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMono,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(WciColors.BgPrimaryDark)
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (remoteUid != null) {
                AndroidView(
                    factory = { remoteTextureView },
                    modifier = Modifier.fillMaxSize()
                )
            } else if (isJoined && !isVideoMuted) {
                AndroidView(
                    factory = { localTextureView },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Agora Live Video Stage",
                        tint = WciColors.Info,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall.copy(color = WciColors.TextSecondaryDark),
                        fontSize = 12.sp
                    )
                }
            }

            if (remoteUid != null && isJoined && !isVideoMuted) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(width = 96.dp, height = 128.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.5.dp, WciColors.Info, RoundedCornerShape(12.dp))
                ) {
                    AndroidView(
                        factory = { localTextureView },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMono,
                color = if (isJoined) WciColors.Success else WciColors.TextMutedDark
            )
        )

        connectionErrorMessage?.let { errMsg ->
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WciColors.Danger.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                    .border(1.dp, WciColors.Danger.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = errMsg,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                ShimmerButton(
                    text = "Retry Connection",
                    icon = Icons.Default.Videocam,
                    onClick = { startOrJoinChannel() },
                    modifier = Modifier.fillMaxWidth().testTag("agora_retry_connection_button")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (!hasAvPermissions) {
            ShimmerButton(
                text = "Grant Camera & Mic Permission for Live Class",
                icon = Icons.Default.Videocam,
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("agora_grant_av_button")
            )
        } else if (!isJoined) {
            ShimmerButton(
                text = if (isHostRole) "Start Live Video Broadcast (Agora RTC)" else "Join Live Video Class (Agora RTC)",
                icon = Icons.Default.Videocam,
                onClick = { startOrJoinChannel() },
                modifier = Modifier.fillMaxWidth().testTag("agora_join_button")
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiquidPill(
                    text = if (isAudioMuted) "Unmute" else "Mute",
                    selected = !isAudioMuted,
                    icon = if (isAudioMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    onClick = {
                        isAudioMuted = !isAudioMuted
                        rtcEngine?.muteLocalAudioStream(isAudioMuted)
                    },
                    modifier = Modifier.weight(1f)
                )
                LiquidPill(
                    text = if (isVideoMuted) "Cam On" else "Cam Off",
                    selected = !isVideoMuted,
                    icon = if (isVideoMuted) Icons.Default.VideocamOff else Icons.Default.Videocam,
                    onClick = {
                        isVideoMuted = !isVideoMuted
                        val engine = rtcEngine
                        if (engine != null) {
                            if (!isVideoMuted) {
                                engine.setupLocalVideo(VideoCanvas(localTextureView, VideoCanvas.RENDER_MODE_HIDDEN, 0))
                                engine.startPreview()
                            } else {
                                engine.stopPreview()
                            }
                            engine.muteLocalVideoStream(isVideoMuted)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                LiquidPill(
                    text = "Flip",
                    selected = false,
                    icon = Icons.Default.Cameraswitch,
                    onClick = { rtcEngine?.switchCamera() }
                )
                LiquidPill(
                    text = "Leave",
                    selected = false,
                    icon = Icons.Default.CallEnd,
                    onClick = { leaveRoom() }
                )
            }
        }
    }
}
