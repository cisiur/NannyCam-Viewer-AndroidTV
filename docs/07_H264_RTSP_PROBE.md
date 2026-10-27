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
URI, requests TCP-interleaved `SETUP` on channels `0-1`, then sends `PLAY`. The returned
Transport header determines the RTP/RTCP channel pair. Case-insensitive `interleaved=X-Y`
parameters with distinct channel numbers in `0..255` are accepted. If the pair is missing or
malformed, the probe clearly logs unconfirmed negotiation and falls back to requested `0-1`
only to continue collecting diagnostics. Only parsed RTP on the selected RTP channel with the
SDP H.264 payload type reaches H.264 inspection.

The worker stops after 50 selected H.264 RTP packets or five seconds of capture, whichever
happens first. Unexpected channels or payload types do not shorten that window. It makes a best-effort
`TEARDOWN` and closes the socket. RTSP text and interleaved `$` frames remain parsed by one
authoritative reader. No raw H.264 payload dump is produced.

The probe reader retains events after a response in the same TCP read, including frames
coalesced with the `PLAY` response. Frames arriving while waiting for that response also
contribute to capture diagnostics. Production audio helpers and playback behavior are unchanged.

## First physical probe result and transport corrective

The first physical probe reached the packet-capture stage, then ended after approximately
five seconds with `No H.264 RTP packets received within capture limit`. That result was
ambiguous: the initial probe hard-coded channel 0 and silently discarded both other channels
and RTP payload types that differed from the selected SDP H.264 payload type. It did not prove
that the server sent no RTP.

Physical testing also reported a very brief static/noise burst immediately after starting the
probe. The probe intentionally creates no audio output path or `AudioTrack`. The source is
unknown; it may indicate unusual camera/session behavior, but it is not attributed to any
track and does not establish that the probe plays audio.

The corrective parses the server's actual Transport header and records every observed channel
and RTP payload type before H.264 filtering. A subsequent physical run confirmed H.264 PT96,
SDP fmtp with a 21-byte SPS and 4-byte PPS, in-band SPS/PPS and IDR/non-IDR, single NAL/FU-A
packets, and negotiated interleaved `0-1`. It also observed channel 4 / PT8 with 640-byte
payloads at about 40 ms intervals during video-only probing. No malformed H.264 payloads were
reported. The custom video POC in `docs/08_CUSTOM_VIDEO_POC.md` explicitly ignores that extra
channel. These findings support the experiment; production replacement remains undecided.

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

One-line milestone logs under `H264RtspProbe` identify worker startup, TCP connection,
DESCRIBE/SETUP/PLAY requests and response status codes, selected video control URI, returned
Transport header, channel negotiation/fallback, and capture start/finish.

Every interleaved frame contributes to `observedInterleavedChannels`. Frames on the negotiated
RTCP channel are also counted as `rtcpChannelFrames`. Recognizable RTCP headers on that channel
(version 2, SR/RR/SDES/BYE/APP type and bounded length, per [RFC 3550](https://www.rfc-editor.org/rfc/rfc3550.html#section-6.4.1))
and frames rejected by the RTP parser increment `nonRtpOrMalformedFrames`. Neither enters H.264
inspection. Unexpected RTP on the RTCP channel is still recorded as RTP metadata. All
successfully parsed RTP payload types are counted globally in `observedPayloadTypes` and by
channel in `channelPayloadTypes`, before checking the selected channel and expected H.264 PT.
The first eight frames retain only channel, frame length, RTP PT/sequence/payload length when
available, and selection metadata. No per-packet log stream or payload retention is added.

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

All completed captures emit a summary, including zero-match runs. It includes expected PT,
requested/negotiated channels, whether negotiation was confirmed, all channel/PT counts,
selected H.264 count, non-RTP/RTCP counts, and the existing H.264 details. The structured outcome
distinguishes H.264 RTP detected, no matching H.264 RTP, and no interleaved data. The UI reports
zero matches as a completed probe with a prompt to see logcat, rather than a generic failure.
An EOF or other capture error still logs accumulated evidence before surfacing an RTSP failure.

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
