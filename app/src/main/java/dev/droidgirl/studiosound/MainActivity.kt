package dev.droidgirl.studiosound

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.playservices.StudioSoundAudioProcessor
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.muxer.SeekableMuxerOutput
import androidx.media3.muxer.WavMuxer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import com.google.common.collect.ImmutableList
import dev.droidgirl.studiosound.ui.theme.MyApplicationTheme
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "StudioSound"

/**
 * Custom [Muxer.Factory] that writes uncompressed multi-channel PCM audio (`AUDIO_RAW`) into a
 * `.wav` container using [WavMuxer].
 *
 * [StudioSoundAudioProcessor] expands the input audio into 8 channels (for mono input) or 16
 * channels (for stereo input), where each detected stem is placed on its own channel(s).
 * Standard AAC/MP4 encoders do not support 8/16-channel layouts, so we mux the raw PCM output
 * directly into a temporary WAV file.
 */
@OptIn(UnstableApi::class)
class WavMuxerFactory : Muxer.Factory {
    override fun create(path: String): Muxer =
        try {
            WavMuxer(SeekableMuxerOutput.of(path))
        } catch (e: Exception) {
            throw MuxerException("Failed to create WavMuxer.", e)
        }

    override fun getSupportedSampleMimeTypes(trackType: @C.TrackType Int): ImmutableList<String> =
        ImmutableList.of(MimeTypes.AUDIO_RAW)
}

/**
 * Represents a single separated audio stem produced by [StudioSoundAudioProcessor].
 *
 * @property channelIndices The zero-based channel index (1 for mono) or indices (2 for stereo:
 *   left, right) in the separated multi-channel WAV file that contain this stem's audio.
 * @property audioType One of the `StudioSoundAudioProcessor.AUDIO_TYPE_*` constants identifying
 *   the category of sound (e.g., Speech, Music, Wind).
 * @property label Human-readable name for [audioType].
 * @property isEnabled Whether this stem should be audible in the mixed preview.
 */
data class ChannelVolume(
    val channelIndices: List<Int>,
    val audioType: Int,
    val label: String,
    val isEnabled: Boolean = true,
) {
    /** Gain factor (`1f` = full volume, `0f` = muted) applied in the channel mixing matrix. */
    val volume: Float
        get() = if (isEnabled) 1f else 0f
}

private sealed interface UiState {
    data object Idle : UiState
    data class Analyzing(val videoUri: Uri) : UiState
    data class Ready(
        val videoUri: Uri,
        val separatedWavFile: File,
        val durationUs: Long,
        val stems: List<ChannelVolume>,
    ) : UiState
}

/** Maps a [StudioSoundAudioProcessor.AudioType] constant to a display label. */
@OptIn(ExperimentalApi::class)
fun toAudioTypeName(audioType: Int): String = when (audioType) {
    StudioSoundAudioProcessor.AUDIO_TYPE_SPEECH -> "Speech"
    StudioSoundAudioProcessor.AUDIO_TYPE_CROWD -> "Crowd"
    StudioSoundAudioProcessor.AUDIO_TYPE_MUSIC -> "Music"
    StudioSoundAudioProcessor.AUDIO_TYPE_NATURE -> "Nature"
    StudioSoundAudioProcessor.AUDIO_TYPE_WIND -> "Wind"
    StudioSoundAudioProcessor.AUDIO_TYPE_NOISE -> "Noise"
    StudioSoundAudioProcessor.AUDIO_TYPE_UNSPECIFIED -> "Unspecified"
    else -> "Unknown ($audioType)"
}

@ExperimentalApi
@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VideoSelectionScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@ExperimentalApi
@OptIn(UnstableApi::class)
@Composable
fun VideoSelectionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            uiState = UiState.Analyzing(uri)
        }
    }

    LaunchedEffect(uiState) {
        val analyzingState = uiState as? UiState.Analyzing ?: return@LaunchedEffect
        uiState = try {
            analyzeVideoAudio(context, analyzingState.videoUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze video audio", e)
            UiState.Idle
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (val state = uiState) {
            UiState.Idle -> {
                VideoSelectionIdleContent(
                    onSelectVideoClick = {
                        pickVideoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    }
                )
            }

            is UiState.Analyzing -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Analyzing video",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            is UiState.Ready -> {
                CompositionPreview(
                    videoUri = state.videoUri,
                    separatedWavPath = state.separatedWavFile.path,
                    durationUs = state.durationUs,
                    initialStems = state.stems,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Phase 1: Runs [StudioSoundAudioProcessor] over the selected video's audio track using Media3
 * [Transformer] and exports all separated stems into a multi-channel `.wav` file.
 *
 * How it works:
 * 1. Creates a [StudioSoundAudioProcessor] instance asynchronously via Google Play Services.
 * 2. Attaches the processor to an [EditedMediaItem] and builds an audio-only [Composition]
 *    (skipping video decoding/transcoding for faster analysis).
 * 3. Configures [Transformer] with [MimeTypes.AUDIO_RAW] and [WavMuxerFactory] so the 8-channel
 *    (mono) or 16-channel (stereo) separated output is written directly to `studio-sound-output.wav`.
 * 4. Once export finishes, reads [StudioSoundAudioProcessor.results] to see which audio stems
 *    were detected and which WAV channel indices correspond to each stem.
 */
@ExperimentalApi
@OptIn(UnstableApi::class)
private suspend fun analyzeVideoAudio(
    context: Context,
    videoUri: Uri,
): UiState.Ready {
    val (processor, durationUs, outputWavFile) = withContext(Dispatchers.IO) {
        // Initialize the on-device Studio Sound ML audio processor on a background thread.
        val audioProcessor = StudioSoundAudioProcessor.create(context).get()
        val videoDurationUs = extractDurationUs(context, videoUri)
        val wavFile = File(context.externalCacheDir, "studio-sound-output.wav").apply {
            if (exists()) delete()
        }
        Triple(audioProcessor, videoDurationUs, wavFile)
    }

    // Attach StudioSoundAudioProcessor to the media item's audio effects pipeline.
    val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(videoUri))
        .setEffects(
            Effects(
                /* audioProcessors= */ listOf(processor),
                /* videoEffects= */ emptyList()
            )
        )
        .build()

    // Extract only the audio track for separation (video will be re-combined during preview).
    val composition = Composition.Builder(
        EditedMediaItemSequence.withAudioFrom(listOf(editedMediaItem))
    ).build()

    // Run Transformer to decode the input audio, pass it through StudioSoundAudioProcessor,
    // and write the separated multi-channel PCM stream to outputWavFile.
    suspendCancellableCoroutine { continuation ->
        val transformer = Transformer.Builder(context)
            .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
            .setAudioMimeType(MimeTypes.AUDIO_RAW)
            .setMuxerFactory(WavMuxerFactory())
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        error: ExportException,
                    ) {
                        outputWavFile.delete()
                        continuation.resumeWithException(error)
                    }
                }
            )
            .build()

        transformer.start(composition, outputWavFile.path)

        continuation.invokeOnCancellation {
            transformer.cancel()
            outputWavFile.delete()
        }
    }

    // After Transformer completes, processor.results contains the detected audio categories
    // (e.g., Speech, Music, Noise) and the output channel index/indices for each stem.
    val stems = checkNotNull(processor.results).map { source ->
        Log.d(
            TAG,
            "Label=${source.label}, channels=${source.channelIndices.contentToString()}",
        )
        ChannelVolume(
            channelIndices = source.channelIndices.toList(),
            audioType = source.label,
            label = toAudioTypeName(source.label),
        )
    }

    return UiState.Ready(
        videoUri = videoUri,
        separatedWavFile = outputWavFile,
        durationUs = durationUs,
        stems = stems,
    )
}

private fun extractDurationUs(context: Context, videoUri: Uri): Long =
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, videoUri)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        durationMs * 1_000L
    }

/**
 * Phase 2: Previews the video using Media3's [CompositionPlayer] and lets the user toggle
 * individual separated audio stems on or off.
 */
@ExperimentalApi
@OptIn(UnstableApi::class)
@Composable
fun CompositionPreview(
    videoUri: Uri,
    separatedWavPath: String,
    durationUs: Long,
    initialStems: List<ChannelVolume>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Initially play the original video with all audio before any stem changes are applied.
    val compositionPlayer = remember(videoUri, durationUs) {
        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(videoUri))
            .apply {
                if (durationUs > 0L) {
                    setDurationUs(durationUs)
                }
            }
            .build()
        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedMediaItem))
        val composition = Composition.Builder(sequence).build()

        CompositionPlayer.Builder(context).build().apply {
            setComposition(composition)
            prepare()
            play()
        }
    }

    DisposableEffect(compositionPlayer) {
        onDispose {
            compositionPlayer.release()
        }
    }

    var stems by remember(videoUri, initialStems) { mutableStateOf(initialStems) }
    var appliedStems by remember(videoUri, initialStems) { mutableStateOf(initialStems) }
    val isSelectionChanged = stems != appliedStems

    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ContentFrame(
            player = compositionPlayer,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        PlayPauseButton(player = compositionPlayer)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Separated Stems",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = stems,
                key = { _, stem -> stem.audioType }
            ) { index, stem ->
                StemListItem(
                    stem = stem,
                    onToggle = {
                        stems = stems.toMutableList().apply {
                            this[index] = stem.copy(isEnabled = !stem.isEnabled)
                        }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            enabled = isSelectionChanged,
            onClick = {
                if (stems.isNotEmpty()) {
                    // Rebuild the Composition with a new ChannelMixingMatrix reflecting the
                    // enabled/disabled stems and swap it into CompositionPlayer for instant preview.
                    val composition = buildPreviewComposition(
                        videoUri = videoUri,
                        separatedWavPath = separatedWavPath,
                        durationUs = durationUs,
                        stems = stems,
                    )
                    compositionPlayer.setComposition(composition)
                    compositionPlayer.prepare()
                    compositionPlayer.play()
                    appliedStems = stems
                }
            }
        ) {
            Text(text = "Update Preview")
        }
    }
}

/**
 * Builds a [ChannelMixingAudioProcessor] that downmixes the multi-channel separated WAV file
 * back into standard mono (8 -> 1) or stereo (16 -> 2) audio for playback.
 *
 * [StudioSoundAudioProcessor] always outputs:
 * - **Mono input**: 8 output channels (1 channel per separated stem).
 * - **Stereo input**: 16 output channels (2 channels [Left, Right] per separated stem).
 *
 * The [ChannelMixingMatrix] is a flattened `[inputChannelCount x outputChannelCount]` array where
 * `coefficients[inCh * outputChannelCount + outCh]` controls how much of input channel `inCh` is
 * routed into output channel `outCh`. Setting a stem's coefficient to `1f` includes it in the mix;
 * setting it to `0f` mutes that stem.
 */
@ExperimentalApi
@OptIn(UnstableApi::class)
fun createChannelMixingProcessor(
    stems: List<ChannelVolume>,
): ChannelMixingAudioProcessor {
    val isMono = stems.first().channelIndices.size == 1
    val inputChannelCount = if (isMono) 8 else 16
    val outputChannelCount = if (isMono) 1 else 2
    val coefficients = FloatArray(inputChannelCount * outputChannelCount)

    for (stem in stems) {
        // outCh is 0 for mono, or 0 (Left) and 1 (Right) for stereo.
        for ((outCh, inCh) in stem.channelIndices.withIndex()) {
            coefficients[inCh * outputChannelCount + outCh] = stem.volume
        }
    }

    return ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(
            ChannelMixingMatrix(inputChannelCount, outputChannelCount, coefficients)
        )
    }
}

/**
 * Combines the original video track (without audio) and the separated multi-channel WAV file
 * (processed through [createChannelMixingProcessor]) into a single parallel [Composition] that
 * [CompositionPlayer] can play in sync.
 */
@ExperimentalApi
@OptIn(UnstableApi::class)
fun buildPreviewComposition(
    videoUri: Uri,
    separatedWavPath: String,
    durationUs: Long,
    stems: List<ChannelVolume>,
): Composition {
    // 1. Video-only sequence from the original video URI (avoids re-encoding or duplicating video).
    val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(videoUri))
        .setDurationUs(durationUs)
        .build()
    val videoSequence = EditedMediaItemSequence.withVideoFrom(listOf(videoItem))

    // 2. Audio-only sequence from the separated .wav file, downmixed in real time using
    //    ChannelMixingAudioProcessor according to the user's selected stems.
    val mixingProcessor = createChannelMixingProcessor(stems)
    val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(separatedWavPath))
        .setDurationUs(durationUs)
        .setEffects(
            Effects(
                /* audioProcessors= */ listOf(mixingProcessor),
                /* videoEffects= */ emptyList()
            )
        )
        .build()
    val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(audioItem))

    // 3. Play the video sequence and mixed audio sequence in parallel.
    return Composition.Builder(listOf(videoSequence, audioSequence)).build()
}

@Composable
private fun StemListItem(
    stem: ChannelVolume,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Switch, onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stem.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Switch(
                checked = stem.isEnabled,
                onCheckedChange = null
            )
        }
    }
}

@Composable
private fun VideoSelectionIdleContent(
    onSelectVideoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Separate audio in any video",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Isolate voices, eliminate background noise, and balance audio stems with on-device AI.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onSelectVideoClick) {
            Text(text = "Select video")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Supports MP4 up to 2 minutes • Processed locally",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Composable
fun VideoSelectionIdlePreview() {
    MyApplicationTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            VideoSelectionIdleContent(onSelectVideoClick = {})
        }
    }
}
