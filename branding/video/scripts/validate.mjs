import { readFileSync, existsSync, readdirSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repo = resolve(root, "../..");
const read = (path) =>
  JSON.parse(readFileSync(resolve(root, path), "utf8").replace(/^\uFEFF/, ""));
const assert = (value, message) => {
  if (!value) throw new Error(message);
};
const hash = (path) =>
  createHash("sha256").update(readFileSync(path)).digest("hex");
const ffprobe = resolve(
  root,
  "node_modules/@remotion/compositor-win32-x64-msvc/ffprobe.exe",
);
const finalAudio = process.argv[2] === "--final";
const outputFile = process.argv[2] && !finalAudio ? process.argv[2] : undefined;
if (finalAudio) {
  assert(
    existsSync(resolve(root, "public/v040/music.json")),
    "Final background music has not been prepared",
  );
  const music = read("public/v040/music.json");
  assert(
    music.mode === "background-music-only" &&
      music.artist === "Lucky Luke" &&
      music.title === "Cooler Than Me (Radio Edit)" &&
      music.userProvided === true &&
      music.seconds === 600 &&
      music.targetLufs === -18 &&
      music.targetTruePeakDb <= -1,
    "Final soundtrack must contain only the requested background music",
  );
  const mix = resolve(root, "out/v040/podor-040-music.wav");
  assert(existsSync(mix), "Final local background music mix is missing");
  assert(
    hash(mix) === music.pcmSha256 &&
      hash(resolve(root, "public/v040/music.m4a")) === music.playbackSha256,
    "Final background music differs from the prepared soundtrack",
  );
  const audio = JSON.parse(
    execFileSync(
      ffprobe,
      ["-v", "error", "-show_streams", "-show_format", "-of", "json", mix],
      { encoding: "utf8" },
    ),
  );
  assert(
    Number(audio.format.duration) === 600 &&
      audio.streams[0].sample_rate === "48000" &&
      audio.streams[0].channels === 2,
    "Final mix must be exactly 600 seconds of stereo 48 kHz audio",
  );
}
assert(
  hash(resolve(root, "public/logo.png")) ===
    hash(resolve(repo, "branding/podor-icon.png")),
  "App logo differs from current production branding",
);
assert(
  hash(resolve(root, "public/v040/stylus-cursor.png")) ===
    hash(
      resolve(
        repo,
        "shared/src/commonMain/composeResources/files/stylus-cursor.png",
      ),
    ),
  "Stylus differs from current production resource",
);
const timeline = read("src/timeline040.json");
assert(
  timeline.length === 9 &&
    timeline.reduce((sum, item) => sum + item.duration, 0) === 18000,
  "Timeline must be exactly 18000 frames / 600 seconds",
);
assert(
  read("node_modules/@remotion/captions/package.json").version === "4.0.529",
  "Captions version mismatch",
);
const featureCues = new Map();
let pairCount = 0;
for (const chapter of timeline) {
  assert(chapter.cues.length > 0, `Missing captions: ${chapter.id}`);
  if (chapter.visualTiming) {
    assert(
      chapter.visualTiming[0].base === 0 &&
        chapter.visualTiming[0].actual === 0 &&
        chapter.visualTiming.at(-1).actual === chapter.duration &&
        chapter.visualTiming
          .slice(1)
          .every(
            (point, index) =>
              point.base > chapter.visualTiming[index].base &&
              point.actual > chapter.visualTiming[index].actual,
          ),
      `Visual timing must follow captions without reversing: ${chapter.id}`,
    );
  }
  for (const [index, cue] of chapter.cues.entries()) {
    assert(
      cue.from + cue.duration <=
        (chapter.cues[index + 1]?.from ?? chapter.duration),
      `Caption cue overlap: ${cue.id}`,
    );
    assert(!cue.zh.includes("、"), `Prohibited punctuation: ${cue.id}`);
    assert(
      cue.captions.length === cue.englishCaptions.length &&
        cue.captions.length > 0,
      `Bilingual captions missing: ${cue.id}`,
    );
    for (const [captionIndex, zh] of cue.captions.entries()) {
      const en = cue.englishCaptions[captionIndex];
      assert(
        zh.startMs === en.startMs && zh.endMs === en.endMs,
        `Caption pair timing mismatch: ${cue.id}`,
      );
      assert(
        (zh.text.match(/[\u3400-\u9fff]/g) ?? []).length <= 36 &&
          en.text.length <= 110,
        `Caption line too long: ${cue.id}`,
      );
      assert(
        zh.endMs > zh.startMs && zh.endMs <= (cue.duration / 30) * 1000 + 1,
        `Caption outside cue: ${cue.id}`,
      );
      pairCount++;
    }
    for (const id of cue.features)
      featureCues.set(id, [...(featureCues.get(id) ?? []), cue.id]);
  }
}
const inventory = read("public/v040/coverage.json");
const omitted = inventory.features
  .filter((feature) => !featureCues.has(feature.id))
  .map((feature) => feature.id);
assert(!omitted.length, `Features without captions: ${omitted.join(", ")}`);
const manifest = read("public/v040/capture/manifest.json");
assert(
  manifest.theme === "Light" && manifest.language === "English",
  "Capture must use Light + English",
);
assert(manifest.appVersion === "0.4.0", "Current app capture version mismatch");
const required = new Set(
  readdirSync(resolve(root, "src/scenes")).flatMap((name) =>
    [
      ...readFileSync(resolve(root, "src/scenes", name), "utf8").matchAll(
        /id:\s*"([^"]+)"/g,
      ),
    ].map((match) => match[1]),
  ),
);
for (const id of required) {
  const shot = manifest.shots.find((item) => item.id === id);
  assert(shot, `Missing current shot: ${id}`);
  assert(
    !shot.file.includes("..") && !shot.metadata.includes(".."),
    `Capture path escapes directory: ${id}`,
  );
  assert(
    existsSync(resolve(root, `public/v040/capture/${shot.file}`)),
    `Missing video file: ${id}`,
  );
  const metadata = read(`public/v040/capture/${shot.metadata}`);
  assert(
    shot.fps === 30 && metadata.fps === 30 && metadata.frames > 0,
    `Capture timing invalid: ${id}`,
  );
  assert(
    metadata.cursor.length === metadata.frames,
    `Recorded cursor samples missing: ${id}`,
  );
  assert(
    metadata.appVersion === "0.4.0" &&
      metadata.theme === "Light" &&
      metadata.language === "English" &&
      metadata.nativeEngine === true &&
      metadata.windowOpened === false,
    `Capture provenance invalid: ${id}`,
  );
  const probe = JSON.parse(
    execFileSync(
      ffprobe,
      [
        "-v",
        "error",
        "-select_streams",
        "v:0",
        "-show_entries",
        "stream=width,height,avg_frame_rate,nb_frames",
        "-of",
        "json",
        resolve(root, `public/v040/capture/${shot.file}`),
      ],
      { encoding: "utf8" },
    ),
  ).streams[0];
  assert(
    probe.width === shot.width &&
      probe.height === shot.height &&
      probe.avg_frame_rate === "30/1" &&
      Number(probe.nb_frames) === metadata.frames,
    `Actual capture video differs from metadata: ${id}`,
  );
}
for (const excerpt of read("public/v040/code/excerpts.json")) {
  const source = readFileSync(resolve(repo, excerpt.file), "utf8").split(
    /\r?\n/,
  );
  assert(
    excerpt.lines.every(
      (line, index) => line === source[excerpt.firstLine - 1 + index],
    ),
    `Source excerpt stale: ${excerpt.file}`,
  );
}
for (const language of ["zh", "en"]) {
  assert(
    existsSync(
      resolve(root, `public/v040/subtitles/podor-040-${language}.srt`),
    ),
    `Missing ${language} SRT`,
  );
}
const report = {
  frames: 18000,
  seconds: 600,
  width: 1920,
  height: 1080,
  fps: 30,
  chapters: 9,
  audioMode: "background-music-only",
  burnedCaptions: "zh",
  externalSubtitles: ["zh", "en"],
  captionPairs: pairCount,
  features: inventory.features.length,
  realUiShots: [...required],
  theme: manifest.theme,
  language: manifest.language,
  captureManifestSha256: hash(
    resolve(root, "public/v040/capture/manifest.json"),
  ),
  logoSha256: hash(resolve(root, "public/logo.png")),
  stylusSha256: hash(resolve(root, "public/v040/stylus-cursor.png")),
};
if (outputFile) {
  const result = JSON.parse(
    execFileSync(
      ffprobe,
      [
        "-v",
        "error",
        "-count_frames",
        "-show_streams",
        "-show_format",
        "-of",
        "json",
        resolve(root, outputFile),
      ],
      { encoding: "utf8" },
    ),
  );
  const video = result.streams.find((stream) => stream.codec_type === "video");
  assert(
    video.width === 1920 &&
      video.height === 1080 &&
      video.avg_frame_rate === "30/1",
    "Output geometry/fps mismatch",
  );
  assert(
    Number(video.nb_read_frames) === 18000 &&
      Number(result.format.duration) === 600,
    "Output duration/frame count mismatch",
  );
  assert(
    result.streams.some((stream) => stream.codec_type === "audio"),
    "Output audio missing",
  );
  report.output = {
    path: outputFile,
    frames: Number(video.nb_read_frames),
    seconds: Number(result.format.duration),
    codec: video.codec_name,
    audio: true,
  };
}
writeFileSync(
  resolve(root, "out/v040/validation.json"),
  JSON.stringify(report, null, 2),
);
console.log(JSON.stringify(report, null, 2));
