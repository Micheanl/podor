import { Composition, Folder } from "remotion";
import { PodorFilm, Scene } from "./Composition";
import { chapters } from "./Operation";
export const RemotionRoot = () => (
  <>
    <Composition
      id="Podor"
      component={PodorFilm}
      durationInFrames={
        chapters.reduce((n, c) => n + c.duration, 0) -
        12 * (chapters.length - 1)
      }
      fps={30}
      width={1920}
      height={1080}
    />
    <Folder name="Operations">
      {chapters.map((chapter) => (
        <Composition
          key={chapter.id}
          id={chapter.id}
          component={Scene}
          defaultProps={{ chapter }}
          durationInFrames={chapter.duration}
          fps={30}
          width={1920}
          height={1080}
        />
      ))}
    </Folder>
  </>
);
