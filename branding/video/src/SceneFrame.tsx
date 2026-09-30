import {
  AbsoluteFill,
  CanvasImage,
  Easing,
  Interactive,
  interpolate,
  staticFile,
  useCurrentFrame,
} from "remotion";
import { ChineseCaptions } from "./Captions";
import type { Chapter } from "./film";
import "./fonts";

export const SceneFrame: React.FC<{
  chapter: Chapter;
  children: React.ReactNode;
}> = ({ chapter, children }) => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill
      style={{
        background: "#f5f4f1",
        fontFamily: "Inter, Noto Sans SC",
        color: "#29272c",
        overflow: "hidden",
      }}
    >
      <Interactive.Div
        name="Chapter heading"
        style={{
          position: "absolute",
          left: 74,
          top: 30,
          display: "flex",
          alignItems: "baseline",
          gap: 18,
          opacity: interpolate(frame, [0, 18], [0, 1], {
            extrapolateLeft: "clamp",
            extrapolateRight: "clamp",
            easing: Easing.bezier(0.2, 0, 0, 1),
          }),
        }}
      >
        <span style={{ color: "#70424f", fontSize: 32, fontWeight: 500 }}>
          {chapter.zh}
        </span>
      </Interactive.Div>
      <div
        style={{
          position: "absolute",
          right: 76,
          top: 24,
          display: "flex",
          alignItems: "center",
          gap: 11,
        }}
      >
        <CanvasImage
          src={staticFile("logo.png")}
          style={{ width: 42, height: 42 }}
        />
        <span style={{ fontSize: 22, fontWeight: 500, letterSpacing: -0.7 }}>
          <span style={{ color: "#9a9195", fontWeight: 400 }}>0.4.0</span>
        </span>
      </div>
      {children}
      <div
        style={{
          position: "absolute",
          left: 74,
          right: 74,
          top: 930,
          height: 1,
          background: "#ded9d8",
        }}
      />
      <ChineseCaptions chapter={chapter} />
    </AbsoluteFill>
  );
};
