import {
  CanvasImage,
  Easing,
  Interactive,
  interpolate,
  staticFile,
  useCurrentFrame,
} from "remotion";
import { chapters } from "../film";
import { SceneFrame } from "../SceneFrame";
export const BrandScene = () => {
  const frame = useCurrentFrame();
  return (
    <SceneFrame chapter={chapters[0]}>
      <Interactive.Div
        name="Current app icon"
        style={{
          position: "absolute",
          left: 136,
          top: 230,
          width: 470,
          opacity: interpolate(frame, [0, 30], [0, 1], {
            extrapolateRight: "clamp",
            easing: Easing.bezier(0.2, 0, 0, 1),
          }),
          translate: interpolate(frame, [0, 60], ["0px 18px", "0px 0px"], {
            extrapolateRight: "clamp",
          }),
        }}
      >
        <CanvasImage
          src={staticFile("logo.png")}
          style={{ width: 238, height: 238 }}
        />
        <div
          style={{
            fontFamily: "Noto Sans SC",
            fontSize: 58,
            lineHeight: 1.5,
            marginTop: 20,
          }}
        >
          从一块颜色开始
        </div>
        <div style={{ fontSize: 28, color: "#8a8186", marginTop: 18 }}>
          绘画 · 图像编辑 · 逐帧动画
        </div>
      </Interactive.Div>
      <Interactive.Div
        name="Painting practice reference"
        style={{
          position: "absolute",
          left: 732,
          top: 126,
          width: 1050,
          height: 732,
          borderRadius: 18,
          overflow: "hidden",
          opacity: interpolate(frame, [25, 60], [0, 1], {
            extrapolateLeft: "clamp",
            extrapolateRight: "clamp",
          }),
        }}
      >
        <CanvasImage
          src={staticFile("v040/artwork/rose-reference.png")}
          style={{ width: "100%", height: "100%", objectFit: "contain" }}
        />
        <div
          style={{
            position: "absolute",
            bottom: 10,
            left: 54,
            padding: "8px 12px",
            background: "#f5f4f1e6",
            borderRadius: 6,
            fontSize: 24,
            color: "#777078",
          }}
        >
          生成练习参考
        </div>
      </Interactive.Div>
    </SceneFrame>
  );
};
