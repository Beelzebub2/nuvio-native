'use strict';
// MIT. Only the concat API used by the tracker client is implemented.
var http = require('http'), https = require('https'), url = require('url'), policy = require('./network-policy.cjs');
var httpAgent = new http.Agent({ lookup: policy.safeLookup }), httpsAgent = new https.Agent({ lookup: policy.safeLookup, rejectUnauthorized: true });
exports.concat = function (destination, callback) {
  var current = null, ended = false, timer, redirects = 0;
  function done(err, response, data) { if (ended) return; ended = true; clearTimeout(timer); callback(err, response, data); }
  function abort() { if (ended) return; done(new Error('Tracker request cancelled')); if (current) current.abort(); }
  function fetch(address) {
    var parsed;
    try { policy.origin(address); parsed = url.parse(address); } catch (err) { process.nextTick(function () { done(err); }); return; }
    current = (parsed.protocol === 'https:' ? https : http).request({ protocol: parsed.protocol, hostname: parsed.hostname, port: parsed.port, path: parsed.path, method: 'GET', agent: parsed.protocol === 'https:' ? httpsAgent : httpAgent, headers: { 'Accept-Encoding': 'identity' } }, function (res) {
      if (ended) return res.destroy();
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume(); if (++redirects > 3) return done(new Error('Too many tracker redirects'));
        return fetch(url.resolve(address, res.headers.location));
      }
      if (res.headers['content-encoding'] && res.headers['content-encoding'] !== 'identity') { done(new Error('Compressed tracker response unsupported')); return res.destroy(); }
      var chunks = [], length = 0;
      res.on('data', function (chunk) { length += chunk.length; if (length > 262144) { done(new Error('Tracker response exceeds limit')); res.destroy(); } else if (!ended) chunks.push(chunk); });
      res.on('end', function () { done(null, res, Buffer.concat(chunks)); });
      res.on('error', done);
    });
    current.on('error', done); current.end();
  }
  timer = setTimeout(function () { done(new Error('Tracker timeout')); if (current) current.abort(); }, 10000);
  fetch(destination); return { abort: abort };
};
