import { Video } from "@remotion/media";
import { useEffect, useState } from "react";
import {
  CanvasImage,
  Easing,
  Freeze,
  interpolate,
  Sequence,
  staticFile,
  useCurrentFrame,
  useDelayRender,
} from "remotion";
import type { Chapter, Shot } from "./film";
import { mapFrame } from "./film";
import { SceneFrame } from "./SceneFrame";

type Cursor = { x: number; y: number; down: boolean; canvasHover?: boolean };
type Capture = {
  frames: number;
  fps: number;
  width: number;
  height: number;
  cursor: Cursor[];
};
type ManifestShot = {
  id: string;
  file: string;
  metadata: string;
  width: number;
  height: number;
  frames: number;
  fps: number;
  cursorEmbedded: boolean;
};
type CaptureManifest = {
  shots: ManifestShot[];
  theme?: string;
  language?: string;
};

const cache = new Map<string, Promise<unknown>>();
const readJson = <T,>(path: string): Promise<T> => {
  if (!cache.has(path)) {
    cache.set(
      path,
      fetch(staticFile(path)).then((response) => {
        if (!response.ok) throw new Error(`Missing current capture: ${path}`);
        return response.json();
      }),
    );
  }
  return cache.get(path) as Promise<T>;
};

const RecordedShot: React.FC<{ shot: Shot }> = ({ shot }) => {
  const frame = useCurrentFrame();
  const [data, setData] = useState<{
    asset: ManifestShot;
    capture: Capture;
  } | null>(null);
  const { delayRender, continueRender, cancelRender } = useDelayRender();
  const [handle] = useState(() => delayRender(`Load current UI: ${shot.id}`));
  useEffect(() => {
    let disposed = false;
    readJson<CaptureManifest>("v040/capture/manifest.json")
      .then(async (manifest) => {
        const asset = manifest.shots.find((item) => item.id === shot.id);
        if (!asset) throw new Error(`Current UI shot absent: ${shot.id}`);
        const capture = await readJson<Capture>(
          `v040/capture/${asset.metadata}`,
        );
        if (!disposed) {
          setData({ asset, capture });
          continueRender(handle);
        }
      })
      .catch(cancelRender);
    return () => {
      disposed = true;
    };
  }, [shot.id, handle, continueRender, cancelRender]);
  if (!data) return null;
  const first = shot.trimBefore ?? 0;
  const end = Math.min(
    data.capture.frames,
    shot.trimAfter ?? data.capture.frames,
  );
  const speed =
    shot.speed === "fit"
      ? (end - first - 1) / Math.max(1, shot.duration - 31)
      : (shot.speed ?? 1);
  const sourceFrame = Math.min(end - 1, Math.floor(frame * speed) + first);
  const point = data.capture.cursor[sourceFrame];
  const lastFrame =
    shot.speed === "fit"
      ? shot.duration - 31
      : Math.max(0, Math.ceil((end - first - 1) / speed));
  const fit = Math.min(1776 / data.asset.width, 820 / data.asset.height);
  return (
    <div
      style={{
        position: "absolute",
        left: 72,
        top: 94,
        width: 1776,
        height: 820,
        overflow: "hidden",
        border: "1px solid #dedbdd",
        borderRadius: 14,
        background: "#ffffff",
        boxShadow: "0 12px 30px #423c4310",
      }}
    >
      <div
        style={{
          position: "absolute",
          left: (1776 - data.asset.width * fit) / 2,
          top: (820 - data.asset.height * fit) / 2,
          width: data.asset.width,
          height: data.asset.height,
          transformOrigin: "top left",
          scale: fit,
        }}
      >
        <div
          style={{
            width: data.asset.width,
            height: data.asset.height,
            transformOrigin: shot.focus
              ? `${shot.focus.x}px ${shot.focus.y}px`
              : "center",
            scale: shot.focus
              ? interpolate(
                  frame,
                  [0, 36, shot.duration - 40, shot.duration - 1],
                  [1, shot.focus.scale, shot.focus.scale, 1],
                  {
                    extrapolateLeft: "clamp",
                    extrapolateRight: "clamp",
                    easing: Easing.bezier(0.25, 0.1, 0.25, 1),
                  },
                )
              : 1,
          }}
        >
          <Freeze frame={lastFrame} active={frame >= lastFrame}>
            <Video
              src={staticFile(`v040/capture/${data.asset.file}`)}
              muted
              playbackRate={speed}
              trimBefore={first}
              trimAfter={end}
              style={{ width: data.asset.width, height: data.asset.height }}
            />
          </Freeze>
          {!data.asset.cursorEmbedded &&
            point &&
            point.x >= 0 &&
            point.y >= 0 &&
            (point.canvasHover ? (
              <CanvasImage
                src={staticFile("v040/stylus-cursor.png")}
                style={{
                  position: "absolute",
                  left: point.x - 32 * 0.0655831021,
                  top: point.y - 32 * 0.8681729719,
                  width: 32,
                  height: 32,
                }}
              />
            ) : (
              <svg
                width="24"
                height="30"
                viewBox="0 0 24 30"
                style={{
                  position: "absolute",
                  left: point.x - 2,
                  top: point.y - 2,
                }}
              >
                <path
                  d="M2 2V24L8 18L12 28L17 26L13 16H22Z"
                  fill="#fff"
                  stroke="#28272b"
                  strokeWidth="1.5"
                  strokeLinejoin="round"
                />
              </svg>
            ))}
        </div>
      </div>
    </div>
  );
};

export const CaptureStage: React.FC<{
  chapter: Chapter;
  shots: Shot[];
  annotation?: string;
}> = ({ chapter, shots, annotation }) => (
  <SceneFrame chapter={chapter}>
    {annotation && (
      <div
        style={{
          position: "absolute",
          left: "50%",
          top: 34,
          transform: "translateX(-50%)",
          fontSize: 24,
          color: "#867a81",
        }}
      >
        {annotation}
      </div>
    )}
    {shots
      .map((shot) => ({
        ...shot,
        from: mapFrame(chapter, shot.from),
        duration:
          mapFrame(chapter, shot.from + shot.duration) -
          mapFrame(chapter, shot.from),
      }))
      .map((shot, index) => (
        <Sequence
          name={shot.id}
          key={`${shot.id}-${index}`}
          from={shot.from}
          durationInFrames={shot.duration}
        >
          <RecordedShot shot={shot} />
        </Sequence>
      ))}
  </SceneFrame>
);
