"use strict";
// Copies the app's JetBrains Sans files into src/fonts so the installer UI uses
// the same type as Starlit. The app's font resources stay the single source.
const fs = require("node:fs");
const path = require("node:path");

const source = path.resolve(__dirname, "..", "..", "composeApp", "src", "commonMain", "composeResources", "font");
const target = path.resolve(__dirname, "..", "src", "fonts");
const files = { "jetbrains_sans_regular.ttf": "regular.ttf", "jetbrains_sans_semibold.ttf": "semibold.ttf", "jetbrains_sans_bold.ttf": "bold.ttf" };

fs.mkdirSync(target, { recursive: true });
for (const [from, to] of Object.entries(files)) {
  const input = path.join(source, from);
  if (!fs.existsSync(input)) {
    console.error("Missing Starlit font " + input + ". Run from a full Starlit checkout.");
    process.exit(1);
  }
  const output = path.join(target, to);
  const bytes = fs.readFileSync(input);
  if (!fs.existsSync(output) || !bytes.equals(fs.readFileSync(output))) fs.writeFileSync(output, bytes);
}
