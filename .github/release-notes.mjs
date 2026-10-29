import { readFileSync, writeFileSync } from 'node:fs';

function versionParts(tag) {
  if (!/^v?\d+\.\d+\.\d+$/.test(tag)) throw new Error(`Invalid stable version: ${tag}`);
  return tag.replace(/^v/, '').split('.').map(BigInt);
}

function compareVersions(a, b) {
  const left = versionParts(a);
  const right = versionParts(b);
  for (let i = 0; i < left.length; i++) {
    if (left[i] !== right[i]) return left[i] < right[i] ? -1 : 1;
  }
  return 0;
}

export function extractVersionNotes(markdown, tag) {
  versionParts(tag);
  const version = tag.replace(/^v/, '');
  const lines = markdown.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').split('\n');
  const sections = [];
  let fence;
  for (let i = 0; i < lines.length; i++) {
    const marker = lines[i].match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if (fence) {
      if (marker && marker[1][0] === fence[0] && marker[1].length >= fence.length && !marker[2].trim()) fence = undefined;
      continue;
    }
    if (marker) {
      fence = marker[1];
      continue;
    }
    const heading = lines[i].match(/^ {0,3}#{1,6}\s+(?:Starlit|Nuvio Native)\s+(\d+\.\d+\.\d+)(?:\s+#+)?\s*$/);
    const paragraph = lines[i].match(/^ {0,3}(?:Starlit|Nuvio Native)\s+(\d+\.\d+\.\d+)(?=\s|$)/);
    if (heading || paragraph) sections.push({ version: (heading || paragraph)[1], line: i, heading: Boolean(heading) });
  }
  const matches = sections.filter(section => section.version === version);
  if (matches.length !== 1) throw new Error(`Expected one section for ${version}; found ${matches.length}.`);
  const section = matches[0];
  const end = sections[sections.indexOf(section) + 1]?.line ?? lines.length;
  const content = lines.slice(section.line + (section.heading ? 1 : 0), end).join('\n');
  if (!content.replace(/^\s*#{1,6}[^\n]*$/gm, '').trim()) throw new Error(`Empty release notes for ${version}.`);
  return `${lines.slice(section.line, end).join('\n').trim()}\n`;
}

export function previousPublishedTag(releases, tag) {
  versionParts(tag);
  const all = releases.flat(Infinity);
  const current = all.find(release => release.tag_name === tag);
  const cutoff = current?.published_at ? Date.parse(current.published_at) : Infinity;
  return all.filter(release => !release.draft && !release.prerelease &&
    /^v\d+\.\d+\.\d+$/.test(release.tag_name) &&
    Number.isFinite(Date.parse(release.published_at)) && Date.parse(release.published_at) < cutoff &&
    compareVersions(release.tag_name, tag) < 0)
    .sort((a, b) => compareVersions(b.tag_name, a.tag_name))[0]?.tag_name ?? null;
}

if (import.meta.main) {
  const [command, input, tag, output] = process.argv.slice(2);
  if (command === 'extract' && input && tag && output) {
    writeFileSync(output, extractVersionNotes(readFileSync(input, 'utf8'), tag));
  } else if (command === 'previous' && input && tag && !output) {
    const releases = JSON.parse(readFileSync(input, 'utf8').replace(/^\uFEFF/, ''));
    const previous = previousPublishedTag(releases, tag);
    if (previous) console.log(previous);
  } else {
    throw new Error('Usage: release-notes.mjs extract <markdown> <tag> <output> | previous <releases.json> <tag>');
  }
}
