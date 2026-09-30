import { execFileSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ffmpeg = resolve(
  root,
  "node_modules/@remotion/compositor-win32-x64-msvc/ffmpeg.exe",
);
const input = process.argv[2] ?? "out/v040/podor-0.4.0-tour-zh-render.mp4";
const output = process.argv[3] ?? "out/v040/podor-0.4.0-tour-zh.mp4";
const seconds = process.argv[4] ?? "600";
const soundtrack = process.argv[5] ?? "out/v040/podor-040-music.wav";
execFileSync(
  ffmpeg,
  [
    "-y",
    "-v",
    "error",
    "-i",
    resolve(root, input),
    "-i",
    resolve(root, soundtrack),
    "-t",
    seconds,
    "-map",
    "0:v:0",
    "-map",
    "1:a:0",
    "-c:v",
    "copy",
    "-c:a",
    "aac",
    "-b:a",
    "192k",
    "-movflags",
    "+faststart",
    "-f",
    "mp4",
    resolve(root, output),
  ],
  { stdio: "inherit" },
);
