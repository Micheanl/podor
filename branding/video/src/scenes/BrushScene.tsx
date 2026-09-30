import { CaptureStage } from "../CaptureStage";
import { chapters } from "../film";
export const BrushScene = () => (
  <CaptureStage
    chapter={chapters[2]}
    shots={[
      { id: "brush-types", from: 0, duration: 180, trimBefore: 2193 },
      {
        id: "brush-library",
        from: 180,
        duration: 180,
        trimBefore: 34,
        trimAfter: 386,
        speed: "fit",
      },
      {
        id: "colors-selection",
        from: 360,
        duration: 390,
        trimBefore: 175,
        trimAfter: 295,
        speed: "fit",
      },
      { id: "brush-willow", from: 750, duration: 360, speed: "fit" },
      {
        id: "brush-settings",
        from: 1110,
        duration: 390,
        trimBefore: 65,
        trimAfter: 306,
        speed: "fit",
      },
      {
        id: "brush-settings",
        from: 1500,
        duration: 150,
        trimBefore: 150,
        trimAfter: 232,
        speed: "fit",
      },
      {
        id: "view-symmetry-transform",
        from: 1650,
        duration: 210,
        trimAfter: 260,
        speed: "fit",
      },
      {
        id: "smudge-layer-blend",
        from: 1860,
        duration: 150,
        trimAfter: 110,
        speed: "fit",
      },
      {
        id: "brush-types",
        from: 2010,
        duration: 150,
        trimBefore: 1970,
        speed: "fit",
      },
      {
        id: "brush-settings",
        from: 2160,
        duration: 90,
        trimBefore: 448,
        speed: "fit",
      },
    ]}
  />
);
