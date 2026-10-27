# ATV-VID-POC-01 — Custom H.264 video

## Physical result and promotion

The user physically validated this POC on the target Fire TV: **correct image, no green
output, very fast startup, nearly unnoticeable perceived live latency, and excellent playback
quality through hardware MediaCodec**. This was the custom RTSP/RTP/H.264 path, not Media3.
No new numeric timing or endurance result is inferred from that report.

ATV-VID-POC-01 is physically validated. That result triggered ATV-VID-01 production migration,
documented in `docs/09_CUSTOM_VIDEO_PRODUCTION.md`. The original implementation below is
retained as a historical POC snapshot. Its separate controls/controller have been promoted
into normal Connect, not retained as a competing second implementation. Media3 is retired.

## Question and scope

Can this camera render a correct picture on the Fire TV when its H.264 RTSP/RTP handling
is outside Media3, while Android hardware decoding is retained? This opt-in developer POC
exists to answer that question. Normal **Connect** still uses the existing Media3 `1.9.4`
video path and independent production audio. Neither is replaced or retuned, and the
diagnostic H.264 probe remains available.

Media3 has physically exhibited intermittent `missing attribute fmtp` errors, initialized
decoders without a first frame, and first-frame/READY callbacks with a persistent green
rectangle. Other attempts have shown a correct picture. Callback success alone therefore
does not prove visual correctness, and the POC does not imply that Media3 should be removed.

## Physically confirmed probe baseline

The subsequent physical probe reported H.264 PT 96 at 90 kHz, `packetization-mode=1`,
`profile-level-id=4d0029`, and SDP `sprop-parameter-sets` containing a 21-byte SPS and 4-byte
PPS. The control was `realvideo`, resolved relative to the configured stream as
`rtsp://<camera>/streamtype=0/realvideo`. SETUP confirmed interleaved `0-1`.

The probe observed single NAL and FU-A packets, SPS/PPS both in SDP and in-band, IDR and
non-IDR slices, and no malformed H.264 payloads. One sample counted 12 single NAL packets,
18 FU-A packets, and NAL types `{1=9, 5=18, 7=1, 8=1, 12=1}`; FU-A type counts are fragment
observations, not a count of complete frames. STAP-A was not observed.

The camera also emitted channel 4 / PT8 with 640-byte payloads at about 40 ms intervals
during video-only probing. The POC ignores channel 4 entirely, without audio decoding,
output, or changes to the existing audio client.

## Architecture

`CustomVideoPlaybackController` owns one POC session with this path:

```text
CustomH264RtspSession (network thread)
  RTSP/TCP -> interleaved video RTP
  -> H264RtpDepacketizer -> H264AccessUnitAssembler
  -> LiveVideoQueue (maximum 3 complete access units)
  -> MediaCodecVideoDecoder (decoder thread)
  -> dedicated SurfaceView
```

The controller does not call Media3 or any audio controller. It reuses the probe SDP,
Transport, and event-reader helpers plus the audio-side bounded interleaved parser, RTP
header parser, request builder, Session parser, and keepalive policy. Production audio
helpers are unchanged. The SDP helper now also exposes decoded parameter-set NAL bytes.

The POC performs DESCRIBE, video SETUP, and PLAY on its own TCP connection. It requires a
valid returned interleaved pair and uses its negotiated RTP channel plus the selected SDP
H.264 PT. It ignores RTCP, wrong channels/PTs, and channel 4. A mapping of video onto the
explicitly excluded channel 4 fails clearly. Missing `fmtp` is allowed when config arrives
in-band. Packetization modes other than 0/1 are rejected.

One socket reader services RTP and RTSP responses, including frames coalesced with PLAY.
Session maintenance reuses GET_PARAMETER at half the advertised timeout (1–30 second
interval; fallback 25 seconds), with a five-second response deadline. Each setup request
has a five-second deadline. Five seconds without selected video RTP is a terminal POC
transport error, even if unrelated channel 4 traffic continues. There is no automatic retry.

## H.264 reconstruction and timing

- Single NAL units (types 1–23) retain their full raw NAL bytes.
- FU-A reconstructs `(indicator & 0xE0) | (header & 0x1F)`, then joins start/middle/end
  data only with continuous sequence numbers and a matching timestamp/header.
- STAP-A validates contained lengths and extracts each NAL separately.
- Missing starts, duplicate/out-of-order packets, discontinuities, timestamp changes during
  fragmentation, invalid headers, and oversized data discard damaged work. No partial FU-A
  reaches the codec.

The RTP parser retains sequence, timestamp, and marker. Complete NALs sharing a timestamp
form one AU; marker is required to close it. A timestamp change discards an unfinished AU
rather than flushing uncertain picture data. Each codec input is one marker-closed Annex-B
AU containing slices and any delivered AUD/SEI/filler data. Config NALs are cached separately.

One fragmented NAL is capped at 1 MiB and an AU at 2 MiB. RTP timestamps use a 90 kHz clock,
signed modular deltas for 32-bit wrap, and microsecond PTS. Repeated/backward timestamp values
are clamped to monotonically increasing PTS. There is no wall-clock playback delay or A/V sync
in this video-only experiment.

## Codec configuration and output

SPS/PPS can come from SDP or complete in-band NALs. The small cache validates NAL type, minimum
length, forbidden bit, and a 4 KiB parameter-set bound; it is not a complete SPS syntax parser.
Identical bytes do not change configuration. Changed parameter sets invalidate queued work,
recreate the codec, and require a fresh IDR. Dynamic format switching is experimental.

The POC uses `MediaCodec.createDecoderByType("video/avc")`, logs the selected codec name and
hardware/software classification, and fails before configure if a software/non-hardware
decoder was selected. To verify that classification reliably, this POC requires API 29+;
the physical Fire TV is API 30. The normal application minSdk is unchanged.

The format currently assumes **1920×1080**, the physically verified camera stream dimensions.
This is an explicit, temporary camera-specific POC assumption; no SPS dimension parser is
implemented. SPS is supplied as `csd-0`, PPS as `csd-1`, each with `00 00 00 01` before its raw
NAL, following the [Android AVC CSD convention](https://developer.android.com/reference/android/media/MediaCodec#CSD).
CSD is supplied once through configure/start; config NALs are not also queued as picture data.

The synchronous decoder worker regularly drains output, logs output-format changes, and
releases output buffers with rendering enabled to the borrowed SurfaceHolder Surface.
It does no YUV conversion, Bitmap rendering, or software decode. It logs first input/IDR
and the first output buffer released to the Surface with elapsed startup time. **That log
does not guarantee a correct visible image.** Only physical observation answers the POC question.

## Freshness and recovery

The network worker never waits for codec input/output. The queue holds at most three AUs
and initially admits an IDR before predicted frames. Overflow clears stale queued work and
waits for the next IDR; a newest arriving IDR can replace the full stale queue immediately.
Loss or incomplete AUs also invalidate the reference chain. A queue epoch lets the decoder
discard already-polled stale work and recreate its reference state before accepting the new
IDR. There is no unbounded packet/frame queue and no large startup prebuffer.

Config, IDR, or first decoded output missing after 15 seconds produces a specific startup
error. This is a POC startup deadline, not a continuous post-first-picture watchdog. GOP
length and actual recovery time must be observed physically; no keyframe request is added.

## UI, ownership, and stopping

Save the endpoint, disconnect normal playback, and let any diagnostic probe finish. Select
**Custom H.264 POC** with the remote. Its status shows connecting, waiting for SPS/PPS,
waiting for IDR, playing, stopping, or error. The dedicated 16:9 SurfaceView occupies the
existing video area; PlayerView is hidden while POC ownership is active. Normal Connect
and the probe reject starts during a POC run. Normal Connect retains its original media path.

**Stop custom POC**, the existing **Disconnect**, Activity stop/destruction, and Surface
destruction cancel the socket/queue/workers. Input shutdown wakes the network reader; its
worker attempts TEARDOWN and closes the socket. The decoder thread owns stop/release, and
the controller waits up to two seconds for it during surface/lifecycle destruction. The
controller retains active ownership until both workers exit, rejects overlapping starts,
and ignores stale callbacks. It borrows rather than releases SurfaceHolder's Surface.
Production rendering is restored only after POC worker cleanup. Active foreground POC intent
uses the existing keep-screen-on helper and clears it on stop; no wake lock/background mode.

## Physical test and outstanding result

Collect `adb logcat -s CustomVideoPoc` while running the POC. Logs contain setup milestones,
selected decoder/config sources, first IDR/AU/output timing, and periodic packet, AU, loss,
queue-drop, and decoder-input/output counts. No raw video payloads or URI credentials are logged.

On the Fire TV, record whether the actual picture is correct, green, black, corrupted,
delayed, or unstable. Also check Stop, Disconnect, repeated starts, and Activity exit, then
confirm normal Media3 Connect and the diagnostic probe remain usable.

The POC's physical visual test is complete as recorded above. **Production normal-Connect
Fire TV validation is still pending**, including audio integration, retry/network-loss
recovery, latency, and multi-minute stability. The POC result alone does not prove those
new production behaviors.
