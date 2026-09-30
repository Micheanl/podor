import argparse
import hashlib
import json
import math
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--music', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--ffmpeg', type=Path, required=True)
    parser.add_argument('--ffprobe', type=Path, required=True)
    parser.add_argument('--duration', type=float, default=600)
    parser.add_argument('--crossfade', type=float, default=4)
    args = parser.parse_args()
    for path in (args.music, args.ffmpeg, args.ffprobe):
        if not path.is_file():
            parser.error(f'Missing local file: {path}')
    probe = subprocess.run([str(args.ffprobe), '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'format=duration', '-of', 'json', str(args.music)], check=True, capture_output=True, text=True)
    track_seconds = float(json.loads(probe.stdout)['format']['duration'])
    if not 0 < args.crossfade < track_seconds or args.duration <= 0:
        parser.error('Duration and crossfade must fit the readable music file.')
    copies = max(1, math.ceil((args.duration - args.crossfade) / (track_seconds - args.crossfade)))
    inputs = [item for _ in range(copies) for item in ('-i', str(args.music))]
    filters = []
    if copies == 1:
        filters.append('[0:a:0]aresample=48000,aformat=channel_layouts=stereo[musicjoin]')
    else:
        for i in range(copies):
            filters.append(f'[{i}:a:0]aresample=48000,aformat=channel_layouts=stereo[music{i}]')
        previous = 'music0'
        for i in range(1, copies):
            output = 'musicjoin' if i == copies - 1 else f'join{i}'
            filters.append(f'[{previous}][music{i}]acrossfade=d={args.crossfade}:c1=qsin:c2=qsin[{output}]')
            previous = output
    filters.append(f'[musicjoin]atrim=duration={args.duration},asetpts=PTS-STARTPTS,afade=t=in:d=3,afade=t=out:st={max(0, args.duration - 6)}:d=6[musicbed]')
    loudness = 'loudnorm=I=-18:TP=-1.5:LRA=11'
    measured = subprocess.run([str(args.ffmpeg), '-hide_banner', '-nostats', *inputs, '-filter_complex', ';'.join(filters + [f'[musicbed]{loudness}:print_format=json[measure]']), '-map', '[measure]', '-f', 'null', '-'], check=True, capture_output=True, text=True, encoding='utf-8', errors='replace')
    levels = json.loads(measured.stderr[measured.stderr.rfind('{'):measured.stderr.rfind('}') + 1])
    normalization = f"{loudness}:measured_I={levels['input_i']}:measured_TP={levels['input_tp']}:measured_LRA={levels['input_lra']}:measured_thresh={levels['input_thresh']}:offset={levels['target_offset']}:linear=true"
    filters.append(f'[musicbed]{normalization},aresample=48000,atrim=duration={args.duration}[mix]')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(args.ffmpeg), '-y', '-hide_banner', '-nostats', *inputs, '-filter_complex', ';'.join(filters), '-map', '[mix]', '-t', str(args.duration), '-c:a', 'pcm_s24le', str(args.output)], check=True)
    result = subprocess.run([str(args.ffprobe), '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', str(args.output)], check=True, capture_output=True, text=True)
    assert float(json.loads(result.stdout)['format']['duration']) == args.duration
    public = Path(__file__).resolve().parents[1] / 'public/v040'
    preview = public / 'music.m4a'
    subprocess.run([str(args.ffmpeg), '-y', '-v', 'error', '-i', str(args.output), '-c:a', 'aac', '-b:a', '192k', '-t', str(args.duration), str(preview)], check=True)
    metadata = dict(mode='background-music-only', title='Cooler Than Me (Radio Edit)', artist='Lucky Luke', userProvided=True, seconds=args.duration, sampleRate=48000, channels=2, sourceMusicSeconds=track_seconds, loopCopies=copies, crossfadeSeconds=args.crossfade, fadeInSeconds=3, fadeOutSeconds=6, targetLufs=-18, targetTruePeakDb=-1.5, pcmSha256=hashlib.sha256(args.output.read_bytes()).hexdigest(), playbackSha256=hashlib.sha256(preview.read_bytes()).hexdigest())
    (public / 'music.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    print(json.dumps(metadata))


if __name__ == '__main__':
    main()
