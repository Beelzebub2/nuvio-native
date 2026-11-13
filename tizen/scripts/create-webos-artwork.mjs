import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const output = path.join(root, ".cache", "webos-artwork");
const markPath = path.join(root, "..", "branding", "starlit-mark.svg");
const ink = "#f0f7ff", ground = "#131a2b";

function innerSvg(raw) {
  const opening = raw.match(/<svg\b[^>]*>/i);
  if (!opening || !/<\/svg>\s*$/i.test(raw)) throw new Error("Invalid Starlit vector mark");
  return raw.slice(raw.indexOf(opening[0]) + opening[0].length).replace(/<\/svg>\s*$/i, "");
}
function smallIcon(inner) {
  return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">' +
    '<rect width="1024" height="1024" fill="' + ground + '"/>' + inner + '</svg>';
}
function splash(inner) {
  return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 1080" width="1920" height="1080">' +
    '<rect width="1920" height="1080" fill="' + ground + '"/>' +
    '<g transform="translate(760 218) scale(0.39)">' + inner + '</g>' +
    '<text x="960" y="775" font-size="75" font-family="Arial, sans-serif" font-weight="700" ' +
    'text-anchor="middle" fill="' + ink + '">Starlit TV</text></svg>';
}
export async function prepareWebOsArtwork() {
  const { Resvg } = await import("@resvg/resvg-js");
  const inner = innerSvg(await readFile(markPath, "utf8"));
  const artwork = [
    ["icon.png", smallIcon(inner), 80],
    ["largeIcon.png", smallIcon(inner), 130],
    ["splash.png", splash(inner), 1920]
  ];
  await mkdir(output, { recursive: true });
  for (const [name, svg, width] of artwork) {
    const png = new Resvg(svg, { fitTo: { mode: "width", value: width } }).render().asPng();
    await writeFile(path.join(output, name), png);
  }
  return output;
}
