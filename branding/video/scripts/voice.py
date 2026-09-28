import asyncio
import hashlib
import json
import math
from pathlib import Path

import edge_tts
from mutagen.mp3 import MP3

root = Path(__file__).resolve().parents[1]
scenes = json.loads((root / 'operations.json').read_text(encoding='utf-8-sig'))


async def generate():
    for scene in scenes:
        clip = root / 'public/clips' / f"{scene['id']}.json"
        scene['duration'] = json.loads(clip.read_text())['frames'] if clip.exists() else scene['duration']
        for index, cue in enumerate(scene['cues']):
            digest = hashlib.sha256(cue['text'].encode()).hexdigest()[:10]
            cue['file'] = f"operation-{scene['id']}-{index}-{digest}.mp3"
            target = root / 'public/voice' / cue['file']
            meta = target.with_suffix('.jsonl')
            if not target.exists():
                await edge_tts.Communicate(cue['text'], 'en-US-GuyNeural', rate='-2%').save(str(target), str(meta))
            cue['from'] = round(cue.pop('at') * 30)
            cue['duration'] = math.ceil(MP3(target).info.length * 30)
            rows = [json.loads(row) for row in meta.read_text().splitlines()]
            cue['captions'] = [dict(start=round(row['offset'] / 10000000 * 30), end=round((row['offset'] + row['duration']) / 10000000 * 30), text=row['text']) for row in rows if row['type'] == 'SentenceBoundary']
            if index:
                previous = scene['cues'][index - 1]
                cue['from'] = max(cue['from'], previous['from'] + previous['duration'] + 6)
            scene['duration'] = max(scene['duration'], cue['from'] + cue['duration'] + 30)
        print(scene['id'], round(scene['duration'] / 30, 2), flush=True)
    (root / 'src/timeline.json').write_text(json.dumps(scenes, indent=2), encoding='utf-8')


asyncio.run(generate())
