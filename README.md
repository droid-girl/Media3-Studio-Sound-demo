# Studio Sound Media3 Sample

An Android sample app built with Jetpack Compose and **AndroidX Media3** that demonstrates on-device AI audio stem separation and real-time audio stem mixing for videos using [`StudioSoundAudioProcessor`](https://developer.android.com/media/media3).

## Overview

This sample shows how to:
1. **Separate audio stems** from any video into distinct categories (**Speech**, **Music**, **Crowd**, **Nature**, **Wind**, **Noise**) on-device using `StudioSoundAudioProcessor` and Media3 `Transformer`.
2. **Store multi-channel separated audio** in an uncompressed `.wav` container using `WavMuxer`.
3. **Preview and mix stems in real time** without re-transcoding the video by combining the original video track and the separated `.wav` audio track in a parallel `Composition` played by `CompositionPlayer`.

---

## How It Works

```
┌──────────────────┐     ┌─────────────────────────────┐     ┌─────────────────────────┐
│  Selected Video  │────▶│ StudioSoundAudioProcessor   │────▶│ Multi-Channel WAV File  │
│  (Audio Track)   │     │ + Media3 Transformer        │     │ (8ch Mono / 16ch Stereo)│
└──────────────────┘     └─────────────────────────────┘     └────────────┬────────────┘
                                                                          │
┌──────────────────┐                                         ┌────────────▼────────────┐
│  Selected Video  │────────────────────┐                    │ ChannelMixingMatrix     │
│  (Video Track)   │                    ▼                    │ (Enable / Mute Stems)   │
└──────────────────┘     ┌─────────────────────────────┐     └────────────┬────────────┘
                         │ Parallel Media3 Composition │◀─────────────────┘
                         │ + CompositionPlayer Preview │
                         └─────────────────────────────┘
```

### Phase 1: Audio Stem Separation (`Transformer` + `StudioSoundAudioProcessor`)

When a user selects a video from the system photo picker:
1. **Initialize the Processor**: `StudioSoundAudioProcessor.create(context).get()` initializes the on-device audio separation processor via Google Play Services.
2. **Build an Audio-Only Composition**: The processor is attached to an `EditedMediaItem`, and `EditedMediaItemSequence.withAudioFrom(...)` extracts only the audio track so video frames don't need to be decoded during analysis.
3. **Export Multi-Channel PCM to WAV**: `StudioSoundAudioProcessor` expands mono input into **8 channels** (1 channel per stem) and stereo input into **16 channels** (2 channels `[Left, Right]` per stem). Because standard AAC/MP4 encoders do not support 8- or 16-channel layouts, `Transformer` is configured with `MimeTypes.AUDIO_RAW` and a custom `WavMuxerFactory` backed by `WavMuxer`.
4. **Inspect Detected Stems**: Once `Transformer` completes, `processor.results` returns a list of `SeparationResult` objects containing each detected stem's `AUDIO_TYPE_*` label and its corresponding channel index/indices in the output `.wav` file.

### Phase 2: Real-Time Stem Mixing (`CompositionPlayer` + `ChannelMixingAudioProcessor`)

When the user toggles stems on or off and taps **Update Preview**:
1. **Downmix Separated Channels**: `createChannelMixingProcessor()` constructs a `ChannelMixingMatrix` that maps the 8-channel (mono) or 16-channel (stereo) WAV input down to 1 (mono) or 2 (stereo) output channels. Enabled stems receive a gain coefficient of `1.0f`, and muted stems receive `0.0f`.
2. **Parallel Video + Audio Composition**: `buildPreviewComposition()` creates two parallel `EditedMediaItemSequence` instances:
   - A **video-only sequence** reading directly from the original video `Uri`.
   - An **audio-only sequence** reading from the separated `.wav` file with the `ChannelMixingAudioProcessor` applied.
3. **Instant Playback**: `CompositionPlayer` plays both sequences in sync, allowing instant stem toggling without re-running ML inference or re-encoding video.

---

## Key Media3 APIs Used

| Component | Module | Purpose |
| :--- | :--- | :--- |
| `StudioSoundAudioProcessor` | `media3-effect-play-services` | Performs on-device ML audio source separation into multi-channel PCM streams. |
| `Transformer` | `media3-transformer` | Executes the audio extraction and separation pipeline asynchronously. |
| `WavMuxer` | `media3-muxer` | Writes the 8- or 16-channel raw PCM (`AUDIO_RAW`) output to a `.wav` file. |
| `ChannelMixingAudioProcessor` | `media3-common` | Downmixes the 8/16 separated channels into mono/stereo based on user stem toggles. |
| `CompositionPlayer` | `media3-transformer` | Previews parallel video and audio sequences with real-time audio effects. |
| `ContentFrame` & `PlayPauseButton` | `media3-ui-compose(-material3)` | Jetpack Compose UI components for rendering video frames and playback controls. |

---

## Running the Sample

1. Open the project in Android Studio.
2. Sync Gradle dependencies (`androidx.media3:*:1.12.0-alpha02`).
3. Run the `app` configuration on a physical device.
4. Tap **Select video**, pick an MP4 video (up to 2 minutes recommended), wait for stem analysis to finish, and toggle individual stems under **Separated Stems** followed by **Update Preview**.

