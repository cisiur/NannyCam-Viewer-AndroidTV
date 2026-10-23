# ATV-02 Audio Implementation Notes

## Scope and codec decision

ATV-02 keeps Media3 `1.9.4` as the existing video-only path. The audio implementation
uses a separate, camera-specific RTSP/TCP session so the camera's malformed SDP audio
metadata never reaches a stock PCMA decoder.

The camera SDP labels the audio track PCMA, but physical capture proved that its RTP
payload is signed PCM16 little-endian at 8 kHz mono. The client identifies the `m=audio`
section structurally and uses its payload type and `a=control` value only to select the
track. It does not use `a=rtpmap` or `a=fmtp` to select a decoder. PCMA/G.711 decoding is
not present.

## RTSP/TCP audio session

`AudioPlaybackController` owns an independent TCP connection and never recreates the
working Media3 video player. Physical Android testing confirmed that this path produces
understandable, effectively live PCM16LE speech at a basic-validation level. Physical
inspection of the Windows reference also confirmed the same intentional architecture: one
independent RTSP/TCP session for video and another for audio, started near-simultaneously.
The Android sessions must remain separate. Its small RTSP client supports only the tested
camera flow:

1. connect the RTSP TCP socket;
2. `DESCRIBE` with `Accept: application/sdp`;
3. parse the response status, headers, Content-Length body, and audio SDP section;
4. resolve the audio control URI from `Content-Base`/`Content-Location` or the configured
   stream URI;
5. `SETUP` that track with `RTP/AVP/TCP;unicast;interleaved=0-1`;
6. retain the returned `Session` value and `PLAY` the configured stream URI;
7. read interleaved frames until failure, stop, or socket close.

Requests carry increasing `CSeq` values and responses are matched by `CSeq`. The parser
supports multi-line RTSP headers and Content-Length bodies; it is not a one-line response
parser. A best-effort `TEARDOWN` is sent when the owning worker can finish normally, and
socket close is always used to cancel a blocked read.

This is deliberately not a replacement video RTSP client or a general-purpose RTSP
library. It does not impose Media3's `fmtp` requirement, so it is independent of the
earlier Media3 `missing attribute fmtp` observation.

### RTSP session keepalive

Physical Android logs showed the camera closing the custom audio RTSP session after about
60 seconds of otherwise working playback. The client now maintains the audio session with
`GET_PARAMETER` over the same TCP socket and never creates a second reader. It parses the
SETUP `Session` header into a session ID plus optional, case-insensitive `timeout=<seconds>`
parameter. A valid timeout of at least two seconds schedules keepalive at half that value,
with a 1-second floor and 30-second cap; no/malformed/pathological timeout uses a 25-second
fallback. Thus `timeout=60` schedules every 30 seconds.

The authoritative network loop sends `GET_PARAMETER` to the active stream URI with a
monotonic CSeq and the retained Session header, then keeps reading and parsing both RTP and
RTSP text. At most one keepalive request can be outstanding. RTP continues to be delivered
while its response is pending; a matching successful CSeq acknowledges it. A non-2xx
response or no acknowledgement within five seconds fails only the audio session and uses
the existing independent reconnect policy. Socket read timeouts simply wake this loop to
service/check keepalive; RTP traffic itself continues to satisfy normal socket reads.

## Interleaved RTP and packet parsing

The streaming parser accepts arbitrary TCP read boundaries. It recognizes both RTSP text
messages and standard interleaved frames (`$`, channel byte, big-endian 16-bit length,
payload), including partial frames, multiple events in one read, and RTSP text immediately
followed by binary data. RTSP text is bounded to 64 KiB and interleaved frames to 32 KiB.

Only RTP channel 0 from the selected audio `SETUP` is considered; channel 1 is ignored as
RTCP. RTP parsing validates version 2, CSRC count, optional header extension, and optional
padding before extracting the payload. Malformed packets are ignored rather than ending the
audio loop. The client tracks valid RTP packet count, payload bytes, latest sequence number,
sequence discontinuities, and duplicate/out-of-order packets.

## PCM16LE, carry, and output

The selected RTP payload is treated directly as PCM16LE: each sample is
`lowByte | (highByte << 8)`, interpreted as signed 16-bit. `Pcm16LeDecoder` retains at
most one unmatched trailing byte and prepends it to the next payload. The carry is reset
on every new/reconnected session.

An output worker creates `AudioTrack` in streaming mode with 8,000 Hz, mono,
`ENCODING_PCM_16BIT`, `USAGE_MEDIA`, and `CONTENT_TYPE_SPEECH`. No audio focus request or
PiP audio policy is implemented here; those are ATV-05 platform work.

The network worker decodes and processes audio, then writes to a bounded application PCM
ring. The output worker consumes up to 320 bytes (about 20 ms) per write. Network reads,
RTP parsing, DSP, and blocking `AudioTrack.write` calls never occur on the UI thread.

## Freshness and bounded buffering

At 8 kHz mono PCM16 the stream is 16 bytes/ms. The application PCM ring is capped at
3,200 bytes (about 200 ms). If an enqueue would exceed that cap, old data is discarded
until only 1,600 bytes (about 100 ms) remains, then the newest data is retained. Stale-drop
bytes and current application-buffer duration are exposed in diagnostics. Platform-required
`AudioTrack.getMinBufferSize` is respected for the output device buffer; it does not enlarge
the bounded application queue.

Mute clears the application buffer immediately, continues consuming the network stream,
and suppresses `AudioTrack.write`. Unmute therefore returns to fresh incoming audio instead
of replaying a muted backlog. It does not tear down audio RTSP or affect video. The UI also
has a developer comparison control for raw PCM versus processed PCM; toggling it clears the
queue and resets DSP state to avoid mixing modes.

## DSP

Raw mode writes reconstructed PCM16LE directly. Processed mode applies exactly the known
cleanup path, in order:

1. First-order high-pass filter at 120 Hz and 8 kHz:
   `y[n] = alpha * (y[n-1] + x[n] - x[n-1])`, where
   `alpha = 1 / (1 + 2*pi*120/8000)` (approximately `0.9139`).
2. Fixed gain of `10^(10/20)` (approximately `3.16227766`).
3. Saturation to `[-32768, 32767]` before returning PCM16.

Filter state persists within an active session and resets on reconnect, audio stop, or a
raw/processed mode change. There is no PCMA decoder, AGC, compressor, noise gate, FFT
denoise, adaptive processing, or large DSP window.

## State, retry, and diagnostics

Audio has its own `STOPPED`, `CONNECTING`, `PLAYING`, `RECONNECTING`, and `ERROR` model.
Valid decoded RTP payload starts `PLAYING`; a socket EOF, I/O/RTSP failure, parser failure,
or output failure releases only the audio resources and retries at 1 s, 2 s, 3 s, then 5 s
repeatedly. One retry may be pending. Explicit Disconnect and Activity stop cancel it;
successful audio resets the sequence. Audio failure never moves the video state or restarts
Media3.

The screen exposes separate audio state, Mute/Unmute, raw/processed mode, and aggregated
audio diagnostics: selected track/control URI, payload type, RTP and PCM counts, sequence
health, application buffer duration, stale drops, reconnect count, mute state, and mode.
The controller logs state changes, track selection, failures, and reconnects, but not every
packet.

## Physical validation still required

The basic custom Android PCM16LE audio path is physically confirmed: speech was
understandable, effectively live, and independent of Media3 video. The new keepalive and
video startup-timeout correctives are not yet physically validated. Still test processed
audio quality, mute freshness, A/V relationship, 15–30 minute audio-session stability, and
independent recovery after camera or network loss on the target Fire TV.
