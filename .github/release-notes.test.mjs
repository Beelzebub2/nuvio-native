import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { extractVersionNotes, previousPublishedTag } from './release-notes.mjs';

test('extracts only the exact modern section, including its subheadings', () => {
  const markdown = '\uFEFF## Starlit 1.0.28\r\n\r\n### Improvements\r\n\r\n- Current.\r\n\r\n## Starlit 1.0.25\r\n- Old.\r\n';
  assert.equal(extractVersionNotes(markdown, 'v1.0.28'), '## Starlit 1.0.28\n\n### Improvements\n\n- Current.\n');
  assert.throws(() => extractVersionNotes(markdown, 'v1.0.2'), /found 0/);
});

test('legacy paragraphs end the preceding section and preserve original wording', () => {
  const markdown = '## Starlit 1.0.23\n- Modern.\n\nStarlit 1.0.22 adds downloads.\n\n- Current.\n\nNuvio Native 1.0.14 adds setup.\n\n- Legacy.\n';
  assert.equal(extractVersionNotes(markdown, '1.0.23'), '## Starlit 1.0.23\n- Modern.\n');
  assert.equal(extractVersionNotes(markdown, '1.0.22'), 'Starlit 1.0.22 adds downloads.\n\n- Current.\n');
  assert.equal(extractVersionNotes(markdown, '1.0.14'), 'Nuvio Native 1.0.14 adds setup.\n\n- Legacy.\n');
});

test('ignores version headings and paragraphs inside backtick and tilde fences', () => {
  const markdown = '## Starlit 1.0.28\n- Current.\n\n````md\n## Starlit 1.0.28\n```\nStarlit 1.0.25 example\n````\n\n~~~\n## Starlit 1.0.24\n~~~\n\nStarlit 1.0.23 older.\n';
  const notes = extractVersionNotes(markdown, 'v1.0.28');
  assert.ok(notes.includes('Starlit 1.0.25 example'));
  assert.ok(notes.includes('## Starlit 1.0.24'));
  assert.ok(!notes.includes('1.0.23'));
});

test('rejects missing, duplicate, empty and invalid versions', () => {
  assert.throws(() => extractVersionNotes('## Starlit 1.0.28\n', '1.0.29'), /found 0/);
  assert.throws(() => extractVersionNotes('## Starlit 1.0.28\n- A.\nStarlit 1.0.28 duplicate.\n', '1.0.28'), /found 2/);
  assert.throws(() => extractVersionNotes('## Starlit 1.0.28\n\n### Improvements\n\n## Starlit 1.0.25\n- Old.\n', '1.0.28'), /Empty/);
  assert.throws(() => extractVersionNotes('## Starlit 1.0.28\n- A.\n', '1.0.28-rc.1'), /Invalid/);
});

test('repository history still extracts modern and legacy versions without older notes', () => {
  const markdown = readFileSync(new URL('../docs/RELEASE-NOTES.md', import.meta.url), 'utf8');
  assert.ok(!extractVersionNotes(markdown, 'v1.0.28').includes('1.0.25'));
  assert.ok(!extractVersionNotes(markdown, 'v1.0.23').includes('1.0.22'));
  assert.ok(!extractVersionNotes(markdown, 'v1.0.22').includes('Starlit 1.0.21'));
});

test('selects a published stable predecessor numerically, ignoring failed tags and later releases', () => {
  const published = tag_name => ({ tag_name, draft: false, prerelease: false, published_at: '2026-10-01T10:00:00Z' });
  const releases = [
    published('v1.0.9'), published('v1.0.25'),
    { ...published('v1.0.26'), prerelease: true },
    { ...published('v1.0.27'), draft: true },
    { ...published('v1.0.27'), published_at: null },
    published('v1.0.29'), published('v1.0.28'), published('v1.0.27-rc.1'),
  ];
  // A current release is normally absent until publication; the paginated REST response is nested.
  assert.equal(previousPublishedTag([releases.filter(release => release.tag_name !== 'v1.0.28')], 'v1.0.28'), 'v1.0.25');
  assert.equal(previousPublishedTag([published('v1.0.9'), published('v1.0.10')], 'v1.0.11'), 'v1.0.10');
  assert.equal(previousPublishedTag(releases, 'v1.0.0'), null);
  assert.equal(previousPublishedTag([], 'v1.0.0'), null);
});

test('historical cleanup never compares against a release published after the target', () => {
  const releases = [
    { tag_name: 'v1.0.25', published_at: '2026-10-21T10:00:00Z' },
    { tag_name: 'v1.0.24', published_at: '2026-10-20T10:00:00Z' },
    { tag_name: 'v1.0.23', published_at: '2026-10-22T10:00:00Z' },
  ];
  assert.equal(previousPublishedTag(releases, 'v1.0.25'), 'v1.0.24');
  assert.equal(previousPublishedTag(releases, 'v1.0.24'), null);
});
