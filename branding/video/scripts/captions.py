import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
scenes = json.loads((root / 'src/timeline040.json').read_text(encoding='utf-8-sig'))


def stamp(milliseconds):
    seconds, milliseconds = divmod(round(milliseconds), 1000)
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f'{hours:02}:{minutes:02}:{seconds:02},{milliseconds:03}'


zh, en = [], []
offset_ms = 0
for scene in scenes:
    chapter_zh, chapter_en = [], []
    for cue in scene['cues']:
        for z, e in zip(cue['captions'], cue['englishCaptions'], strict=True):
            addition = offset_ms + cue['from'] / 30 * 1000
            z = dict(z, startMs=round(z['startMs'] + addition), endMs=round(z['endMs'] + addition))
            e = dict(e, startMs=round(e['startMs'] + addition), endMs=round(e['endMs'] + addition))
            zh.append(z)
            en.append(e)
            chapter_zh.append(z)
            chapter_en.append(e)
    folder = root / 'public/v040/subtitles'
    for language, values in [('zh', chapter_zh), ('en', chapter_en)]:
        (folder / f"{scene['id']}-{language}.json").write_text(json.dumps(values, ensure_ascii=False, indent=2), encoding='utf-8')
    offset_ms += scene['duration'] / 30 * 1000
assert offset_ms == 600000
for language, values in [('zh', zh), ('en', en)]:
    (folder / f'podor-040-{language}.json').write_text(json.dumps(values, ensure_ascii=False, indent=2), encoding='utf-8')
    srt = '\n\n'.join(f"{index + 1}\n{stamp(value['startMs'])} --> {stamp(value['endMs'])}\n{value['text']}" for index, value in enumerate(values)) + '\n'
    (folder / f'podor-040-{language}.srt').write_text(srt, encoding='utf-8')
    (root / 'out/v040' / f'podor-040-{language}.srt').write_text(srt, encoding='utf-8')
print(json.dumps(dict(frames=18000, caption_pairs=len(zh), seconds=600)))
