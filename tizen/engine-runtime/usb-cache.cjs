'use strict';
// Copyright (c) 2026 Starlit contributors. SPDX-License-Identifier: MIT.
// Removable, disposable torrent-piece cache for the legacy Node 4 TV service.
var fs = require('fs'), path = require('path'), crypto = require('crypto');
var memoryCache = require('./bounded-store.cjs'), createRecency = require('./recency-list.cjs');
var MiB = 1024 * 1024, GiB = 1024 * MiB;
var RAM_LIMIT = 48 * MiB, HOT_LIMIT = 16 * MiB;
var DIR_NAME = 'Starlit-Torrent-Cache', MARKER = '.starlit-owned-v1';
var OWNER = 'starlit-torrent-cache-v1\n';
var OWNED_FILES = /^(?:p-[a-f0-9]{24}-[0-9]{1,6}\.piece|bench-[a-f0-9]{24}\.tmp)$/;

function policy(free, total, writeMBps) {
  if (!isFinite(free) || !isFinite(total) || !isFinite(writeMBps) ||
      free < 0 || total <= 0 || free > total || total > 16 * 1024 * GiB) return null;
  var reserve = Math.max(GiB, Math.ceil(total * 0.20));
  var available = Math.floor((free - reserve) / (4 * MiB)) * (4 * MiB);
  if (writeMBps < 5 || available < 256 * MiB) return null;
  var tier = writeMBps < 12 ? 256 * MiB : writeMBps < 30 ? 512 * MiB :
    writeMBps < 60 ? GiB : 2 * GiB;
  return { cacheBytes: Math.min(tier, available), ramBytes: HOT_LIMIT, reserveBytes: reserve,
    profile: writeMBps < 12 ? 'conservative' : writeMBps < 30 ? 'balanced' :
      writeMBps < 60 ? 'fast' : 'high-performance' };
}
function presentError(error) {
  return String(error && (error.code || error.message) || 'USB unavailable').slice(0, 100);
}
function safeRoot(root) {
  if (typeof root !== 'string' || root.length < 4 || root.length > 700 ||
      !path.isAbsolute(root) || /[\u0000-\u001f]/.test(root) ||
      root.split(/[\/\\]+/).some(function (p) { return p === '..'; })) {
    throw new Error('Invalid external storage path');
  }
  var resolved = fs.realpathSync(root);
  if (!path.isAbsolute(resolved) || path.parse(resolved).root === resolved ||
      !fs.statSync(resolved).isDirectory()) throw new Error('Invalid removable root');
  return resolved;
}
function prepare(root) {
  var realRoot = safeRoot(root), dir = path.join(realRoot, DIR_NAME), made = false;
  try { fs.mkdirSync(dir, 448); made = true; } catch (err) { if (err.code !== 'EEXIST') throw err; }
  var dirStat = fs.lstatSync(dir);
  if (!dirStat.isDirectory() || dirStat.isSymbolicLink()) throw new Error('Unsafe USB cache directory');
  var marker = path.join(dir, MARKER);
  if (made) {
    try { fs.writeFileSync(marker, OWNER, { flag: 'wx', mode: 384 }); }
    catch (err) { try { fs.rmdirSync(dir); } catch (_) {} throw err; }
  }
  var stat = fs.lstatSync(marker);
  if (!stat.isFile() || stat.isSymbolicLink() || fs.readFileSync(marker, 'utf8') !== OWNER) {
    throw new Error('USB cache directory belongs to another application');
  }
  return dir;
}
function purgeStale(dir) {
  // Only files matching Starlit's private, generated names inside an owner-marked folder.
  fs.readdirSync(dir).forEach(function (name) {
    if (!OWNED_FILES.test(name)) return;
    var file = path.join(dir, name), stat = fs.lstatSync(file);
    if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('Unsafe cache entry');
    fs.unlinkSync(file);
  });
}
function elapsed(start) {
  var d = process.hrtime(start);
  return Math.max(0.001, d[0] + d[1] / 1e9);
}
function benchmark(dir, cb) {
  var bytes = 8 * MiB, step = 256 * 1024, repeats = bytes / step;
  var file = path.join(dir, 'bench-' + crypto.randomBytes(12).toString('hex') + '.tmp');
  var buffer = new Buffer(step), readBuffer = new Buffer(step);
  for (var i = 0; i < step; i++) buffer[i] = (i * 13 + 39) % 251;
  var fd = null, pos = 0, start = null, writeSeconds = 0, readSeconds = 0, finished = false;
  function done(err, result) {
    if (finished) return;
    finished = true;
    function afterClose() {
      fs.unlink(file, function (cleanupErr) {
        cb(err || (cleanupErr && cleanupErr.code !== 'ENOENT' ? cleanupErr : null), result);
      });
    }
    if (fd === null) return afterClose();
    var old = fd; fd = null;
    fs.close(old, function (closeErr) { if (!err && closeErr) err = closeErr; afterClose(); });
  }
  function readNext() {
    if (pos === bytes) {
      readSeconds = elapsed(start);
      return done(null, { writeMBps: bytes / 1000000 / writeSeconds,
        readMBps: bytes / 1000000 / readSeconds });
    }
    fs.read(fd, readBuffer, 0, step, pos, function (err, n) {
      if (err || n !== step) return done(err || new Error('USB short read'));
      if (!readBuffer.equals(buffer)) return done(new Error('USB verification failed'));
      pos += n; readNext();
    });
  }
  function beginRead() {
    var old = fd; fd = null;
    fs.close(old, function (err) {
      if (err) return done(err);
      fs.open(file, 'r', function (readErr, handle) {
        if (readErr) return done(readErr);
        fd = handle; pos = 0; start = process.hrtime(); readNext();
      });
    });
  }
  function writeNext() {
    if (pos === bytes) {
      return fs.fsync(fd, function (err) {
        if (err) return done(err);
        writeSeconds = elapsed(start); beginRead();
      });
    }
    fs.write(fd, buffer, 0, step, pos, function (err, n) {
      if (err || n !== step) return done(err || new Error('USB short write'));
      pos += n; writeNext();
    });
  }
  fs.open(file, 'wx', 384, function (err, handle) {
    if (err) return done(err);
    fd = handle; start = process.hrtime(); writeNext();
  });
}
function probe(root, free, total, cb) {
  var dir;
  try {
    if (!policy(free, total, 100)) return process.nextTick(function () { cb(null, { mode: 'memory', reason: 'insufficient-space' }); });
    dir = prepare(root);
    purgeStale(dir);
  } catch (err) {
    return process.nextTick(function () { cb(null, { mode: 'memory', reason: presentError(err) }); });
  }
  benchmark(dir, function (error, speed) {
    if (error) return cb(null, { mode: 'memory', reason: presentError(error) });
    var selection = policy(free, total, speed.writeMBps);
    if (!selection) return cb(null, { mode: 'memory', reason: 'USB too slow or too small',
      writeMBps: speed.writeMBps, readMBps: speed.readMBps });
    cb(null, { mode: 'usb', dir: dir, cacheBytes: selection.cacheBytes,
      ramBytes: selection.ramBytes, reserveBytes: selection.reserveBytes,
      profile: selection.profile, writeMBps: speed.writeMBps, readMBps: speed.readMBps });
  });
}
function create(config, evict, onFailure) {
  var session = crypto.randomBytes(12).toString('hex'), sequence = 0;
  var entries = Object.create(null), owned = Object.create(null), erasing = Object.create(null), order = createRecency();
  var hot = Object.create(null), hotOrder = createRecency(), hotBytes = 0;
  var bytes = 0, pending = 0, closed = false, finishing = false, mode = 'usb', closeCallbacks = [];
  var stats = { mode: 'usb', bytes: 0, limit: config.cacheBytes, ramBytes: 0,
    ramLimit: HOT_LIMIT, evictions: 0, hits: 0, misses: 0,
    diskReadBytes: 0, diskReadOps: 0, ownedFiles: 0, reason: null };
  var fallback = memoryCache(RAM_LIMIT, evict), fallbackStore = null;
  function unhot(index) {
    if (!hot[index]) return;
    hotBytes -= hot[index].length;
    delete hot[index];
    hotOrder.remove(index);
    stats.ramBytes = hotBytes;
  }
  function keepHot(index, data) {
    unhot(index);
    if (data.length > HOT_LIMIT) return;
    hot[index] = data; hotBytes += data.length;
    hotOrder.touch(index);
    while (hotBytes > HOT_LIMIT) unhot(hotOrder.oldest());
    stats.ramBytes = hotBytes;
  }
  function eraseOwnedFile(file, callback) {
    if (!owned[file]) {
      return process.nextTick(function () { if (callback) callback(); });
    }
    if (erasing[file]) {
      if (callback) erasing[file].push(callback);
      return;
    }
    erasing[file] = callback ? [callback] : [];
    fs.unlink(file, function (err) {
      var callbacks = erasing[file];
      delete erasing[file];
      if ((!err || err.code === 'ENOENT') && owned[file]) {
        delete owned[file];
        stats.ownedFiles--;
      }
      callbacks.forEach(function (cb) { cb(err && err.code !== 'ENOENT' ? err : null); });
    });
  }
  function discard(index, notify) {
    var item = entries[index];
    if (!item) return;
    delete entries[index];
    bytes -= item.length;
    stats.bytes = bytes;
    unhot(index);
    order.remove(index);
    eraseOwnedFile(item.file);
    if (notify) { stats.evictions++; evict(Number(index)); }
  }
  function degrade(reason) {
    if (mode !== 'usb') return;
    mode = 'memory'; stats.mode = 'memory'; stats.reason = reason || 'USB disconnected';
    Object.keys(entries).forEach(function (index) { discard(index, true); });
    entries = Object.create(null); order.clear(); bytes = 0; stats.bytes = 0;
    hot = Object.create(null); hotOrder.clear(); hotBytes = 0; stats.ramBytes = 0;
    stats.limit = RAM_LIMIT;
    if (typeof onFailure === 'function') onFailure(stats.reason);
  }
  function finalizeClose() {
    if (!closed || pending > 0 || finishing) return;
    finishing = true;
    var names = Object.keys(owned), closeError = null;
    function next(error) {
      if (error && !closeError) closeError = error;
      if (!names.length) {
        return fallbackStore.close(function (fallbackError) {
          var callbacks = closeCallbacks; closeCallbacks = [];
          callbacks.forEach(function (cb) { if (cb) cb(closeError || fallbackError || null); });
        });
      }
      // A previous eviction may already be unlinking this file. Wait for that
      // exact operation rather than racing two unlinks and losing the result.
      eraseOwnedFile(names.pop(), next);
    }
    next();
  }
  function readPieceRange(item, offset, length, callback) {
    // Avoid reading a 32 MiB torrent piece just to serve a tiny AVPlay Range.
    // Validate the full file size before reading only the requested bytes.
    fs.open(item.file, 'r', function (openError, fd) {
      if (openError) return callback(openError);
      function finish(error, result) {
        fs.close(fd, function (closeError) { callback(error || closeError, result); });
      }
      fs.fstat(fd, function (statError, info) {
        if (statError || !info || !info.isFile() || info.size !== item.length) {
          return finish(statError || new Error('USB cache piece length changed'));
        }
        var result = new Buffer(length), received = 0;
        function next() {
          if (received === length) return finish(null, result);
          fs.read(fd, result, received, length - received, offset + received, function (readError, n) {
            if (readError || !n) return finish(readError || new Error('USB short read'));
            received += n; stats.diskReadBytes += n; stats.diskReadOps++;
            next();
          });
        }
        next();
      });
    });
  }
  return {
    stats: stats,
    disable: function (reason) { degrade(reason); },
    factory: function (length) {
      fallbackStore = fallback.factory(length);
      return {
        chunkLength: length,
        put: function (index, data, cb) {
          if (closed) return process.nextTick(function () { cb(new Error('Cache closed')); });
          if (mode !== 'usb') return fallbackStore.put(index, data, function (err) {
            stats.bytes = fallback.stats.bytes; stats.ramBytes = fallback.stats.bytes;
            if (cb) cb(err);
          });
          if (data.length > config.cacheBytes) return process.nextTick(function () { cb(new Error('Piece too large')); });
          pending++;
          var file = path.join(config.dir, 'p-' + session + '-' + (sequence++) + '.piece');
          owned[file] = true; stats.ownedFiles++;
          fs.writeFile(file, data, { flag: 'wx', mode: 384 }, function (err) {
            pending--;
            if (closed) { if (cb) cb(new Error('Cache closed')); return finalizeClose(); }
            if (err) {
              degrade('USB write failed: ' + presentError(err));
              return fallbackStore.put(index, data, function (memErr) {
                stats.bytes = fallback.stats.bytes; stats.ramBytes = fallback.stats.bytes;
                if (cb) cb(memErr);
              });
            }
            if (mode !== 'usb') {
              eraseOwnedFile(file);
              return fallbackStore.put(index, data, function (memErr) {
                stats.bytes = fallback.stats.bytes; stats.ramBytes = fallback.stats.bytes;
                if (cb) cb(memErr);
              });
            }
            discard(index, false);
            entries[index] = { file: file, length: data.length };
            order.touch(String(index));
            bytes += data.length; stats.bytes = bytes;
            keepHot(index, data);
            while (bytes > config.cacheBytes && order.length()) discard(order.oldest(), true);
            if (cb) cb();
          });
        },
        get: function (index, opts, cb) {
          if (typeof opts === 'function') { cb = opts; opts = null; }
          if (closed) return process.nextTick(function () { cb(new Error('Cache closed')); });
          if (mode !== 'usb') return fallbackStore.get(index, opts, cb);
          var item = entries[index];
          if (!item) { stats.misses++; return process.nextTick(function () { cb(new Error('Piece absent')); }); }
          var start = opts && opts.offset || 0;
          var length = opts && opts.length ? opts.length : item.length - start;
          if (!isFinite(start) || start < 0 || start % 1 || !isFinite(length) ||
              length < 0 || length % 1 || start + length > item.length) {
            return process.nextTick(function () { cb(new Error('Invalid USB piece range')); });
          }
          var cached = hot[index];
          if (cached) {
            stats.hits++; hotOrder.touch(index); order.touch(String(index));
            return process.nextTick(function () { cb(null, opts ? cached.slice(start, start + length) : cached); });
          }
          pending++;
          readPieceRange(item, start, length, function (err, data) {
            pending--;
            if (closed) { cb(new Error('Cache closed')); return finalizeClose(); }
            if (mode !== 'usb') return fallbackStore.get(index, opts, cb);
            if (err || !data || data.length !== length) {
              stats.misses++;
              if (err && err.code === 'ENOENT' && entries[index] !== item) return cb(new Error('Piece evicted'));
              degrade('USB read failed: ' + presentError(err || new Error('short read')));
              return cb(new Error('USB cache read failed'));
            }
            if (entries[index] !== item) return cb(new Error('Piece evicted'));
            stats.hits++; order.touch(String(index));
            if (start === 0 && length === item.length) keepHot(index, data);
            cb(null, data);
          });
        },
        close: function (cb) { closed = true; if (cb) closeCallbacks.push(cb); finalizeClose(); },
        destroy: function (cb) { this.close(cb); }
      };
    }
  };
}
module.exports = { probe: probe, policy: policy, create: create };
