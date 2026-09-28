import { loadFont } from "@remotion/fonts";
import { staticFile } from "remotion";
export const fontsReady = Promise.all([
  loadFont({
    family: "Cormorant",
    url: staticFile("fonts/CormorantGaramond.ttf"),
    weight: "300 700",
  }),
  loadFont({
    family: "Inter",
    url: staticFile("fonts/Inter.ttf"),
    weight: "100 900",
  }),
]);
