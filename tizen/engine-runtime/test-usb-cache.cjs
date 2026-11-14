'use strict';
var assert = require('assert'), fs = require('fs'), path = require('path');
var os = require('os'), crypto = require('crypto');
var usb = require('./usb-cache.cjs'), MiB = 1024 * 1024, GiB = 1024 * MiB;
var dir = path.join(os.tmpdir(), 'starlit-usb-cache-test-' + crypto.randomBytes(10).toString('hex'));
var cacheDir = path.join(dir, 'Starlit-Torrent-Cache');
assert.equal(usb.policy(2 * GiB, 4 * GiB, 4.9), null);
assert.equal(usb.policy(2 * GiB, 4 * GiB, 8).cacheBytes, 256 * MiB);
assert.equal(usb.policy(2 * GiB, 4 * GiB, 20).cacheBytes, 512 * MiB);
assert.equal(usb.policy(2 * GiB, 4 * GiB, 40).cacheBytes, GiB);
assert.equal(usb.policy(4 * GiB, 4 * GiB, 80).cacheBytes, 2 * GiB);
assert.equal(usb.policy(400 * MiB, 4 * GiB, 50), null);
assert.equal(usb.policy(-1, 4 * GiB, 50), null);
assert.deepEqual(usb.parseDiskSpace('Filesystem 1024-blocks Used Available Capacity Mounted on\n' +
  '/dev/sda1 4194304 1048576 3145728 25% /media/USB Drive\n'),
  { totalBytes: 4 * GiB, freeBytes: 3 * GiB });
assert.equal(usb.parseDiskSpace('Filesystem 1024-blocks Used Available Capacity Mounted on\n' +
  '/dev/root 1024 512 2048 100% /\n'), null, 'Impossible free-space figures must be rejected');
assert.equal(usb.parseDiskSpace('not a filesystem'), null);
function read(file) { return fs.readFileSync(file); }
function probe(root, free, total) { return new Promise(function (resolve, reject) {
  usb.probe(root, free, total, function (err, result) { if (err) reject(err); else resolve(result); });
}); }
function put(store, index, data) { return new Promise(function (resolve, reject) {
  store.put(index, data, function (err) { if (err) reject(err); else resolve(); });
}); }
function get(store, index, opts) { return new Promise(function (resolve, reject) {
  function done(err, buffer) { if (err) reject(err); else resolve(buffer); }
  if (opts) store.get(index, opts, done);
  else store.get(index, done);
}); }
function close(store) { return new Promise(function (resolve, reject) {
  store.close(function (err) { if (err) reject(err); else resolve(); });
}); }
function cleanup() {
  if (fs.existsSync(cacheDir)) {
    fs.readdirSync(cacheDir).forEach(function (name) {
      var file = path.join(cacheDir, name);
      if (fs.lstatSync(file).isFile()) fs.unlinkSync(file);
    });
    fs.rmdirSync(cacheDir);
  }
  if (fs.existsSync(dir)) fs.rmdirSync(dir);
}
var failures = [], store, cache;
fs.mkdirSync(dir);
var observedCommand;
usb.readDiskSpace(dir, function (err, metrics) {
  assert.ifError(err);
  assert.deepEqual(metrics, { totalBytes: 4 * GiB, freeBytes: 3 * GiB });
  assert.deepEqual(observedCommand, ['df', '-Pk', fs.realpathSync(dir)]);
}, function (command, args, options, callback) {
  observedCommand = [command].concat(args);
  assert(options.timeout <= 3000 && options.maxBuffer <= 8192);
  process.nextTick(function () {
    callback(null, 'Filesystem 1024-blocks Used Available Capacity Mounted on\n' +
      '/dev/sda1 4194304 1048576 3145728 25% /mnt/usb\n');
  });
});
var childProcess = require('child_process'), originalExecFile = childProcess.execFile;
childProcess.execFile = function (command, args, options, done) {
  assert.equal(command, 'df');
  assert.deepEqual(args, ['-Pk', fs.realpathSync(dir)]);
  process.nextTick(function () {
    done(null, 'Filesystem 1024-blocks Used Available Capacity Mounted on\n' +
      '/dev/sda1 4194304 1048576 3145728 25% /mnt/usb\n');
  });
};
probe(dir, null, null).then(function (fallbackResult) {
  childProcess.execFile = originalExecFile;
  assert.equal(fallbackResult.mode, 'usb', 'Missing SystemInfo capacity must use per-mount measurement');
  return probe(dir, 3 * GiB, 4 * GiB);
}, function (error) {
  childProcess.execFile = originalExecFile;
  throw error;
}).then(function (result) {
  assert.equal(result.mode, 'usb', 'Fast test disk should allow USB cache');
  assert(result.writeMBps > 0 && result.readMBps > 0);
  assert(fs.existsSync(path.join(cacheDir, '.starlit-owned-v1')));
  var foreign = path.join(cacheDir, 'personal-video.mp4');
  fs.writeFileSync(foreign, 'keep');
  var stale = path.join(cacheDir, 'p-' + new Array(25).join('a') + '-4.piece');
  fs.writeFileSync(stale, 'old cache');
  return probe(dir, 3 * GiB, 4 * GiB).then(function (result2) {
    assert.equal(result2.mode, 'usb');
    assert(!fs.existsSync(stale), 'Old generated pieces should be reclaimed');
    assert.equal(read(foreign).toString(), 'keep', 'Unowned files must survive');
    fs.unlinkSync(foreign);
    var buffer = new Buffer(65536); buffer.fill(73);
    cache = usb.create({ dir: cacheDir, cacheBytes: 2 * buffer.length }, function (index) { failures.push(index); });
    store = cache.factory(buffer.length);
    return put(store, 0, buffer).then(function () { return put(store, 1, buffer); })
      .then(function () { return get(store, 0); }).then(function (actual) { assert(actual.equals(buffer)); })
      .then(function () { return put(store, 2, buffer); }).then(function () {
        assert(cache.stats.bytes <= 131072, 'Disk usage must be capped');
        assert(failures.indexOf(1) >= 0, 'Oldest unselected piece evicted');
        return get(store, 2);
      }).then(function (actual) {
        assert(actual.equals(buffer));
        cache.disable('Disconnected');
        assert.equal(cache.stats.mode, 'memory');
        assert(cache.stats.limit === 48 * MiB);
        return put(store, 10, buffer);
      }).then(function () { return get(store, 10); }).then(function (actual) {
        assert(actual.equals(buffer), 'RAM fallback must preserve newly written pieces');
        return close(store);
      }).then(function () {
        assert.equal(fs.readdirSync(cacheDir).filter(function (name) { return /\.piece$/.test(name); }).length, 0, 'Temporary pieces must be erased');
      });
  });
}).then(function () {
  // A cold 32 MiB piece must serve a 4 KiB native-player range without
  // reading 32 MiB from removable storage or polluting the 16 MiB hot cache.
  var big = new Buffer(32 * MiB); big.fill(45);
  var largeCache = usb.create({ dir: cacheDir, cacheBytes: 64 * MiB }, function () {});
  var largeStore = largeCache.factory(big.length);
  return put(largeStore, 0, big).then(function () {
    assert.equal(largeCache.stats.ramBytes, 0);
    return get(largeStore, 0, { offset: MiB, length: 4096 });
  }).then(function (data) {
    assert(data.equals(big.slice(MiB, MiB + 4096)));
    assert.equal(largeCache.stats.diskReadBytes, 4096, 'Small range must only read the requested bytes');
    assert.equal(largeCache.stats.ramBytes, 0, 'Partial range must not be cached as a full piece');
    return get(largeStore, 0, { offset: big.length - 4096, length: 4096 });
  }).then(function (data) {
    assert(data.equals(big.slice(big.length - 4096)));
    assert.equal(largeCache.stats.diskReadBytes, 8192);
    return get(largeStore, 0, { offset: big.length - 1, length: 4096 }).then(function () {
      throw new Error('Out-of-bounds read unexpectedly succeeded');
    }, function (error) {
      assert(/Invalid USB piece range/.test(error.message));
    });
  }).then(function () {
    assert.equal(largeCache.stats.mode, 'usb', 'Invalid range must not disable a healthy cache');
    var piece = fs.readdirSync(cacheDir).filter(function (name) { return /\.piece$/.test(name); })[0];
    assert(piece, 'Expected temporary large piece');
    fs.truncateSync(path.join(cacheDir, piece), 4096);
    return get(largeStore, 0, { offset: 0, length: 1024 }).then(function () {
      throw new Error('Truncated cached file unexpectedly succeeded');
    }, function (error) {
      assert(/USB cache read failed/.test(error.message));
      assert.equal(largeCache.stats.mode, 'memory', 'Corrupt disk cache must fall back to RAM');
    });
  }).then(function () { return close(largeStore); }).then(function () {
    assert.equal(largeCache.stats.ownedFiles, 0, 'Closing a cache must release owned file records');
  });
}).then(function () {
  // A long stream must not keep every past evicted filename in memory.
  var churnCache = usb.create({ dir: cacheDir, cacheBytes: 2 * 65536 }, function () {});
  var churnStore = churnCache.factory(65536);
  var piece = new Buffer(65536); piece.fill(17);
  var tasks = Promise.resolve();
  for (var n = 0; n < 48; n++) {
    (function (index) { tasks = tasks.then(function () { return put(churnStore, index, piece); }); })(n);
  }
  function waitForCleanup(attempts) {
    if (churnCache.stats.ownedFiles <= 2) return Promise.resolve();
    if (attempts >= 40) throw new Error('Evicted filenames were retained after unlink');
    return new Promise(function (resolve) { setTimeout(resolve, 25); }).then(function () {
      return waitForCleanup(attempts + 1);
    });
  }
  return tasks.then(function () {
    assert(churnCache.stats.evictions >= 46);
    return waitForCleanup(0);
  }).then(function () {
    assert.equal(churnCache.stats.ownedFiles, 2, 'Only the live disk pieces should remain tracked');
    return close(churnStore);
  }).then(function () {
    assert.equal(churnCache.stats.ownedFiles, 0, 'Stopping playback must release all filename records');
  });
}).then(function () {
  // Force an eviction unlink to remain in flight when close begins. Shutdown
  // must wait for that unlink, not issue a competing delete and return early.
  var originalUnlink = fs.unlink, delayed = null;
  var retiring = usb.create({ dir: cacheDir, cacheBytes: 65536 }, function () {});
  var retiringStore = retiring.factory(65536), piece = new Buffer(65536);
  piece.fill(65);
  fs.unlink = function (file, cb) {
    if (!delayed && /p-[a-f0-9]{24}-[0-9]+\.piece$/.test(String(file))) {
      delayed = function () { originalUnlink.call(fs, file, cb); };
      return;
    }
    return originalUnlink.apply(fs, arguments);
  };
  return put(retiringStore, 0, piece).then(function () {
    return put(retiringStore, 1, piece);
  }).then(function () {
    assert(delayed, 'Expected an evicted piece awaiting unlink');
    var closed = false;
    var closing = close(retiringStore).then(function () { closed = true; });
    return new Promise(function (resolve) { setTimeout(resolve, 25); }).then(function () {
      assert.equal(closed, false, 'Close must wait for the outstanding eviction unlink');
      var release = delayed; delayed = null; release();
      return closing;
    });
  }).then(function () {
    assert.equal(retiring.stats.ownedFiles, 0, 'Close must reclaim in-flight eviction records');
    fs.unlink = originalUnlink;
  }, function (error) {
    fs.unlink = originalUnlink;
    if (delayed) delayed();
    throw error;
  });
}).then(function () {
  var occupied = path.join(dir, 'Starlit-Torrent-Cache');
  // Simulate a USB drive being disconnected during a piece write. A failed
  // disk operation must switch the live store to its bounded RAM fallback.
  var originalWrite = fs.writeFile, fallbackCount = 0;
  var errored = usb.create({ dir: cacheDir, cacheBytes: 256 * MiB }, function () {},
    function () { fallbackCount++; });
  var erroredStore = errored.factory(65536), buffer = new Buffer(65536);
  buffer.fill(81);
  fs.writeFile = function (file, data, options, cb) {
    if (/p-[a-f0-9]{24}-[0-9]+\.piece$/.test(String(file))) {
      return process.nextTick(function () {
        var error = new Error('USB removed'); error.code = 'ENODEV'; cb(error);
      });
    }
    return originalWrite.apply(fs, arguments);
  };
  return put(erroredStore, 3, buffer).then(function () {
    fs.writeFile = originalWrite;
    assert.equal(errored.stats.mode, 'memory');
    assert.equal(fallbackCount, 1);
    return get(erroredStore, 3);
  }).then(function (data) {
    assert(data.equals(buffer));
    return close(erroredStore);
  }).then(function () {
    // An existing cache path without a valid ownership marker must never be touched.
    fs.writeFileSync(path.join(occupied, '.starlit-owned-v1'), 'not ours');
    return probe(dir, 3 * GiB, 4 * GiB).then(function (result) {
      assert.equal(result.mode, 'memory');
      assert.equal(read(path.join(occupied, '.starlit-owned-v1')).toString(), 'not ours');
    });
  }, function (error) {
    fs.writeFile = originalWrite;
    throw error;
  });
}).then(function () {
  cleanup();
  console.log('usb policy/probe/ownership/eviction/readback/RAM fallback/cleanup: PASS on ' + process.version);
}, function (err) {
  console.error(err.stack || err);
  try { cleanup(); } catch (_) {}
  process.exitCode = 1;
});
