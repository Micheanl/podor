import {
  CanvasImage,
  Easing,
  interpolate,
  staticFile,
  useCurrentFrame,
} from "remotion";
import excerpts from "../../public/v040/code/excerpts.json";
import { chapters, mapFrame } from "../film";
import { SceneFrame } from "../SceneFrame";

const titles = [
  "状态决定界面",
  "笔尖连接系统指针",
  "画布接收真实输入",
  "笔触交给像素引擎",
  "只更新改变的像素块",
  "共享未改变的像素",
  "撤销保留原来的内容",
  "参数成为可预览的结果",
];

const colorize = (line: string) =>
  line
    .split(
      /("[^"\n]*"|\b(?:fun|val|var|return|if|else|pub|fn|let|mut|struct|impl|use|const|type|private|internal|expect|actual)\b|\b\d+(?:\.\d+)?\b|@Composable)/g,
    )
    .map((part, index) => (
      <span
        key={index}
        style={{
          color: part.startsWith('"')
            ? "#087d50"
            : /^\d/.test(part)
              ? "#1750b5"
              : /^(fun|val|var|return|if|else|pub|fn|let|mut|struct|impl|use|const|type|private|internal|expect|actual)$/.test(
                    part,
                  )
                ? "#a52877"
                : part === "@Composable"
                  ? "#b16917"
                  : "#2b2f36",
        }}
      >
        {part}
      </span>
    ));

export const CodeScene = () => {
  const frame = mapFrame(chapters[7], useCurrentFrame(), true);
  const index = Math.max(
    0,
    excerpts.findIndex(
      (item) => frame >= item.start && frame < item.start + item.duration,
    ),
  );
  const excerpt = excerpts[index];
  const localFrame = frame - excerpt.start;
  const first = Math.min(
    Math.floor(localFrame / 120) * 6,
    Math.max(0, excerpt.lines.length - 6),
  );
  const lines = excerpt.lines.slice(first, first + 6);
  const activeLine = Math.min(
    lines.length - 1,
    Math.floor((localFrame % 120) / 20),
  );
  const kotlin = excerpt.language === "Kotlin";
  return (
    <SceneFrame chapter={chapters[7]}>
      <div
        style={{
          position: "absolute",
          left: 72,
          top: 94,
          width: 1776,
          height: 820,
          border: "1px solid #d9dce1",
          borderRadius: 16,
          background: "#eef0f3",
          overflow: "hidden",
        }}
      >
        <div
          style={{
            position: "absolute",
            left: 88,
            top: 38,
            height: 104,
            display: "flex",
            alignItems: "center",
            gap: 50,
          }}
        >
          <CanvasImage
            src={staticFile(
              kotlin
                ? "v040/brands/kotlin-logo.svg"
                : "v040/brands/rust-logo.svg",
            )}
            style={{
              width: kotlin ? 268 : 104,
              height: kotlin ? 74 : 104,
              objectFit: "contain",
            }}
          />
          {kotlin && (
            <CanvasImage
              src={staticFile("v040/brands/compose-logo.svg")}
              style={{ width: 576, height: 60, objectFit: "contain" }}
            />
          )}
        </div>
        <div
          style={{
            position: "absolute",
            left: 88,
            top: 164,
            fontSize: 48,
            fontWeight: 500,
          }}
        >
          {titles[index]}
        </div>
        <div
          style={{
            position: "absolute",
            right: 88,
            top: 178,
            fontSize: 26,
            color: "#7c8490",
          }}
        >
          真实源码摘录
        </div>
        <div
          style={{
            position: "absolute",
            left: 88,
            top: 266,
            width: 1600,
            height: 458,
            padding: "26px 0",
            borderRadius: 12,
            background: "#fff",
            opacity: interpolate(localFrame, [0, 14], [0, 1], {
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.2, 0, 0, 1),
            }),
            fontFamily: "JetBrains Mono",
            fontSize: 28,
            lineHeight: "42px",
          }}
        >
          {lines.map((line, lineIndex) => (
            <div
              key={first + lineIndex}
              style={{
                display: "flex",
                minHeight: 42,
                background:
                  lineIndex === activeLine ? "#fff3d8" : "transparent",
              }}
            >
              <span
                style={{
                  width: 114,
                  flexShrink: 0,
                  textAlign: "right",
                  paddingRight: 26,
                  color: "#a4acb8",
                }}
              >
                {excerpt.firstLine + first + lineIndex}
              </span>
              <span
                style={{
                  whiteSpace: "pre-wrap",
                  overflowWrap: "anywhere",
                  paddingRight: 30,
                }}
              >
                {colorize(line)}
              </span>
            </div>
          ))}
        </div>
      </div>
    </SceneFrame>
  );
};
