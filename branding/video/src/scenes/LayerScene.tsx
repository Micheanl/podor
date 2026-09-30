import { CaptureStage } from "../CaptureStage";
import { chapters } from "../film";
export const LayerScene = () => (
  <CaptureStage
    chapter={chapters[4]}
    shots={[
      {
        id: "advanced-editing",
        from: 0,
        duration: 200,
        trimAfter: 228,
        speed: "fit",
      },
      {
        id: "advanced-editing",
        from: 200,
        duration: 161,
        trimBefore: 747,
        speed: "fit",
      },
      {
        id: "smudge-layer-blend",
        from: 361,
        duration: 200,
        trimBefore: 110,
        trimAfter: 407,
        speed: "fit",
      },
      {
        id: "advanced-editing",
        from: 561,
        duration: 159,
        trimBefore: 228,
        trimAfter: 402,
        speed: "fit",
      },
      {
        id: "advanced-editing",
        from: 720,
        duration: 360,
        trimBefore: 402,
        trimAfter: 747,
        speed: "fit",
      },
      {
        id: "view-symmetry-transform",
        from: 1080,
        duration: 130,
        trimBefore: 535,
        trimAfter: 681,
        speed: "fit",
      },
      {
        id: "adjustments",
        from: 1210,
        duration: 133,
        trimBefore: 300,
        trimAfter: 504,
        speed: "fit",
      },
      {
        id: "smudge-layer-blend",
        from: 1343,
        duration: 100,
        trimBefore: 407,
        speed: "fit",
      },
      {
        id: "vector-assistants",
        from: 1443,
        duration: 207,
        trimBefore: 175,
        speed: "fit",
      },
      {
        id: "comic-lines",
        from: 1650,
        duration: 150,
        trimBefore: 393,
        speed: "fit",
      },
    ]}
  />
);
