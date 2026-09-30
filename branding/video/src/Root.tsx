import { Composition, Folder, Still } from "remotion";
import { PodorFilm } from "./Composition";
import { BrandScene } from "./scenes/BrandScene";
import { WorkspaceScene } from "./scenes/WorkspaceScene";
import { BrushScene } from "./scenes/BrushScene";
import { ColorSelectionScene } from "./scenes/ColorSelectionScene";
import { LayerScene } from "./scenes/LayerScene";
import { AnimationScene } from "./scenes/AnimationScene";
import { PaintingScene } from "./scenes/PaintingScene";
import { CodeScene } from "./scenes/CodeScene";
import { ReleaseScene } from "./scenes/ReleaseScene";
import { CoverScene } from "./scenes/CoverScene";
import { ReviewScene } from "./scenes/ReviewScene";
import { chapters } from "./film";
export const RemotionRoot = () => (
  <>
    <Composition
      id="Podor040Review"
      component={ReviewScene}
      durationInFrames={600}
      fps={30}
      width={1920}
      height={1080}
    />
    <Still
      id="PodorCover040"
      component={CoverScene}
      width={1920}
      height={1080}
    />
    <Composition
      id="Podor040"
      component={PodorFilm}
      durationInFrames={18000}
      fps={30}
      width={1920}
      height={1080}
    />
    <Folder name="Chapters040">
      <Composition
        id="Brand040"
        component={BrandScene}
        durationInFrames={chapters[0].duration}
        fps={30}
        width={1920}
        height={1080}
      />
      <Composition
        id="Workspace040"
        component={WorkspaceScene}
        durationInFrames={chapters[1].duration}
        fps={30}
        width={1920}
        height={1080}
      />
      <Composition
        id="Brushes040"
        component={BrushScene}
        durationInFrames={chapters[2].duration}
        fps={30}
        width={1920}
        height={1080}
      />
      <Composition
        id="ColorSelection040"
        component={ColorSelectionScene}
        durationInFrames={chapters[3].duration}
        fps={30}
        width={1920}
        height={1080}
      />
      <Composition
        id="Layers040"
        component={LayerScene}
        durationInFrames={chapters[4].duration}
        fps={30}
        width={1920}
        height={1080}
      />
      <Composition
        id="Animation040"
        component={AnimationScene}
        durationInFrames={chapters[5].duration}
        fps={30}
        width={1920}
        height={1080}
      />
      <Composition
        id="Painting040"
        component={PaintingScene}
        durationInFrames={chapters[6].duration}
        fps={30}
        width={1920}
        height={1080}
      />
      <Composition
        id="Code040"
        component={CodeScene}
        durationInFrames={chapters[7].duration}
        fps={30}
        width={1920}
        height={1080}
      />
      <Composition
        id="Release040"
        component={ReleaseScene}
        durationInFrames={chapters[8].duration}
        fps={30}
        width={1920}
        height={1080}
      />
    </Folder>
  </>
);
