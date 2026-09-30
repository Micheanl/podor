import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

root = Path(__file__).resolve().parents[1]
scratch = root.parents[1] / '.tools/video-v040'
scratch.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='voice-import-', dir=scratch) as temporary:
    folder = Path(temporary).resolve()
    assert folder.is_relative_to(scratch.resolve())
    video = folder / 'video'
    (video / 'scripts').mkdir(parents=True)
    (video / 'src').mkdir()
    (video / 'public/v040').mkdir(parents=True)
    for name in ['scripts/voice.py', 'src/visual-timing040.json', 'operations.json']:
        shutil.copyfile(root / name, video / name)
    scenes = json.loads((video / 'operations.json').read_text(encoding='utf-8-sig'))
    rows = []
    for scene in scenes:
        seconds = 6.4 if scene['id'] == 'brand' else 6.5 if scene['id'] == 'ui' else 1.0
        name = f'{scene["id"]}.wav'
        samples = round(seconds * 24000)
        with wave.open(str(folder / name), 'wb') as audio:
            audio.setnchannels(1)
            audio.setsampwidth(3)
            audio.setframerate(24000)
            audio.writeframes(b'\x01\x02\x03' * samples)
        digest = hashlib.sha256((folder / name).read_bytes()).hexdigest()
        for cue_index, cue in enumerate(scene['segments']):
            for pair_index, pair in enumerate(cue['pairs']):
                rows.append(dict(cueId=f'{scene["id"]}-{cue_index:02}', pairIndex=pair_index, file=name, sampleRate=24000, frames=samples, seconds=seconds, text=pair['zh'], englishText=pair['en'], sha256=digest))
    manifest = dict(engine='Qwen3-TTS', model='Qwen3-TTS-12Hz-0.6B-Base', revision='fixture', referenceOrigin='user-authorized recording', complete=True, sourceSha256=hashlib.sha256((video / 'operations.json').read_bytes()).hexdigest(), sentences=rows)
    edge_pcm = bytes(19200 * 3) + b'\x01\x02\x03' * 153600 + bytes(24000 * 3)
    with wave.open(str(folder / 'edges.wav'), 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(3)
        audio.setframerate(24000)
        audio.writeframes(edge_pcm)
    rows[0].update(file='edges.wav', frames=196800, seconds=8.2, sha256=hashlib.sha256((folder / 'edges.wav').read_bytes()).hexdigest())
    path = folder / 'manifest.json'
    path.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    command = [sys.executable, str(video / 'scripts/voice.py'), str(path)]
    subprocess.run(command, check=True, capture_output=True)
    timeline_file = video / 'src/timeline040.json'
    timeline = json.loads(timeline_file.read_text(encoding='utf-8'))
    assert sum(scene['duration'] for scene in timeline) == 18000
    assert timeline[0]['duration'] == 799
    assert timeline[1]['duration'] == 2007
    assert timeline[6]['duration'] == 3044
    assert timeline[7]['duration'] == 3000
    indexed = {(row['cueId'], row['pairIndex']): row for row in rows}
    offset = 0
    with wave.open(str(video / 'out/v040/podor-040-narration.wav'), 'rb') as full:
        assert full.getnframes() == 600 * 24000
        assert full.getsampwidth() == 3
        for scene in timeline:
            points = scene['visualTiming']
            assert points[0] == dict(base=0, actual=0)
            assert points[-1]['actual'] == scene['duration']
            assert all(left['actual'] < right['actual'] for left, right in zip(points, points[1:]))
            for cue in scene['cues']:
                row = indexed[(cue['id'], 0)]
                samples = row['frames']
                first = bytes(2880 * 3) + b'\x01\x02\x03' * 153600 + bytes(5280 * 3) if cue['id'] == 'brand-00' else b'\x01\x02\x03' * samples
                second_samples = indexed[(cue['id'], 1)]['frames']
                expected = first + bytes(4320 * 3) + b'\x01\x02\x03' * second_samples
                assert cue['captions'][0]['startMs'] == 0
                assert cue['captions'][0]['endMs'] == round(len(first) / 3 / 24)
                assert cue['captions'][1]['startMs'] == round(len(first) / 3 / 24 + 180)
                with wave.open(str(video / 'public/v040/voice' / cue['file']), 'rb') as audio:
                    assert audio.readframes(audio.getnframes()) == expected
                full.setpos((offset + cue['from']) * 800)
                assert full.readframes(len(expected) // 3) == expected
            offset += scene['duration']
    before = hashlib.sha256(timeline_file.read_bytes()).hexdigest()
    manifest['sentences'][0]['sha256'] = '0' * 64
    path.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    assert subprocess.run(command, capture_output=True).returncode != 0
    assert hashlib.sha256(timeline_file.read_bytes()).hexdigest() == before
    with wave.open(str(folder / 'over-budget.wav'), 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(3)
        audio.setframerate(24000)
        audio.writeframes(b'\x01\x02\x03' * 6 * 24000)
    digest = hashlib.sha256((folder / 'over-budget.wav').read_bytes()).hexdigest()
    for row in rows:
        row.update(file='over-budget.wav', frames=6 * 24000, seconds=6.0, sha256=digest)
    path.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    failed = subprocess.run(command, capture_output=True)
    assert failed.returncode != 0 and b'Narration needs' in failed.stderr
    assert hashlib.sha256(timeline_file.read_bytes()).hexdigest() == before
print(json.dumps(dict(result='pass', sentences=100, cues=50, exactSeconds=600, audiblePcmUnchanged=True, edgeRetentionMs=[120, 220], invalidHashRejected=True, budgetBorrowedFromPainting=True, overBudgetRejected=True)))
