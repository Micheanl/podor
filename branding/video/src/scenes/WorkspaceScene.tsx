import { CaptureStage } from "../CaptureStage";
import { chapters } from "../film";
export const WorkspaceScene = () => (
  <CaptureStage
    chapter={chapters[1]}
    shots={[
      {
        id: "brush-settings",
        from: 0,
        duration: 180,
        trimAfter: 65,
        speed: "fit",
      },
      {
        id: "workspace",
        from: 180,
        duration: 180,
        trimAfter: 154,
        speed: "fit",
      },
      {
        id: "export-settings",
        from: 360,
        duration: 360,
        trimBefore: 268,
        trimAfter: 507,
        speed: "fit",
      },
      {
        id: "workspace",
        from: 720,
        duration: 360,
        trimBefore: 154,
        trimAfter: 558,
        speed: "fit",
      },
      {
        id: "resize-crop-view",
        from: 1080,
        duration: 360,
        trimBefore: 559,
        speed: "fit",
      },
      { id: "home", from: 1440, duration: 360, speed: "fit" },
    ]}
  />
);
