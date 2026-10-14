package dev.nfish.biliaudio.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import dev.nfish.biliaudio.data.Video
import dev.nfish.biliaudio.data.formatTime
import dev.nfish.biliaudio.playback.MusicVolume
import dev.nfish.biliaudio.playback.PartPlaybackState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun PlayerScreen(vm: PlayerViewModel = viewModel()) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val error by vm.playbackError.collectAsStateWithLifecycle()
    val resolving by vm.resolving.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val parts by vm.parts.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val canRetry by vm.canRetry.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var confirmLogout by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFF9CD8C3),
        background = Color(0xFF101614), surface = Color(0xFF18211D), surfaceContainer = Color(0xFF202B25))
    else lightColorScheme(primary = Color(0xFF246B5B), onPrimary = Color.White,
        background = Color(0xFFF7F9F6), surface = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFEDF2EC), secondaryContainer = Color(0xFFDCEDE3))
    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.background,
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(colors.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Headphones, null, tint = colors.onPrimary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("听见", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("B站音频 · 在线听", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    TextButton(onClick = { if (screen.loggedIn) confirmLogout = true else vm.openLogin() }) {
                        Icon(Icons.Outlined.AccountCircle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp)); Text(if (screen.loggedIn) "已登录" else "登录")
                    }
                }
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding()) {
                    if (error != null) {
                        Surface(color = colors.errorContainer, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = colors.onErrorContainer)
                                if (canRetry) TextButton(onClick = vm::retryPlayback, enabled = !resolving) { Text("重试") }
                            }
                        }
                    }
                    PlayerPanel(player, parts, volume, resolving, vm::togglePlay, vm::seek,
                        vm::repeat, vm::stop, vm::previousPart, vm::nextPart, vm::openCurrentParts,
                        vm::musicVolume, vm::toggleMusicMute)
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                OutlinedTextField(value = screen.query, onValueChange = vm::query,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    placeholder = { Text("搜索歌曲、歌手或 BV 号", style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true, shape = RoundedCornerShape(18.dp),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { focus.clearFocus(); vm.search() }, enabled = screen.query.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "搜索", tint = colors.primary)
                        }
                    }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }))
                if (!player.connected) Text("正在连接播放器…", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
                if (screen.videos.isEmpty() && !screen.loading && history.isNotEmpty()) {
                    if (screen.searchError != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(screen.searchError.orEmpty(), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = colors.error)
                            TextButton(onClick = { focus.clearFocus(); vm.search() }) { Text("重新搜索") }
                        }
                    } else if (screen.searched.isNotEmpty()) {
                        Text("没有找到相关视频", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Text("最近播放", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(history, key = { it.key }) { entry ->
                            val video = entry.video.copy(duration = "P${entry.part.number} · ${entry.part.title}")
                            VideoRow(video, parts.mediaId == entry.key,
                                screen.detailLoading == entry.video.bvid, player.connected) {
                                focus.clearFocus(); vm.playHistory(entry)
                            }
                        }
                    }
                } else if (screen.videos.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        when {
                            screen.loading -> CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                            screen.searchError != null -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.CloudOff, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(12.dp)); Text(screen.searchError.orEmpty(), color = colors.onSurfaceVariant)
                                TextButton(onClick = { vm.search() }) { Text("重新搜索") }
                            }
                            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.MusicNote, null, tint = colors.primary.copy(alpha = .65f), modifier = Modifier.size(54.dp))
                                Spacer(Modifier.height(16.dp))
                                Text(if (screen.searched.isEmpty()) "找一首，此刻想听的歌" else "没有找到相关视频", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(if (screen.searched.isEmpty()) "输入关键词，或直接粘贴 B站视频链接" else "换一个关键词再试", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("搜索结果", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                        Text("${screen.videos.size} 个视频", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(screen.videos, key = { it.bvid }) { video ->
                            VideoRow(video, parts.detail?.video?.bvid == video.bvid, screen.detailLoading == video.bvid,
                                player.connected) { vm.choose(video) }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (screen.searchError != null) Text(screen.searchError.orEmpty(), color = colors.error, style = MaterialTheme.typography.bodySmall)
                                if (screen.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else if (screen.hasMore) TextButton(onClick = { vm.search(more = true) }) { Text("加载更多") }
                                else Text("已显示全部结果", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        screen.detail?.let { detail ->
            val currentIndex = if (parts.detail?.video?.bvid == detail.video.bvid) parts.index else -1
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
            ModalBottomSheet(onDismissRequest = vm::dismissParts) {
                Text("选择分 P", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
                Text(detail.video.title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).navigationBarsPadding(), state = listState) {
                    items(detail.parts, key = { it.cid }) { part ->
                        val selected = parts.detail?.video?.bvid == detail.video.bvid && parts.part?.cid == part.cid
                        ListItem(headlineContent = { Text("P${part.number}  ${part.title}") },
                            leadingContent = { if (selected) Icon(Icons.Outlined.GraphicEq, "当前 P", tint = colors.primary) },
                            trailingContent = { Text(formatTime(part.durationSeconds * 1000), style = MaterialTheme.typography.labelSmall) },
                            colors = ListItemDefaults.colors(containerColor = if (selected) colors.secondaryContainer else colors.surface),
                            modifier = Modifier.clickable(enabled = player.connected) { vm.play(detail, part) })
                    }
                }
            }
        }
        if (screen.loginOpen) {
            val bitmap = remember(screen.qr?.url) { screen.qr?.url?.let(::qrBitmap) }
            AlertDialog(onDismissRequest = vm::closeLogin, title = { Text("登录哔哩哔哩") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        if (bitmap != null) Image(bitmap.asImageBitmap(), "B站登录二维码", Modifier.size(210.dp).clip(RoundedCornerShape(8.dp)))
                        else if (!screen.loginExpired) CircularProgressIndicator(Modifier.padding(30.dp))
                        Spacer(Modifier.height(12.dp)); Text(screen.loginMessage, color = if (screen.loginExpired) colors.error else colors.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("使用哔哩哔哩 App 扫码。只有一部手机时，可先截图，再从扫一扫的相册中识别。", style = MaterialTheme.typography.bodySmall)
                    }
                }, confirmButton = { if (screen.loginExpired) TextButton(onClick = vm::openLogin) { Text("刷新二维码") } },
                dismissButton = { TextButton(onClick = vm::closeLogin) { Text("关闭") } })
        }
        if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出账号") },
            text = { Text("清除本机的 B站登录凭据。") },
            confirmButton = { TextButton(onClick = { vm.logout(); confirmLogout = false }) { Text("退出登录") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
    }
}

@Composable
private fun VideoRow(video: Video, selected: Boolean, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = if (selected) colors.secondaryContainer else colors.surface,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = video.cover, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainer))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text("${video.author} · ${video.duration}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(if (selected) Icons.Outlined.GraphicEq else Icons.Outlined.PlayCircleOutline,
                if (selected) "当前播放" else "播放", tint = colors.primary, modifier = Modifier.size(25.dp))
        }
    }
}

@Composable
private fun PlayerPanel(state: PlayerState, parts: PartPlaybackState, volume: MusicVolume, resolving: Boolean,
    toggle: () -> Unit, seek: (Long) -> Unit, repeat: () -> Unit, stop: () -> Unit,
    previous: () -> Unit, next: () -> Unit, openParts: () -> Unit,
    setVolume: (Float) -> Unit, toggleMute: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val hasItem = parts.part != null
    val currentVideo = parts.detail?.video
    val title = if (parts.part != null && currentVideo != null) {
        if ((parts.detail?.parts?.size ?: 0) > 1) "${currentVideo.title} · ${parts.part?.title}" else currentVideo.title
    } else state.title
    val position = if (state.mediaId == parts.mediaId && !resolving) state.position else 0
    val duration = if (state.mediaId == parts.mediaId && !resolving) state.duration else 0
    var dragged by remember(parts.mediaId) { mutableStateOf<Float?>(null) }
    Surface(color = colors.surface, tonalElevation = 2.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (!hasItem && !resolving) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                Icon(Icons.Outlined.Headphones, null, tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(12.dp)); Text("点击搜索结果开始播放", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = currentVideo?.cover ?: state.cover, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainer))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable(enabled = hasItem && state.connected, onClick = openParts)) {
                        Text(title.ifEmpty { "正在加载音频…" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (resolving) "正在获取音频…" else if (state.buffering) "正在缓冲…" else currentVideo?.author ?: state.author,
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("P${parts.part?.number ?: 1} / ${parts.detail?.parts?.size ?: 1}",
                        modifier = Modifier.padding(start = 8.dp).clickable(enabled = hasItem && state.connected, onClick = openParts),
                        style = MaterialTheme.typography.labelMedium, color = colors.primary)
                }
                Slider(value = dragged ?: position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                    onValueChange = { dragged = it }, onValueChangeFinished = { dragged?.let { seek(it.toLong()) }; dragged = null },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = duration > 0 && !resolving,
                    modifier = Modifier.fillMaxWidth().height(30.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(dragged?.toLong() ?: position), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                MusicVolumeRow(volume, state.connected, setVolume, toggleMute)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = repeat, enabled = hasItem && state.connected) {
                        Icon(Icons.Outlined.RepeatOne, "单曲循环", tint = if (state.repeat) colors.primary else colors.onSurfaceVariant.copy(alpha = .5f))
                    }
                    IconButton(onClick = previous, enabled = parts.hasPrevious && state.connected) {
                        Icon(Icons.Outlined.SkipPrevious, "上一 P")
                    }
                    FilledIconButton(onClick = toggle, enabled = hasItem && !resolving, modifier = Modifier.size(52.dp)) {
                        Icon(if (state.playing || state.buffering) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            if (state.playing || state.buffering) "暂停" else "播放", modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = next, enabled = parts.hasNext && state.connected) {
                        Icon(Icons.Outlined.SkipNext, "下一 P")
                    }
                    IconButton(onClick = stop, enabled = hasItem && state.connected) {
                        Icon(Icons.Outlined.Close, "停止播放")
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicVolumeRow(volume: MusicVolume, enabled: Boolean,
    setVolume: (Float) -> Unit, toggleMute: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    var requested by remember { mutableFloatStateOf(volume.decibels) }
    val db = if (dragging) requested else volume.decibels
    val label = if (volume.muted && !dragging) "−∞ dB"
        else String.format(Locale.US, if (db % 1f == 0f) "%.0f dB" else "%.1f dB", db)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = toggleMute, enabled = enabled) {
            Icon(if (volume.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeDown,
                if (volume.muted) "恢复音乐音量" else "静音音乐", tint = colors.onSurfaceVariant)
        }
        Slider(value = db, onValueChange = {
            dragging = true; requested = it; setVolume(it)
        }, onValueChangeFinished = { setVolume(requested); dragging = false },
            valueRange = MusicVolume.MIN_DB..0f, enabled = enabled,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = "音乐音量"
                stateDescription = if (volume.muted && !dragging) "静音" else label
            })
        Text(label, Modifier.width(70.dp).padding(start = 10.dp),
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

private fun qrBitmap(value: String): Bitmap {
    val size = 480
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
