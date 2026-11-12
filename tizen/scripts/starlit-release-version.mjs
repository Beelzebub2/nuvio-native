import { readFile, writeFile } from "node:fs/promises";
const tag = process.argv[2];
if (!/^v\d+\.\d+\.\d+$/.test(tag || "")) throw new Error("Expected vMAJOR.MINOR.PATCH");
for (const name of ["package.json", "appinfo.json"]) {
  const path = new URL(`../${name}`, import.meta.url);
  const value = JSON.parse(await readFile(path, "utf8")); value.version = tag.slice(1);
  await writeFile(path, JSON.stringify(value, null, 2) + "\n");
}
