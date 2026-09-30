import { CaptureStage } from "../CaptureStage";
import { chapters } from "../film";
export const PaintingScene = () => (
  <CaptureStage
    chapter={chapters[6]}
    annotation="加速原生笔触练习"
    shots={[
      { id: "bouquet-stage-01", from: 0, duration: 660, speed: "fit" },
      { id: "bouquet-stage-02", from: 660, duration: 660, speed: "fit" },
      { id: "bouquet-stage-03", from: 1320, duration: 660, speed: "fit" },
      { id: "bouquet-stage-04", from: 1980, duration: 660, speed: "fit" },
      { id: "bouquet-stage-05", from: 2640, duration: 660, speed: "fit" },
    ]}
  />
);
