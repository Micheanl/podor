import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
scenes = json.loads((root / 'src/timeline.json').read_text())


def timestamp(frame):
    milliseconds = round(frame / 30 * 1000)
    seconds, milliseconds = divmod(milliseconds, 1000)
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f'{hours:02}:{minutes:02}:{seconds:02},{milliseconds:03}'


rows = []
offset = 0
for scene in scenes:
    for cue in scene['cues']:
        for caption in cue['captions']:
            words = caption['text'].split()
            count = (len(words) + 9) // 10
            for part in range(count):
                start = offset + cue['from'] + caption['start'] + (caption['end'] - caption['start']) * part / count
                end = offset + cue['from'] + caption['start'] + (caption['end'] - caption['start']) * (part + 1) / count
                rows.append(f"{len(rows) + 1}\n{timestamp(start)} --> {timestamp(end)}\n{' '.join(words[part * 10:(part + 1) * 10])}\n")
    offset += scene['duration'] - 12
(root / 'out').mkdir(exist_ok=True)
(root / 'out/podor-introduction-en.srt').write_text('\n'.join(rows), encoding='utf-8')
