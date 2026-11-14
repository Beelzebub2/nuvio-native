'use strict';
// Copyright (c) 2026 Starlit contributors. SPDX-License-Identifier: MIT
var http = require('http'), url = require('url'), crypto = require('crypto'), Readable = require('stream').Readable;
var torrentStream = require('torrent-stream'), createCache = require('./bounded-store.cjs');
var usbCache = require('./usb-cache.cjs');
var networkPolicy = require('./network-policy.cjs'), net = require('net');
var MAX_CACHE = 48 * 1024 * 1024, MAX_PIECE = 32 * 1024 * 1024;
var MAX_FILES = 1024, MAX_PIECES = 65536, MAX_TORRENT_BYTES = 1024 * 1024 * 1024 * 1024;
var MAX_MEDIA_STREAMS = 4;
var PROBE = new Buffer('starlit-range-probe-v1');
function validateTorrent(t) {
  if (!t || !Array.isArray(t.files) || !Array.isArray(t.pieces) || !t.files.length || !t.pieces.length) {
    throw new Error('Invalid torrent file or piece metadata');
  }
  if (t.files.length > MAX_FILES) throw new Error('Torrent exceeds streaming limits: more than 1024 files');
  if (t.pieces.length > MAX_PIECES) throw new Error('Torrent exceeds streaming limits: more than 65536 pieces');
  if (!isFinite(t.pieceLength) || t.pieceLength % 1 || t.pieceLength < 16384 || t.pieceLength > MAX_PIECE) {
    throw new Error('Torrent exceeds streaming limits: piece size must be between 16 KiB and 32 MiB');
  }
  if (!isFinite(t.length) || t.length % 1 || t.length < 1 || t.length > MAX_TORRENT_BYTES) {
    throw new Error('Torrent exceeds streaming limits: invalid size or larger than 1 TiB');
  }
  t.files.forEach(function (f) {
    if (!f || !isFinite(f.length) || f.length < 0 || f.length % 1 ||
        !isFinite(f.offset) || f.offset < 0 || f.offset % 1 ||
        typeof f.path !== 'string' || f.path.length > 2048 ||
        typeof f.name !== 'string' || f.name.length > 512) {
      throw new Error('Invalid torrent file metadata');
    }
  });
}
function start(options, callback) {
  options = options || {};
  if (!/^[a-f0-9]{64}$/.test(options.controlToken || '')) throw new Error('Trusted bridge control token required');
  var port = options.port === undefined ? 2710 : options.port;
  var active = null, server, sockets = [], stopping = false, generation = 0;
  var usbProfile = null, usbProbing = false;
  function json(res, status, data) { if (res.finished) return; var body = JSON.stringify(data); res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), 'Cache-Control': 'no-store' }); res.end(body); }
  function cancelStreams(entry) {
    if (!entry || !entry.streams) return;
    entry.streams.slice().forEach(function (stream) { stream.destroy(); });
  }
  function remove(cb) {
    generation++;
    var old = active; active = null;
    if (!old) return process.nextTick(cb || function () {});
    cancelStreams(old); clearTimeout(old.timer);
    if (old.pending) { old.pending.forEach(function (res) { json(res, 409, { error: 'Torrent removed' }); }); old.pending = []; }
    old.engine.destroy(cb || function () {});
  }
  function listing(entry, requestedIndex) {
    var files = entry.engine.files.map(function (f) { return { name: f.name, path: f.path, length: f.length }; });
    var guessed = 0;
    files.forEach(function (f, i) { if (/\.(mkv|mp4|webm|avi|m4v|ts|mov)$/i.test(f.name) && (!/\.(mkv|mp4|webm|avi|m4v|ts|mov)$/i.test(files[guessed].name) || f.length > files[guessed].length)) guessed = i; });
    var selected = typeof requestedIndex === 'number' && files[requestedIndex] ? requestedIndex : guessed;
    if (!entry.tickets[selected]) entry.tickets[selected] = crypto.randomBytes(32).toString('hex');
    return { infoHash: entry.hash, files: files, guessedFileIdx: guessed, fileIdx: selected, filename: files[selected].name, playbackTicket: entry.tickets[selected], ticketFileIdx: selected, memoryOnly: entry.cache.stats.mode !== 'usb' };
  }
  function create(hash, body, res) {
    if (!body.torrent || body.torrent.infoHash !== hash) return json(res, 400, { error: 'Info hash mismatch' });
    if (body.fileIdx !== undefined && (typeof body.fileIdx !== 'number' || body.fileIdx < 0 || body.fileIdx % 1 || body.fileIdx >= MAX_FILES)) return json(res, 400, { error: 'Invalid file index' });
    res._starlitFileIdx = body.fileIdx;
    if (active && active.hash === hash) { if (active.ready) return json(res, 200, listing(active, body.fileIdx)); if (active.pending.length >= 8) return json(res, 429, { error: 'Create already pending' }); active.pending.push(res); return; }
    var serial = generation + 1;
    remove(function () {
      if (stopping || res.finished) return;
      if (generation !== serial) return json(res, 409, { error: 'Create superseded' });
      var sources = body.peerSearch && body.peerSearch.sources || [];
      if (!Array.isArray(sources) || sources.length > 9) return json(res, 400, { error: 'Too many trackers' });
      var trackers = [];
      try {
        sources.forEach(function (source) {
          if (typeof source !== 'string' || source.length > 2048) throw new Error('Invalid tracker');
          if (/^dht:/.test(source)) return;
          var tracker = source.replace(/^tracker:/, ''), parsed = url.parse(tracker);
          if (!/^(https?:|udp:)$/.test(parsed.protocol) || !parsed.hostname || parsed.auth || /[\r\n]/.test(tracker)) throw new Error('Invalid tracker');
          trackers.push(tracker);
        });
      } catch (err) { return json(res, 400, { error: err.message }); }
      var entry = { hash: hash, tickets: Object.create(null), pending: [res], ready: false, streams: [], timer: null, engine: null, cache: null };
      var invalidate = function (index) { if (entry.engine && entry.engine.invalidate) entry.engine.invalidate(index); };
      var cache = usbProfile ? usbCache.create(usbProfile, invalidate, function () { usbProfile = null; }) :
        createCache(Math.min(options.cacheBytes || MAX_CACHE, MAX_CACHE), invalidate);
      entry.cache = cache; active = entry;
      try {
        entry.engine = torrentStream('magnet:?xt=urn:btih:' + hash, { storage: cache.factory, memoryOnly: true, validateTorrent: validateTorrent, verify: false, connections: 12, uploads: false, trackers: trackers, dht: options.dht !== false, tracker: trackers.length > 0, validPeer: function (address) { var host = address.split(':')[0]; return options.allowPrivatePeers === true || (net.isIP(host) === 4 && !networkPolicy.blockedAddress(host)); } });
      } catch (err) { active = null; return json(res, 400, { error: err.message }); }
      entry.engine.on('error', function (err) {
        entry.error = String(err.message || err);
        if (entry.pending) { entry.pending.forEach(function (r) { json(r, 502, { error: entry.error }); }); entry.pending = []; }
        if (active === entry) remove();
      });
      entry.engine.on('ready', function () {
        if (active !== entry) return;
        clearTimeout(entry.timer); entry.ready = true;
        entry.pending.forEach(function (r) { json(r, 200, listing(entry, r._starlitFileIdx)); }); entry.pending = [];
      });
      entry.timer = setTimeout(function () { if (active !== entry) return; entry.pending.forEach(function (r) { json(r, 504, { error: 'Torrent metadata timeout' }); }); entry.pending = []; remove(); }, options.metadataTimeout || 50000);
      // Integration-test hook is never populated by HTTP input or production startup.
      if (options.onEngine) options.onEngine(entry.engine);
    });
  }
  function range(header, length) {
    if (!header) return { start: 0, end: length - 1, partial: false };
    var m = /^bytes=(\d*)-(\d*)$/.exec(header);
    if (!m || (!m[1] && !m[2])) return null;
    var a = m[1] ? Number(m[1]) : Math.max(0, length - Number(m[2]));
    var b = m[1] ? (m[2] ? Math.min(length - 1, Number(m[2])) : length - 1) : length - 1;
    if (!isFinite(a) || !isFinite(b) || a < 0 || b < a || a >= length || (!m[1] && !Number(m[2]))) return null;
    return { start: a, end: b, partial: true };
  }
  function headers(res, req, length, mime) {
    var r = range(req.headers.range, length);
    if (!r) { res.writeHead(416, { 'Content-Range': 'bytes */' + length, 'Content-Length': 0 }); res.end(); return null; }
    var h = { 'Content-Type': mime, 'Content-Length': r.end - r.start + 1, 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store' };
    if (r.partial) h['Content-Range'] = 'bytes ' + r.start + '-' + r.end + '/' + length;
    res.writeHead(r.partial ? 206 : 200, h); return r;
  }
  function media(req, res, entry, index) {
    var file = entry.engine.files[index];
    if (!file || !file.length) return json(res, 404, { error: 'File unavailable' });
    // Native players may ask for the header and a distant index at once.
    // Keep both byte ranges alive, but cap simultaneous requests to protect
    // the memory-only engine on low-RAM TVs.
    if (req.method === 'GET' && entry.streams.length >= MAX_MEDIA_STREAMS) return json(res, 429, { error: 'Too many concurrent playback ranges' });
    var extension = /\.([^.]*)$/.exec(file.name), mime = { mp4: 'video/mp4', m4v: 'video/mp4', mkv: 'video/x-matroska', ts: 'video/mp2t', webm: 'video/webm', mov: 'video/quicktime', avi: 'video/x-msvideo' };
    var r = headers(res, req, file.length, mime[extension && extension[1].toLowerCase()] || 'application/octet-stream');
    if (!r) return;
    if (req.method === 'HEAD') return res.end();
    var engine = entry.engine, position = file.offset + r.start, end = file.offset + r.end, waiting = false, selected = -1, dead = false;
    var stream = new Readable(); entry.streams.push(stream);
    function detach() {
      var index = entry.streams.indexOf(stream);
      if (index >= 0) entry.streams.splice(index, 1);
    }
    function deselect() { if (selected >= 0) { engine.deselect(selected, selected, true, notify); selected = -1; } }
    function notify() {
      if (dead || !waiting || selected < 0 || !engine.bitfield.get(selected)) return;
      var piece = selected; waiting = false;
      engine.store.get(piece, function (err, buffer) {
        if (dead) return;
        if (err) { engine.invalidate(piece); waiting = true; return engine.select(piece, piece, true, notify); }
        deselect();
        var offset = position - piece * engine.torrent.pieceLength;
        var count = Math.min(buffer.length - offset, end - position + 1);
        position += count; stream.push(buffer.slice(offset, offset + count));
        if (position > end) stream.push(null);
      });
    }
    stream._read = function () {
      if (dead || waiting || position > end) return;
      waiting = true; selected = Math.floor(position / engine.torrent.pieceLength);
      engine.select(selected, selected, true, notify); notify();
    };
    stream.destroy = function () {
      if (dead) return; dead = true; deselect();
      detach();
      stream.unpipe(res); stream.emit('close'); if (!res.finished) res.destroy();
      // Cancel incomplete pieces only after the last range closes. A seek or
      // canceled header request must not invalidate another live AVPlay range.
      if (!entry.streams.length && engine.releasePending) engine.releasePending();
    };
    stream.on('error', function () { stream.destroy(); });
    res.on('close', stream.destroy); stream.on('end', function () { dead = true; deselect(); detach(); });
    stream.pipe(res);
  }
  server = http.createServer(function (req, res) {
    var host = req.headers.host, origin = req.headers.origin, local = req.socket.remoteAddress;
    var expectedPort = server.address().port;
    if ((local !== '127.0.0.1' && local !== '::ffff:127.0.0.1' && local !== '::1') || (host !== '127.0.0.1:' + expectedPort && host !== 'localhost:' + expectedPort)) return json(res, 403, { error: 'Loopback host required' });
    if (origin && origin !== 'http://' + host && !(origin === 'null' && options.allowNullOrigin === true)) return json(res, 403, { error: 'Origin rejected' });
    if (req.headers['sec-fetch-site'] === 'cross-site' && !(origin === 'null' && options.allowNullOrigin === true)) return json(res, 403, { error: 'Cross-site request rejected' });
    if (origin) { res.setHeader('Access-Control-Allow-Origin', origin); res.setHeader('Access-Control-Expose-Headers', 'Accept-Ranges, Content-Length, Content-Range'); res.setHeader('Vary', 'Origin'); }
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Starlit-Local, X-Starlit-Token, Range'); res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, POST, OPTIONS');
    if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
    var pathname = url.parse(req.url).pathname;
    if (options.proxyHandler && options.proxyHandler(req, res)) return;
    if (pathname === '/settings' && req.method === 'GET') return json(res, 200, { values: { cacheSize: usbProfile ? usbProfile.cacheBytes : MAX_CACHE }, version: 'starlit-torrent-1', memoryOnly: !usbProfile, capabilities: { torrent: true, httpRange: true, usbCache: true, offline: false }, limits: { files: MAX_FILES, pieces: MAX_PIECES, maxPieceBytes: MAX_PIECE, maxMetadataBytes: 2 * 1024 * 1024, cacheBytes: MAX_CACHE } });
    if (pathname === '/range-probe' && (req.method === 'GET' || req.method === 'HEAD')) { var probe = headers(res, req, PROBE.length, 'application/octet-stream'); if (probe) res.end(req.method === 'HEAD' ? undefined : PROBE.slice(probe.start, probe.end + 1)); return; }
    if (pathname === '/cache/status' && req.method === 'GET') {
      if (req.headers['x-starlit-token'] !== options.controlToken) return json(res, 403, { error: 'Trusted control token required' });
      return json(res, 200, { configured: Boolean(usbProfile), profile: usbProfile ? usbProfile.profile : 'memory',
        cacheBytes: usbProfile ? usbProfile.cacheBytes : MAX_CACHE,
        writeMBps: usbProfile ? usbProfile.writeMBps : 0, readMBps: usbProfile ? usbProfile.readMBps : 0,
        active: active && active.cache ? active.cache.stats : null });
    }
    if (pathname === '/cache/disable' && req.method === 'POST') {
      if (req.headers['x-starlit-token'] !== options.controlToken) return json(res, 403, { error: 'Trusted control token required' });
      usbProfile = null;
      if (active && active.cache && active.cache.disable) active.cache.disable('USB cache disabled or disconnected');
      return json(res, 200, { configured: false, activeMode: active && active.cache ? active.cache.stats.mode : 'memory' });
    }
    if (pathname === '/cache/probe' && req.method === 'POST') {
      if (req.headers['x-starlit-token'] !== options.controlToken) return json(res, 403, { error: 'Trusted control token required' });
      if (usbProbing || active) return json(res, 409, { error: 'Cache probe requires an idle torrent service' });
      var probeChunks = [], probeSize = 0, oversized = false;
      req.on('data', function (chunk) {
        probeSize += chunk.length;
        if (probeSize > 2048) { oversized = true; probeChunks = []; return json(res, 413, { error: 'Cache request too large' }); }
        if (!oversized) probeChunks.push(chunk);
      });
      req.on('end', function () {
        if (oversized) return;
        var body;
        try { body = JSON.parse(Buffer.concat(probeChunks).toString('utf8')); }
        catch (_) { return json(res, 400, { error: 'Invalid cache probe' }); }
        if (!body || typeof body.root !== 'string' || body.root.length > 700 ||
            typeof body.freeBytes !== 'number' || typeof body.totalBytes !== 'number') return json(res, 400, { error: 'Invalid storage details' });
        usbProbing = true;
        usbCache.probe(body.root, body.freeBytes, body.totalBytes, function (error, result) {
          usbProbing = false;
          if (stopping || res.finished) return;
          if (error) return json(res, 500, { error: 'Cache probe failed' });
          usbProfile = result.mode === 'usb' ? result : null;
          return json(res, 200, { mode: result.mode, reason: result.reason || null,
            cacheBytes: result.cacheBytes || 0, ramBytes: result.ramBytes || MAX_CACHE,
            reserveBytes: result.reserveBytes || 0, writeMBps: result.writeMBps || 0,
            readMBps: result.readMBps || 0, profile: result.profile || 'memory' });
        });
      });
      return;
    }
    if ((pathname === '/removeAll' || /\/[a-f0-9]{40}\/remove$/.test(pathname)) && req.method === 'POST') {
      if (req.headers['x-starlit-token'] !== options.controlToken) return json(res, 403, { error: 'Trusted control token required' });
      var hash = pathname.split('/')[1];
      if (hash !== 'removeAll' && active && hash !== active.hash) return json(res, 200, { removed: false });
      return remove(function () { json(res, 200, { removed: true }); });
    }
    pathname = pathname.replace(/\/(\d+)\/stats\.json$/, '/stats');
    var m = /^\/([a-f0-9]{40})\/(create|stats|\d+)$/.exec(pathname);
    if (!m) return json(res, 404, { error: 'Route unavailable' });
    if (m[2] === 'create' && req.method === 'POST') {
      if (req.headers['x-starlit-token'] !== options.controlToken) return json(res, 403, { error: 'Trusted control token required' });
      var chunks = [], size = 0, failed = false;
      req.on('data', function (chunk) { size += chunk.length; if (size > 16384) { failed = true; chunks = []; json(res, 413, { error: 'Request too large' }); } else if (!failed) chunks.push(chunk); });
      req.on('end', function () { if (failed) return; try { create(m[1], JSON.parse(Buffer.concat(chunks).toString()), res); } catch (err) { json(res, 400, { error: 'Invalid create request' }); } }); return;
    }
    if (!active || active.hash !== m[1] || !active.ready) return json(res, 404, { error: 'Torrent unavailable' });
    if (m[2] === 'stats' && req.method === 'GET') { if (req.headers['x-starlit-token'] !== options.controlToken) return json(res, 403, { error: 'Trusted control token required' }); return json(res, 200, { infoHash: active.hash, peers: active.engine.swarm.wires.length, downloaded: active.engine.swarm.downloaded, downloadSpeed: active.engine.swarm.downloadSpeed(), activeRanges: active.streams.length, cache: active.cache.stats }); }
    if (/^\d+$/.test(m[2]) && (req.method === 'GET' || req.method === 'HEAD')) { var suppliedTicket = url.parse(req.url, true).query.ticket; if (!suppliedTicket || suppliedTicket !== active.tickets[Number(m[2])]) return json(res, 403, { error: 'Playback ticket required' }); return media(req, res, active, Number(m[2])); }
    json(res, 405, { error: 'Method unavailable' });
  });
  server.timeout = 60000;
  server.maxHeadersCount = 32;
  server.on('connection', function (socket) { if (sockets.length >= 16) return socket.destroy(); sockets.push(socket); socket.on('close', function () { var i = sockets.indexOf(socket); if (i >= 0) sockets.splice(i, 1); }); });
  server.on('clientError', function (err, socket) { socket.destroy(); });
  server.listen(port, '127.0.0.1', callback);
  return { server: server, stop: function (cb) { stopping = true; remove(function () { sockets.slice().forEach(function (s) { s.destroy(); }); server.close(cb); }); } };
}
module.exports = { start: start, validateTorrent: validateTorrent, version: 'starlit-torrent-1' };
