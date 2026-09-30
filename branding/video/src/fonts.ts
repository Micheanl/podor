import { loadFont } from "@remotion/fonts";
import { staticFile } from "remotion";
export const fontsReady = Promise.all([
  loadFont({
    family: "Inter",
    url: staticFile("fonts/Inter.ttf"),
    weight: "100 900",
  }),
  loadFont({
    family: "Noto Sans SC",
    url: staticFile("fonts/NotoSansSC.ttf"),
    weight: "100 900",
  }),
  loadFont({
    family: "JetBrains Mono",
    url: staticFile("fonts/JetBrainsMono.ttf"),
    weight: "400",
  }),
]);
