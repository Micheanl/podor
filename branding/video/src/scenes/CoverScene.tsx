import { AbsoluteFill, CanvasImage, staticFile } from "remotion";
import "../fonts";

export const CoverScene = () => (
  <AbsoluteFill
    style={{
      background: "#f5f4f1",
      color: "#29272c",
      fontFamily: "Inter, Noto Sans SC",
    }}
  >
    <CanvasImage
      src={staticFile("v040/cover-background.png")}
      style={{ width: "100%", height: "100%", objectFit: "cover" }}
    />
    <div
      style={{
        position: "absolute",
        left: 94,
        top: 86,
        display: "flex",
        alignItems: "center",
        gap: 22,
      }}
    >
      <CanvasImage
        src={staticFile("logo.png")}
        style={{ width: 88, height: 88 }}
      />
      <div
        style={{ fontFamily: "Inter", fontSize: 54, fontWeight: 600, lineHeight: 1 }}
      >
        podor
        <span
          style={{
            fontFamily: "Inter",
            fontSize: 30,
            color: "#80665e",
            marginLeft: 20,
          }}
        >
          0.4.0
        </span>
      </div>
    </div>
    <div
      style={{
        position: "absolute",
        left: 94,
        top: 270,
        fontFamily: "Noto Sans SC",
        fontSize: 132,
        fontWeight: 600,
        lineHeight: 1.2,
        letterSpacing: 3,
      }}
    >
      让创作
      <br />
      <span style={{ color: "#902746" }}>更顺手</span>
    </div>
    <div
      style={{
        position: "absolute",
        left: 100,
        top: 640,
        color: "#64584e",
        fontSize: 32,
        letterSpacing: 3,
      }}
    >
      绘画 · 图层 · 动画
    </div>
    <div
      style={{
        position: "absolute",
        left: 694,
        top: 464,
        width: 1148,
        height: 574,
        borderRadius: 16,
        overflow: "hidden",
        border: "1px solid #ddd5cb",
        background: "#fff",
        boxShadow: "0 22px 58px #35261f30",
      }}
    >
      <CanvasImage
        src={staticFile("v040/capture/bouquet-stage-05-last.png")}
        style={{ width: "100%", height: "100%", objectFit: "contain" }}
      />
    </div>
    <div
      style={{
        position: "absolute",
        left: 100,
        top: 906,
        fontSize: 30,
        color: "#64584e",
      }}
    >
      十分钟完整演示
    </div>
  </AbsoluteFill>
);
