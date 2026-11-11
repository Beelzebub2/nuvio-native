'use strict';
var assert = require('assert'), fs = require('fs'), vm = require('vm'), http = require('http'), policy = require('./network-policy.cjs');
['127.0.0.1','10.0.0.1','172.16.0.1','192.168.1.1','169.254.169.254','100.64.0.1','0.0.0.0','224.0.0.1','::1','::ffff:127.0.0.1','fc00::1','fe80::1','2001:db8::1'].forEach(function(x){assert(policy.blockedAddress(x),x);});
assert(!policy.blockedAddress('8.8.8.8'));
assert.throws(function(){policy.origin('http://127.0.0.1/private');});
assert.throws(function(){policy.origin('http://user:password@example.com');});
// Exercise the real Agent lookup path against a real local HTTP listener.
var dns = require('dns'), original = dns.lookup, connections = 0, server, activeResponse;
var timer = setTimeout(function(){console.error('Network test timeout');process.exit(1);},10000);
server = http.createServer(function(req,res){connections++;activeResponse=res;if(req.url==='/big'){res.end(new Buffer(262145));}else if(req.url==='/redirect'){res.writeHead(302,{Location:'/big'});res.end();}else if(req.url==='/hang'){res.write('partial');}else{res.end('safe');}});
new Promise(function(resolve){server.listen(0,'127.0.0.1',resolve);}).then(function(){
  dns.lookup=function(host,opts,cb){if(typeof opts==='function'){cb=opts;opts={};}process.nextTick(function(){cb(null,opts.all?[{address:'127.0.0.1',family:4}]:'127.0.0.1',4);});};
  return new Promise(function(resolve,reject){var req=http.get({hostname:'rebinding.test',port:server.address().port,agent:new http.Agent({lookup:policy.safeLookup})},function(){reject(new Error('Private DNS reached listener'));});req.on('error',function(err){assert.equal(err.code,'EACCES');resolve();});});
}).then(function(){
  dns.lookup=original;assert.equal(connections,0,'Blocked DNS made a connection');
  // Isolate address policy so a local synthetic server can prove response limits and cancellation.
  var sandbox={exports:{},Buffer:Buffer,process:process,setTimeout:setTimeout,clearTimeout:clearTimeout,require:function(name){if(name==='./network-policy.cjs')return {origin:function(){},safeLookup:function(host,opts,cb){if(typeof opts==='function')cb=opts;process.nextTick(function(){cb(null,opts&&opts.all?[{address:'127.0.0.1',family:4}]:'127.0.0.1',4);});}};return require(name);}};
  vm.runInNewContext(fs.readFileSync(require.resolve('./tracker-http.cjs'),'utf8'),sandbox);
  var fetch=sandbox.exports.concat, base='http://fixture.test:'+server.address().port;
  return new Promise(function(resolve,reject){fetch(base+'/safe',function(err,res,body){if(err)return reject(err);assert.equal(body.toString(),'safe');resolve();});}).then(function(){
    return new Promise(function(resolve){fetch(base+'/big',function(err){assert(err);assert(/limit/.test(err.message));resolve();});});
  }).then(function(){return new Promise(function(resolve){fetch(base+'/redirect',function(err){assert(err);assert(/limit/.test(err.message));resolve();});});}).then(function(){
    return new Promise(function(resolve){var handle=fetch(base+'/hang',function(err){assert(err);assert(/cancelled/.test(err.message));resolve();});setTimeout(function(){handle.abort();},30);});
  });
}).then(function(){if(activeResponse)activeResponse.destroy();server.close(function(){clearTimeout(timer);console.log('public-DNS Agent/rebinding/tracker-response-limit/redirect/cancel: PASS on '+process.version);});},function(err){dns.lookup=original;console.error(err.stack);if(activeResponse)activeResponse.destroy();server.close(function(){clearTimeout(timer);process.exitCode=1;});});
