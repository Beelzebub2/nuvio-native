// Machine translation draft generator. Run via translate_popular_languages.py.
// Intentionally excludes Hindi as configured in Python; never overwrites locale folders.
import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const dataRoot = path.join(root, "build", "localization-drafts-20261107");
const { languages, prepared } = JSON.parse(fs.readFileSync(path.join(dataRoot, "source.json"), "utf8"));
const resourceDir = path.join(root, "composeApp", "src", "commonMain", "composeResources");
const items = Object.entries(prepared).map(([name, { text }], index) => ({ index, name, text }));
const keyMatcher = /\[\[K(\d{4})\]\]/g;
const MAX_WORKERS = 4;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function *batches(pending) {
  let batch = [], size = 0;
  for (const item of pending) {
    const n = item.text.length + 16;
    if (batch.length && (size + n > 2600 || batch.length >= 40)) {
      yield batch;
      batch = [];
      size = 0;
    }
    batch.push(item);
    size += n;
  }
  if (batch.length) yield batch;
}

async function translate(text, language) {
  const urls = [
    ["https://translate.googleapis.com/translate_a/single", {
      client: "gtx", sl: "en", tl: language, dt: "t", q: text,
    }, data => data[0]?.map(x => x[0] || "").join("")],
    ["https://clients5.google.com/translate_a/t", {
      client: "dict-chrome-ex", sl: "en", tl: language, q: text,
    }, data => data[0]],
  ];
  for (let attempt = 0; attempt < 5; attempt++) {
    for (const [base, params, extract] of urls) {
      try {
        const url = new URL(base);
        Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v));
        const response = await fetch(url, { signal: AbortSignal.timeout(28000) });
        if (!response.ok) continue;
        const answer = extract(await response.json());
        if (typeof answer === "string" && answer.trim()) return answer;
      } catch {
        // Retry only the current batch. Never accept an error as a translation.
      }
    }
    await sleep(800 + 1000 * attempt);
  }
  return null;
}

async function translateBatch(batch, language) {
  const input = batch.map(({ index, text }) =>
    "[[K" + String(index).padStart(4, "0") + "]] " + text + "\n").join("");
  const result = await translate(input, language);
  if (result) {
    const markers = [...result.matchAll(keyMatcher)];
    if (markers.length === batch.length &&
      markers.every((m, i) => Number(m[1]) === batch[i].index)) {
      const mapped = {};
      markers.forEach((match, i) => {
        const end = i + 1 < markers.length ? markers[i + 1].index : result.length;
        mapped[batch[i].name] = result.substring(match.index + match[0].length, end).trim();
      });
      return mapped;
    }
  }
  if (batch.length > 1) {
    const half = Math.ceil(batch.length / 2);
    const a = await translateBatch(batch.slice(0, half), language);
    const b = await translateBatch(batch.slice(half), language);
    return { ...a, ...b };
  }
  return { [batch[0].name]: (await translate(batch[0].text, language)) || null };
}

for (const [folder, language] of Object.entries(languages)) {
  if (fs.existsSync(path.join(resourceDir, "values-" + folder))) {
    console.log("SKIP existing locale " + folder);
    continue;
  }
  const outFile = path.join(dataRoot, folder + ".json");
  const saved = fs.existsSync(outFile) ? JSON.parse(fs.readFileSync(outFile, "utf8")) : {};
  const pending = items.filter(item => saved[item.name] == null);
  const jobs = [...batches(pending)];
  console.log(folder + ": " + items.length + " strings, " + jobs.length + " batches", { flush: true });
  let index = 0, complete = 0;
  const worker = async () => {
    while (index < jobs.length) {
      const batch = jobs[index++];
      const translated = await translateBatch(batch, language);
      Object.assign(saved, translated);
      complete++;
      if (complete % 10 === 0 || complete === jobs.length) {
        fs.writeFileSync(outFile, JSON.stringify(saved), "utf8");
        console.log("  " + folder + ": " + complete + "/" + jobs.length + " batches");
      }
    }
  };
  await Promise.all(Array.from({ length: MAX_WORKERS }, () => worker()));
  fs.writeFileSync(outFile, JSON.stringify(saved), "utf8");
}
console.log("Finished draft source translations");
