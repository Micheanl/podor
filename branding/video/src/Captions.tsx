import { useCurrentFrame } from "remotion";
import type { Chapter } from "./film";

export const ChineseCaptions: React.FC<{ chapter: Chapter }> = ({
  chapter,
}) => {
  const frame = useCurrentFrame();
  const timeMs = (frame / 30) * 1000;
  const pages = chapter.cues.flatMap((cue) =>
    cue.captions.map((zh) => ({
      startMs: (cue.from / 30) * 1000 + zh.startMs,
      zh: zh.text,
    })),
  );
  const page = pages.filter((item) => item.startMs <= timeMs).pop() ?? pages[0];
  return (
    <div
      style={{
        position: "absolute",
        left: 80,
        right: 80,
        top: 942,
        height: 112,
        display: "flex",
        flexDirection: "column",
        justifyContent: "center",
        alignItems: "center",
        gap: 8,
        color: "#29272c",
        textAlign: "center",
      }}
    >
      <div
        style={{
          fontFamily: "Noto Sans SC",
          fontSize: 38,
          fontWeight: 450,
          lineHeight: 1.45,
          maxWidth: 1650,
        }}
      >
        {page?.zh}
      </div>
    </div>
  );
};
