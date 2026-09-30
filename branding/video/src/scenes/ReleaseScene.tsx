import { CanvasImage, Sequence, staticFile, useCurrentFrame } from "remotion";
import { CaptureStage } from "../CaptureStage";
import { chapters, mapFrame } from "../film";
import { SceneFrame } from "../SceneFrame";

const Closing = () => {
  const split = mapFrame(chapters[8], 600);
  const frame = mapFrame(chapters[8], useCurrentFrame() + split, true) - 600;
  return (
    <SceneFrame
      chapter={{
        ...chapters[8],
        cues: chapters[8].cues
          .map((cue) => ({ ...cue, from: cue.from - split }))
          .filter((cue) => cue.from >= 0),
      }}
    >
      <CanvasImage
        src={staticFile("logo.png")}
        style={{
          position: "absolute",
          left: 170,
          top: 260,
          width: 196,
          height: 196,
        }}
      />
      <div
        style={{
          position: "absolute",
          left: 170,
          top: 518,
          width: 700,
          fontSize: 54,
          fontWeight: 500,
          lineHeight: 1.5,
        }}
      >
        {frame < 300 ? "保留署名，继续创作" : "保存这一次，再画下一幅"}
      </div>
      <CanvasImage
        src={staticFile("v040/capture/bouquet-stage-05-last.png")}
        style={{
          position: "absolute",
          left: 900,
          top: 188,
          width: 850,
          height: 630,
          objectFit: "contain",
          borderRadius: 12,
        }}
      />
    </SceneFrame>
  );
};

export const ReleaseScene = () => (
  <>
    <Sequence durationInFrames={mapFrame(chapters[8], 600)}>
      <CaptureStage
        chapter={chapters[8]}
        shots={[
          {
            id: "export-settings",
            from: 0,
            duration: 300,
            trimAfter: 268,
            speed: "fit",
          },
          {
            id: "formats",
            from: 300,
            duration: 300,
            trimAfter: 290,
            speed: "fit",
          },
        ]}
      />
    </Sequence>
    <Sequence
      from={mapFrame(chapters[8], 600)}
      durationInFrames={chapters[8].duration - mapFrame(chapters[8], 600)}
    >
      <Closing />
    </Sequence>
  </>
);
