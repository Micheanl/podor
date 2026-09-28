import { Audio, Video } from "@remotion/media";
import {
  AbsoluteFill,
  CanvasImage,
  Easing,
  Freeze,
  interpolate,
  Sequence,
  staticFile,
  useCurrentFrame,
} from "remotion";
import timeline from "./timeline.json";
import newCanvas from "../public/clips/NewCanvas.json";
import brushes from "../public/clips/Brushes.json";
import color from "../public/clips/Color.json";
import layers from "../public/clips/Layers.json";
import selection from "../public/clips/Selection.json";
import transform from "../public/clips/Transform.json";
import curves from "../public/clips/Curves.json";
import references from "../public/clips/References.json";
import exportClip from "../public/clips/Export.json";
import workspace from "../public/clips/Workspace.json";
import "./fonts";

export const chapters = timeline;
export type Chapter = (typeof chapters)[number];
type Capture = { frames: number; cursor: number[][]; clicks: number[] };
const captures: Record<string, Capture> = {
  NewCanvas: newCanvas,
  Brushes: brushes,
  Color: color,
  Layers: layers,
  Selection: selection,
  Transform: transform,
  Curves: curves,
  References: references,
  Export: exportClip,
  Workspace: workspace,
};
const ease = {
  extrapolateLeft: "clamp",
  extrapolateRight: "clamp",
  easing: Easing.bezier(0.22, 1, 0.36, 1),
} as const;
const focus: Record<string, number[]> = {
  NewCanvas: [0, 45, 220, 330],
  Brushes: [0, 45, 230, 335],
  Color: [0, 60, 400, 510],
  Layers: [0, 50, 460, 610],
  Curves: [0, 90, 270, 390],
  Export: [0, 65, 350, 480],
};
const keys: Record<string, { start: number; end: number; text: string }[]> = {
  NewCanvas: [{ start: 30, end: 85, text: "Ctrl N" }],
  Selection: [
    { start: 30, end: 90, text: "M" },
    { start: 230, end: 295, text: "Ctrl Shift C" },
    { start: 295, end: 360, text: "Ctrl V" },
    { start: 360, end: 425, text: "Ctrl D" },
  ],
  Transform: [
    { start: 30, end: 110, text: "Ctrl T" },
    { start: 215, end: 295, text: "Enter" },
    { start: 320, end: 460, text: "Ctrl Z" },
  ],
  References: [{ start: 70, end: 150, text: "Ctrl Shift V" }],
  Workspace: [{ start: 30, end: 90, text: "Ctrl S" }],
};

const Voice: React.FC<{ chapter: Chapter }> = ({ chapter }) => {
  const frame = useCurrentFrame();
  const captions = chapter.cues.flatMap((cue) =>
    cue.captions.flatMap((caption) => {
      const words = caption.text.split(" ");
      const count = Math.ceil(words.length / 10);
      return Array.from({ length: count }, (_, i) => ({
        text: words.slice(i * 10, (i + 1) * 10).join(" "),
        start:
          cue.from +
          caption.start +
          ((caption.end - caption.start) * i) / count,
        end:
          cue.from +
          caption.start +
          ((caption.end - caption.start) * (i + 1)) / count,
      }));
    }),
  );
  const caption = captions.find((c) => frame >= c.start && frame < c.end);
  return (
    <>
      {chapter.cues.map((cue) => (
        <Sequence
          key={cue.file}
          from={cue.from}
          durationInFrames={cue.duration}
        >
          <Audio src={staticFile(`voice/${cue.file}`)} />
        </Sequence>
      ))}
      <div
        style={{
          position: "absolute",
          left: 120,
          right: 120,
          bottom: 32,
          textAlign: "center",
          color: "#e9e2df",
          fontSize: 28,
          fontWeight: 400,
          lineHeight: 1.4,
        }}
      >
        {caption?.text}
      </div>
    </>
  );
};

const Cursor: React.FC<{ capture: Capture }> = ({ capture }) => {
  const frame = Math.min(useCurrentFrame(), capture.frames - 1);
  const [x, y] = capture.cursor[frame] ?? [680, 450];
  const click = capture.clicks
    .filter((c) => c <= frame && c > frame - 23)
    .slice(-1)[0];
  return (
    <>
      {click !== undefined && (
        <div
          style={{
            position: "absolute",
            left: x - 25,
            top: y - 25,
            width: 50,
            height: 50,
            border: "2px solid #ecc7d0",
            borderRadius: "50%",
            background: "#c66c8930",
            opacity: interpolate(frame - click, [0, 22], [0.9, 0]),
            scale: interpolate(frame - click, [0, 22], [0.4, 1.8], ease),
          }}
        />
      )}
      <svg
        width="27"
        height="34"
        viewBox="0 0 27 34"
        style={{
          position: "absolute",
          left: x - 2,
          top: y - 2,
          filter: "drop-shadow(0 2px 3px #0009)",
        }}
      >
        <path
          d="M3 2L3 27L9 21L14 32L19 29L14 19L24 19Z"
          fill="#faf5f0"
          stroke="#242126"
          strokeWidth="1.8"
          strokeLinejoin="round"
        />
      </svg>
    </>
  );
};

export const Operation: React.FC<{ chapter: Chapter }> = ({ chapter }) => {
  const frame = useCurrentFrame();
  const capture = captures[chapter.id];
  const zoom = focus[chapter.id];
  const panel = ["Brushes", "Color", "Layers", "Curves"].includes(chapter.id);
  const shortcut = keys[chapter.id]?.find(
    (k) => frame >= k.start && frame < k.end,
  );
  return (
    <AbsoluteFill
      style={{
        background: "#131315",
        color: "#eee8e1",
        fontFamily: "Inter",
        overflow: "hidden",
      }}
    >
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(ellipse at 75% 100%, #43253266, transparent 65%)",
        }}
      />
      <div
        style={{
          position: "absolute",
          left: 86,
          top: 23,
          fontSize: 24,
          fontWeight: 450,
          letterSpacing: -0.4,
          opacity: interpolate(frame, [0, 22], [0, 1], ease),
        }}
      >
        {chapter.title}
      </div>
      <div
        style={{
          position: "absolute",
          right: 88,
          top: 17,
          display: "flex",
          alignItems: "center",
          gap: 10,
        }}
      >
        <CanvasImage
          src={staticFile("logo.png")}
          style={{ width: 34, height: 34 }}
        />
        <span style={{ fontSize: 24, fontFamily: "Cormorant" }}>podor</span>
      </div>
      <div
        style={{
          position: "absolute",
          left: 80,
          top: 70,
          width: 1760,
          height: 920,
          background: "#17181a",
          borderRadius: 20,
          overflow: "hidden",
          boxShadow: "0 24px 65px #0005",
          border: "1px solid #353236",
        }}
      >
        <div
          style={{
            position: "absolute",
            left: 200,
            top: 10,
            width: 1360,
            height: 900,
            transformOrigin: "center center",
            scale: zoom
              ? interpolate(frame, zoom, [1, 1.18, 1.18, 1], ease)
              : 1,
            translate:
              zoom && panel
                ? interpolate(
                    frame,
                    zoom,
                    ["0px 0px", "-120px 0px", "-120px 0px", "0px 0px"],
                    ease,
                  )
                : "0px 0px",
          }}
        >
          <Freeze frame={capture.frames - 1} active={frame >= capture.frames}>
            <Video
              src={staticFile(`clips/${chapter.id}.mp4`)}
              muted
              style={{ width: 1360, height: 900 }}
            />
          </Freeze>
          <Cursor capture={capture} />
        </div>
        {shortcut && (
          <div
            style={{
              position: "absolute",
              left: 32,
              bottom: 26,
              background: "#292329",
              border: "1px solid #755060",
              borderRadius: 10,
              color: "#f0d8df",
              padding: "10px 18px",
              fontSize: 23,
              opacity: interpolate(
                frame - shortcut.start,
                [0, 7],
                [0, 1],
                ease,
              ),
            }}
          >
            {shortcut.text}
          </div>
        )}
      </div>
      <Voice chapter={chapter} />
    </AbsoluteFill>
  );
};

export const Brand: React.FC<{ chapter: Chapter }> = ({ chapter }) => {
  const frame = useCurrentFrame();
  const closing = chapter.id === "Outro";
  return (
    <AbsoluteFill
      style={{
        background: "#151416",
        color: "#f3ebe1",
        fontFamily: "Inter",
        overflow: "hidden",
      }}
    >
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(ellipse at 65% 50%, #5d29354a, transparent 60%)",
        }}
      />
      <div
        style={{
          position: "absolute",
          left: 460,
          top: 360,
          display: "flex",
          gap: 45,
          alignItems: "center",
          opacity: interpolate(
            frame,
            [0, 30, chapter.duration - 24, chapter.duration],
            [0, 1, 1, 0],
            ease,
          ),
          translate: interpolate(frame, [0, 50], ["0px 24px", "0px 0px"], ease),
        }}
      >
        <CanvasImage
          src={staticFile("logo.png")}
          style={{
            width: 230,
            height: 230,
            scale: interpolate(frame, [0, 55], [0.92, 1], ease),
          }}
        />
        <div>
          <div
            style={{
              fontFamily: "Cormorant",
              fontSize: 202,
              fontWeight: 400,
              lineHeight: 1,
            }}
          >
            podor
          </div>
          <div
            style={{
              marginTop: 17,
              marginLeft: 10,
              fontSize: 21,
              color: "#b9a7ae",
              letterSpacing: 3,
            }}
          >
            {closing ? "KEEP CREATING" : "FROM A BLANK CANVAS"}
          </div>
        </div>
      </div>
      <div
        style={{
          position: "absolute",
          left: 460,
          right: 460,
          top: 682,
          height: 1,
          background:
            "linear-gradient(90deg,transparent,#9b4361,#d59b7b,#d9ccab,transparent)",
          scale: `${interpolate(frame, [15, 80], [0, 1], ease)} 1`,
          opacity: 0.6,
        }}
      />
      <Voice chapter={chapter} />
    </AbsoluteFill>
  );
};
