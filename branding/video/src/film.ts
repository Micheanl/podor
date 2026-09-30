import type { Caption } from "@remotion/captions";
import timeline from "./timeline040.json";

export type Cue = {
  id: string;
  from: number;
  duration: number;
  zh: string;
  en: string;
  captions: Caption[];
  englishCaptions: Caption[];
  features: string[];
};

export type Chapter = {
  id: string;
  zh: string;
  en: string;
  duration: number;
  cues: Cue[];
  visualTiming?: { base: number; actual: number }[];
};

export const chapters: Chapter[] = timeline;

export const mapFrame = (chapter: Chapter, frame: number, inverse = false) => {
  const points = chapter.visualTiming;
  if (!points) return frame;
  const from = inverse ? "actual" : "base";
  const to = inverse ? "base" : "actual";
  const index = points.findIndex((point) => point[from] > frame);
  if (index < 0) return points[points.length - 1][to];
  if (index === 0) return points[0][to];
  const left = points[index - 1];
  const right = points[index];
  return Math.min(
    points[points.length - 1][to] - 1,
    Math.round(
      left[to] +
        ((frame - left[from]) / (right[from] - left[from])) *
          (right[to] - left[to]),
    ),
  );
};

export type Shot = {
  id: string;
  from: number;
  duration: number;
  trimBefore?: number;
  trimAfter?: number;
  speed?: number | "fit";
  focus?: { x: number; y: number; scale: number };
};
