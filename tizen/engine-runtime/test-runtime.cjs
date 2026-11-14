'use strict';
var assert = require('assert'), crypto = require('crypto'), net = require('net'), http = require('http'), fs = require('fs');
var Wire = require('peer-wire-protocol'), bncode = require('bncode'), runtime = require('./media-http.cjs');
assert.throws(function(){runtime.start();},/control token/);
assert.throws(function(){runtime.start({controlToken:''});},/control token/);
assert.throws(function(){runtime.start({controlToken:'not-a-token'});},/control token/);
var token = new Buffer(32); token.fill(7); token = token.toString('hex');
var first = new Buffer(65539), second = new Buffer(3 * 1024 * 1024 + 37);
for (var i = 0; i < first.length; i++) first[i] = i * 17 % 251;
for (i = 0; i < second.length; i++) second[i] = (i * 31 + 7) % 253;
var content = Buffer.concat([first, second]), pieceLength = 16384, hashes = [];
for (i = 0; i < content.length; i += pieceLength) hashes.push(crypto.createHash('sha1').update(content.slice(i, i + pieceLength)).digest());
var metadata = bncode.encode({ name: 'synthetic', 'piece length': pieceLength, pieces: Buffer.concat(hashes), files: [{length:first.length,path:['small.txt']},{length:second.length,path:['movie.mp4']}] });
var hash = crypto.createHash('sha1').update(metadata).digest('hex');
var requested = [], seederSockets = [], engines = [], service, seed, port, seedPort, ticket, delayedSeedMs = 0;
var fsCalls = 0, restore = {};
['writeFile', 'readFile', 'mkdir', 'unlink', 'rmdir'].forEach(function (name) { restore[name] = fs[name]; fs[name] = function () { fsCalls++; throw new Error('Unexpected filesystem access: ' + name); }; });
function request(path, method, body, extra) {
  return new Promise(function (resolve, reject) {
    var headers = { Host: '127.0.0.1:' + port };
    Object.keys(extra || {}).forEach(function (key) { headers[key] = extra[key]; });
    if (body) { body = JSON.stringify(body); headers['Content-Length'] = Buffer.byteLength(body); headers['Content-Type'] = 'application/json'; }
    var req = http.request({host:'127.0.0.1',port:port,path:path,method:method || 'GET',headers:headers},function (res) {var chunks=[];res.on('data',function(c){chunks.push(c);});res.on('end',function(){resolve({status:res.statusCode,headers:res.headers,body:Buffer.concat(chunks)});});});
    req.on('error',reject);req.setTimeout(10000,function(){req.abort();reject(new Error('Request timeout '+path));});req.end(body);
  });
}
function jsonRequest(path, method, body) {return request(path,method,body,{'X-Starlit-Token':token}).then(function(r){r.json=JSON.parse(r.body.toString());return r;});}
function create(fileIdx) {fileIdx = typeof fileIdx === 'number' ? fileIdx : 1; return jsonRequest('/'+hash+'/create','POST',{torrent:{infoHash:hash},fileIdx:fileIdx,peerSearch:{sources:[]}}).then(function(r){assert.equal(r.status,200,r.body.toString());assert.equal(r.json.files.length,2);assert.equal(r.json.guessedFileIdx,1);ticket=r.json.playbackTicket;assert(/^[a-f0-9]{64}$/.test(ticket));return r;});}
function media(start,end,file) {file=file===undefined?1:file;var source=file?second:first;return request('/'+hash+'/'+file+'?ticket='+ticket,'GET',null,{Range:'bytes='+start+'-'+end}).then(function(r){assert.equal(r.status,206);assert.equal(r.headers['content-range'],'bytes '+start+'-'+end+'/'+source.length);assert.equal(r.body.length,end-start+1);assert(r.body.equals(source.slice(start,end+1)),'Range bytes differ');return r;});}
function stats(){return request('/'+hash+'/stats').then(function(unauthorized){assert.equal(unauthorized.status,403,'Process diagnostics must not be public');return jsonRequest('/'+hash+'/stats');}).then(function(r){assert.equal(r.status,200);assert(r.json.cache.bytes<=65536);assert(Number.isInteger(r.json.activeRanges));assert(r.json.activeRanges>=0&&r.json.activeRanges<=4);assert(r.json.memory && r.json.memory.rssBytes>0,'Authenticated memory diagnostics missing');assert(r.json.memory.heapTotalBytes>=r.json.memory.heapUsedBytes);assert(r.json.uptimeSeconds>=0);return r.json;});}
function verifyRangeUsesPartialCacheReads() {
  var engine = engines[engines.length - 1], originalGet = engine.store.get, seen = [];
  engine.store.get = function (index, options, callback) {
    if (options && typeof options === 'object') seen.push({ offset: options.offset, length: options.length });
    return originalGet.apply(this, arguments);
  };
  return media(7, 60000).then(function () {
    engine.store.get = originalGet;
    assert(seen.length >= 2, 'A multifile HTTP range should request several piece slices');
    assert(seen.some(function (read) { return read.offset > 0 && read.length < 16384; }),
      'HTTP byte-range requests must only retrieve the necessary bytes from the piece cache');
  }, function (error) { engine.store.get = originalGet; throw error; });
}
function concurrentRanges() {
  // AVPlay can issue a second range while the first is still downloading.
  // Wait for the first response headers before starting the second request,
  // ensuring the two GETs overlap even with a local synthetic seeder.
  var ranges = [{ start: 0, end: 65535 }, { start: 1000000, end: 1004095 }];
  return new Promise(function (resolve, reject) {
    var clients = [], completed = 0, finished = false;
    function fail(error) {
      if (finished) return;
      finished = true;
      clients.forEach(function (client) { client.destroy(); });
      reject(error);
    }
    function start(index) {
      var range = ranges[index], chunks = [], ended = false;
      var address = '/' + hash + '/1?ticket=' + ticket;
      var req = http.get({
        host: '127.0.0.1', port: port, path: address,
        headers: { Host: '127.0.0.1:' + port, Range: 'bytes=' + range.start + '-' + range.end }
      }, function (res) {
        if (res.statusCode !== 206) return fail(new Error('Concurrent range HTTP ' + res.statusCode));
        if (index === 0) start(1);
        res.on('data', function (chunk) { chunks.push(chunk); });
        res.on('aborted', function () { fail(new Error('Concurrent range was aborted')); });
        res.on('close', function () {
          if (!ended) fail(new Error('Concurrent range closed before completion'));
        });
        res.on('end', function () {
          ended = true;
          var body = Buffer.concat(chunks);
          if (!body.equals(second.slice(range.start, range.end + 1)))
            return fail(new Error('Concurrent range returned incomplete or incorrect data'));
          completed++;
          if (completed === ranges.length && !finished) { finished = true; resolve(); }
        });
      });
      clients.push(req);
      req.on('error', fail);
      req.setTimeout(10000, function () { fail(new Error('Concurrent range timeout')); });
    }
    start(0);
  });
}
function boundedConcurrentRanges() {
  // Keep several distinct pieces in flight to verify the playback-request cap
  // without allowing more concurrent piece downloads on constrained TVs.
  delayedSeedMs = 350;
  var clients = [];
  function closeAll() {
    delayedSeedMs = 0;
    clients.forEach(function (req) { req.destroy(); });
  }
  for (var i = 0; i < 4; i++) {
    var start = 200000 + i * 350000;
    var req = http.get({
      host: '127.0.0.1', port: port, path: '/' + hash + '/1?ticket=' + ticket,
      headers: { Range: 'bytes=' + start + '-' + (start + 4095) }
    }, function (res) { res.on('error', function () {}); res.resume(); });
    req.on('error', function () {});
    clients.push(req);
  }
  function waitForFour(attempts) {
    return stats().then(function (snapshot) {
      if (snapshot.activeRanges === 4) return;
      if (attempts >= 25) throw new Error('Expected four simultaneous media ranges');
      return new Promise(function (resolve) { setTimeout(resolve, 10); }).then(function () {
        return waitForFour(attempts + 1);
      });
    });
  }
  return waitForFour(0).then(function () {
    return request('/' + hash + '/1?ticket=' + ticket, 'GET', null, { Range: 'bytes=2100000-2100100' });
  }).then(function (response) {
    assert.equal(response.status, 429, 'A fifth concurrent range must be rejected');
    closeAll();
    return new Promise(function (resolve) { setTimeout(resolve, 400); }).then(stats).then(function (snapshot) {
      assert.equal(snapshot.activeRanges, 0, 'Aborting all media clients must release every range');
    });
  }, function (error) {
    closeAll();
    throw error;
  });
}
function concurrentCreate() {
  var old = engines[engines.length - 1], destroy = old.destroy;
  old.destroy = function (cb) { destroy.call(old, function () { setTimeout(cb, 50); }); };
  var other = new Array(41).join('b');
  var a = jsonRequest('/' + other + '/create', 'POST', { torrent: { infoHash: other } });
  return new Promise(function(resolve){setTimeout(resolve,10);}).then(create).then(function(){return a;}).then(function(r){assert.equal(r.status,409,'Superseded create should fail');}).then(function(){return media(12,32780);});
}
function cleanup() {Object.keys(restore).forEach(function(name){fs[name]=restore[name];});return new Promise(function(resolve){function end(){seederSockets.forEach(function(s){s.destroy();});if(seed)seed.close(resolve);else resolve();}if(service)service.stop(end);else end();});}
seed=net.createServer(function(socket){
  if (process.env.TEST_DEBUG) console.log('seed connection');
  if (process.env.TEST_DEBUG) socket.on('data',function(b){console.log('seed data',b.length,b.slice(0,80).toString('hex'));});
  seederSockets.push(socket);var wire=new Wire();socket.pipe(wire).pipe(socket);socket.on('error',function(e){if(process.env.TEST_DEBUG)console.log('seed socket error',e);});wire.on('error',function(e){console.error('seed wire error',e);});
  wire.on('handshake',function(infoHash){if(process.env.TEST_DEBUG)console.log('handshake',infoHash.toString('hex'),hash);if(infoHash.toString('hex')!==hash)return socket.destroy();wire.handshake(hash,'-STSEED-123456789012',{extended:true});var bits=new Buffer(Math.ceil(hashes.length/8));bits.fill(255);wire.bitfield({buffer:bits});wire.unchoke();});
  wire.on('interested',function(){wire.unchoke();});
  wire.on('extended',function(id,payload){var msg=bncode.decode(payload);if(process.env.TEST_DEBUG)console.log('seed extended',id,msg);if(id===0){wire.extended(0,{m:{ut_metadata:1},metadata_size:metadata.length});}else if(id===1&&msg.msg_type===0){wire.extended(1,Buffer.concat([bncode.encode({msg_type:1,piece:msg.piece}),metadata.slice(msg.piece*16384,(msg.piece+1)*16384)]));}});
  wire.on('request',function(index,offset,length,cb){requested.push(index);var piece=content.slice(index*pieceLength+offset,index*pieceLength+offset+length);if(delayedSeedMs)setTimeout(function(){cb(null,piece);},delayedSeedMs);else cb(null,piece);});
});
var watchdog=setTimeout(function(){console.error('TEST WATCHDOG');cleanup().then(function(){process.exit(1);});},30000);
new Promise(function(resolve){seed.listen(0,'127.0.0.1',resolve);}).then(function(){
  seedPort=seed.address().port;
  return new Promise(function(resolve){service=runtime.start({port:0,controlToken:token,cacheBytes:65536,dht:false,allowPrivatePeers:true,metadataTimeout:5000,onEngine:function(engine){engines.push(engine);engine.connect('127.0.0.1:'+seedPort);}},resolve);});
}).then(function(){port=service.server.address().port;return request('/settings');}).then(function(r){assert.equal(r.status,200);return request('/range-probe','GET',null,{Range:'bytes=2-6',Origin:'http://127.0.0.1:'+port});}).then(function(r){assert.equal(r.status,206);assert.equal(r.body.toString(),'arlit');assert.equal(r.headers['content-range'],'bytes 2-6/22');assert.equal(r.headers['access-control-allow-origin'],'http://127.0.0.1:'+port);assert(/\bContent-Range\b/i.test(r.headers['access-control-expose-headers']||''),'Range header must be visible to the TV app');return request('/removeAll','POST');}).then(function(r){assert.equal(r.status,403);return request('/settings','GET',null,{Host:'evil.example:'+port});}).then(function(r){assert.equal(r.status,403);return request('/settings','GET',null,{Origin:'https://evil.example'});}).then(function(r){assert.equal(r.status,403);return request('/settings','GET',null,{Origin:'null'});}).then(function(r){assert.equal(r.status,403);return create();}).then(function(){
  assert.equal(requested.length,0,'Create preloaded media');return boundedConcurrentRanges().then(concurrentRanges).then(function(){return request('/'+hash+'/1');});
}).then(function(r){assert.equal(r.status,403);return verifyRangeUsesPartialCacheReads();}).then(function(){return create(0).then(function(){return media(0,100,0);}).then(function(){return create(1);});}).then(function(){return media(1000000,1100000);}).then(function(){return stats();}).then(function(s){assert(s.cache.evictions>0,'No cache eviction');var count=requested.length;return media(7,60000).then(function(){assert(requested.length>count,'Evicted pieces not refetched');});}).then(function(){
  return request('/'+hash+'/1?ticket='+ticket,'HEAD',null,{Range:'bytes=10-20'});
}).then(function(r){assert.equal(r.status,206);assert.equal(r.body.length,0);assert.equal(r.headers['content-length'],'11');return request('/'+hash+'/1?ticket='+ticket,'GET',null,{Range:'bytes=-17'});}).then(function(r){assert.equal(r.status,206);assert(r.body.equals(second.slice(second.length-17)));return request('/'+hash+'/1?ticket='+ticket,'GET',null,{Range:'bytes=999999999-'});}).then(function(r){assert.equal(r.status,416);
  return new Promise(function(resolve,reject){var req=http.get({host:'127.0.0.1',port:port,path:'/'+hash+'/1?ticket='+ticket},function(res){res.once('data',function(){req.abort();resolve();});});req.on('error',function(err){if(err.code!=='ECONNRESET')reject(err);});});
}).then(function(){return media(second.length-50000,second.length-1);}).then(function(){return stats();}).then(function(s){console.log('range/multifile/seek/evict/refetch/cancel: PASS '+JSON.stringify(s.cache));return jsonRequest('/removeAll','POST');}).then(function(r){assert.equal(r.status,200);assert.equal(engines[0].swarm.wires.length,0);return request('/'+hash+'/1?ticket='+ticket);}).then(function(r){assert.equal(r.status,404);return create();}).then(function(){return media(12,32780);}).then(concurrentCreate).then(function(){assert.equal(fsCalls,0,'Persistent I/O occurred');return cleanup();}).then(function(){clearTimeout(watchdog);console.log('security/range/remove/recreate/no-filesystem/cleanup: PASS on '+process.version);},function(err){console.error(err.stack);cleanup().then(function(){clearTimeout(watchdog);process.exitCode=1;});});
