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
function read(file) { return fs.readFileSync(file); }
function probe(root, free, total) { return new Promise(function (resolve, reject) {
  usb.probe(root, free, total, function (err, result) { if (err) reject(err); else resolve(result); });
}); }
function put(store, index, data) { return new Promise(function (resolve, reject) {
  store.put(index, data, function (err) { if (err) reject(err); else resolve(); });
}); }
function get(store, index) { return new Promise(function (resolve, reject) {
  store.get(index, function (err, buffer) { if (err) reject(err); else resolve(buffer); });
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
probe(dir, 3 * GiB, 4 * GiB).then(function (result) {
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
