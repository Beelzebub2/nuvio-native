import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { extractVersionNotes } from './release-notes.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const versionFile = resolve(root, 'composeApp/Configuration/DesktopVersion.properties');
const notesFile = resolve(root, 'docs/RELEASE-NOTES.md');

export function versionParts(raw) {
  const match = /^(?:v)?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.exec(raw ?? '');
  if (!match) throw new Error(`Expected stable MAJOR.MINOR.PATCH, got ${raw}`);
  const parts = match.slice(1).map(Number);
  if (parts.some(n => !Number.isSafeInteger(n))) throw new Error('Version exceeds the safe integer range');
  return parts;
}

export function versionCode(version) {
  const [major, minor, patch] = versionParts(version);
  if (minor > 99 || patch > 99) {
    throw new Error('MINOR and PATCH must be at most 99 for monotonic Android VERSION_CODE');
  }
  const code = major * 10000 + minor * 100 + patch;
  if (!Number.isSafeInteger(code) || code > 2147483647 || code < 1) {
    throw new Error('VERSION_CODE must be a positive signed 32-bit integer');
  }
  return code;
}

export function nextVersion(current, type) {
  const [major, minor, patch] = versionParts(current);
  if (type === 'major') return `${major + 1}.0.0`;
  if (type === 'minor') return `${major}.${minor + 1}.0`;
  if (type === 'patch') return `${major}.${minor}.${patch + 1}`;
  throw new Error('Release type must be major, minor or patch');
}

export function compareVersions(a, b) {
  const left = versionParts(a);
  const right = versionParts(b);
  for (let i = 0; i < 3; i++) {
    if (left[i] !== right[i]) return left[i] > right[i] ? 1 : -1;
  }
  return 0;
}

export function validateRelease({ properties, notes, tag, existingTags = [] }) {
  const versionLines = properties.replace(/\r/g, '').split('\n');
  const readKey = key => {
    const matches = versionLines.filter(line => line.startsWith(`${key}=`));
    if (matches.length !== 1) throw new Error(`Expected one ${key} in DesktopVersion.properties`);
    return matches[0].slice(key.length + 1).trim();
  };
  const version = readKey('VERSION_NAME');
  const actualCode = readKey('VERSION_CODE');
  const expectedCode = versionCode(version);
  if (!/^\d+$/.test(actualCode) || Number(actualCode) !== expectedCode) {
    throw new Error(`VERSION_CODE must be ${expectedCode} for ${version}, got ${actualCode}`);
  }
  if (tag !== `v${version}`) throw new Error(`Release tag ${tag} does not match v${version}`);
  const section = extractVersionNotes(notes, tag);
  if (/\b(?:TODO|TBD|PLACEHOLDER)\b/i.test(section)) throw new Error('Release notes contain unfinished placeholders');
  for (const existing of existingTags) {
    if (!/^v\d+\.\d+\.\d+$/.test(existing) || existing === tag) continue;
    if (compareVersions(tag, existing) <= 0) {
      throw new Error(`Release ${tag} must be higher than existing tag ${existing}`);
    }
  }
  return { version, code: expectedCode };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [command, argument] = process.argv.slice(2);
  if (command === 'next') {
    const properties = readFileSync(versionFile, 'utf8');
    const current = properties.match(/^VERSION_NAME=(.+)$/m)?.[1]?.trim();
    const next = nextVersion(current, argument);
    console.log(`Next ${argument} release: v${next} (VERSION_CODE=${versionCode(next)})`);
  } else if (command === 'verify') {
    const tags = execFileSync('git', ['tag', '--list', 'v*'], { cwd: root, encoding: 'utf8' })
      .split(/\r?\n/).filter(Boolean);
    const result = validateRelease({
      properties: readFileSync(versionFile, 'utf8'),
      notes: readFileSync(notesFile, 'utf8'),
      tag: argument,
      existingTags: tags,
    });
    console.log(`Verified Starlit v${result.version} (Android VERSION_CODE=${result.code})`);
  } else {
    throw new Error('Usage: node .github/release-policy.mjs next major|minor|patch | verify vMAJOR.MINOR.PATCH');
  }
}
