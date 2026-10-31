#!/usr/bin/env node
/** Read-only loopback HTTP probe for an already-running Nuvio/TorrServer stream. */
import { writeFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';

const usage = `Usage: node scripts/probe-torrent-stream.mjs --url "http://127.0.0.1:PORT/stream?..." [options]

  --bytes N        Bytes requested per sample (default 8388608, maximum 67108864)
  --seek N         Also measure a range request beginning at this file byte offset
  --repeat N       Repeat each sample N times (default 3; maximum 20)
  --timeout-ms N   Maximum time per sample (default 120000)
  --stall-ms N     Count inter-chunk gaps above this threshold (default 2500)
  --out FILE       Optional JSON output; omits the stream URL

The local HTTP server must already be running with a playable torrent. Measurements
include time to first HTTP byte, delivered throughput, and read gaps, not decoder FPS.
`;

function optionsFrom(argv) {
  const settings = { bytes: 8 * 1024 * 1024, seek: null, repeat: 3, timeoutMs: 120_000, stallMs: 2500, out: null, url: null };
  const names = { '--url': 'url', '--bytes': 'bytes', '--seek': 'seek', '--repeat': 'repeat', '--timeout-ms': 'timeoutMs', '--stall-ms': 'stallMs', '--out': 'out' };
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    if (name === '--help' || name === '-h') { process.stdout.write(usage); process.exit(0); }
    const field = names[name];
    if (!field || !argv[index + 1]) throw new Error(`Missing/unknown option ${name}\n${usage}`);
    settings[field] = argv[index + 1];
  }
  if (!settings.url) throw new Error(`Missing --url\n${usage}`);
  const parsed = new URL(settings.url);
  if (parsed.protocol !== 'http:' || !['127.0.0.1', '[::1]', 'localhost'].includes(parsed.hostname)) {
    throw new Error('The probe only accepts HTTP streams on loopback addresses');
  }
  settings.url = parsed;
  for (const field of ['bytes', 'repeat', 'timeoutMs', 'stallMs', 'seek']) {
    if (settings[field] == null) continue;
    const value = Number(settings[field]);
    if (!Number.isSafeInteger(value) || value < 0) throw new Error(`Invalid ${field}: ${settings[field]}`);
    settings[field] = value;
  }
  if (settings.bytes < 1 || settings.bytes > 64 * 1024 * 1024) throw new Error('--bytes must be between 1 and 67108864');
  if (settings.repeat < 1 || settings.repeat > 20) throw new Error('--repeat must be between 1 and 20');
  if (settings.timeoutMs < 1 || settings.stallMs < 1) throw new Error('Timeout and stall threshold must be positive');
  if (settings.seek != null && settings.seek + settings.bytes - 1 > Number.MAX_SAFE_INTEGER) throw new Error('Seek range overflows');
  return settings;
}

async function sample(settings, offset, label) {
  const start = performance.now();
  const response = await fetch(settings.url, {
    headers: { Range: `bytes=${offset}-${offset + settings.bytes - 1}`, 'Accept-Encoding': 'identity' },
    signal: AbortSignal.timeout(settings.timeoutMs),
    redirect: 'error',
    cache: 'no-store',
  });
  const headersMs = performance.now() - start;
  if (response.status !== 206 && !(response.status === 200 && offset === 0)) {
    await response.body?.cancel();
    throw new Error(`${label}: HTTP ${response.status}; range ${offset} was not served correctly`);
  }
  if (response.status === 206) {
    const range = /^bytes (\d+)-(\d+)\/(?:\d+|\*)$/.exec(response.headers.get('content-range') || '');
    if (!range || Number(range[1]) !== offset || Number(range[2]) < offset) {
      await response.body?.cancel();
      throw new Error(`${label}: invalid Content-Range response for byte ${offset}`);
    }
  }
  const contentType = response.headers.get('content-type')?.toLowerCase() || '';
  if (/text\/html|application\/json/.test(contentType) || !response.body) {
    await response.body?.cancel();
    throw new Error(`${label}: received an error/document instead of a media byte stream`);
  }
  const reader = response.body.getReader();
  let bytes = 0;
  let firstByteAt = null;
  let previousChunkAt = null;
  let maxGapMs = 0;
  let stalls = 0;
  let finalByteAt = start;
  try {
    while (bytes < settings.bytes) {
      const { value, done } = await reader.read();
      if (done) break;
      if (!value?.byteLength) continue;
      const now = performance.now();
      if (firstByteAt == null) firstByteAt = now;
      if (previousChunkAt != null) {
        const gap = now - previousChunkAt;
        maxGapMs = Math.max(maxGapMs, gap);
        if (gap > settings.stallMs) stalls += 1;
      }
      previousChunkAt = now;
      finalByteAt = now;
      bytes += Math.min(value.byteLength, settings.bytes - bytes);
    }
  } finally {
    await reader.cancel().catch(() => {});
  }
  if (bytes === 0 || firstByteAt == null) throw new Error(`${label}: no bytes received`);
  return {
    label,
    status: response.status,
    offset,
    bytes,
    headersMs: Math.round(headersMs),
    firstByteMs: Math.round(firstByteAt - start),
    durationMs: Math.round(finalByteAt - firstByteAt),
    throughputMiBs: Number((bytes / (1024 * 1024) / Math.max((finalByteAt - firstByteAt) / 1000, 0.001)).toFixed(2)),
    maxGapMs: Math.round(maxGapMs),
    stallCount: stalls,
  };
}

const median = values => {
  const ordered = [...values].sort((a, b) => a - b);
  return (ordered[Math.floor((ordered.length - 1) / 2)] + ordered[Math.floor(ordered.length / 2)]) / 2;
};

async function main() {
  const settings = optionsFrom(process.argv.slice(2));
  const results = [];
  for (let i = 0; i < settings.repeat; i += 1) {
    results.push(await sample(settings, 0, 'start'));
    if (settings.seek != null) results.push(await sample(settings, settings.seek, 'seek'));
  }
  const summarize = label => {
    const rows = results.filter(result => result.label === label);
    return {
      samples: rows.length,
      medianFirstByteMs: median(rows.map(row => row.firstByteMs)),
      medianThroughputMiBs: median(rows.map(row => row.throughputMiBs)),
      maxObservedGapMs: Math.max(...rows.map(row => row.maxGapMs)),
      stallEvents: rows.reduce((sum, row) => sum + row.stallCount, 0),
    };
  };
  const output = {
    capturedAt: new Date().toISOString(),
    origin: settings.url.origin,
    bytesPerSample: settings.bytes,
    stallThresholdMs: settings.stallMs,
    summary: { start: summarize('start'), ...(settings.seek == null ? {} : { seek: summarize('seek') }) },
    measurements: results,
  };
  process.stdout.write(`${JSON.stringify(output, null, 2)}\n`);
  if (settings.out) await writeFile(settings.out, `${JSON.stringify(output, null, 2)}\n`, { flag: 'w' });
}

main().catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
