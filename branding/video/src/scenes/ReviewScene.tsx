import { Audio } from "@remotion/media";
import { AbsoluteFill, CanvasImage, Sequence, staticFile } from "remotion";
import { CaptureStage } from "../CaptureStage";
import { chapters } from "../film";
import { CoverScene } from "./CoverScene";
import "../fonts";

export const ReviewScene = () => (
  <AbsoluteFill style={{ background: "#f5f4f1", color: "#29272c" }}>
    <Audio src={staticFile("v040/music.m4a")} trimAfter={600} />
    <Sequence durationInFrames={180}>
      <CoverScene />
    </Sequence>
    <Sequence from={180} durationInFrames={180}>
      <CaptureStage
        chapter={{
          id: chapters[0].id,
          en: "",
          zh: "闭合轮廓，松手填色",
          duration: 180,
          cues: [],
        }}
        shots={[
          {
            id: "colors-selection",
            from: 0,
            duration: 180,
            trimBefore: 175,
            trimAfter: 295,
            speed: "fit",
          },
        ]}
      />
    </Sequence>
    <Sequence from={360} durationInFrames={240}>
      <AbsoluteFill>
        <div
          style={{
            position: "absolute",
            left: 170,
            right: 170,
            top: 260,
            height: 150,
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
          }}
        >
          <CanvasImage
            src={staticFile("v040/brands/kotlin-logo.svg")}
            style={{ width: 348, height: 96, objectFit: "contain" }}
          />
          <CanvasImage
            src={staticFile("v040/brands/compose-logo.svg")}
            style={{ width: 672, height: 70, objectFit: "contain" }}
          />
          <CanvasImage
            src={staticFile("v040/brands/rust-logo.svg")}
            style={{ width: 132, height: 132, objectFit: "contain" }}
          />
        </div>
        <div
          style={{
            position: "absolute",
            top: 534,
            width: "100%",
            textAlign: "center",
            fontFamily: "Noto Sans SC",
            fontSize: 64,
            fontWeight: 500,
          }}
        >
          界面与像素，分工清楚
        </div>
      </AbsoluteFill>
    </Sequence>
  </AbsoluteFill>
);
