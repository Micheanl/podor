import argparse
import hashlib
import json
import math
import wave
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('manifest', type=Path)
args = parser.parse_args()
manifest_file = args.manifest.resolve()
manifest = json.loads(manifest_file.read_text(encoding='utf-8-sig'))
scenes = json.loads((root / 'operations.json').read_text(encoding='utf-8-sig'))
visual = json.loads((root / 'src/visual-timing040.json').read_text(encoding='utf-8-sig'))
assert manifest['engine'] == 'Qwen3-TTS'
assert manifest['referenceOrigin'] == 'user-authorized recording'
assert manifest['complete'] is True
assert manifest['sourceSha256'] == hashlib.sha256((root / 'operations.json').read_bytes()).hexdigest()
sentences = manifest['sentences']
assert len(sentences) == 100
indexed = {(row['cueId'], row['pairIndex']): row for row in sentences}
assert len(indexed) == len(sentences)
sample_rate = 24000
sample_width = 3
gap_samples = round(sample_rate * 0.18)
timeline, recordings, trims = [], {}, []


def trim_silence(pcm):
    count = len(pcm) // sample_width
    window = round(sample_rate * 0.02)
    threshold_squared = (2 ** 23 * 10 ** (-50 / 20)) ** 2

    def quiet(start, end):
        data = pcm[start * sample_width:end * sample_width]
        energy = sum(int.from_bytes(data[index:index + sample_width], 'little', signed=True) ** 2 for index in range(0, len(data), sample_width))
        return energy <= (end - start) * threshold_squared

    leading = 0
    while leading < count and quiet(leading, min(count, leading + window)):
        leading = min(count, leading + window)
    if leading == count:
        raise ValueError('Synthesized sentence has no audible signal')
    ending = count
    while ending > leading and quiet(max(leading, ending - window), ending):
        ending = max(leading, ending - window)
    start = max(0, leading - round(sample_rate * 0.12))
    end = min(count, ending + round(sample_rate * 0.22))
    return pcm[start * sample_width:end * sample_width], start, end

for scene in scenes:
    cues = []
    for index, segment in enumerate(scene['segments']):
        cue_id = f"{scene['id']}-{index:02}"
        samples, zh, en = 0, [], []
        chunks = []
        for pair_index, pair in enumerate(segment['pairs']):
            row = indexed[(cue_id, pair_index)]
            assert row['text'] == pair['zh'], (cue_id, pair_index, 'text mismatch')
            assert row['englishText'] == pair['en'], (cue_id, pair_index, 'translation mismatch')
            source = (manifest_file.parent / row['file']).resolve()
            assert source.is_relative_to(manifest_file.parent)
            assert hashlib.sha256(source.read_bytes()).hexdigest() == row['sha256']
            with wave.open(str(source), 'rb') as audio:
                assert audio.getnchannels() == 1 and audio.getsampwidth() == sample_width
                assert audio.getframerate() == sample_rate == row['sampleRate']
                count = audio.getnframes()
                assert count == row['frames']
                assert abs(count / sample_rate - row['seconds']) < 1e-6
                data = audio.readframes(count)
            data, trim_start, trim_end = trim_silence(data)
            trims.append(dict(cueId=cue_id, pairIndex=pair_index, originalFrames=count, trimStartFrame=trim_start, trimEndFrame=trim_end, frames=trim_end - trim_start))
            count = trim_end - trim_start
            if pair_index:
                chunks.append(bytes(gap_samples * sample_width))
                samples += gap_samples
            timing = dict(startMs=round(samples / sample_rate * 1000), endMs=round((samples + count) / sample_rate * 1000), timestampMs=None, confidence=None)
            zh.append(dict(text=pair['zh'], **timing))
            en.append(dict(text=pair['en'], **timing))
            chunks.append(data)
            samples += count
        pcm = b''.join(chunks)
        digest = hashlib.sha256(pcm).hexdigest()[:12]
        file_name = f'{cue_id}-{digest}.wav'
        recordings[file_name] = pcm
        cues.append(dict(id=cue_id, **{'from': round(segment['at'] * 30)}, duration=math.ceil(samples / sample_rate * 30), file=file_name, zh=segment['zh'], en=segment['en'], captions=zh, englishCaptions=en, features=segment.get('features', [])))
    timeline.append(dict(id=scene['id'], zh=scene['title_zh'], en=scene['title_en'], duration=round(scene['seconds'] * 30), cues=cues))
minimum = [sum(cue['duration'] for cue in scene['cues']) + 6 * (len(scene['cues']) - 1) + 3 for scene in timeline]
if sum(minimum) > 18000:
    raise ValueError(f"Narration needs {sum(minimum) / 30:.2f} seconds; all sentences must fit within 600 seconds")
deficit = sum(max(0, needed - scene['duration']) for scene, needed in zip(timeline, minimum, strict=True))
for scene, needed in zip(timeline, minimum, strict=True):
    scene['duration'] = max(scene['duration'], needed)
for index in [6, 7, 8, 5, 4, 3, 2, 1, 0]:
    borrowed = min(deficit, timeline[index]['duration'] - minimum[index])
    timeline[index]['duration'] -= borrowed
    deficit -= borrowed
assert deficit == 0
for scene, base in zip(timeline, visual, strict=True):
    assert scene['id'] == base['id']
    duration, cues = scene['duration'], scene['cues']
    for cue, point in zip(cues, base['cues'], strict=True):
        assert cue['id'] == point['id']
        cue['from'] = round(point['from'] / base['duration'] * duration)
    for index, cue in enumerate(cues):
        if index:
            cue['from'] = max(cue['from'], cues[index - 1]['from'] + cues[index - 1]['duration'] + 6)
    for index in range(len(cues) - 1, -1, -1):
        following = cues[index + 1]['from'] - 6 if index + 1 < len(cues) else duration - 3
        cues[index]['from'] = min(cues[index]['from'], following - cues[index]['duration'])
    assert all(cue['from'] >= 0 for cue in cues)
    scene['visualTiming'] = [dict(base=0, actual=0)] + [dict(base=point['from'], actual=cue['from']) for cue, point in zip(cues[1:], base['cues'][1:], strict=True)] + [dict(base=base['duration'], actual=duration)]
assert sum(scene['duration'] for scene in timeline) == 18000
assert sum(len(scene['cues']) for scene in timeline) == 50
folder = root / 'public/v040/voice'
folder.mkdir(parents=True, exist_ok=True)


def save_audio(file, pcm):
    with wave.open(str(file), 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(sample_width)
        audio.setframerate(sample_rate)
        audio.writeframes(pcm)


for name, pcm in recordings.items():
    save_audio(folder / name, pcm)
full = bytearray(sample_rate * 600 * sample_width)
offset_frames = 0
for scene in timeline:
    for cue in scene['cues']:
        start = round((offset_frames + cue['from']) / 30 * sample_rate) * sample_width
        pcm = recordings[cue['file']]
        full[start:start + len(pcm)] = pcm
    offset_frames += scene['duration']
output = root / 'out/v040'
output.mkdir(parents=True, exist_ok=True)
save_audio(output / 'podor-040-narration.wav', full)
(root / 'src/timeline040.json').write_text(json.dumps(timeline, ensure_ascii=False, indent=2), encoding='utf-8')
metadata = dict(engine='Qwen3-TTS', model=manifest['model'], revision=manifest['revision'], referenceOrigin='user-authorized recording', synthesized=True, sentences=100, segments=50, sampleRate=sample_rate, durationFrames=18000, sentenceGapSeconds=0.18, edgeSilence=dict(windowSeconds=0.02, thresholdDb=-50, retainedLeadingSeconds=0.12, retainedTrailingSeconds=0.22), trims=trims, chapters=[dict(id=scene['id'], seconds=scene['duration'] / 30, voiceSeconds=sum(cue['duration'] for cue in scene['cues']) / 30) for scene in timeline], operationsSha256=manifest['sourceSha256'], manifestSha256=hashlib.sha256(manifest_file.read_bytes()).hexdigest())
(root / 'public/v040/narration.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
print(json.dumps(dict(segments=50, sentences=100, frames=18000, audioSeconds=600)))
