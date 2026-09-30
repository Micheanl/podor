import { CaptureStage } from "../CaptureStage";
import { chapters } from "../film";
export const ColorSelectionScene = () => (
  <CaptureStage
    chapter={chapters[3]}
    shots={[
      {
        id: "colors-selection",
        from: 0,
        duration: 180,
        trimAfter: 175,
        speed: "fit",
      },
      {
        id: "fill-palette",
        from: 180,
        duration: 169,
        trimBefore: 175,
        speed: "fit",
      },
      {
        id: "indexed-palette",
        from: 349,
        duration: 341,
        trimBefore: 442,
        speed: "fit",
      },
      {
        id: "colors-selection",
        from: 690,
        duration: 364,
        trimBefore: 280,
        trimAfter: 491,
        speed: "fit",
      },
      {
        id: "colors-selection",
        from: 1054,
        duration: 170,
        trimBefore: 491,
        speed: "fit",
      },
      {
        id: "view-symmetry-transform",
        from: 1224,
        duration: 171,
        trimBefore: 260,
        trimAfter: 507,
        speed: "fit",
      },
      { id: "references-clipboard", from: 1395, duration: 355, speed: "fit" },
      {
        id: "indexed-palette",
        from: 1750,
        duration: 350,
        trimAfter: 442,
        speed: "fit",
      },
    ]}
  />
);
