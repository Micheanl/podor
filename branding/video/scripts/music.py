import json
import wave
from pathlib import Path

import numpy as np

root = Path(__file__).resolve().parents[1]
scenes = json.loads((root / 'src/timeline.json').read_text())
duration = (sum(scene['duration'] for scene in scenes) - 12 * (len(scenes) - 1)) / 30
rate = 48000
chords = [[130.8128, 155.5635, 195.9977, 293.6648], [103.8262, 155.5635, 207.6523, 261.6256], [116.5409, 174.6141, 233.0819, 293.6648], [97.9989, 146.8324, 195.9977, 246.9417]]
output = root / 'out'
output.mkdir(exist_ok=True)

with wave.open(str(output / 'music.wav'), 'wb') as stream:
    stream.setnchannels(2)
    stream.setsampwidth(2)
    stream.setframerate(rate)
    for start in range(0, int(duration * rate), rate * 2):
        t = np.arange(start, min(start + rate * 2, int(duration * rate))) / rate
        stereo = np.zeros((len(t), 2))
        first = max(0, int((t[0] - 11) // 8))
        last = int(t[-1] // 8)
        for index in range(first, last + 1):
            local = t - index * 8
            envelope = np.clip(local / 2.5, 0, 1) * np.clip((11 - local) / 3, 0, 1)
            for phase, frequency in enumerate(chords[index % len(chords)]):
                stereo[:, 0] += envelope * (np.sin(2 * np.pi * frequency * t + phase) + .18 * np.sin(2 * np.pi * frequency * 2 * t)) * .025
                stereo[:, 1] += envelope * (np.sin(2 * np.pi * (frequency + .10) * t + phase) + .18 * np.sin(2 * np.pi * frequency * 2 * t + .2)) * .025
        stereo *= (np.clip(t / 3, 0, 1) * np.clip((duration - t) / 4, 0, 1))[:, None]
        stream.writeframes((np.clip(stereo, -1, 1) * 32767).astype('<i2').tobytes())
