# H.264 RTSP Probe

## Purpose

The production video path remains Media3 `1.9.4` over RTSP/TCP, using the platform AVC
decoder and `PlayerView`. Physical testing has exposed intermittent Media3 SDP errors
(`IllegalArgumentException: missing attribute fmtp`), startup attempts that never render a
frame, and attempts which report decoder initialization, first frame, and `STATE_READY` while
the visible output remains a persistent green rectangle. The healthy, independent audio path
does not change those observations.

This developer-only probe collects enough RTSP/SDP and H.264 RTP structure to decide whether
the camera emits a stream that a future, minimal RTP/MediaCodec video path could consume. It is
diagnostic only: it does not decode, render, queue, or send H.264 data to MediaCodec, Media3, or
the production player.

## Architecture and bounds

The probe opens its own short-lived RTSP/TCP connection only when normal playback is fully
disconnected. It reuses the small, proven audio-side primitives for RTSP requests, bounded
interleaved RTSP/TCP parsing, Session header handling, and RTP header parsing. It does not share
the audio or Media3 socket and does not change either production session.

It performs `DESCRIBE`, identifies the `m=video` H.264 media section, resolves its `a=control`
URI, performs TCP-interleaved `SETUP` on channels `0-1`, then sends `PLAY`. Channel 0 RTP packets
for the selected video payload type are structurally inspected. The worker stops after 50 video
RTP packets or five seconds of capture, whichever happens first, then makes a best-effort
`TEARDOWN` and closes the socket. RTSP text and interleaved `$` frames remain parsed by one
authoritative reader. No raw H.264 payload dump is produced.

## SDP information recorded

The `H264RtspProbe` log tag records one concise SDP summary containing:

- the selected `m=video` line and RTP payload type;
- the matching `a=rtpmap` value;
- whether a matching `a=fmtp` exists and its value when present;
- `packetization-mode`, when present;
- whether `sprop-parameter-sets` is present, plus decoded SPS/PPS byte lengths when parsable;
- the media `a=control` value and resolved control URI.

Missing `fmtp` is a diagnostic result, not a reason for the probe to reject the camera. The
probe structurally selects H.264 from the video section rather than treating the malformed audio
metadata as video codec input.

## RTP/H.264 information recorded

RTP parsing validates version, variable CSRC count, header extensions, padding, and payload
boundaries before the probe looks only at H.264 packet structure. It classifies:

- single NAL packets (types 1–23), including non-IDR slices (1), IDR slices (5), SEI (6), SPS
  (7), PPS (8), and AUD (9);
- STAP-A packets (24), with bounded validation of each contained NAL length; and
- FU-A fragments (28), including original NAL type and start/end bits.

Malformed or unsupported H.264 payloads are counted without terminating the probe. The final
summary records RTP packet count, single-NAL/STAP-A/FU-A counts, SPS/PPS source (SDP, RTP, both,
or neither), IDR/non-IDR presence, malformed/unsupported count, and first/last RTP sequence
numbers. If SDP lacks `sprop-parameter-sets` but RTP carries SPS or PPS, the probe logs that the
codec parameters are present in-band.

## Running it on the Fire TV

1. Save a valid camera endpoint and make sure normal video/audio playback is disconnected.
2. In **Runtime diagnostics**, select **Probe H.264 Video** with the D-pad.
3. Wait for the bounded run to complete or fail; the on-screen message only reports that result.
4. Collect the detailed `H264RtspProbe` lines from logcat. They contain no full video payload
   dumps and omit RTSP user-info from logged URIs.

The probe cannot establish whether a rendered image is visually correct. Its result can only
inform a later custom-video-path decision: valid H.264 RTP with codec parameters (from SDP or
in-band SPS/PPS), IDR slices, and bounded packet structure would support further investigation.
No replacement of the Media3 video path is implemented or implied by this probe.
