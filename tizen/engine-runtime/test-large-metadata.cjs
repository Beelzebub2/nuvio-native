'use strict';
// Tests an actual BitTorrent metadata exchange without downloading media.
var assert = require('assert'), crypto = require('crypto'), net = require('net'), http = require('http');
var Wire = require('peer-wire-protocol'), bncode = require('bncode'), runtime = require('./media-http.cjs');
var pieceCount = 16385, pieceLength = 4 * 1024 * 1024;
var pieceHashes = new Buffer(pieceCount * 20);
pieceHashes.fill(1);
var fileCount = 300, files = [];
for (var fileIdx = 0; fileIdx < fileCount - 1; fileIdx++) {
  files.push({ length: 1, path: ['sample-' + fileIdx + '.txt'] });
}
files.push({ length: pieceCount * pieceLength - (fileCount - 1), path: ['video.mkv'] });
var metadata = bncode.encode({
  name: 'synthetic-large-legal-fixture.mkv',
  'piece length': pieceLength,
  pieces: pieceHashes,
  files: files
});
assert(metadata.length > 256 * 1024, 'Fixture must exceed the previous metadata limit');
assert(metadata.length < 2 * 1024 * 1024, 'Fixture must fit the new metadata bound');
var hash = crypto.createHash('sha1').update(metadata).digest('hex');
var token = new Buffer(32);
token.fill(4);
token = token.toString('hex');
var seed, service, sockets = [], mediaRequests = 0, settled = false;
var watchdog = setTimeout(function () { finish(new Error('Large metadata exchange timeout')); }, 22000);
function finish(error) {
  if (settled) return;
  settled = true; clearTimeout(watchdog);
  sockets.forEach(function (socket) { socket.destroy(); });
  function done() {
    if (error) { console.error(error.stack || error); process.exitCode = 1; }
    else console.log('metadata >256 KiB / >16384 pieces / 4 MiB piece: PASS on ' + process.version);
  }
  if (service) service.stop(function () { seed.close(done); });
  else if (seed) seed.close(done);
  else done();
}
function create() {
  var port = service.server.address().port;
  var body = JSON.stringify({ torrent: { infoHash: hash }, fileIdx: fileCount - 1, peerSearch: { sources: [] } });
  var request = http.request({
    host: '127.0.0.1', port: port, method: 'POST', path: '/' + hash + '/create',
    headers: { Host: '127.0.0.1:' + port, 'X-Starlit-Token': token, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }
  }, function (response) {
    var chunks = [];
    response.on('data', function (chunk) { chunks.push(chunk); });
    response.on('end', function () {
      try {
        assert.equal(response.statusCode, 200, Buffer.concat(chunks).toString());
        var result = JSON.parse(Buffer.concat(chunks).toString());
        assert.equal(result.files.length, fileCount);
        assert.equal(result.fileIdx, fileCount - 1, 'Explicit index above old 256-file limit accepted');
        assert.equal(result.files[fileCount - 1].length, pieceCount * pieceLength - (fileCount - 1));
        assert(/^[a-f0-9]{64}$/.test(result.playbackTicket));
        assert.equal(mediaRequests, 0, 'Create must not download media payloads');
        finish();
      } catch (error) { finish(error); }
    });
  });
  request.on('error', finish);
  request.end(body);
}
seed = net.createServer(function (socket) {
  sockets.push(socket);
  var wire = new Wire();
  socket.pipe(wire).pipe(socket);
  socket.on('error', function () {});
  wire.on('error', function (error) { finish(error); });
  wire.on('handshake', function (peerHash) {
    if (peerHash.toString('hex') !== hash) return finish(new Error('Wrong info hash'));
    wire.handshake(hash, '-STSEED-123456789012', { extended: true });
    var bits = new Buffer(Math.ceil(pieceCount / 8));
    bits.fill(255);
    wire.bitfield({ buffer: bits });
    wire.unchoke();
  });
  wire.on('extended', function (id, payload) {
    try {
      var request = bncode.decode(payload);
      if (id === 0) wire.extended(0, { m: { ut_metadata: 1 }, metadata_size: metadata.length });
      else if (id === 1 && request.msg_type === 0) {
        var start = request.piece * 16384;
        wire.extended(1, Buffer.concat([
          bncode.encode({ msg_type: 1, piece: request.piece }),
          metadata.slice(start, start + 16384)
        ]));
      }
    } catch (error) { finish(error); }
  });
  wire.on('request', function () { mediaRequests++; finish(new Error('Unexpected media piece requested')); });
});
seed.listen(0, '127.0.0.1', function () {
  service = runtime.start({
    port: 0, controlToken: token, dht: false, allowPrivatePeers: true, metadataTimeout: 12000,
    onEngine: function (engine) { engine.connect('127.0.0.1:' + seed.address().port); }
  }, function () {
    var port = service.server.address().port;
    http.get({ host: '127.0.0.1', port: port, path: '/settings', headers: { Host: '127.0.0.1:' + port } }, function (response) {
      var body = [];
      response.on('data', function (chunk) { body.push(chunk); });
      response.on('end', function () {
        try {
          assert.equal(response.statusCode, 200);
          var limits = JSON.parse(Buffer.concat(body).toString()).limits;
          assert.equal(limits.files, 1024);
          assert.equal(limits.pieces, 65536);
          assert.equal(limits.maxPieceBytes, 32 * 1024 * 1024);
          assert.equal(limits.maxMetadataBytes, 2 * 1024 * 1024);
          assert.equal(limits.cacheBytes, 48 * 1024 * 1024);
          create();
        } catch (error) { finish(error); }
      });
    }).on('error', finish);
  });
});
