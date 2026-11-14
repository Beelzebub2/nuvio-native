'use strict';
var assert = require('assert'), http = require('http'), fs = require('fs');
var os = require('os'), path = require('path'), crypto = require('crypto');
var runtime = require('./media-http.cjs');
var MiB = 1024 * 1024, GiB = 1024 * MiB;
var root = path.join(os.tmpdir(), 'starlit-usb-service-test-' + crypto.randomBytes(10).toString('hex'));
var folder = path.join(root, 'Starlit-Torrent-Cache');
var token = new Array(65).join('a'), service, port;
fs.mkdirSync(root);
function request(route, method, body, authenticated) {
  return new Promise(function (resolve, reject) {
    var payload = body ? JSON.stringify(body) : null;
    var headers = { Host: '127.0.0.1:' + port };
    if (authenticated) headers['X-Starlit-Token'] = token;
    if (payload) { headers['Content-Type'] = 'application/json'; headers['Content-Length'] = Buffer.byteLength(payload); }
    var req = http.request({host:'127.0.0.1',port:port,path:route,method:method||'GET',headers:headers},function (res) {
      var chunks=[];
      res.on('data',function(chunk){chunks.push(chunk);});
      res.on('end',function(){
        var text=Buffer.concat(chunks).toString();
        try { resolve({status:res.statusCode,json:JSON.parse(text)}); }
        catch(e) {reject(new Error('Unexpected response '+text));}
      });
    });
    req.on('error',reject);
    req.setTimeout(30000,function(){req.abort();reject(new Error('USB service request timeout'));});
    req.end(payload);
  });
}
function cleanup() {
  return new Promise(function (resolve) {
    function done() {
      try {
        if (fs.existsSync(folder)) {
          fs.readdirSync(folder).forEach(function (name) {
            var file=path.join(folder,name);
            if (fs.lstatSync(file).isFile()) fs.unlinkSync(file);
          });
          fs.rmdirSync(folder);
        }
        fs.rmdirSync(root);
      } catch (err) { console.error('Test cleanup failed: '+err.message); process.exitCode=1; }
      resolve();
    }
    if (service) service.stop(done);
    else done();
  });
}
new Promise(function(resolve) {
  service=runtime.start({port:0,controlToken:token,dht:false},resolve);
}).then(function() {
  port=service.server.address().port;
  return request('/cache/status');
}).then(function(result) {
  assert.equal(result.status,403);
  return request('/cache/probe','POST',{root:root,freeBytes:3*GiB,totalBytes:4*GiB});
}).then(function(result) {
  assert.equal(result.status,403);
  assert(!fs.existsSync(folder),'Unauthorized callers must not create cache files');
  return request('/cache/probe','POST',{root:'/',freeBytes:3*GiB,totalBytes:4*GiB},true);
}).then(function(result) {
  assert.equal(result.status,200);
  assert.equal(result.json.mode,'memory');
  return request('/cache/probe','POST',{root:root,freeBytes:3*GiB,totalBytes:4*GiB},true);
}).then(function(result) {
  assert.equal(result.status,200);
  assert.equal(result.json.mode,'usb');
  assert(result.json.cacheBytes>=256*MiB);
  assert(!result.json.root && !result.json.dir,'Storage paths must not be returned to the app');
  return request('/cache/status','GET',null,true);
}).then(function(result) {
  assert.equal(result.status,200);
  assert.equal(result.json.configured,true);
  assert.equal(result.json.active,null);
  return request('/settings');
}).then(function(result) {
  assert.equal(result.status,200);
  assert.equal(result.json.capabilities.usbCache,true);
  assert.equal(result.json.memoryOnly,false);
  return request('/cache/disable','POST',{},true);
}).then(function(result) {
  assert.equal(result.status,200);
  assert.equal(result.json.configured,false);
  return request('/cache/status','GET',null,true);
}).then(function(result) {
  assert.equal(result.json.configured,false);
  return cleanup();
}).then(function() {
  console.log('usb service access-control/probe/config/disable/cleanup: PASS on ' + process.version);
}, function(err) {
  console.error(err.stack||err);
  cleanup().then(function(){process.exitCode=1;});
});
