import { Audio } from "@remotion/media";
import { Sequence, staticFile } from "remotion";
import { BrandScene } from "./scenes/BrandScene";
import { WorkspaceScene } from "./scenes/WorkspaceScene";
import { BrushScene } from "./scenes/BrushScene";
import { ColorSelectionScene } from "./scenes/ColorSelectionScene";
import { LayerScene } from "./scenes/LayerScene";
import { AnimationScene } from "./scenes/AnimationScene";
import { PaintingScene } from "./scenes/PaintingScene";
import { CodeScene } from "./scenes/CodeScene";
import { ReleaseScene } from "./scenes/ReleaseScene";
import { chapters } from "./film";

const scenes = [
  BrandScene,
  WorkspaceScene,
  BrushScene,
  ColorSelectionScene,
  LayerScene,
  AnimationScene,
  PaintingScene,
  CodeScene,
  ReleaseScene,
];

export const PodorFilm = () => {
  let offset = 0;
  return (
    <>
      <Audio src={staticFile("v040/music.m4a")} />
      {chapters.map((chapter, index) => {
        const from = offset;
        offset += chapter.duration;
        const Scene = scenes[index];
        return (
          <Sequence
            key={chapter.id}
            from={from}
            durationInFrames={chapter.duration}
            name={chapter.zh}
          >
            <Scene />
          </Sequence>
        );
      })}
    </>
  );
};
