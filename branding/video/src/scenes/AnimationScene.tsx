import { CaptureStage } from "../CaptureStage";
import { chapters } from "../film";
export const AnimationScene = () => (
  <CaptureStage
    chapter={chapters[5]}
    shots={[
      { id: "animation", from: 0, duration: 377, trimAfter: 396, speed: "fit" },
      {
        id: "animation",
        from: 377,
        duration: 330,
        trimBefore: 336,
        trimAfter: 427,
        speed: "fit",
      },
      {
        id: "animation",
        from: 707,
        duration: 346,
        trimBefore: 579,
        trimAfter: 762,
        speed: "fit",
      },
      {
        id: "animation",
        from: 1053,
        duration: 366,
        trimBefore: 427,
        trimAfter: 579,
        speed: "fit",
      },
      {
        id: "animation",
        from: 1419,
        duration: 150,
        trimBefore: 762,
        trimAfter: 965,
        speed: "fit",
      },
      {
        id: "animation",
        from: 1569,
        duration: 120,
        trimBefore: 965,
        trimAfter: 1078,
        speed: "fit",
      },
      {
        id: "animation",
        from: 1689,
        duration: 111,
        trimBefore: 1078,
        speed: "fit",
      },
    ]}
  />
);
