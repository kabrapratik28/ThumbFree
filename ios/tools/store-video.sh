#!/usr/bin/env bash
# Makes the App Store preview video (UITests/StoreVideoUITests.swift) in build/store-video/: ThumbFree's keyboard in a
# Messages conversation with Maya, a made-up contact. A voice dictates two short messages, ThumbFree's real engine types
# them, and the video's sound is that voice, in sync with the takes. Captions say what happens, since previews start muted.
#
# Optional: the listing (docs/brand/ios/listing.md) has screenshots only, no App Preview video. This script still works,
# for when a video is wanted.
#
# The voice is macOS `say` (Samantha; TF_VOICE picks another), or your own recordings: pass two audio files, each a short
# everyday message of a few seconds. Either way it plays as ThumbFree's microphone through the Debug build's -TFAudioFile, so the
# engine types what the viewer hears. The recordings and the video stay in build/, never in git.
#
# Apple's App Preview specification, checked 2026-09-29 (App Store Connect Help > App preview specifications): iPhone
# 6.9-inch portrait 886 x 1920; 15 to 30 seconds; at most 30 fps; H.264 progressive, High Profile up to Level 4.0, 10 to 12
# Mbps; stereo AAC 256 kbps at 44.1 or 48 kHz, all tracks enabled; .mov, .m4v or .mp4; at most 500 MB. This makes 886 x
# 1920, 30 fps, H.264 High 4.0 at 11 Mbps, AAC 256 kbps (constant) 48 kHz stereo in .mp4, and checks each number at the end.
#
# The Simulator is "iPhone 17 Pro Max (store)" (6.9 inch, iOS 26.5), as for tools/store-screenshots.sh. The build is Debug
# for the test hooks, without TF_AUTO_RETURN: the video never shows automatic return. Needs ffmpeg and ffprobe.
# Usage: tools/store-video.sh [recording1 recording2]
set -euo pipefail
cd "$(dirname "$0")/.."
sim="iPhone 17 Pro Max (store)"
out="$PWD/build/store-video"
video="$out/ThumbFree-preview-886x1920.mp4"
voice="${TF_VOICE:-Samantha}"
# What the voice says, and words the typed text must contain. Without names: the engine may spell a name its own way.
warmup="This is a quick test of the microphone.|quick test"
lines=("Running ten minutes late. Save me a seat.|minutes late" "Want me to grab you a coffee?|coffee")
if [ $# -gt 0 ]; then lines=(); for file in "$@"; do lines+=("$file|"); done; fi # a recording: any text will do
[ ${#lines[@]} -ge 2 ] || { echo "Pass two recordings, or none for the built-in voice"; exit 1; }
# The captions, in order: during the first take, after its stop, during the next takes, at the end ("|": a new line).
captions=("Tap the mic. Talk." "Tap again.|Your words appear." "Use it in any app." "Works offline.|Nothing leaves your iPhone.")

ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
seconds() { ffprobe -v error -show_entries format=duration -of csv=p=0 "$1"; }
rm -rf "$out" && mkdir -p "$out/voice"

# The voice: each line as 16 kHz mono (the engine's format) and 48 kHz stereo (the video's), then one WAV for the
# microphone with the warm-up line at 0.5 s and the lines after a long silence, which covers the trip to Messages.
line_audio() { # <text or file> <name>
  if [ -f "$1" ]; then ff -i "$1" -af loudnorm=I=-16:TP=-1.5 -ar 48000 -ac 2 "$out/voice/$2-48k.wav"
  else say -v "$voice" -o "$out/voice/$2.aiff" "$1" && ff -i "$out/voice/$2.aiff" -ar 48000 -ac 2 "$out/voice/$2-48k.wav"; fi
  ff -i "$out/voice/$2-48k.wav" -ar 16000 -ac 1 -c:a pcm_s16le "$out/voice/$2.wav"
}
line_audio "${warmup%%|*}" warmup
starts=(0.5) names=(warmup) at=60
for i in "${!lines[@]}"; do
  line_audio "${lines[$i]%%|*}" "line$((i + 1))"
  starts+=("$at") names+=("line$((i + 1))")
  at=$(echo "$at + $(seconds "$out/voice/line$((i + 1)).wav") + 5.5" | bc)
done
inputs=() mix=""
for i in "${!names[@]}"; do
  inputs+=(-i "$out/voice/${names[$i]}.wav")
  mix+="[$i]adelay=delays=$(echo "${starts[$i]} * 1000 / 1" | bc):all=1[a$i];"
done
labels=$(for i in "${!names[@]}"; do printf "[a%d]" "$i"; done)
ff "${inputs[@]}" -filter_complex "${mix}${labels}amix=inputs=${#names[@]}:normalize=0,apad=pad_dur=8" \
  -ar 16000 -ac 1 -c:a pcm_s16le "$out/voice.wav"
line_json() { # <index into names> <expect>
  local start=${starts[$1]}
  printf '{"start": %s, "end": %s, "expect": "%s"}' "$start" "$(echo "$start + $(seconds "$out/voice/${names[$1]}.wav")" | bc)" "$2"
}
{
  printf '{"voice": "%s", "conversation": "555-1212", "lead": 1.3, "stopAfter": 0.6, "hold": 4.0,\n' "$out/voice.wav"
  printf ' "warmup": %s,\n "lines": [' "$(line_json 0 "${warmup##*|}")"
  for i in "${!lines[@]}"; do [ "$i" = 0 ] || printf ', '; line_json $((i + 1)) "${lines[$i]##*|}"; done
  printf ']}\n'
} > "$out/plan.json"

# The Simulator: the keyboard, a 9:41 status bar, Maya and the rest (tools/store-sim.sh).
xcodegen generate --quiet
udid=$(tools/store-sim.sh "$sim")

build=(xcodebuild -project ThumbFree.xcodeproj -scheme ThumbFree -destination "platform=iOS Simulator,id=$udid"
  -derivedDataPath build/StoreShots -collect-test-diagnostics never
  SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG GCC_PREPROCESSOR_DEFINITIONS=DEBUG=1)
"${build[@]}" build-for-testing 2>&1 | tail -1

# Record the whole test; the edit below cuts the part in Messages.
xcrun simctl io "$udid" recordVideo --codec=h264 --force "$out/raw.mov" >"$out/record.log" 2>&1 &
recorder=$!
trap 'kill -INT $recorder 2>/dev/null || true' EXIT
for _ in $(seq 200); do grep -q "Recording started" "$out/record.log" && break; sleep 0.05; done
python3 -c 'import time; print(time.time())' > "$out/record-start.txt"
TEST_RUNNER_TF_STORE_VIDEO="$out" "${build[@]}" -only-testing:ThumbFreeUITests/StoreVideoUITests test-without-building 2>&1 | tail -3
kill -INT $recorder && wait $recorder || true
trap - EXIT
[ -f "$out/events.json" ] || { echo "The UI test did not finish: no events.json"; exit 1; }
echo "Typed: $(cat "$out/typed.txt")"

# Sync: each take's own recording (in History) starts 0.3 s before the take (its pre-roll), and the take starts on the
# frame where the mic turns red. The line's place in the take's recording gives its place in the video.
history="$(xcrun simctl get_app_container "$udid" io.github.kabrapratik28.thumbfree data)/Library/Application Support/History"
python3 - "$out" "$history" "${#lines[@]}" <<'PY' > "$out/timeline.json"
import json, math, os, struct, subprocess, sys, wave
out, history, count = sys.argv[1], sys.argv[2], int(sys.argv[3])
events = json.load(open(f"{out}/events.json"))
start = float(open(f"{out}/record-start.txt").read())

def onset(path):  # seconds to the first 10 ms frame at a tenth of the loudest frame's level
    w = wave.open(path)
    x = struct.unpack(f"<{w.getnframes()}h", w.readframes(w.getnframes()))
    rms = [math.sqrt(sum(v * v for v in x[i:i + 160]) / 160) for i in range(0, len(x) - 160, 160)]
    peak = max(rms)
    return next(i for i, r in enumerate(rms) if r >= peak / 10) * 0.01

takes = sorted((json.load(open(f"{history}/{d}/take.json")) | {"dir": d} for d in os.listdir(history)
                if os.path.exists(f"{history}/{d}/take.json")), key=lambda t: t["order"])[-count:]
scale = 1320 / events["screenWidth"]
x, y = round((events["micX"] - events["micSize"] * 0.2) * scale), round(events["micY"] * scale) # on the disc, off its key
a = events["take1Tap"] - start - 1.5
b = events[f"take{count}Stop"] - start + 2.5
raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(a), "-t", str(b - a), "-i", f"{out}/raw.mov", "-vf",
                      f"fps=60,crop=8:8:{x - 4}:{y - 4}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True).stdout
red = []
for i in range(len(raw) // 192):
    px = raw[i * 192:(i + 1) * 192]
    r, g, bl = (sum(px[c::3]) / 64 for c in range(3))
    red.append(r > 190 and g < 120 and bl < 120)
spans, begin = [], None
for i, is_red in enumerate(red + [False]):
    if is_red and begin is None: begin = i
    if not is_red and begin is not None:
        if i - begin > 30: spans.append((a + begin / 60, a + i / 60))
        begin = None
if len(spans) != count: sys.exit(f"found {len(spans)} red spans of the mic, expected {count}: {spans}")
lines = []
for k, (tap, stop) in enumerate(spans):
    take = onset(f"{history}/{takes[k]['dir']}/audio.wav")
    line = onset(f"{out}/voice/line{k + 1}.wav")
    lines.append({"tap": tap, "stop": stop, "voice": tap - 0.3 + take - line})
json.dump({"lines": lines}, sys.stdout)
PY

# The cut: from 1.5 s before the first tap (so Apple's default poster frame, at 5 s, shows the take recording) to 4.5 s
# after the last stop, which must fall within Apple's 15 to 30 seconds.
read -r t0 dur < <(python3 -c 'import json, sys; l = json.load(open(sys.argv[1]))["lines"]; t0 = l[0]["tap"] - 1.5
print(round(t0, 3), round(l[-1]["stop"] + 4.5 - t0, 3))' "$out/timeline.json")
python3 -c 'import sys; d = float(sys.argv[1]); sys.exit(0 if 15.5 <= d <= 29.5 else f"the preview would last {d} s; Apple allows 15 to 30")' "$dur"
for i in "${!captions[@]}"; do swift tools/store-compose.swift caption "$out/caption$((i + 1)).png" 886 "${captions[$i]}"; done
swift tools/store-compose.swift tap "$out/tap.png" 104

# Captions: the first during the first take, the second from its stop to the next tap, the third during the later takes,
# the last from 0.8 s after the last stop. Tap marks on the mic at each tap and stop. The voice where the takes heard it.
graph=$(python3 - "$out" "$t0" "$dur" <<'PY'
import json, sys
out, t0, dur = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
lines = [{k: v - t0 for k, v in line.items()} for line in json.load(open(f"{out}/timeline.json"))["lines"]]
events = json.load(open(f"{out}/events.json"))
s = 886 / events["screenWidth"] # points to output pixels
mx, my = events["micX"] * s - 52, events["micY"] * s - 3 - 52
spans = [(0, lines[0]["stop"]), (lines[0]["stop"], lines[1]["tap"]), (lines[1]["tap"], lines[-1]["stop"])]
spans.append((spans[-1][1] + 0.8, dur))
f = ["[0:v]fps=30,zscale=primaries=709:transfer=iec61966-2-1:matrix=709,scale=886:1926:flags=lanczos,crop=886:1920:0:3,format=yuv420p[v0]"]
v = "v0"
for n, (a, b) in enumerate(spans):
    d = b - a
    f.append(f"[{n + 1}:v]format=rgba,trim=duration={d:.3f},fade=t=in:d=0.2:alpha=1,fade=t=out:st={d - 0.2:.3f}:d=0.2:alpha=1,"
             f"setpts=PTS+{a:.3f}/TB[c{n}]")
    f.append(f"[{v}][c{n}]overlay=x=(W-w)/2:y=420:eof_action=pass[v{n + 1}]")
    v = f"v{n + 1}"
taps = [t for line in lines for t in (line["tap"], line["stop"])]
tap_input = len(spans) + 1
f.append(f"[{tap_input}:v]format=rgba,split={len(taps)}" + "".join(f"[t{i}]" for i in range(len(taps))))
for i, t in enumerate(taps):
    f.append(f"[t{i}]trim=duration=0.5,fade=t=out:st=0.3:d=0.2:alpha=1,setpts=PTS+{t - 0.25:.3f}/TB[tt{i}]")
    f.append(f"[{v}][tt{i}]overlay=x={mx:.0f}:y={my:.0f}:eof_action=pass[w{i}]")
    v = f"w{i}"
f.append(f"[{v}]format=yuv420p,setsar=1[video]")
voices = ""
for k, line in enumerate(lines):
    f.append(f"[{tap_input + 1 + k}:a]adelay=delays={max(0, round(line['voice'] * 1000))}:all=1[a{k}]")
    voices += f"[a{k}]"
f.append(f"{voices}amix=inputs={len(lines)}:normalize=0,apad,atrim=duration={dur:.3f},aformat=sample_rates=48000:channel_layouts=stereo[audio]")
print(";".join(f))
PY
)
caption_inputs=()
for i in "${!captions[@]}"; do caption_inputs+=(-loop 1 -framerate 30 -i "$out/caption$((i + 1)).png"); done
voice_inputs=()
for i in "${!lines[@]}"; do voice_inputs+=(-i "$out/voice/line$((i + 1))-48k.wav"); done
ff -ss "$t0" -t "$dur" -i "$out/raw.mov" "${caption_inputs[@]}" -loop 1 -framerate 30 -i "$out/tap.png" "${voice_inputs[@]}" \
  -filter_complex "$graph" -map "[video]" -map "[audio]" -t "$dur" \
  -c:v libx264 -profile:v high -level:v 4.0 -pix_fmt yuv420p -r 30 -preset slow \
  -b:v 11M -minrate 11M -maxrate 11M -bufsize 22M -x264-params nal-hrd=cbr:force-cfr=1 \
  -color_primaries bt709 -color_trc iec61966-2-1 -colorspace bt709 \
  -c:a aac_at -aac_at_mode cbr -b:a 256k -ar 48000 -ac 2 -movflags +faststart "$video" # Apple's AAC encoder, constant 256 kbps

# Apple's numbers, read back from the file.
ffprobe -v error -show_entries stream=codec_type,codec_name,profile,level,width,height,pix_fmt,field_order,r_frame_rate,avg_frame_rate,bit_rate,sample_rate,channels \
  -show_entries format=duration,size,bit_rate -of json "$video" > "$out/probe.json"
python3 - "$out/probe.json" <<'PY' | tee "$out/spec-check.txt"
import json, sys
p = json.load(open(sys.argv[1])); v = next(s for s in p["streams"] if s["codec_type"] == "video")
a = [s for s in p["streams"] if s["codec_type"] == "audio"]; f = p["format"]
checks = {
    "886 x 1920 (6.9-inch portrait)": (v["width"], v["height"]) == (886, 1920),
    "15 to 30 seconds": 15 <= float(f["duration"]) <= 30,
    "at most 30 fps, constant": v["r_frame_rate"] == v["avg_frame_rate"] == "30/1",
    "H.264 High Profile, Level 4.0 at most": v["codec_name"] == "h264" and v["profile"] == "High" and v["level"] <= 40,
    "progressive, 4:2:0": v.get("field_order", "progressive") == "progressive" and v["pix_fmt"] == "yuv420p",
    "10 to 12 Mbps": 10e6 <= int(v["bit_rate"]) <= 12e6,
    "one stereo AAC track, 256 kbps, 48 kHz": len(a) == 1 and a[0]["codec_name"] == "aac" and a[0]["channels"] == 2
        and a[0]["sample_rate"] == "48000" and 240e3 <= int(a[0]["bit_rate"]) <= 270e3,
    "at most 500 MB": int(f["size"]) <= 500e6,
}
print(f"{sys.argv[1].rsplit('/', 1)[0]}/ThumbFree-preview-886x1920.mp4: {float(f['duration']):.2f} s, "
      f"{int(v['bit_rate']) / 1e6:.2f} Mbps video, {int(a[0]['bit_rate']) / 1e3:.0f} kbps audio, {int(f['size']) / 1e6:.1f} MB")
for name, ok in checks.items(): print(("ok   " if ok else "FAIL ") + name)
sys.exit(0 if all(checks.values()) else "The preview does not meet Apple's specification")
PY
