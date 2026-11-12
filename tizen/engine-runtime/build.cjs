'use strict';
// Deterministic, fail-closed modifications of MIT torrent-stream 1.2.1 sources.
var fs = require('fs'), path = require('path'), crypto = require('crypto'), browserify = require('browserify');
var root = __dirname, pkg = require('torrent-stream/package.json');
if (fs.existsSync(path.join(root, 'media-http.cjs'))) fs.unlinkSync(path.join(root, 'media-http.cjs'));
if (pkg.version !== '1.2.1') throw new Error('Unexpected torrent-stream version');
function once(source, marker, replacement) {
  var count = typeof marker === 'string' ? source.split(marker).length - 1 : (source.match(marker) || []).length;
  if (count !== (marker.global ? 2 : 1)) throw new Error('Dependency patch marker changed: ' + marker);
  return source.replace(marker, replacement);
}
function patch(name, edits) {
  var original = fs.readFileSync(require.resolve('torrent-stream/' + name), 'utf8');
  var source = original;
  edits.forEach(function (edit) {
    if (source.split(edit[0]).length !== 2) throw new Error('Patch marker changed: ' + name + ' ' + edit[0]);
    source = source.replace(edit[0], edit[1]);
  });
  return { original: crypto.createHash('sha256').update(original).digest('hex'), source: source };
}
var index = patch('index.js', [
  ["var FSChunkStore = require('fs-chunk-store')", 'var FSChunkStore = null'],
  ["var mkdirp = require('mkdirp')", 'var mkdirp = null'],
  ["var rimraf = require('rimraf')", 'var rimraf = null'],
  ['var ontorrent = function (torrent) {', 'var ontorrent = function (torrent) {\n    if (destroyed) return\n    try { if (opts.validateTorrent) opts.validateTorrent(torrent) } catch (err) { engine.emit("error", err); return }'],
  ['    var reservations = torrent.pieces.map(function () {', '    engine.invalidate = function (index) {\n      if (destroyed || pieces[index]) return\n      pieces[index] = piece(index === torrent.pieces.length - 1 ? pieceRemainder : pieceLength)\n      reservations[index] = []\n      engine.bitfield.set(index, false)\n      engine.selection.forEach(function (s) { if (index >= s.from && index <= s.to) s.offset = Math.min(s.offset, index - s.from) })\n      refresh()\n    }\n    var reservations = torrent.pieces.map(function () {'],
  ['    var oninterestchange = function () {', '    engine.releasePending = function () {\n      pieces.forEach(function (p, i) { if (p && p._buffer) { pieces[i] = piece(p.length); reservations[i] = [] } })\n      refresh()\n    }\n    var oninterestchange = function () {'],
  ['    ontorrent(parseTorrent(buf))', '    try { ontorrent(parseTorrent(buf)) } catch (err) { return engine.emit("error", err) }\n    if (opts.memoryOnly) return'],
  ['    fs.readFile(torrentPath, function (_, buf) {', '    if (opts.memoryOnly) {\n      process.nextTick(function () { if (!destroyed) { swarm.resume(); discovery.setTorrent(link) } })\n    } else fs.readFile(torrentPath, function (_, buf) {'],
  ['  var removeTmp = function (cb) {', '  var removeTmp = function (cb) {\n    if (opts.memoryOnly) return process.nextTick(cb || noop)'],
  ['    if (!pieces[index]) return false', '    if (opts.allowedPiece && !opts.allowedPiece(index)) return false\n    if (!pieces[index]) return false'],
  ['    discovery.stop()', '    discovery.stop()']
  ,['    swarm.add(addr)', '    if (!opts.validPeer || opts.validPeer(addr)) swarm.add(addr)']
  ,["  discovery.on('peer', function (addr) {", "  discovery.on('error', function (err) { engine.emit('error', err) })\n  discovery.on('peer', function (addr) {"]
  ,['  var blocked = blocklist(opts.blocklist)', '  var blocked = blocklist(opts.blocklist), blockedCount = 0']
  ,["    blocked.add(addr.split(':')[0])", "    if (blockedCount < 1024 && !blocked.contains(addr.split(':')[0])) { blocked.add(addr.split(':')[0]); blockedCount++ }"]
]);
// The final identical marker above verifies the lifecycle call without changing it.
var metadata = patch('lib/exchange-metadata.js', [
  ['var METADATA_MAX_SIZE = 1 << 22', 'var METADATA_MAX_SIZE = 256 * 1024'],
  ['      var size = handshake.metadata_size', '      var size = handshake.metadata_size\n      if (typeof size !== "number" || size % 1 || size <= 0 || size > METADATA_MAX_SIZE) return'],
  ['        if (piece < 0) return', '        if (typeof piece !== "number" || piece % 1 || piece < 0 || piece >= Math.ceil(size / METADATA_BLOCK_SIZE)) return\n        if (ext.length > METADATA_BLOCK_SIZE + 1024) return'],
  ['          metadataPieces[piece] = ext.slice(delimiter + 2)', '          var payload = ext.slice(delimiter + 2)\n          if (payload.length !== Math.min(METADATA_BLOCK_SIZE, size - piece * METADATA_BLOCK_SIZE)) return\n          metadataPieces[piece] = payload'],
  ['          callback(engine.metadata = metadata)', '          try { callback(engine.metadata = metadata) } catch (err) { engine.emit("error", err) }']
]);
var b = browserify({ browserField: false, builtins: false, commondir: false, detectGlobals: false, standalone: 'StarlitTorrentRuntime' });
var through = require('through2');
b.transform(function (file) {
  var text = '';
  return through(function (chunk, enc, next) { text += chunk; next(); }, function (next) {
    if (file === require.resolve('torrent-stream')) text = index.source;
    if (file === require.resolve('torrent-stream/lib/exchange-metadata')) text = metadata.source;
    // bitfield@0.1.0 has no license; replace it in full with this newly authored MIT bitset.
    if (file === require.resolve('bitfield')) text = 'function Bitset(value) { if (!(this instanceof Bitset)) return new Bitset(value); this.buffer = Buffer.isBuffer(value) ? value : new Buffer(Math.ceil(value / 8)); if (!Buffer.isBuffer(value)) this.buffer.fill(0); }\nBitset.prototype.get = function (index) { return !!(this.buffer[Math.floor(index / 8)] & (128 >> (index % 8))); };\nBitset.prototype.set = function (index, value) { var byte = Math.floor(index / 8), mask = 128 >> (index % 8); if (value === false) this.buffer[byte] &= ~mask; else this.buffer[byte] |= mask; };\nmodule.exports = Bitset;';
    if (file === require.resolve('hat')) text = 'module.exports = function (bits) { return require("crypto").randomBytes(Math.ceil(bits / 8)).toString("hex").slice(0, Math.ceil(bits / 4)); };';
    if (file === require.resolve('flatten')) text = 'module.exports = function flat(values) { var result = []; values.forEach(function (value) { if (Array.isArray(value)) result = result.concat(flat(value)); else result.push(value); }); return result; };';
    if (file === require.resolve('buffer-equal')) text = 'module.exports = function (a, b) { if (!Buffer.isBuffer(a) || !Buffer.isBuffer(b) || a.length !== b.length) return false; for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false; return true; };';
    if (file === require.resolve('simple-sha1')) text = 'function hash(value) { return require("crypto").createHash("sha1").update(value).digest("hex"); }\nfunction asyncHash(value, callback) { var result = hash(value); process.nextTick(function () { callback(result); }); }\nmodule.exports = asyncHash; module.exports.sync = hash;';
    if (/[\\/]simple-get[\\/]index\.js$/.test(file)) text = 'module.exports = require(' + JSON.stringify(path.relative(path.dirname(file), path.join(root, 'tracker-http.cjs')).replace(/\\/g, '/')) + ');';
    if (file === require.resolve('bittorrent-tracker/lib/client/http-tracker')) {
      text = once(text, 'self.scrapeUrl = null', 'self.requests = []; self.scrapeUrl = null');
      text = once(text, 'clearInterval(self.interval)', 'clearInterval(self.interval); self.requests.forEach(function (request) { request.abort() }); self.requests = []');
      text = once(text, '  get.concat(u, function (err, res, data) {', '  var task = get.concat(u, function (err, res, data) {\n    var index = self.requests.indexOf(task); if (index >= 0) self.requests.splice(index, 1)');
      text = once(text, '    cb(data)\n  })', '    cb(data)\n  })\n  self.requests.push(task)');
    }
    if (file === require.resolve('bittorrent-tracker/lib/client/tracker')) text = once(text, 'if (intervalMs) {', 'if (intervalMs) {\n    intervalMs = Math.min(3600000, Math.max(60000, intervalMs))');
    if (file === require.resolve('bittorrent-tracker/lib/client/udp-tracker')) text = once(text, 'socket.send(message, 0, message.length, parsedUrl.port, parsedUrl.hostname)', 'require(' + JSON.stringify(path.relative(path.dirname(file), path.join(root, 'network-policy.cjs')).replace(/\\/g, '/')) + ').safeLookup(parsedUrl.hostname, { family: 4 }, function (err, address) { if (err) return onError(err); if (socket) socket.send(message, 0, message.length, parsedUrl.port, address) })');
    if (file === require.resolve('k-rpc-socket')) {
      var policyRequire = 'require(' + JSON.stringify(path.relative(path.dirname(file), path.join(root, 'network-policy.cjs')).replace(/\\/g, '/')) + ')';
      text = once(text, 't: Buffer.allocUnsafe(2)', 't: new Buffer(2)');
      text = once(text, '  var buf = bencode.encode(message)', '  if (' + policyRequire + '.blockedAddress(peer.address || peer.host)) return process.nextTick(function () { if (cb) cb(new Error("DHT destination rejected")) })\n  var buf = bencode.encode(message)');
      text = once(text, '  if (!cb) cb = noop\n  if (!this.isIP(peer.host))', '  if (!cb) cb = noop\n  if (this.isIP(peer.host) && ' + policyRequire + '.blockedAddress(peer.host)) return process.nextTick(function () { cb(new Error("DHT destination rejected")) })\n  if (!this.isIP(peer.host))');
    }
    // TCP only: this legacy JS uTP implementation delays cancellation and adds an unnecessary UDP listener.
    if (file === require.resolve('peer-wire-swarm')) text = once(text, "var utp = require('utp');", 'var utp = null;');
    if (file === require.resolve('peer-wire-swarm')) text = once(text, 'if (this._destroyed || this._peers[addr]) return;', 'if (this._destroyed || this._peers[addr] || Object.keys(this._peers).length >= 128) return;');
    // WebRTC is unavailable on the service runtime; remove its otherwise unused dependency tree.
    if (file === require.resolve('bittorrent-tracker/client')) text = once(text, "var WebSocketTracker = require('./lib/client/websocket-tracker')", 'var WebSocketTracker = null');
    if (file === require.resolve('peer-wire-protocol')) {
      text = once(text, 'if (length) return self._parse(length, onmessage);', 'if (length > 262144) return self.destroy();\n\t\tif (length) return self._parse(length, onmessage);');
      text = once(text, 'switch (buffer[0]) {', 'var minimum = { 4: 5, 6: 13, 7: 9, 8: 13, 9: 3, 20: 2 };\n\t\tif (minimum[buffer[0]] && buffer.length < minimum[buffer[0]]) return self.destroy();\n\t\tswitch (buffer[0]) {');
      text = once(text, 'Wire.prototype._onhave = function(i) {', 'Wire.prototype._onhave = function(i) {\n\tif (i >= 16384) return this.destroy();');
      text = once(text, 'Wire.prototype._onbitfield = function(buffer) {', 'Wire.prototype._onbitfield = function(buffer) {\n\tif (buffer.length > 2048) return this.destroy();');
    }
    if (/[\\/]bncode[\\/](bncode|bencode)\.js$/.test(file)) {
      text = once(text, 'str = new Buffer(strLen)', 'if (strLen > 262144) throw new Error("Bencode string limit");\n            str = new Buffer(strLen)');
      text = once(text, /depth \+= 1/g, 'depth += 1; if (depth > 32) throw new Error("Bencode nesting limit")');
      text = once(text, /dic\s*=\s*\{\}/, 'dic = Object.create(null)');
    }
    this.push(text.replace(/\r\n/g, '\n')); next();
  });
}, { global: true });
var modules = {};
b.on('file', function (file) {
  var dir = path.dirname(file);
  while (dir !== path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'package.json'))) { modules[dir] = true; break; }
    dir = path.dirname(dir);
  }
});
b.add(path.join(root, 'runtime.cjs'));
b.bundle(function (err, data) {
  if (err) throw err;
  fs.writeFileSync(path.join(root, 'media-http.cjs'), data);
  require('acorn').parse(data.toString(), { ecmaVersion: 5, allowReserved: true });
  var notices = [], manifest = [];
  fs.writeFileSync(path.join(root, 'bundled-module-dirs.json'), JSON.stringify(Object.keys(modules), null, 2));
  Object.keys(modules).sort().forEach(function (dir) {
    var p = JSON.parse(fs.readFileSync(path.join(dir, 'package.json')));
    if (dir === root) return;
    if (['bitfield', 'hat', 'flatten', 'buffer-equal', 'simple-sha1', 'simple-get'].indexOf(p.name) >= 0) { manifest.push({ name: 'starlit-compat-' + p.name, version: '1.0.0', license: 'MIT', replaces: p.name + '@' + p.version + '; original source excluded' }); return; }
    var licenses = fs.readdirSync(dir).filter(function (x) { return /^(license|copying|notice)(\.|$|-)/i.test(x); });
    if (!licenses.length && fs.existsSync(path.join(root, 'supplemental-licenses', p.name + '.txt'))) { licenses.push(path.join(root, 'supplemental-licenses', p.name + '.txt')); }
    if (!licenses.length) throw new Error('Missing license: ' + p.name);
    var licenseText = licenses.map(function (x) { return fs.readFileSync(path.isAbsolute(x) ? x : path.join(dir, x), 'utf8'); }).join('\n');
    manifest.push({ name: p.name, version: p.version, license: p.license || p.licenses || (/Permission is hereby granted, free of charge/.test(licenseText) ? 'MIT' : 'SEE LICENSE'), files: licenses.map(function (name) { return path.isAbsolute(name) ? 'supplemental-licenses/' + path.basename(name) : name; }) });
    notices.push('\n===== ' + p.name + '@' + p.version + ' =====\n' + licenseText);
  });
  fs.writeFileSync(path.join(root, 'THIRD-PARTY-LICENSES.txt'), notices.join('\n'));
  fs.writeFileSync(path.join(root, 'runtime-manifest.json'), JSON.stringify({ engine: 'torrent-stream@1.2.1', patches: { index: index.original, metadata: metadata.original }, modules: manifest, sha256: crypto.createHash('sha256').update(data).digest('hex') }, null, 2));
  console.log('ES5 bundle: ' + data.length + ' bytes; ' + manifest.length + ' licensed packages');
});
