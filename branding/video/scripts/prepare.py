import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
chapters = [
    ('brand', '从一支笔开始', 'From one pen', 25),
    ('workspace', '把空间交给画布', 'A clearer workspace', 60),
    ('brushes', '认识笔触', 'Brushes with character', 75),
    ('color', '颜色与形状', 'Color and shape', 70),
    ('layers', '分层塑造画面', 'Build in layers', 60),
    ('animation', '让画面动起来', 'Frame by frame', 60),
    ('painting', '一束花的厚涂练习', 'A painted bouquet', 110),
    ('code', '界面与像素的分工', 'Inside the source', 100),
    ('release', '保存并继续创作', 'Keep creating', 40),
]
timeline = [dict(id=key, zh=zh, en=en, duration=seconds * 30, cues=[]) for key, zh, en, seconds in chapters]
assert sum(chapter['duration'] for chapter in timeline) == 18000
target = root / 'src/timeline040.json'
if not target.exists():
    target.write_text(json.dumps(timeline, ensure_ascii=False, indent=2), encoding='utf-8')

selections = [
    ('shared/src/commonMain/kotlin/app/podor/ui/PodorApp.kt', 21, 39, 'Kotlin', 'State → declarative UI', 0, 690),
    ('shared/src/commonMain/kotlin/app/podor/ui/input/PlatformCanvasPointer.kt', 19, 31, 'Kotlin', 'Native pointer · background loading', 690, 390),
    ('shared/src/commonMain/kotlin/app/podor/ui/CanvasWorkspace.kt', 164, 179, 'Kotlin', 'The canvas receives input', 1080, 390),
    ('engine/src/lib.rs', 2641, 2659, 'Rust', 'Filtered input → native stroke', 1470, 360),
    ('engine/src/model.rs', 4, 6, 'Rust', '128 × 128 pixel tiles', 1830, 190),
    ('engine/src/model.rs', 85, 98, 'Rust', 'Shared unmodified pixel storage', 2020, 200),
    ('engine/src/history.rs', 17, 35, 'Rust', 'Snapshots · bounded undo history', 2220, 390),
    ('engine/src/assistants.rs', 395, 411, 'Rust', 'Drawing assistants project input', 2610, 390),
]
excerpts = []
for file, first, last, language, label, start, duration in selections:
    source = (repo / file).read_text(encoding='utf-8')
    lines = source.splitlines()[first - 1:last]
    excerpts.append(dict(file=file, firstLine=first, lines=lines, language=language, label=label, sourceSha256=hashlib.sha256((repo / file).read_bytes()).hexdigest(), start=start, duration=duration))
(root / 'public/v040/code/excerpts.json').write_text(json.dumps(excerpts, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(chapters=len(timeline), frames=18000, source_excerpts=len(excerpts))))
