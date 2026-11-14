'use strict';
// Synthetic metadata only. Do not allocate actual torrent payloads.
var assert = require('assert');
var runtime = require('./media-http.cjs');
var createCache = require('./bounded-store.cjs');
var MiB = 1024 * 1024;
function fixture(pieceLength, pieces, files) {
  var fileList = [];
  var totalLength = pieceLength * pieces;
  var fileLength = Math.ceil(totalLength / files);
  for (var i = 0; i < files; i++) {
    var offset = i * fileLength;
    fileList.push({
      name: 'file-' + i + '.mkv',
      path: 'file-' + i + '.mkv',
      offset: offset,
      length: Math.min(fileLength, Math.max(0, totalLength - offset))
    });
  }
  return { files: fileList, pieces: new Array(pieces), pieceLength: pieceLength, length: totalLength };
}
function invalid(metadata, reason) {
  assert.throws(function () { runtime.validateTorrent(metadata); }, reason);
}
assert.equal(typeof runtime.validateTorrent, 'function', 'Bundled runtime exposes its real validator');
runtime.validateTorrent(fixture(16 * 1024, 1, 1));
runtime.validateTorrent(fixture(4 * MiB, 16385, 2)); // Prior piece-size and piece-count limits.
runtime.validateTorrent(fixture(32 * MiB, 4, 1));
runtime.validateTorrent(fixture(16 * MiB, 65536, 1024)); // Maximum supported piece and file counts.
invalid(fixture(64 * MiB, 1, 1), /piece size must be between 16 KiB and 32 MiB/);
invalid(fixture(16 * MiB, 65537, 1), /more than 65536 pieces/);
invalid(fixture(16 * MiB, 1, 1025), /more than 1024 files/);
invalid(fixture(32 * MiB, 32769, 1), /larger than 1 TiB/);
var badPath = fixture(4 * MiB, 1, 1);
badPath.files[0].path = new Array(2050).join('a');
invalid(badPath, /Invalid torrent file metadata/);
invalid(fixture(8 * 1024, 1, 1), /piece size must be/);
var cache = createCache(48 * MiB, function () {});
var storage = cache.factory(32 * MiB);
var first = new Buffer(32 * MiB), second = new Buffer(32 * MiB);
first.fill(1); second.fill(2);
storage.put(0, first, function (err) { assert.ifError(err); });
storage.put(1, second, function (err) { assert.ifError(err); });
assert(cache.stats.bytes <= 48 * MiB, 'Full-size pieces must respect the existing cache cap');
assert(cache.stats.evictions > 0, 'One 32 MiB piece must evict the previous one');
assert.throws(function () { cache.factory(64 * MiB); }, /Piece exceeds cache capacity/);
console.log('large torrent limits / bounded piece cache: PASS on ' + process.version);
