import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { versionCode, versionParts, nextVersion, compareVersions, validateRelease } from './release-policy.mjs';

const notes = '## Starlit 1.1.0\n\n### Improvements\n\n- More reliable account synchronization.\n\n## Starlit 1.0.48\n\n- Previous release.\n';
const properties = 'VERSION_NAME=1.1.0\nVERSION_CODE=10100\n';

test('stable semver and monotonic Android build code', () => {
  assert.deepEqual(versionParts('v1.1.0'), [1, 1, 0]);
  assert.equal(versionCode('1.0.48'), 10048);
  assert.equal(versionCode('1.1.0'), 10100);
  assert.equal(versionCode('2.0.0'), 20000);
  assert.throws(() => versionCode('1.100.0'), /at most 99/);
  assert.throws(() => versionParts('v1.01.0'), /stable/);
  assert.throws(() => versionParts('1.1.0-rc.1'), /stable/);
});

test('major, minor and patch bumps keep semantic meaning', () => {
  assert.equal(nextVersion('1.0.48', 'major'), '2.0.0');
  assert.equal(nextVersion('1.0.48', 'minor'), '1.1.0');
  assert.equal(nextVersion('1.0.48', 'patch'), '1.0.49');
  assert.equal(compareVersions('1.1.0', '1.0.48'), 1);
  assert.equal(compareVersions('v1.0.9', 'v1.0.48'), -1);
  assert.throws(() => nextVersion('1.0.48', 'breaking'), /must be/);
});

test('release checks enforce tag, version code, notes and increasing version', () => {
  assert.deepEqual(validateRelease({ properties, notes, tag: 'v1.1.0', existingTags: ['v1.0.48'] }),
    { version: '1.1.0', code: 10100 });
  assert.throws(() => validateRelease({ properties, notes, tag: 'v1.0.49' }), /does not match/);
  assert.throws(() => validateRelease({ properties: properties.replace('10100', '10049'), notes, tag: 'v1.1.0' }), /VERSION_CODE/);
  assert.throws(() => validateRelease({ properties, notes, tag: 'v1.1.0', existingTags: ['v1.1.1'] }), /higher than/);
  assert.throws(() => validateRelease({ properties, notes: notes.replace('More reliable account synchronization.', 'TODO'), tag: 'v1.1.0' }), /placeholders/);
  assert.throws(() => validateRelease({ properties, notes: '## Starlit 1.0.48\n- Old.\n', tag: 'v1.1.0' }), /found 0/);
});

test('every published release requires a signed Android TV package and checksum', () => {
  const workflow = readFileSync(new URL('./workflows/windows-release.yml', import.meta.url), 'utf8');
  assert.match(workflow, /^  tv:$/m, 'TV release build must be a required job');
  assert.match(workflow, /:tvApp:assembleRelease/, 'TV release APK must be built, not reused from a debug run');
  assert.match(workflow, /name: starlit-tv-unsigned/, 'TV unsigned build must be handed to the signing job');
  assert.match(workflow, /needs: \[android, tv\]/, 'Android signing must fail closed when TV build fails');
  assert.match(workflow, /"\$tools\/apksigner" verify "\$tv"/, 'TV APK signature must be verified');
  assert.match(workflow, /name: starlit-tv-release/, 'Signed TV APK must be attached to publish inputs');
  assert.match(workflow, /SHA256SUMS-android\.txt SHA256SUMS-tv\.txt/, 'TV checksum must be included in release verification');
  assert.match(workflow, /"Starlit-TV-\$version\.apk"/, 'TV APK must be uploaded to the public release');
});
