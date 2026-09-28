import { Audio } from "@remotion/media";
import { staticFile } from "remotion";
import { TransitionSeries, linearTiming } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { Fragment } from "react";
import { Brand, Operation, chapters, Chapter } from "./Operation";

export const Scene: React.FC<{ chapter: Chapter }> = ({ chapter }) =>
  ["Intro", "Outro"].includes(chapter.id) ? (
    <Brand chapter={chapter} />
  ) : (
    <Operation chapter={chapter} />
  );
export const PodorFilm = () => (
  <>
    <Audio src={staticFile("bed.m4a")} volume={0.17} />
    <TransitionSeries>
      {chapters.map((chapter, index) => (
        <Fragment key={chapter.id}>
          {index > 0 && (
            <TransitionSeries.Transition
              presentation={fade()}
              timing={linearTiming({ durationInFrames: 12 })}
            />
          )}
          <TransitionSeries.Sequence
            name={chapter.title}
            durationInFrames={chapter.duration}
          >
            <Scene chapter={chapter} />
          </TransitionSeries.Sequence>
        </Fragment>
      ))}
    </TransitionSeries>
  </>
);
