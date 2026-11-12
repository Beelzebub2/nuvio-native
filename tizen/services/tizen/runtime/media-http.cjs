(function(f){if(typeof exports==="object"&&typeof module!=="undefined"){module.exports=f()}else if(typeof define==="function"&&define.amd){define([],f)}else{var g;if(typeof window!=="undefined"){g=window}else if(typeof global!=="undefined"){g=global}else if(typeof self!=="undefined"){g=self}else{g=this}g.StarlitTorrentRuntime = f()}})(function(){var define,module,exports;return (function(){function r(e,n,t){function o(i,f){if(!n[i]){if(!e[i]){var c="function"==typeof require&&require;if(!f&&c)return c(i,!0);if(u)return u(i,!0);var a=new Error("Cannot find module '"+i+"'");throw a.code="MODULE_NOT_FOUND",a}var p=n[i]={exports:{}};e[i][0].call(p.exports,function(r){var n=e[i][1][r];return o(n||r)},p,p.exports,r,e,n,t)}return n[i].exports}for(var u="function"==typeof require&&require,i=0;i<t.length;i++)o(t[i]);return o}return r})()({1:[function(require,module,exports){
'use strict';
// MIT. Bounded piece cache; eviction restores download eligibility in the engine.
module.exports = function (limit, evict) {
  var items = Object.create(null), order = [], bytes = 0, closed = false;
  var stats = { bytes: 0, limit: limit, evictions: 0, hits: 0, misses: 0 };
  function touch(index) { var p = order.indexOf(index); if (p >= 0) order.splice(p, 1); order.push(index); }
  function done(cb, err, data) { process.nextTick(function () { if (cb) cb(err, data); }); }
  return {
    stats: stats,
    factory: function (length) {
      if (length > limit) throw new Error('Piece exceeds cache capacity');
      return {
        chunkLength: length,
        put: function (index, buf, cb) {
          if (closed) return done(cb, new Error('Cache closed'));
          if (buf.length > limit) return done(cb, new Error('Piece exceeds cache capacity'));
          if (items[index]) bytes -= items[index].length;
          items[index] = buf; bytes += buf.length; touch(index);
          while (bytes > limit) {
            var old = order.shift(); bytes -= items[old].length; delete items[old];
            stats.evictions++; evict(old);
          }
          stats.bytes = bytes; done(cb);
        },
        get: function (index, opts, cb) {
          if (typeof opts === 'function') { cb = opts; opts = null; }
          var buf = items[index];
          if (!buf || closed) { stats.misses++; return done(cb, new Error('Piece absent')); }
          stats.hits++; touch(index);
          var start = opts && opts.offset || 0;
          done(cb, null, opts ? buf.slice(start, opts.length ? start + opts.length : undefined) : buf);
        },
        close: function (cb) { closed = true; items = Object.create(null); order = []; bytes = stats.bytes = 0; done(cb); },
        destroy: function (cb) { this.close(cb); }
      };
    }
  };
};

},{}],2:[function(require,module,exports){
/* MIT. Public-address DNS policy for outbound service requests. */
"use strict";
var dns = require("dns");
var net = require("net");
var http = require("http");
var https = require("https");
var url = require("url");

function blockedAddress(address) {
  var value = String(address || "").toLowerCase().split("%")[0];
  var family = net.isIP(value);
  if (family === 4) {
    var p = value.split(".").map(Number);
    return p[0] === 0 || p[0] === 10 || p[0] === 127 || p[0] >= 224 ||
      (p[0] === 100 && p[1] >= 64 && p[1] <= 127) ||
      (p[0] === 169 && p[1] === 254) ||
      (p[0] === 172 && p[1] >= 16 && p[1] <= 31) ||
      (p[0] === 192 && (p[1] === 0 || p[1] === 168 || (p[1] === 88 && p[2] === 99))) ||
      (p[0] === 198 && (p[1] === 18 || p[1] === 19 || (p[1] === 51 && p[2] === 100))) ||
      (p[0] === 203 && p[1] === 0 && p[2] === 113);
  }
  if (family === 6) {
    // Restrict to globally allocated unicast; reject mapped, ULA and link-local forms.
    if (!/^[23][0-9a-f]{0,3}:/.test(value)) return true;
    return /^(2001:(?:0:|db8:|2:|10:|20:)|2002:)/.test(value);
  }
  return true;
}

function safeLookup(hostname, options, callback) {
  if (typeof options === "function") { callback = options; options = {}; }
  if (typeof options === "number") options = { family: options };
  options = options || {};
  dns.lookup(hostname, { family: options.family || 0, all: true }, function (error, addresses) {
    if (error) return callback(error);
    if (!addresses || !addresses.length || addresses.some(function (entry) { return blockedAddress(entry.address); })) {
      var denied = new Error("Proxy destination is not a public address");
      denied.code = "EACCES";
      return callback(denied);
    }
    if (options.all) return callback(null, addresses);
    callback(null, addresses[0].address, addresses[0].family);
  });
}

function origin(destination) {
  var parsed = typeof destination === "string" ? url.parse(destination) : destination;
  if (!parsed || (parsed.protocol !== "http:" && parsed.protocol !== "https:") ||
      !parsed.hostname || parsed.auth || /[\r\n]/.test(parsed.href || "")) throw new Error("Invalid proxy destination");
  var hostname = String(parsed.hostname).toLowerCase();
  var bare = hostname.replace(/^\[|\]$/g, "");
  if (net.isIP(bare) && blockedAddress(bare)) throw new Error("Proxy destination is not a public address");
  var port = parsed.port || (parsed.protocol === "https:" ? "443" : "80");
  if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) throw new Error("Invalid proxy port");
  return parsed.protocol + "//" + hostname + ":" + Number(port);
}

var httpAgent = new http.Agent({ lookup: safeLookup });
var httpsAgent = new https.Agent({ lookup: safeLookup, rejectUnauthorized: true });
var portable = /^(accept|accept-encoding|accept-language|range|if-range|user-agent)$/i;

function protectedFetch(destination, request, headers, options, fetch) {
  var currentOrigin = origin(destination);
  if (!options.__starlitCredentialOrigin) options.__starlitCredentialOrigin = currentOrigin;
  var safeHeaders = new fetch.Headers();
  headers.forEach(function (value, name) {
    if (/[\r\n]/.test(name) || /[\r\n]/.test(value)) throw new Error("Invalid proxy header");
    if (/^(host|connection|transfer-encoding|content-length|proxy-authorization)$/i.test(name)) return;
    if (currentOrigin !== options.__starlitCredentialOrigin && !portable.test(name)) return;
    safeHeaders.set(name, value);
  });
  if (request.method !== "GET" && request.method !== "HEAD") throw new Error("Unsupported proxy method");
  return fetch(url.format(destination), {
    method: request.method,
    headers: safeHeaders,
    agent: destination.protocol === "https:" ? httpsAgent : httpAgent,
    redirect: "manual"
  });
}

module.exports = { blockedAddress: blockedAddress, safeLookup: safeLookup, origin: origin, protectedFetch: protectedFetch };

},{"dns":undefined,"http":undefined,"https":undefined,"net":undefined,"url":undefined}],3:[function(require,module,exports){
function Bitset(value) { if (!(this instanceof Bitset)) return new Bitset(value); this.buffer = Buffer.isBuffer(value) ? value : new Buffer(Math.ceil(value / 8)); if (!Buffer.isBuffer(value)) this.buffer.fill(0); }
Bitset.prototype.get = function (index) { return !!(this.buffer[Math.floor(index / 8)] & (128 >> (index % 8))); };
Bitset.prototype.set = function (index, value) { var byte = Math.floor(index / 8), mask = 128 >> (index % 8); if (value === false) this.buffer[byte] &= ~mask; else this.buffer[byte] |= mask; };
module.exports = Bitset;
},{}],4:[function(require,module,exports){
module.exports = DHT

var inherits = require('inherits')
var EventEmitter = require('events').EventEmitter
var krpc = require('k-rpc')
var KBucket = require('k-bucket')
var crypto = require('crypto')
var bencode = require('bencode')
var equals = require('buffer-equals')
var LRU = require('lru')
var debug = require('debug')('bittorrent-dht')

var ROTATE_INTERVAL = 5 * 60 * 1000 // rotate secrets every 5 minutes

inherits(DHT, EventEmitter)

function DHT (opts) {
  if (!(this instanceof DHT)) return new DHT(opts)
  if (!opts) opts = {}

  var self = this

  this._tables = LRU({maxAge: ROTATE_INTERVAL, max: opts.maxTables || 1000})
  this._values = LRU(opts.maxValues || 1000)
  this._peers = new PeerStore(opts.maxPeers || 10000)

  this._secrets = null
  this._rpc = krpc(opts)
  this._rpc.on('query', onquery)
  this._rpc.on('node', onnode)
  this._rpc.on('warning', onwarning)
  this._rpc.on('error', onerror)
  this._rpc.on('listening', onlistening)
  this._rotateSecrets()
  this._verify = opts.verify || null
  this._host = opts.host || null
  this._interval = setInterval(rotateSecrets, ROTATE_INTERVAL)

  this.listening = false
  this.destroyed = false
  this.nodeId = this._rpc.id
  this.nodes = this._rpc.nodes

  process.nextTick(bootstrap)

  EventEmitter.call(this)
  this._debug('new DHT %s', this.nodeId)

  function onlistening () {
    self.listening = true
    self._debug('listening %d', self.address().port)
    self.emit('listening')
  }

  function onquery (query, peer) {
    self._onquery(query, peer)
  }

  function rotateSecrets () {
    self._rotateSecrets()
  }

  function bootstrap () {
    if (!self.destroyed) self._bootstrap(opts.bootstrap !== false)
  }

  function onwarning (err) {
    self.emit('warning', err)
  }

  function onerror (err) {
    self.emit('error', err)
  }

  function onnode (node) {
    self.emit('node', node)
  }
}

DHT.prototype.addNode = function (node) {
  var self = this
  if (node.id) {
    node.id = toBuffer(node.id)
    var old = !!this._rpc.nodes.get(node.id)
    this._rpc.nodes.add(node)
    if (!old) this.emit('node', node)
    return
  }
  this._sendPing(node, function (_, node) {
    if (node) self.addNode(node)
  })
}

DHT.prototype.removeNode = function (id) {
  this._rpc.nodes.remove({id: toBuffer(id)})
}

DHT.prototype._sendPing = function (node, cb) {
  this._rpc.query(node, {q: 'ping'}, function (err, pong, node) {
    if (err) return cb(err)
    if (!pong.r || !pong.r.id || !Buffer.isBuffer(pong.r.id) || pong.r.id.length !== 20) {
      return cb(new Error('Bad reply'))
    }
    cb(null, {
      id: pong.r.id,
      host: node.host || node.address,
      port: node.port
    })
  })
}

DHT.prototype.toJSON =
DHT.prototype.toArray = function () {
  return this._rpc.nodes.toArray().map(toNode)
}

DHT.prototype.put = function (opts, cb) {
  if (Buffer.isBuffer(opts) || typeof opts === 'string') opts = {v: opts}
  var isMutable = !!opts.k
  if (opts.v === undefined) {
    throw new Error('opts.v not given')
  }
  if (opts.v.length >= 1000) {
    throw new Error('v must be less than 1000 bytes in put()')
  }
  if (isMutable && opts.cas !== undefined && typeof opts.cas !== 'number') {
    throw new Error('opts.cas must be an integer if provided')
  }
  if (isMutable && !opts.k) {
    throw new Error('opts.k ed25519 public key required for mutable put')
  }
  if (isMutable && opts.k.length !== 32) {
    throw new Error('opts.k ed25519 public key must be 32 bytes')
  }
  if (isMutable && typeof opts.sign !== 'function' && !Buffer.isBuffer(opts.sig)) {
    throw new Error('opts.sign function or options.sig signature is required for mutable put')
  }
  if (isMutable && opts.salt && opts.salt.length > 64) {
    throw new Error('opts.salt is > 64 bytes long')
  }
  if (isMutable && opts.seq === undefined) {
    throw new Error('opts.seq not provided for a mutable update')
  }
  if (isMutable && typeof opts.seq !== 'number') {
    throw new Error('opts.seq not an integer')
  }

  return this._put(opts, cb)
}

DHT.prototype._put = function (opts, cb) {
  if (!cb) cb = noop

  var isMutable = !!opts.k
  var v = typeof opts.v === 'string' ? new Buffer(opts.v) : opts.v
  var key = isMutable
    ? sha1(opts.salt ? Buffer.concat([opts.salt, opts.k]) : opts.k)
    : sha1(bencode.encode(v))

  var table = this._tables.get(key.toString('hex'))
  if (!table) return this._preput(key, opts, cb)

  var message = {
    q: 'put',
    a: {
      id: this._rpc.id,
      token: null, // queryAll sets this
      v: v
    }
  }

  if (isMutable) {
    if (typeof opts.cas === 'number') message.a.cas = opts.cas
    if (opts.salt) message.a.salt = opts.salt
    message.a.k = opts.k
    message.a.seq = opts.seq
    if (typeof opts.sign === 'function') message.a.sig = opts.sign(encodeSigData(message.a))
    else if (Buffer.isBuffer(opts.sig)) message.a.sig = opts.sig
  }

  this._values.set(key.toString('hex'), message.a)
  this._rpc.queryAll(table.closest({id: key}), message, null, function (err, n) {
    if (err) return cb(err, key, n)
    cb(null, key, n)
  })

  return key
}

DHT.prototype._preput = function (key, opts, cb) {
  var self = this

  this._closest(key, {
    q: 'get',
    a: {
      id: this._rpc.id,
      target: key
    }
  }, null, function (err, n) {
    if (err) return cb(err)
    self.put(opts, cb)
  })

  return key
}

DHT.prototype.get = function (key, opts, cb) {
  key = toBuffer(key)
  if (typeof opts === 'function') {
    cb = opts
    opts = null
  }

  if (!opts) opts = {}
  var verify = opts.verify || this._verify
  var value = this._values.get(key.toString('hex')) || null

  if (value) {
    value = createGetResponse(this._rpc.id, null, value)
    return process.nextTick(done)
  }

  this._closest(key, {
    q: 'get',
    a: {
      id: this._rpc.id,
      target: key
    }
  }, onreply, done)

  function done (err) {
    if (err) return cb(err)
    cb(null, value)
  }

  function onreply (message) {
    var r = message.r
    if (!r || !r.v) return true

    var isMutable = r.k || r.sig

    if (isMutable) {
      if (!verify || !r.sig || !r.k) return true
      if (!verify(r.sig, encodeSigData(r), r.k)) return true
      if (equals(sha1(r.salt ? Buffer.concat([r.salt, r.k]) : r.k), key)) {
        value = r
        return false
      }
    } else {
      if (equals(sha1(bencode.encode(r.v)), key)) {
        value = r
        return false
      }
    }

    return true
  }
}

DHT.prototype.announce = function (infoHash, port, cb) {
  if (typeof port === 'function') return this.announce(infoHash, 0, port)
  infoHash = toBuffer(infoHash)
  if (!cb) cb = noop

  var table = this._tables.get(infoHash.toString('hex'))
  if (!table) return this._preannounce(infoHash, port, cb)

  if (this._host) {
    var dhtPort = this.listening ? this.address().port : 0
    this._addPeer(
      {host: this._host, port: port || dhtPort},
      infoHash,
      {host: this._host, port: dhtPort}
    )
  }

  var message = {
    q: 'announce_peer',
    a: {
      id: this._rpc.id,
      token: null, // queryAll sets this
      info_hash: infoHash,
      port: port,
      implied_port: port ? 0 : 1
    }
  }

  this._debug('announce %s %d', infoHash, port)
  this._rpc.queryAll(table.closest({id: infoHash}), message, null, cb)
}

DHT.prototype._preannounce = function (infoHash, port, cb) {
  var self = this

  this.lookup(infoHash, function (err) {
    if (self.destroyed) return cb(new Error('dht is destroyed'))
    if (err) return cb(err)
    self.announce(infoHash, port, cb)
  })
}

DHT.prototype.lookup = function (infoHash, cb) {
  infoHash = toBuffer(infoHash)
  if (!cb) cb = noop
  var self = this
  var aborted = false

  this._debug('lookup %s', infoHash)
  process.nextTick(emit)
  this._closest(infoHash, {
    q: 'get_peers',
    a: {
      id: this._rpc.id,
      info_hash: infoHash
    }
  }, onreply, cb)

  function emit (values, from) {
    if (!values) values = self._peers.get(infoHash.toString('hex'))
    var peers = decodePeers(values)
    for (var i = 0; i < peers.length; i++) {
      self.emit('peer', peers[i], infoHash, from || null)
    }
  }

  function onreply (message, node) {
    if (aborted) return false
    if (message.r.values) emit(message.r.values, node)
  }

  return function abort () { aborted = true }
}

DHT.prototype.address = function () {
  return this._rpc.address()
}

DHT.prototype.listen = function (port, cb) {
  if (typeof port === 'function') return this.listen(0, port)
  this._rpc.bind(port, cb)
}

DHT.prototype.destroy = function (cb) {
  if (this.destroyed) {
    if (cb) process.nextTick(cb)
    return
  }
  this.destroyed = true
  var self = this
  clearInterval(this._interval)
  this._debug('destroying')
  this._rpc.destroy(function () {
    self.emit('close')
    if (cb) cb()
  })
}

DHT.prototype._onquery = function (query, peer) {
  var q = query.q.toString()
  this._debug('received %s query from %s:%d', q, peer.address, peer.port)
  if (!query.a) return

  switch (q) {
    case 'ping':
      return this._rpc.response(peer, query, {id: this._rpc.id})

    case 'find_node':
      return this._onfindnode(query, peer)

    case 'get_peers':
      return this._ongetpeers(query, peer)

    case 'announce_peer':
      return this._onannouncepeer(query, peer)

    case 'get':
      return this._onget(query, peer)

    case 'put':
      return this._onput(query, peer)
  }
}

DHT.prototype._onfindnode = function (query, peer) {
  var target = query.a.target
  if (!target) return this._rpc.error(peer, query, [203, '`find_node` missing required `a.target` field'])

  var nodes = this._rpc.nodes.closest({ id: target })
  this._rpc.response(peer, query, {id: this._rpc.id}, nodes)
}

DHT.prototype._ongetpeers = function (query, peer) {
  var host = peer.address || peer.host
  var infoHash = query.a.info_hash
  if (!infoHash) return this._rpc.error(peer, query, [203, '`get_peers` missing required `a.info_hash` field'])

  var r = {id: this._rpc.id, token: this._generateToken(host)}
  var peers = this._peers.get(infoHash.toString('hex'))

  if (peers.length) {
    r.values = peers
    this._rpc.response(peer, query, r)
  } else {
    this._rpc.response(peer, query, r, this._rpc.nodes.closest({id: infoHash}))
  }
}

DHT.prototype._onannouncepeer = function (query, peer) {
  var host = peer.address || peer.host
  var port = query.a.implied_port ? peer.port : query.a.port
  if (!port || typeof port !== 'number') return
  var infoHash = query.a.info_hash
  var token = query.a.token
  if (!infoHash || !token) return

  if (!this._validateToken(host, token)) {
    return this._rpc.error(peer, query, [203, 'cannot `announce_peer` with bad token'])
  }

  this._addPeer({host: host, port: port}, infoHash, {host: host, port: peer.port})
  this._rpc.response(peer, query, {id: this._rpc.id})
}

DHT.prototype._addPeer = function (peer, infoHash, from) {
  this._peers.add(infoHash.toString('hex'), encodePeer(peer.host, peer.port))
  this.emit('announce', peer, infoHash, from)
}

DHT.prototype._onget = function (query, peer) {
  var host = peer.address || peer.host
  var target = query.a.target
  if (!target) return
  var token = this._generateToken(host)
  var value = this._values.get(target.toString('hex'))

  if (!value) {
    var nodes = this._rpc.nodes.closest({id: target})
    this._rpc.response(peer, query, {id: this._rpc.id, token: token}, nodes)
  } else {
    this._rpc.response(peer, query, createGetResponse(this._rpc.id, token, value))
  }
}

DHT.prototype._onput = function (query, peer) {
  var host = peer.address || peer.host

  var a = query.a
  if (!a) return
  var v = query.a.v
  if (!v) return

  var token = a.token
  if (!token) return

  if (!this._validateToken(host, token)) {
    return this._rpc.error(peer, query, [203, 'cannot `put` with bad token'])
  }
  if (v.length > 1000) {
    return this._rpc.error(peer, query, [205, 'data payload too large'])
  }

  var isMutable = !!(a.k || a.sig)
  if (isMutable && !a.k && !a.sig) return

  var key = isMutable
    ? sha1(a.salt ? Buffer.concat([a.salt, a.k]) : a.k)
    : sha1(bencode.encode(v))
  var keyHex = key.toString('hex')

  if (isMutable) {
    if (!this._verify) return this._rpc.error(peer, query, [400, 'verification not supported'])
    if (!this._verify(a.sig, encodeSigData(a), a.k)) return
    var prev = this._values.get(keyHex)
    if (prev && typeof a.cas === 'number' && prev.seq !== a.cas) {
      return this._rpc.error(peer, query, [301, 'CAS mismatch, re-read and try again'])
    }
    if (prev && typeof prev.seq === 'number' && !(a.seq > prev.seq)) {
      return this._rpc.error(peer, query, [302, 'sequence number less than current'])
    }
    this._values.set(keyHex, {v: v, k: a.k, salt: a.salt, sig: a.sig, seq: a.seq})
  } else {
    this._values.set(keyHex, {v: v})
  }

  this._rpc.response(peer, query, {id: this._rpc.id})
}

DHT.prototype._bootstrap = function (populate) {
  var self = this
  if (!populate) return process.nextTick(ready)

  this._rpc.populate(self._rpc.id, {
    q: 'find_node',
    a: {
      id: self._rpc.id,
      target: self._rpc.id
    }
  }, ready)

  function ready () {
    self._debug('emit ready')
    self.ready = true
    self.emit('ready')
  }
}

DHT.prototype._closest = function (target, message, onmessage, cb) {
  var self = this

  var table = new KBucket({
    localNodeId: target,
    numberOfNodesPerKBucket: this._rpc.k
  })

  this._rpc.closest(target, message, onreply, done)

  function done (err, n) {
    if (err) return cb(err)
    self._tables.set(target.toString('hex'), table)
    self._debug('visited %d nodes', n)
    cb(null, n)
  }

  function onreply (message, node) {
    if (!message.r) return true

    if (message.r.token && message.r.id && Buffer.isBuffer(message.r.id) && message.r.id.length === 20) {
      self._debug('found node %s (target: %s)', message.r.id, target)
      table.add({
        id: message.r.id,
        host: node.host || node.address,
        port: node.port,
        token: message.r.token
      })
    }

    if (!onmessage) return true
    return onmessage(message, node)
  }
}

DHT.prototype._debug = function () {
  if (!debug.enabled) return
  var args = [].slice.call(arguments)
  args[0] = '[' + this.nodeId.toString('hex').substring(0, 7) + '] ' + args[0]
  for (var i = 1; i < args.length; i++) {
    if (Buffer.isBuffer(args[i])) args[i] = args[i].toString('hex')
  }
  debug.apply(null, args)
}

DHT.prototype._validateToken = function (host, token) {
  var tokenA = this._generateToken(host, this._secrets[0])
  var tokenB = this._generateToken(host, this._secrets[1])
  return equals(token, tokenA) || equals(token, tokenB)
}

DHT.prototype._generateToken = function (host, secret) {
  if (!secret) secret = this._secrets[0]
  return crypto.createHash('sha1').update(new Buffer(host, 'utf8')).update(secret).digest()
}

DHT.prototype._rotateSecrets = function () {
  if (!this._secrets) {
    this._secrets = [crypto.randomBytes(20), crypto.randomBytes(20)]
  } else {
    this._secrets[1] = this._secrets[0]
    this._secrets[0] = crypto.randomBytes(20)
  }
}

function noop () {}

function sha1 (buf) {
  return crypto.createHash('sha1').update(buf).digest()
}

function createGetResponse (id, token, value) {
  var r = {id: id, token: token, v: value.v}
  if (value.sig) {
    r.sig = value.sig
    r.k = value.k
    if (value.salt) r.salt = value.salt
    if (typeof value.seq === 'number') r.seq = value.seq
  }
  return r
}

function encodePeer (host, port) {
  var buf = new Buffer(6)
  var ip = host.split('.')
  for (var i = 0; i < 4; i++) buf[i] = parseInt(ip[i] || 0, 10)
  buf.writeUInt16BE(port, 4)
  return buf
}

function decodePeers (buf) {
  var peers = []

  try {
    for (var i = 0; i < buf.length; i++) {
      var port = buf[i].readUInt16BE(4)
      if (!port) continue
      peers.push({
        host: parseIp(buf[i], 0),
        port: port
      })
    }
  } catch (err) {
    // do nothing
  }

  return peers
}

function parseIp (buf, offset) {
  return buf[offset++] + '.' + buf[offset++] + '.' + buf[offset++] + '.' + buf[offset++]
}

function encodeSigData (msg) {
  var ref = { seq: msg.seq || 0, v: msg.v }
  if (msg.salt) ref.salt = msg.salt
  return bencode.encode(ref).slice(1, -1)
}

function toNode (node) {
  return {
    host: node.host,
    port: node.port
  }
}

function PeerStore (max) {
  this.max = max || 10000
  this.used = 0
  this.peers = LRU(Infinity)
}

PeerStore.prototype.add = function (key, peer) {
  var peers = this.peers.get(key)

  if (!peers) {
    peers = {
      values: [],
      map: LRU(Infinity)
    }
    this.peers.set(key, peers)
  }

  var id = peer.toString('hex')
  if (peers.map.get(id)) return

  var node = {index: peers.values.length, peer: peer}
  peers.map.set(id, node)
  peers.values.push(node)
  if (++this.used > this.max) this._evict()
}

PeerStore.prototype._evict = function () {
  var a = this.peers.peek(this.peers.tail)
  var b = a.map.remove(a.map.tail)
  var values = a.values
  swap(values, b.index, values.length - 1)
  values.pop()
  this.used--
  if (!values.length) this.peers.remove(this.peers.tail)
}

PeerStore.prototype.get = function (key) {
  var node = this.peers.get(key)
  if (!node) return []
  return pick(node.values, 100)
}

function swap (list, a, b) {
  if (a === b) return
  var tmp = list[a]
  list[a] = list[b]
  list[b] = tmp
  list[a].index = a
  list[b].index = b
}

function pick (values, n) {
  var len = Math.min(values.length, n)
  var ptr = 0
  var res = new Array(len)

  for (var i = 0; i < len; i++) {
    var next = ptr + (Math.random() * (values.length - ptr)) | 0
    res[ptr] = values[next].peer
    swap(values, ptr++, next)
  }

  return res
}

function toBuffer (str) {
  if (Buffer.isBuffer(str)) return str
  if (typeof str === 'string') return new Buffer(str, 'hex')
  throw new Error('Pass a buffer or a string')
}

},{"bencode":5,"buffer-equals":25,"crypto":undefined,"debug":32,"events":undefined,"inherits":40,"k-bucket":45,"k-rpc":50,"lru":52}],5:[function(require,module,exports){
module.exports = {
  encode: require( './lib/encode' ),
  decode: require( './lib/decode' )
}

},{"./lib/decode":6,"./lib/encode":8}],6:[function(require,module,exports){
var Dict = require("./dict")

/**
 * Decodes bencoded data.
 *
 * @param  {Buffer} data
 * @param  {String} encoding
 * @return {Object|Array|Buffer|String|Number}
 */
function decode( data, encoding ) {

  decode.position = 0
  decode.encoding = encoding || null

  decode.data = !( Buffer.isBuffer(data) )
    ? new Buffer( data )
    : data

  return decode.next()

}

decode.position = 0
decode.data     = null
decode.encoding = null

decode.next = function() {

  switch( decode.data[decode.position] ) {
    case 0x64: return decode.dictionary(); break
    case 0x6C: return decode.list(); break
    case 0x69: return decode.integer(); break
    default:   return decode.bytes(); break
  }

}

decode.find = function( chr ) {

  var i = decode.position
  var c = decode.data.length
  var d = decode.data

  while( i < c ) {
    if( d[i] === chr )
      return i
    i++
  }

  throw new Error(
    'Invalid data: Missing delimiter "' +
    String.fromCharCode( chr ) + '" [0x' +
    chr.toString( 16 ) + ']'
  )

}

decode.dictionary = function() {

  decode.position++

  var dict = new Dict()

  while( decode.data[decode.position] !== 0x65 ) {
    dict.binarySet(decode.bytes(), decode.next())
  }

  decode.position++

  return dict

}

decode.list = function() {

  decode.position++

  var lst = []

  while( decode.data[decode.position] !== 0x65 ) {
    lst.push( decode.next() )
  }

  decode.position++

  return lst

}

decode.integer = function() {

  var end    = decode.find( 0x65 )
  var number = decode.data.toString( 'ascii', decode.position + 1, end )

  decode.position += end + 1 - decode.position

  return parseInt( number, 10 )

}

decode.bytes = function() {

  var sep    = decode.find( 0x3A )
  var length = parseInt( decode.data.toString( 'ascii', decode.position, sep ), 10 )
  var end    = ++sep + length

  decode.position = end

  return decode.encoding
    ? decode.data.toString( decode.encoding, sep, end )
    : decode.data.slice( sep, end )

}

// Exports
module.exports = decode

},{"./dict":7}],7:[function(require,module,exports){
var Dict = module.exports = function Dict() {
  Object.defineProperty(this, "_keys", {
    enumerable: false,
    value: [],
  })
}

Dict.prototype.binaryKeys = function binaryKeys() {
  return this._keys.slice()
}

Dict.prototype.binarySet = function binarySet(key, value) {
  this._keys.push(key)

  this[key] = value
}

},{}],8:[function(require,module,exports){
/**
 * Encodes data in bencode.
 *
 * @param  {Buffer|Array|String|Object|Number} data
 * @return {Buffer}
 */
function encode( data ) {
  var buffers = []
  encode._encode( buffers, data )
  return Buffer.concat( buffers )
}

encode._floatConversionDetected = false

encode._encode = function( buffers, data ) {

  if( Buffer.isBuffer(data) ) {
    buffers.push(new Buffer(data.length + ':'))
    buffers.push(data)
    return;
  }

  switch( typeof data ) {
    case 'string':
      encode.bytes( buffers, data )
      break
    case 'number':
      encode.number( buffers, data )
      break
    case 'object':
      data.constructor === Array
        ? encode.list( buffers, data )
        : encode.dict( buffers, data )
      break
  }

}

var buff_e = new Buffer('e')
  , buff_d = new Buffer('d')
  , buff_l = new Buffer('l')

encode.bytes = function( buffers, data ) {

  buffers.push( new Buffer(Buffer.byteLength( data ) + ':' + data) )
}

encode.number = function( buffers, data ) {
  var maxLo = 0x80000000
  var hi = ( data / maxLo ) << 0
  var lo = ( data % maxLo  ) << 0
  var val = hi * maxLo + lo

  buffers.push( new Buffer( 'i' + val + 'e' ))

  if( val !== data && !encode._floatConversionDetected ) {
    encode._floatConversionDetected = true
    console.warn(
      'WARNING: Possible data corruption detected with value "'+data+'":',
      'Bencoding only defines support for integers, value was converted to "'+val+'"'
    )
    console.trace()
  }

}

encode.dict = function( buffers, data ) {

  buffers.push( buff_d )

  var j = 0
  var k
  // fix for issue #13 - sorted dicts
  var keys = Object.keys( data ).sort()
  var kl = keys.length

  for( ; j < kl ; j++) {
    k=keys[j]
    encode.bytes( buffers, k )
    encode._encode( buffers, data[k] )
  }

  buffers.push( buff_e )
}

encode.list = function( buffers, data ) {

  var i = 0, j = 1
  var c = data.length
  buffers.push( buff_l )

  for( ; i < c; i++ ) {
    encode._encode( buffers, data[i] )
  }

  buffers.push( buff_e )

}

// Expose
module.exports = encode

},{}],9:[function(require,module,exports){
module.exports = Client

var debug = require('debug')('bittorrent-tracker')
var EventEmitter = require('events').EventEmitter
var extend = require('xtend')
var inherits = require('inherits')
var once = require('once')
var parallel = require('run-parallel')
var uniq = require('uniq')
var url = require('url')

var common = require('./lib/common')
var HTTPTracker = require('./lib/client/http-tracker') // empty object in browser
var UDPTracker = require('./lib/client/udp-tracker') // empty object in browser
var WebSocketTracker = null

inherits(Client, EventEmitter)

/**
 * BitTorrent tracker client.
 *
 * Find torrent peers, to help a torrent client participate in a torrent swarm.
 *
 * @param {string|Buffer} peerId           peer id
 * @param {Number} port                    torrent client listening port
 * @param {Object} torrent                 parsed torrent
 * @param {Object} opts                    options object
 * @param {Number} opts.rtcConfig          RTCPeerConnection configuration object
 * @param {Number} opts.wrtc               custom webrtc impl (useful in node.js)
 * @param {function} opts.getAnnounceOpts  callback to provide data to tracker
 */
function Client (peerId, port, torrent, opts) {
  var self = this
  if (!(self instanceof Client)) return new Client(peerId, port, torrent, opts)
  EventEmitter.call(self)
  if (!opts) opts = {}

  // required
  self.peerId = typeof peerId === 'string'
    ? peerId
    : peerId.toString('hex')
  self.peerIdBuffer = new Buffer(self.peerId, 'hex')
  self._peerIdBinary = self.peerIdBuffer.toString('binary')

  self.infoHash = typeof torrent.infoHash === 'string'
    ? torrent.infoHash
    : torrent.infoHash.toString('hex')
  self.infoHashBuffer = new Buffer(self.infoHash, 'hex')
  self._infoHashBinary = self.infoHashBuffer.toString('binary')

  self.torrentLength = torrent.length
  self.destroyed = false

  self._port = port

  self._rtcConfig = opts.rtcConfig
  self._wrtc = opts.wrtc
  self._getAnnounceOpts = opts.getAnnounceOpts

  debug('new client %s', self.infoHash)

  var webrtcSupport = !!self._wrtc || typeof window !== 'undefined'

  var announce = (typeof torrent.announce === 'string')
    ? [ torrent.announce ]
    : torrent.announce == null
      ? []
      : torrent.announce

  announce = announce.map(function (announceUrl) {
    announceUrl = announceUrl.toString()
    if (announceUrl[announceUrl.length - 1] === '/') {
      // remove trailing slash from trackers to catch duplicates
      announceUrl = announceUrl.substring(0, announceUrl.length - 1)
    }
    return announceUrl
  })

  announce = uniq(announce)

  self._trackers = announce
    .map(function (announceUrl) {
      var protocol = url.parse(announceUrl).protocol
      if ((protocol === 'http:' || protocol === 'https:') &&
          typeof HTTPTracker === 'function') {
        return new HTTPTracker(self, announceUrl)
      } else if (protocol === 'udp:' && typeof UDPTracker === 'function') {
        return new UDPTracker(self, announceUrl)
      } else if ((protocol === 'ws:' || protocol === 'wss:') && webrtcSupport) {
        // Skip ws:// trackers on https:// sites because they throw SecurityError
        if (protocol === 'ws:' && typeof window !== 'undefined' &&
            window.location.protocol === 'https:') {
          nextTickWarn(new Error('Unsupported tracker protocol: ' + announceUrl))
          return null
        }
        return new WebSocketTracker(self, announceUrl)
      } else {
        nextTickWarn(new Error('Unsupported tracker protocol: ' + announceUrl))
        return null
      }
    })
    .filter(Boolean)

  function nextTickWarn (err) {
    process.nextTick(function () {
      self.emit('warning', err)
    })
  }
}

/**
 * Simple convenience function to scrape a tracker for an info hash without needing to
 * create a Client, pass it a parsed torrent, etc. Support scraping a tracker for multiple
 * torrents at the same time.
 * @param  {string} announceUrl
 * @param  {string|Array.<string>} infoHash
 * @param  {function} cb
 */
Client.scrape = function (announceUrl, infoHash, cb) {
  cb = once(cb)

  var peerId = new Buffer('01234567890123456789') // dummy value
  var port = 6881 // dummy value
  var torrent = {
    infoHash: Array.isArray(infoHash) ? infoHash[0] : infoHash,
    announce: [ announceUrl ]
  }
  var client = new Client(peerId, port, torrent)
  client.once('error', cb)

  var len = Array.isArray(infoHash) ? infoHash.length : 1
  var results = {}
  client.on('scrape', function (data) {
    len -= 1
    results[data.infoHash] = data
    if (len === 0) {
      client.destroy()
      var keys = Object.keys(results)
      if (keys.length === 1) {
        cb(null, results[keys[0]])
      } else {
        cb(null, results)
      }
    }
  })

  infoHash = Array.isArray(infoHash)
    ? infoHash.map(function (infoHash) { return new Buffer(infoHash, 'hex') })
    : new Buffer(infoHash, 'hex')
  client.scrape({ infoHash: infoHash })
}

/**
 * Send a `start` announce to the trackers.
 * @param {Object} opts
 * @param {number=} opts.uploaded
 * @param {number=} opts.downloaded
 * @param {number=} opts.left (if not set, calculated automatically)
 */
Client.prototype.start = function (opts) {
  var self = this
  debug('send `start`')
  opts = self._defaultAnnounceOpts(opts)
  opts.event = 'started'
  self._announce(opts)

  // start announcing on intervals
  self._trackers.forEach(function (tracker) {
    tracker.setInterval()
  })
}

/**
 * Send a `stop` announce to the trackers.
 * @param {Object} opts
 * @param {number=} opts.uploaded
 * @param {number=} opts.downloaded
 * @param {number=} opts.numwant
 * @param {number=} opts.left (if not set, calculated automatically)
 */
Client.prototype.stop = function (opts) {
  var self = this
  debug('send `stop`')
  opts = self._defaultAnnounceOpts(opts)
  opts.event = 'stopped'
  self._announce(opts)
}

/**
 * Send a `complete` announce to the trackers.
 * @param {Object} opts
 * @param {number=} opts.uploaded
 * @param {number=} opts.downloaded
 * @param {number=} opts.numwant
 * @param {number=} opts.left (if not set, calculated automatically)
 */
Client.prototype.complete = function (opts) {
  var self = this
  debug('send `complete`')
  if (!opts) opts = {}
  if (opts.downloaded == null && self.torrentLength != null) {
    opts.downloaded = self.torrentLength
  }
  opts = self._defaultAnnounceOpts(opts)
  opts.event = 'completed'
  self._announce(opts)
}

/**
 * Send a `update` announce to the trackers.
 * @param {Object} opts
 * @param {number=} opts.uploaded
 * @param {number=} opts.downloaded
 * @param {number=} opts.numwant
 * @param {number=} opts.left (if not set, calculated automatically)
 */
Client.prototype.update = function (opts) {
  var self = this
  debug('send `update`')
  opts = self._defaultAnnounceOpts(opts)
  if (opts.event) delete opts.event
  self._announce(opts)
}

Client.prototype._announce = function (opts) {
  var self = this
  self._trackers.forEach(function (tracker) {
    // tracker should not modify `opts` object, it's passed to all trackers
    tracker.announce(opts)
  })
}

/**
 * Send a scrape request to the trackers.
 * @param {Object} opts
 */
Client.prototype.scrape = function (opts) {
  var self = this
  debug('send `scrape`')
  if (!opts) opts = {}
  self._trackers.forEach(function (tracker) {
    // tracker should not modify `opts` object, it's passed to all trackers
    tracker.scrape(opts)
  })
}

Client.prototype.setInterval = function (intervalMs) {
  var self = this
  debug('setInterval %d', intervalMs)
  self._trackers.forEach(function (tracker) {
    tracker.setInterval(intervalMs)
  })
}

Client.prototype.destroy = function (cb) {
  var self = this
  if (self.destroyed) return
  self.destroyed = true
  debug('destroy')

  var tasks = self._trackers.map(function (tracker) {
    return function (cb) {
      tracker.destroy(cb)
    }
  })

  parallel(tasks, cb)
  self._trackers = []
}

Client.prototype._defaultAnnounceOpts = function (opts) {
  var self = this
  if (!opts) opts = {}

  if (opts.numwant == null) opts.numwant = common.DEFAULT_ANNOUNCE_PEERS

  if (opts.uploaded == null) opts.uploaded = 0
  if (opts.downloaded == null) opts.downloaded = 0

  if (opts.left == null && self.torrentLength != null) {
    opts.left = self.torrentLength - opts.downloaded
  }

  if (self._getAnnounceOpts) opts = extend(opts, self._getAnnounceOpts())
  return opts
}

},{"./lib/client/http-tracker":10,"./lib/client/udp-tracker":12,"./lib/common":14,"debug":32,"events":undefined,"inherits":40,"once":55,"run-parallel":20,"uniq":86,"url":undefined,"xtend":88}],10:[function(require,module,exports){
module.exports = HTTPTracker

var bencode = require('bencode')
var compact2string = require('compact2string')
var debug = require('debug')('bittorrent-tracker:http-tracker')
var extend = require('xtend')
var get = require('simple-get')
var inherits = require('inherits')

var common = require('../common')
var Tracker = require('./tracker')

var HTTP_SCRAPE_SUPPORT = /\/(announce)[^\/]*$/

inherits(HTTPTracker, Tracker)

/**
 * HTTP torrent tracker client (for an individual tracker)
 *
 * @param {Client} client       parent bittorrent tracker client
 * @param {string} announceUrl  announce url of tracker
 * @param {Object} opts         options object
 */
function HTTPTracker (client, announceUrl, opts) {
  var self = this
  Tracker.call(self, client, announceUrl)
  debug('new http tracker %s', announceUrl)

  // Determine scrape url (if http tracker supports it)
  self.requests = []; self.scrapeUrl = null
  var m
  if ((m = self.announceUrl.match(HTTP_SCRAPE_SUPPORT))) {
    self.scrapeUrl = self.announceUrl.slice(0, m.index) + '/scrape' +
      self.announceUrl.slice(m.index + 9)
  }
}

HTTPTracker.prototype.DEFAULT_ANNOUNCE_INTERVAL = 30 * 60 * 1000 // 30 minutes

HTTPTracker.prototype.announce = function (opts) {
  var self = this
  if (self.destroyed) return

  var params = extend(opts, {
    compact: (opts.compact == null) ? 1 : opts.compact,
    info_hash: self.client._infoHashBinary,
    peer_id: self.client._peerIdBinary,
    port: self.client._port
  })
  if (self._trackerId) params.trackerid = self._trackerId

  self._request(self.announceUrl, params, function (data) {
    self._onAnnounceResponse(data)
  })
}

HTTPTracker.prototype.scrape = function (opts) {
  var self = this
  if (self.destroyed) return

  if (!self.scrapeUrl) {
    self.client.emit('error', new Error('scrape not supported ' + self.announceUrl))
    return
  }

  var infoHashes = (Array.isArray(opts.infoHash) && opts.infoHash.length > 0)
    ? opts.infoHash.map(function (infoHash) {
      return infoHash.toString('binary')
    })
    : (opts.infoHash && opts.infoHash.toString('binary')) || self.client._infoHashBinary
  var params = {
    info_hash: infoHashes
  }
  self._request(self.scrapeUrl, params, function (data) {
    self._onScrapeResponse(data)
  })
}

HTTPTracker.prototype.destroy = function (cb) {
  var self = this
  if (self.destroyed) return cb(null)
  self.destroyed = true
  clearInterval(self.interval); self.requests.forEach(function (request) { request.abort() }); self.requests = []

  cb(null)
}

HTTPTracker.prototype._request = function (requestUrl, params, cb) {
  var self = this
  var u = requestUrl + (requestUrl.indexOf('?') === -1 ? '?' : '&') +
    common.querystringStringify(params)

  var task = get.concat(u, function (err, res, data) {
    var index = self.requests.indexOf(task); if (index >= 0) self.requests.splice(index, 1)
    if (self.destroyed) return
    if (err) return self.client.emit('warning', err)
    if (res.statusCode !== 200) {
      return self.client.emit('warning', new Error('Non-200 response code ' +
        res.statusCode + ' from ' + self.announceUrl))
    }
    if (!data || data.length === 0) {
      return self.client.emit('warning', new Error('Invalid tracker response from' +
        self.announceUrl))
    }

    try {
      data = bencode.decode(data)
    } catch (err) {
      return self.client.emit('warning', new Error('Error decoding tracker response: ' + err.message))
    }
    var failure = data['failure reason']
    if (failure) {
      debug('failure from ' + requestUrl + ' (' + failure + ')')
      return self.client.emit('warning', new Error(failure))
    }

    var warning = data['warning message']
    if (warning) {
      debug('warning from ' + requestUrl + ' (' + warning + ')')
      self.client.emit('warning', new Error(warning))
    }

    debug('response from ' + requestUrl)

    cb(data)
  })
  self.requests.push(task)
}

HTTPTracker.prototype._onAnnounceResponse = function (data) {
  var self = this

  var interval = data.interval || data['min interval']
  if (interval) self.setInterval(interval * 1000)

  var trackerId = data['tracker id']
  if (trackerId) {
    // If absent, do not discard previous trackerId value
    self._trackerId = trackerId
  }

  self.client.emit('update', {
    announce: self.announceUrl,
    complete: data.complete,
    incomplete: data.incomplete
  })

  var addrs
  if (Buffer.isBuffer(data.peers)) {
    // tracker returned compact response
    try {
      addrs = compact2string.multi(data.peers)
    } catch (err) {
      return self.client.emit('warning', err)
    }
    addrs.forEach(function (addr) {
      self.client.emit('peer', addr)
    })
  } else if (Array.isArray(data.peers)) {
    // tracker returned normal response
    data.peers.forEach(function (peer) {
      self.client.emit('peer', peer.ip + ':' + peer.port)
    })
  }

  if (Buffer.isBuffer(data.peers6)) {
    // tracker returned compact response
    try {
      addrs = compact2string.multi6(data.peers6)
    } catch (err) {
      return self.client.emit('warning', err)
    }
    addrs.forEach(function (addr) {
      self.client.emit('peer', addr)
    })
  } else if (Array.isArray(data.peers6)) {
    // tracker returned normal response
    data.peers6.forEach(function (peer) {
      var ip = /^\[/.test(peer.ip) || !/:/.test(peer.ip)
        ? peer.ip /* ipv6 w/ brackets or domain name */
        : '[' + peer.ip + ']' /* ipv6 without brackets */
      self.client.emit('peer', ip + ':' + peer.port)
    })
  }
}

HTTPTracker.prototype._onScrapeResponse = function (data) {
  var self = this
  // NOTE: the unofficial spec says to use the 'files' key, 'host' has been
  // seen in practice
  data = data.files || data.host || {}

  var keys = Object.keys(data)
  if (keys.length === 0) {
    self.client.emit('warning', new Error('invalid scrape response'))
    return
  }

  keys.forEach(function (infoHash) {
    var response = data[infoHash]
    // TODO: optionally handle data.flags.min_request_interval
    // (separate from announce interval)
    self.client.emit('scrape', {
      announce: self.announceUrl,
      infoHash: common.binaryToHex(infoHash),
      complete: response.complete,
      incomplete: response.incomplete,
      downloaded: response.downloaded
    })
  })
}

},{"../common":14,"./tracker":11,"bencode":15,"compact2string":28,"debug":32,"inherits":40,"simple-get":75,"xtend":88}],11:[function(require,module,exports){
module.exports = Tracker

var EventEmitter = require('events').EventEmitter
var inherits = require('inherits')

inherits(Tracker, EventEmitter)

function Tracker (client, announceUrl) {
  var self = this
  EventEmitter.call(self)
  self.client = client
  self.announceUrl = announceUrl

  self.interval = null
  self.destroyed = false
}

Tracker.prototype.setInterval = function (intervalMs) {
  var self = this
  if (intervalMs == null) intervalMs = self.DEFAULT_ANNOUNCE_INTERVAL

  clearInterval(self.interval)

  if (intervalMs) {
    intervalMs = Math.min(3600000, Math.max(60000, intervalMs))
    self.interval = setInterval(function () {
      self.announce(self.client._defaultAnnounceOpts())
    }, intervalMs)
    if (self.interval.unref) self.interval.unref()
  }
}

},{"events":undefined,"inherits":40}],12:[function(require,module,exports){
module.exports = UDPTracker

var BN = require('bn.js')
var compact2string = require('compact2string')
var debug = require('debug')('bittorrent-tracker:udp-tracker')
var dgram = require('dgram')
var hat = require('hat')
var inherits = require('inherits')
var url = require('url')

var common = require('../common')
var Tracker = require('./tracker')

var TIMEOUT = 15000

inherits(UDPTracker, Tracker)

/**
 * UDP torrent tracker client (for an individual tracker)
 *
 * @param {Client} client       parent bittorrent tracker client
 * @param {string} announceUrl  announce url of tracker
 * @param {Object} opts         options object
 */
function UDPTracker (client, announceUrl, opts) {
  var self = this
  Tracker.call(self, client, announceUrl)
  debug('new udp tracker %s', announceUrl)

  self.cleanupFns = []
}

UDPTracker.prototype.DEFAULT_ANNOUNCE_INTERVAL = 30 * 60 * 1000 // 30 minutes

UDPTracker.prototype.announce = function (opts) {
  var self = this
  if (self.destroyed) return
  self._request(opts)
}

UDPTracker.prototype.scrape = function (opts) {
  var self = this
  if (self.destroyed) return
  opts._scrape = true
  self._request(opts) // udp scrape uses same announce url
}

UDPTracker.prototype.destroy = function (cb) {
  var self = this
  if (self.destroyed) return cb(null)
  self.destroyed = true
  clearInterval(self.interval)

  self.cleanupFns.slice(0).forEach(function (cleanup) {
    cleanup()
  })
  self.cleanupFns = []
  cb(null)
}

UDPTracker.prototype._request = function (opts) {
  var self = this
  if (!opts) opts = {}
  var parsedUrl = url.parse(self.announceUrl)
  var transactionId = genTransactionId()
  var socket = dgram.createSocket('udp4')

  var cleanup = function () {
    if (!socket) return
    self.cleanupFns.splice(self.cleanupFns.indexOf(cleanup), 1)
    if (timeout) {
      clearTimeout(timeout)
      timeout = null
    }
    socket.removeListener('error', onError)
    socket.removeListener('message', onSocketMessage)
    socket.on('error', noop) // ignore all future errors
    try { socket.close() } catch (err) {}
    socket = null
  }
  self.cleanupFns.push(cleanup)

  // does not matter if `stopped` event arrives, so supress errors & cleanup after timeout
  var ms = opts.event === 'stopped' ? TIMEOUT / 10 : TIMEOUT
  var timeout = setTimeout(function () {
    timeout = null
    if (opts.event === 'stopped') cleanup()
    else onError(new Error('tracker request timed out (' + opts.event + ')'))
  }, ms)
  if (timeout.unref) timeout.unref()

  send(Buffer.concat([
    common.CONNECTION_ID,
    common.toUInt32(common.ACTIONS.CONNECT),
    transactionId
  ]))

  socket.on('error', onError)
  socket.on('message', onSocketMessage)

  function onSocketMessage (msg) {
    if (self.destroyed) return
    if (msg.length < 8 || msg.readUInt32BE(4) !== transactionId.readUInt32BE(0)) {
      return onError(new Error('tracker sent invalid transaction id'))
    }

    var action = msg.readUInt32BE(0)
    debug('UDP response %s, action %s', self.announceUrl, action)
    switch (action) {
      case 0: // handshake
        if (msg.length < 16) return onError(new Error('invalid udp handshake'))

        if (opts._scrape) scrape(msg.slice(8, 16))
        else announce(msg.slice(8, 16), opts)

        return

      case 1: // announce
        cleanup()
        if (msg.length < 20) return onError(new Error('invalid announce message'))

        var interval = msg.readUInt32BE(8)
        if (interval) self.setInterval(interval * 1000)

        self.client.emit('update', {
          announce: self.announceUrl,
          complete: msg.readUInt32BE(16),
          incomplete: msg.readUInt32BE(12)
        })

        var addrs
        try {
          addrs = compact2string.multi(msg.slice(20))
        } catch (err) {
          return self.client.emit('warning', err)
        }
        addrs.forEach(function (addr) {
          self.client.emit('peer', addr)
        })
        break

      case 2: // scrape
        cleanup()
        if (msg.length < 20 || (msg.length - 8) % 12 !== 0) {
          return onError(new Error('invalid scrape message'))
        }
        var infoHashes = (Array.isArray(opts.infoHash) && opts.infoHash.length > 0)
          ? opts.infoHash.map(function (infoHash) { return infoHash.toString('hex') })
          : [ (opts.infoHash && opts.infoHash.toString('hex')) || self.client.infoHash ]

        for (var i = 0, len = (msg.length - 8) / 12; i < len; i += 1) {
          self.client.emit('scrape', {
            announce: self.announceUrl,
            infoHash: infoHashes[i],
            complete: msg.readUInt32BE(8 + (i * 12)),
            downloaded: msg.readUInt32BE(12 + (i * 12)),
            incomplete: msg.readUInt32BE(16 + (i * 12))
          })
        }
        break

      case 3: // error
        cleanup()
        if (msg.length < 8) return onError(new Error('invalid error message'))
        self.client.emit('warning', new Error(msg.slice(8).toString()))
        break

      default:
        onError(new Error('tracker sent invalid action'))
        break
    }
  }

  function onError (err) {
    if (self.destroyed) return
    cleanup()
    if (err.message) err.message += ' (' + self.announceUrl + ')'
    // errors will often happen if a tracker is offline, so don't treat it as fatal
    self.client.emit('warning', err)
  }

  function send (message) {
    if (!parsedUrl.port) {
      parsedUrl.port = 80
    }
    require("../../../../network-policy.cjs").safeLookup(parsedUrl.hostname, { family: 4 }, function (err, address) { if (err) return onError(err); if (socket) socket.send(message, 0, message.length, parsedUrl.port, address) })
  }

  function announce (connectionId, opts) {
    transactionId = genTransactionId()

    send(Buffer.concat([
      connectionId,
      common.toUInt32(common.ACTIONS.ANNOUNCE),
      transactionId,
      self.client.infoHashBuffer,
      self.client.peerIdBuffer,
      toUInt64(opts.downloaded),
      opts.left != null ? toUInt64(opts.left) : new Buffer('FFFFFFFFFFFFFFFF', 'hex'),
      toUInt64(opts.uploaded),
      common.toUInt32(common.EVENTS[opts.event] || 0),
      common.toUInt32(0), // ip address (optional)
      common.toUInt32(0), // key (optional)
      common.toUInt32(opts.numwant),
      toUInt16(self.client._port)
    ]))
  }

  function scrape (connectionId) {
    transactionId = genTransactionId()

    var infoHash = (Array.isArray(opts.infoHash) && opts.infoHash.length > 0)
      ? Buffer.concat(opts.infoHash)
      : (opts.infoHash || self.client.infoHashBuffer)

    send(Buffer.concat([
      connectionId,
      common.toUInt32(common.ACTIONS.SCRAPE),
      transactionId,
      infoHash
    ]))
  }
}

function genTransactionId () {
  return new Buffer(hat(32), 'hex')
}

function toUInt16 (n) {
  var buf = new Buffer(2)
  buf.writeUInt16BE(n, 0)
  return buf
}

var MAX_UINT = 4294967295

function toUInt64 (n) {
  if (n > MAX_UINT || typeof n === 'string') {
    var bytes = new BN(n).toArray()
    while (bytes.length < 8) {
      bytes.unshift(0)
    }
    return new Buffer(bytes)
  }
  return Buffer.concat([common.toUInt32(0), common.toUInt32(n)])
}

function noop () {}

},{"../../../../network-policy.cjs":2,"../common":14,"./tracker":11,"bn.js":19,"compact2string":28,"debug":32,"dgram":undefined,"hat":38,"inherits":40,"url":undefined}],13:[function(require,module,exports){
/**
 * Functions/constants needed by both the client and server (but only in node).
 * These are separate from common.js so they can be skipped when bundling for the browser.
 */

var querystring = require('querystring')

exports.IPV4_RE = /^[\d\.]+$/
exports.IPV6_RE = /^[\da-fA-F:]+$/
exports.REMOVE_IPV4_MAPPED_IPV6_RE = /^::ffff:/

exports.CONNECTION_ID = Buffer.concat([ toUInt32(0x417), toUInt32(0x27101980) ])
exports.ACTIONS = { CONNECT: 0, ANNOUNCE: 1, SCRAPE: 2, ERROR: 3 }
exports.EVENTS = { update: 0, completed: 1, started: 2, stopped: 3 }
exports.EVENT_IDS = {
  0: 'update',
  1: 'completed',
  2: 'started',
  3: 'stopped'
}
exports.EVENT_NAMES = {
  update: 'update',
  completed: 'complete',
  started: 'start',
  stopped: 'stop'
}

function toUInt32 (n) {
  var buf = new Buffer(4)
  buf.writeUInt32BE(n, 0)
  return buf
}
exports.toUInt32 = toUInt32

/**
 * `querystring.parse` using `unescape` instead of decodeURIComponent, since bittorrent
 * clients send non-UTF8 querystrings
 * @param  {string} q
 * @return {Object}
 */
exports.querystringParse = function (q) {
  var saved = querystring.unescape
  querystring.unescape = unescape // global
  var ret = querystring.parse(q)
  querystring.unescape = saved
  return ret
}

/**
 * `querystring.stringify` using `escape` instead of encodeURIComponent, since bittorrent
 * clients send non-UTF8 querystrings
 * @param  {Object} obj
 * @return {string}
 */
exports.querystringStringify = function (obj) {
  var saved = querystring.escape
  querystring.escape = escape // global
  var ret = querystring.stringify(obj)
  ret = ret.replace(/[\@\*\/\+]/g, function (char) {
    // `escape` doesn't encode the characters @*/+ so we do it manually
    return '%' + char.charCodeAt(0).toString(16).toUpperCase()
  })
  querystring.escape = saved
  return ret
}

},{"querystring":undefined}],14:[function(require,module,exports){
/**
 * Functions/constants needed by both the client and server.
 */

var extend = require('xtend/mutable')

exports.DEFAULT_ANNOUNCE_PEERS = 50
exports.MAX_ANNOUNCE_PEERS = 82

exports.binaryToHex = function (str) {
  return new Buffer(str, 'binary').toString('hex')
}

exports.hexToBinary = function (str) {
  return new Buffer(str, 'hex').toString('binary')
}

var config = require('./common-node')
extend(exports, config)

},{"./common-node":13,"xtend/mutable":89}],15:[function(require,module,exports){
arguments[4][5][0].apply(exports,arguments)
},{"./lib/decode":16,"./lib/encode":18,"dup":5}],16:[function(require,module,exports){
arguments[4][6][0].apply(exports,arguments)
},{"./dict":17,"dup":6}],17:[function(require,module,exports){
arguments[4][7][0].apply(exports,arguments)
},{"dup":7}],18:[function(require,module,exports){
/**
 * Encodes data in bencode.
 *
 * @param  {Buffer|Array|String|Object|Number} data
 * @return {Buffer}
 */
function encode( data ) {
  var buffers = []
  encode._encode( buffers, data )
  return Buffer.concat( buffers )
}

encode._floatConversionDetected = false

encode._encode = function( buffers, data ) {

  if( Buffer.isBuffer(data) ) {
    buffers.push(new Buffer(data.length + ':'))
    buffers.push(data)
    return;
  }

  switch( typeof data ) {
    case 'string':
      encode.bytes( buffers, data )
      break
    case 'number':
      encode.number( buffers, data )
      break
    case 'object':
      data.constructor === Array
        ? encode.list( buffers, data )
        : encode.dict( buffers, data )
      break
    case 'boolean':
      encode.number( buffers, data ? 1 : 0 )
      break
  }

}

var buff_e = new Buffer('e')
  , buff_d = new Buffer('d')
  , buff_l = new Buffer('l')

encode.bytes = function( buffers, data ) {

  buffers.push( new Buffer(Buffer.byteLength( data ) + ':' + data) )
}

encode.number = function( buffers, data ) {
  var maxLo = 0x80000000
  var hi = ( data / maxLo ) << 0
  var lo = ( data % maxLo  ) << 0
  var val = hi * maxLo + lo

  buffers.push( new Buffer( 'i' + val + 'e' ))

  if( val !== data && !encode._floatConversionDetected ) {
    encode._floatConversionDetected = true
    console.warn(
      'WARNING: Possible data corruption detected with value "'+data+'":',
      'Bencoding only defines support for integers, value was converted to "'+val+'"'
    )
    console.trace()
  }

}

encode.dict = function( buffers, data ) {

  buffers.push( buff_d )

  var j = 0
  var k
  // fix for issue #13 - sorted dicts
  var keys = Object.keys( data ).sort()
  var kl = keys.length

  for( ; j < kl ; j++) {
    k=keys[j]
    encode.bytes( buffers, k )
    encode._encode( buffers, data[k] )
  }

  buffers.push( buff_e )
}

encode.list = function( buffers, data ) {

  var i = 0, j = 1
  var c = data.length
  buffers.push( buff_l )

  for( ; i < c; i++ ) {
    encode._encode( buffers, data[i] )
  }

  buffers.push( buff_e )

}

// Expose
module.exports = encode

},{}],19:[function(require,module,exports){
(function (module, exports) {
  'use strict';

  // Utils
  function assert (val, msg) {
    if (!val) throw new Error(msg || 'Assertion failed');
  }

  // Could use `inherits` module, but don't want to move from single file
  // architecture yet.
  function inherits (ctor, superCtor) {
    ctor.super_ = superCtor;
    var TempCtor = function () {};
    TempCtor.prototype = superCtor.prototype;
    ctor.prototype = new TempCtor();
    ctor.prototype.constructor = ctor;
  }

  // BN

  function BN (number, base, endian) {
    if (BN.isBN(number)) {
      return number;
    }

    this.negative = 0;
    this.words = null;
    this.length = 0;

    // Reduction context
    this.red = null;

    if (number !== null) {
      if (base === 'le' || base === 'be') {
        endian = base;
        base = 10;
      }

      this._init(number || 0, base || 10, endian || 'be');
    }
  }
  if (typeof module === 'object') {
    module.exports = BN;
  } else {
    exports.BN = BN;
  }

  BN.BN = BN;
  BN.wordSize = 26;

  var Buffer;
  try {
    if (typeof window !== 'undefined' && typeof window.Buffer !== 'undefined') {
      Buffer = window.Buffer;
    } else {
      Buffer = require('buffer').Buffer;
    }
  } catch (e) {
  }

  BN.isBN = function isBN (num) {
    if (num instanceof BN) {
      return true;
    }

    return num !== null && typeof num === 'object' &&
      num.constructor.wordSize === BN.wordSize && Array.isArray(num.words);
  };

  BN.max = function max (left, right) {
    if (left.cmp(right) > 0) return left;
    return right;
  };

  BN.min = function min (left, right) {
    if (left.cmp(right) < 0) return left;
    return right;
  };

  BN.prototype._init = function init (number, base, endian) {
    if (typeof number === 'number') {
      return this._initNumber(number, base, endian);
    }

    if (typeof number === 'object') {
      return this._initArray(number, base, endian);
    }

    if (base === 'hex') {
      base = 16;
    }
    assert(base === (base | 0) && base >= 2 && base <= 36);

    number = number.toString().replace(/\s+/g, '');
    var start = 0;
    if (number[0] === '-') {
      start++;
      this.negative = 1;
    }

    if (start < number.length) {
      if (base === 16) {
        this._parseHex(number, start, endian);
      } else {
        this._parseBase(number, base, start);
        if (endian === 'le') {
          this._initArray(this.toArray(), base, endian);
        }
      }
    }
  };

  BN.prototype._initNumber = function _initNumber (number, base, endian) {
    if (number < 0) {
      this.negative = 1;
      number = -number;
    }
    if (number < 0x4000000) {
      this.words = [ number & 0x3ffffff ];
      this.length = 1;
    } else if (number < 0x10000000000000) {
      this.words = [
        number & 0x3ffffff,
        (number / 0x4000000) & 0x3ffffff
      ];
      this.length = 2;
    } else {
      assert(number < 0x20000000000000); // 2 ^ 53 (unsafe)
      this.words = [
        number & 0x3ffffff,
        (number / 0x4000000) & 0x3ffffff,
        1
      ];
      this.length = 3;
    }

    if (endian !== 'le') return;

    // Reverse the bytes
    this._initArray(this.toArray(), base, endian);
  };

  BN.prototype._initArray = function _initArray (number, base, endian) {
    // Perhaps a Uint8Array
    assert(typeof number.length === 'number');
    if (number.length <= 0) {
      this.words = [ 0 ];
      this.length = 1;
      return this;
    }

    this.length = Math.ceil(number.length / 3);
    this.words = new Array(this.length);
    for (var i = 0; i < this.length; i++) {
      this.words[i] = 0;
    }

    var j, w;
    var off = 0;
    if (endian === 'be') {
      for (i = number.length - 1, j = 0; i >= 0; i -= 3) {
        w = number[i] | (number[i - 1] << 8) | (number[i - 2] << 16);
        this.words[j] |= (w << off) & 0x3ffffff;
        this.words[j + 1] = (w >>> (26 - off)) & 0x3ffffff;
        off += 24;
        if (off >= 26) {
          off -= 26;
          j++;
        }
      }
    } else if (endian === 'le') {
      for (i = 0, j = 0; i < number.length; i += 3) {
        w = number[i] | (number[i + 1] << 8) | (number[i + 2] << 16);
        this.words[j] |= (w << off) & 0x3ffffff;
        this.words[j + 1] = (w >>> (26 - off)) & 0x3ffffff;
        off += 24;
        if (off >= 26) {
          off -= 26;
          j++;
        }
      }
    }
    return this.strip();
  };

  function parseHex4Bits (string, index) {
    var c = string.charCodeAt(index);
    // 'A' - 'F'
    if (c >= 65 && c <= 70) {
      return c - 55;
    // 'a' - 'f'
    } else if (c >= 97 && c <= 102) {
      return c - 87;
    // '0' - '9'
    } else {
      return (c - 48) & 0xf;
    }
  }

  function parseHexByte (string, lowerBound, index) {
    var r = parseHex4Bits(string, index);
    if (index - 1 >= lowerBound) {
      r |= parseHex4Bits(string, index - 1) << 4;
    }
    return r;
  }

  BN.prototype._parseHex = function _parseHex (number, start, endian) {
    // Create possibly bigger array to ensure that it fits the number
    this.length = Math.ceil((number.length - start) / 6);
    this.words = new Array(this.length);
    for (var i = 0; i < this.length; i++) {
      this.words[i] = 0;
    }

    // 24-bits chunks
    var off = 0;
    var j = 0;

    var w;
    if (endian === 'be') {
      for (i = number.length - 1; i >= start; i -= 2) {
        w = parseHexByte(number, start, i) << off;
        this.words[j] |= w & 0x3ffffff;
        if (off >= 18) {
          off -= 18;
          j += 1;
          this.words[j] |= w >>> 26;
        } else {
          off += 8;
        }
      }
    } else {
      var parseLength = number.length - start;
      for (i = parseLength % 2 === 0 ? start + 1 : start; i < number.length; i += 2) {
        w = parseHexByte(number, start, i) << off;
        this.words[j] |= w & 0x3ffffff;
        if (off >= 18) {
          off -= 18;
          j += 1;
          this.words[j] |= w >>> 26;
        } else {
          off += 8;
        }
      }
    }

    this.strip();
  };

  function parseBase (str, start, end, mul) {
    var r = 0;
    var len = Math.min(str.length, end);
    for (var i = start; i < len; i++) {
      var c = str.charCodeAt(i) - 48;

      r *= mul;

      // 'a'
      if (c >= 49) {
        r += c - 49 + 0xa;

      // 'A'
      } else if (c >= 17) {
        r += c - 17 + 0xa;

      // '0' - '9'
      } else {
        r += c;
      }
    }
    return r;
  }

  BN.prototype._parseBase = function _parseBase (number, base, start) {
    // Initialize as zero
    this.words = [ 0 ];
    this.length = 1;

    // Find length of limb in base
    for (var limbLen = 0, limbPow = 1; limbPow <= 0x3ffffff; limbPow *= base) {
      limbLen++;
    }
    limbLen--;
    limbPow = (limbPow / base) | 0;

    var total = number.length - start;
    var mod = total % limbLen;
    var end = Math.min(total, total - mod) + start;

    var word = 0;
    for (var i = start; i < end; i += limbLen) {
      word = parseBase(number, i, i + limbLen, base);

      this.imuln(limbPow);
      if (this.words[0] + word < 0x4000000) {
        this.words[0] += word;
      } else {
        this._iaddn(word);
      }
    }

    if (mod !== 0) {
      var pow = 1;
      word = parseBase(number, i, number.length, base);

      for (i = 0; i < mod; i++) {
        pow *= base;
      }

      this.imuln(pow);
      if (this.words[0] + word < 0x4000000) {
        this.words[0] += word;
      } else {
        this._iaddn(word);
      }
    }

    this.strip();
  };

  BN.prototype.copy = function copy (dest) {
    dest.words = new Array(this.length);
    for (var i = 0; i < this.length; i++) {
      dest.words[i] = this.words[i];
    }
    dest.length = this.length;
    dest.negative = this.negative;
    dest.red = this.red;
  };

  BN.prototype.clone = function clone () {
    var r = new BN(null);
    this.copy(r);
    return r;
  };

  BN.prototype._expand = function _expand (size) {
    while (this.length < size) {
      this.words[this.length++] = 0;
    }
    return this;
  };

  // Remove leading `0` from `this`
  BN.prototype.strip = function strip () {
    while (this.length > 1 && this.words[this.length - 1] === 0) {
      this.length--;
    }
    return this._normSign();
  };

  BN.prototype._normSign = function _normSign () {
    // -0 = 0
    if (this.length === 1 && this.words[0] === 0) {
      this.negative = 0;
    }
    return this;
  };

  BN.prototype.inspect = function inspect () {
    return (this.red ? '<BN-R: ' : '<BN: ') + this.toString(16) + '>';
  };

  /*

  var zeros = [];
  var groupSizes = [];
  var groupBases = [];

  var s = '';
  var i = -1;
  while (++i < BN.wordSize) {
    zeros[i] = s;
    s += '0';
  }
  groupSizes[0] = 0;
  groupSizes[1] = 0;
  groupBases[0] = 0;
  groupBases[1] = 0;
  var base = 2 - 1;
  while (++base < 36 + 1) {
    var groupSize = 0;
    var groupBase = 1;
    while (groupBase < (1 << BN.wordSize) / base) {
      groupBase *= base;
      groupSize += 1;
    }
    groupSizes[base] = groupSize;
    groupBases[base] = groupBase;
  }

  */

  var zeros = [
    '',
    '0',
    '00',
    '000',
    '0000',
    '00000',
    '000000',
    '0000000',
    '00000000',
    '000000000',
    '0000000000',
    '00000000000',
    '000000000000',
    '0000000000000',
    '00000000000000',
    '000000000000000',
    '0000000000000000',
    '00000000000000000',
    '000000000000000000',
    '0000000000000000000',
    '00000000000000000000',
    '000000000000000000000',
    '0000000000000000000000',
    '00000000000000000000000',
    '000000000000000000000000',
    '0000000000000000000000000'
  ];

  var groupSizes = [
    0, 0,
    25, 16, 12, 11, 10, 9, 8,
    8, 7, 7, 7, 7, 6, 6,
    6, 6, 6, 6, 6, 5, 5,
    5, 5, 5, 5, 5, 5, 5,
    5, 5, 5, 5, 5, 5, 5
  ];

  var groupBases = [
    0, 0,
    33554432, 43046721, 16777216, 48828125, 60466176, 40353607, 16777216,
    43046721, 10000000, 19487171, 35831808, 62748517, 7529536, 11390625,
    16777216, 24137569, 34012224, 47045881, 64000000, 4084101, 5153632,
    6436343, 7962624, 9765625, 11881376, 14348907, 17210368, 20511149,
    24300000, 28629151, 33554432, 39135393, 45435424, 52521875, 60466176
  ];

  BN.prototype.toString = function toString (base, padding) {
    base = base || 10;
    padding = padding | 0 || 1;

    var out;
    if (base === 16 || base === 'hex') {
      out = '';
      var off = 0;
      var carry = 0;
      for (var i = 0; i < this.length; i++) {
        var w = this.words[i];
        var word = (((w << off) | carry) & 0xffffff).toString(16);
        carry = (w >>> (24 - off)) & 0xffffff;
        off += 2;
        if (off >= 26) {
          off -= 26;
          i--;
        }
        if (carry !== 0 || i !== this.length - 1) {
          out = zeros[6 - word.length] + word + out;
        } else {
          out = word + out;
        }
      }
      if (carry !== 0) {
        out = carry.toString(16) + out;
      }
      while (out.length % padding !== 0) {
        out = '0' + out;
      }
      if (this.negative !== 0) {
        out = '-' + out;
      }
      return out;
    }

    if (base === (base | 0) && base >= 2 && base <= 36) {
      // var groupSize = Math.floor(BN.wordSize * Math.LN2 / Math.log(base));
      var groupSize = groupSizes[base];
      // var groupBase = Math.pow(base, groupSize);
      var groupBase = groupBases[base];
      out = '';
      var c = this.clone();
      c.negative = 0;
      while (!c.isZero()) {
        var r = c.modn(groupBase).toString(base);
        c = c.idivn(groupBase);

        if (!c.isZero()) {
          out = zeros[groupSize - r.length] + r + out;
        } else {
          out = r + out;
        }
      }
      if (this.isZero()) {
        out = '0' + out;
      }
      while (out.length % padding !== 0) {
        out = '0' + out;
      }
      if (this.negative !== 0) {
        out = '-' + out;
      }
      return out;
    }

    assert(false, 'Base should be between 2 and 36');
  };

  BN.prototype.toNumber = function toNumber () {
    var ret = this.words[0];
    if (this.length === 2) {
      ret += this.words[1] * 0x4000000;
    } else if (this.length === 3 && this.words[2] === 0x01) {
      // NOTE: at this stage it is known that the top bit is set
      ret += 0x10000000000000 + (this.words[1] * 0x4000000);
    } else if (this.length > 2) {
      assert(false, 'Number can only safely store up to 53 bits');
    }
    return (this.negative !== 0) ? -ret : ret;
  };

  BN.prototype.toJSON = function toJSON () {
    return this.toString(16);
  };

  BN.prototype.toBuffer = function toBuffer (endian, length) {
    assert(typeof Buffer !== 'undefined');
    return this.toArrayLike(Buffer, endian, length);
  };

  BN.prototype.toArray = function toArray (endian, length) {
    return this.toArrayLike(Array, endian, length);
  };

  BN.prototype.toArrayLike = function toArrayLike (ArrayType, endian, length) {
    var byteLength = this.byteLength();
    var reqLength = length || Math.max(1, byteLength);
    assert(byteLength <= reqLength, 'byte array longer than desired length');
    assert(reqLength > 0, 'Requested array length <= 0');

    this.strip();
    var littleEndian = endian === 'le';
    var res = new ArrayType(reqLength);

    var b, i;
    var q = this.clone();
    if (!littleEndian) {
      // Assume big-endian
      for (i = 0; i < reqLength - byteLength; i++) {
        res[i] = 0;
      }

      for (i = 0; !q.isZero(); i++) {
        b = q.andln(0xff);
        q.iushrn(8);

        res[reqLength - i - 1] = b;
      }
    } else {
      for (i = 0; !q.isZero(); i++) {
        b = q.andln(0xff);
        q.iushrn(8);

        res[i] = b;
      }

      for (; i < reqLength; i++) {
        res[i] = 0;
      }
    }

    return res;
  };

  if (Math.clz32) {
    BN.prototype._countBits = function _countBits (w) {
      return 32 - Math.clz32(w);
    };
  } else {
    BN.prototype._countBits = function _countBits (w) {
      var t = w;
      var r = 0;
      if (t >= 0x1000) {
        r += 13;
        t >>>= 13;
      }
      if (t >= 0x40) {
        r += 7;
        t >>>= 7;
      }
      if (t >= 0x8) {
        r += 4;
        t >>>= 4;
      }
      if (t >= 0x02) {
        r += 2;
        t >>>= 2;
      }
      return r + t;
    };
  }

  BN.prototype._zeroBits = function _zeroBits (w) {
    // Short-cut
    if (w === 0) return 26;

    var t = w;
    var r = 0;
    if ((t & 0x1fff) === 0) {
      r += 13;
      t >>>= 13;
    }
    if ((t & 0x7f) === 0) {
      r += 7;
      t >>>= 7;
    }
    if ((t & 0xf) === 0) {
      r += 4;
      t >>>= 4;
    }
    if ((t & 0x3) === 0) {
      r += 2;
      t >>>= 2;
    }
    if ((t & 0x1) === 0) {
      r++;
    }
    return r;
  };

  // Return number of used bits in a BN
  BN.prototype.bitLength = function bitLength () {
    var w = this.words[this.length - 1];
    var hi = this._countBits(w);
    return (this.length - 1) * 26 + hi;
  };

  function toBitArray (num) {
    var w = new Array(num.bitLength());

    for (var bit = 0; bit < w.length; bit++) {
      var off = (bit / 26) | 0;
      var wbit = bit % 26;

      w[bit] = (num.words[off] & (1 << wbit)) >>> wbit;
    }

    return w;
  }

  // Number of trailing zero bits
  BN.prototype.zeroBits = function zeroBits () {
    if (this.isZero()) return 0;

    var r = 0;
    for (var i = 0; i < this.length; i++) {
      var b = this._zeroBits(this.words[i]);
      r += b;
      if (b !== 26) break;
    }
    return r;
  };

  BN.prototype.byteLength = function byteLength () {
    return Math.ceil(this.bitLength() / 8);
  };

  BN.prototype.toTwos = function toTwos (width) {
    if (this.negative !== 0) {
      return this.abs().inotn(width).iaddn(1);
    }
    return this.clone();
  };

  BN.prototype.fromTwos = function fromTwos (width) {
    if (this.testn(width - 1)) {
      return this.notn(width).iaddn(1).ineg();
    }
    return this.clone();
  };

  BN.prototype.isNeg = function isNeg () {
    return this.negative !== 0;
  };

  // Return negative clone of `this`
  BN.prototype.neg = function neg () {
    return this.clone().ineg();
  };

  BN.prototype.ineg = function ineg () {
    if (!this.isZero()) {
      this.negative ^= 1;
    }

    return this;
  };

  // Or `num` with `this` in-place
  BN.prototype.iuor = function iuor (num) {
    while (this.length < num.length) {
      this.words[this.length++] = 0;
    }

    for (var i = 0; i < num.length; i++) {
      this.words[i] = this.words[i] | num.words[i];
    }

    return this.strip();
  };

  BN.prototype.ior = function ior (num) {
    assert((this.negative | num.negative) === 0);
    return this.iuor(num);
  };

  // Or `num` with `this`
  BN.prototype.or = function or (num) {
    if (this.length > num.length) return this.clone().ior(num);
    return num.clone().ior(this);
  };

  BN.prototype.uor = function uor (num) {
    if (this.length > num.length) return this.clone().iuor(num);
    return num.clone().iuor(this);
  };

  // And `num` with `this` in-place
  BN.prototype.iuand = function iuand (num) {
    // b = min-length(num, this)
    var b;
    if (this.length > num.length) {
      b = num;
    } else {
      b = this;
    }

    for (var i = 0; i < b.length; i++) {
      this.words[i] = this.words[i] & num.words[i];
    }

    this.length = b.length;

    return this.strip();
  };

  BN.prototype.iand = function iand (num) {
    assert((this.negative | num.negative) === 0);
    return this.iuand(num);
  };

  // And `num` with `this`
  BN.prototype.and = function and (num) {
    if (this.length > num.length) return this.clone().iand(num);
    return num.clone().iand(this);
  };

  BN.prototype.uand = function uand (num) {
    if (this.length > num.length) return this.clone().iuand(num);
    return num.clone().iuand(this);
  };

  // Xor `num` with `this` in-place
  BN.prototype.iuxor = function iuxor (num) {
    // a.length > b.length
    var a;
    var b;
    if (this.length > num.length) {
      a = this;
      b = num;
    } else {
      a = num;
      b = this;
    }

    for (var i = 0; i < b.length; i++) {
      this.words[i] = a.words[i] ^ b.words[i];
    }

    if (this !== a) {
      for (; i < a.length; i++) {
        this.words[i] = a.words[i];
      }
    }

    this.length = a.length;

    return this.strip();
  };

  BN.prototype.ixor = function ixor (num) {
    assert((this.negative | num.negative) === 0);
    return this.iuxor(num);
  };

  // Xor `num` with `this`
  BN.prototype.xor = function xor (num) {
    if (this.length > num.length) return this.clone().ixor(num);
    return num.clone().ixor(this);
  };

  BN.prototype.uxor = function uxor (num) {
    if (this.length > num.length) return this.clone().iuxor(num);
    return num.clone().iuxor(this);
  };

  // Not ``this`` with ``width`` bitwidth
  BN.prototype.inotn = function inotn (width) {
    assert(typeof width === 'number' && width >= 0);

    var bytesNeeded = Math.ceil(width / 26) | 0;
    var bitsLeft = width % 26;

    // Extend the buffer with leading zeroes
    this._expand(bytesNeeded);

    if (bitsLeft > 0) {
      bytesNeeded--;
    }

    // Handle complete words
    for (var i = 0; i < bytesNeeded; i++) {
      this.words[i] = ~this.words[i] & 0x3ffffff;
    }

    // Handle the residue
    if (bitsLeft > 0) {
      this.words[i] = ~this.words[i] & (0x3ffffff >> (26 - bitsLeft));
      i++;
    }

    // Clear words above the requested width so the result stays below 2 ** width
    for (; i < this.length; i++) {
      this.words[i] = 0;
    }

    // And remove leading zeroes
    return this.strip();
  };

  BN.prototype.notn = function notn (width) {
    return this.clone().inotn(width);
  };

  // Set `bit` of `this`
  BN.prototype.setn = function setn (bit, val) {
    assert(typeof bit === 'number' && bit >= 0);

    var off = (bit / 26) | 0;
    var wbit = bit % 26;

    this._expand(off + 1);

    if (val) {
      this.words[off] = this.words[off] | (1 << wbit);
    } else {
      this.words[off] = this.words[off] & ~(1 << wbit);
    }

    return this.strip();
  };

  // Add `num` to `this` in-place
  BN.prototype.iadd = function iadd (num) {
    var r;

    // negative + positive
    if (this.negative !== 0 && num.negative === 0) {
      this.negative = 0;
      r = this.isub(num);
      this.negative ^= 1;
      return this._normSign();

    // positive + negative
    } else if (this.negative === 0 && num.negative !== 0) {
      num.negative = 0;
      r = this.isub(num);
      num.negative = 1;
      return r._normSign();
    }

    // a.length > b.length
    var a, b;
    if (this.length > num.length) {
      a = this;
      b = num;
    } else {
      a = num;
      b = this;
    }

    var carry = 0;
    for (var i = 0; i < b.length; i++) {
      r = (a.words[i] | 0) + (b.words[i] | 0) + carry;
      this.words[i] = r & 0x3ffffff;
      carry = r >>> 26;
    }
    for (; carry !== 0 && i < a.length; i++) {
      r = (a.words[i] | 0) + carry;
      this.words[i] = r & 0x3ffffff;
      carry = r >>> 26;
    }

    this.length = a.length;
    if (carry !== 0) {
      this.words[this.length] = carry;
      this.length++;
    // Copy the rest of the words
    } else if (a !== this) {
      for (; i < a.length; i++) {
        this.words[i] = a.words[i];
      }
    }

    return this;
  };

  // Add `num` to `this`
  BN.prototype.add = function add (num) {
    var res;
    if (num.negative !== 0 && this.negative === 0) {
      num.negative = 0;
      res = this.sub(num);
      num.negative ^= 1;
      return res;
    } else if (num.negative === 0 && this.negative !== 0) {
      this.negative = 0;
      res = num.sub(this);
      this.negative = 1;
      return res;
    }

    if (this.length > num.length) return this.clone().iadd(num);

    return num.clone().iadd(this);
  };

  // Subtract `num` from `this` in-place
  BN.prototype.isub = function isub (num) {
    // this - (-num) = this + num
    if (num.negative !== 0) {
      num.negative = 0;
      var r = this.iadd(num);
      num.negative = 1;
      return r._normSign();

    // -this - num = -(this + num)
    } else if (this.negative !== 0) {
      this.negative = 0;
      this.iadd(num);
      this.negative = 1;
      return this._normSign();
    }

    // At this point both numbers are positive
    var cmp = this.cmp(num);

    // Optimization - zeroify
    if (cmp === 0) {
      this.negative = 0;
      this.length = 1;
      this.words[0] = 0;
      return this;
    }

    // a > b
    var a, b;
    if (cmp > 0) {
      a = this;
      b = num;
    } else {
      a = num;
      b = this;
    }

    var carry = 0;
    for (var i = 0; i < b.length; i++) {
      r = (a.words[i] | 0) - (b.words[i] | 0) + carry;
      carry = r >> 26;
      this.words[i] = r & 0x3ffffff;
    }
    for (; carry !== 0 && i < a.length; i++) {
      r = (a.words[i] | 0) + carry;
      carry = r >> 26;
      this.words[i] = r & 0x3ffffff;
    }

    // Copy rest of the words
    if (carry === 0 && i < a.length && a !== this) {
      for (; i < a.length; i++) {
        this.words[i] = a.words[i];
      }
    }

    this.length = Math.max(this.length, i);

    if (a !== this) {
      this.negative = 1;
    }

    return this.strip();
  };

  // Subtract `num` from `this`
  BN.prototype.sub = function sub (num) {
    return this.clone().isub(num);
  };

  function smallMulTo (self, num, out) {
    out.negative = num.negative ^ self.negative;
    var len = (self.length + num.length) | 0;
    out.length = len;
    len = (len - 1) | 0;

    // Peel one iteration (compiler can't do it, because of code complexity)
    var a = self.words[0] | 0;
    var b = num.words[0] | 0;
    var r = a * b;

    var lo = r & 0x3ffffff;
    var carry = (r / 0x4000000) | 0;
    out.words[0] = lo;

    for (var k = 1; k < len; k++) {
      // Sum all words with the same `i + j = k` and accumulate `ncarry`,
      // note that ncarry could be >= 0x3ffffff
      var ncarry = carry >>> 26;
      var rword = carry & 0x3ffffff;
      var maxJ = Math.min(k, num.length - 1);
      for (var j = Math.max(0, k - self.length + 1); j <= maxJ; j++) {
        var i = (k - j) | 0;
        a = self.words[i] | 0;
        b = num.words[j] | 0;
        r = a * b + rword;
        ncarry += (r / 0x4000000) | 0;
        rword = r & 0x3ffffff;
      }
      out.words[k] = rword | 0;
      carry = ncarry | 0;
    }
    if (carry !== 0) {
      out.words[k] = carry | 0;
    } else {
      out.length--;
    }

    return out.strip();
  }

  // TODO(indutny): it may be reasonable to omit it for users who don't need
  // to work with 256-bit numbers, otherwise it gives 20% improvement for 256-bit
  // multiplication (like elliptic secp256k1).
  var comb10MulTo = function comb10MulTo (self, num, out) {
    var a = self.words;
    var b = num.words;
    var o = out.words;
    var c = 0;
    var lo;
    var mid;
    var hi;
    var a0 = a[0] | 0;
    var al0 = a0 & 0x1fff;
    var ah0 = a0 >>> 13;
    var a1 = a[1] | 0;
    var al1 = a1 & 0x1fff;
    var ah1 = a1 >>> 13;
    var a2 = a[2] | 0;
    var al2 = a2 & 0x1fff;
    var ah2 = a2 >>> 13;
    var a3 = a[3] | 0;
    var al3 = a3 & 0x1fff;
    var ah3 = a3 >>> 13;
    var a4 = a[4] | 0;
    var al4 = a4 & 0x1fff;
    var ah4 = a4 >>> 13;
    var a5 = a[5] | 0;
    var al5 = a5 & 0x1fff;
    var ah5 = a5 >>> 13;
    var a6 = a[6] | 0;
    var al6 = a6 & 0x1fff;
    var ah6 = a6 >>> 13;
    var a7 = a[7] | 0;
    var al7 = a7 & 0x1fff;
    var ah7 = a7 >>> 13;
    var a8 = a[8] | 0;
    var al8 = a8 & 0x1fff;
    var ah8 = a8 >>> 13;
    var a9 = a[9] | 0;
    var al9 = a9 & 0x1fff;
    var ah9 = a9 >>> 13;
    var b0 = b[0] | 0;
    var bl0 = b0 & 0x1fff;
    var bh0 = b0 >>> 13;
    var b1 = b[1] | 0;
    var bl1 = b1 & 0x1fff;
    var bh1 = b1 >>> 13;
    var b2 = b[2] | 0;
    var bl2 = b2 & 0x1fff;
    var bh2 = b2 >>> 13;
    var b3 = b[3] | 0;
    var bl3 = b3 & 0x1fff;
    var bh3 = b3 >>> 13;
    var b4 = b[4] | 0;
    var bl4 = b4 & 0x1fff;
    var bh4 = b4 >>> 13;
    var b5 = b[5] | 0;
    var bl5 = b5 & 0x1fff;
    var bh5 = b5 >>> 13;
    var b6 = b[6] | 0;
    var bl6 = b6 & 0x1fff;
    var bh6 = b6 >>> 13;
    var b7 = b[7] | 0;
    var bl7 = b7 & 0x1fff;
    var bh7 = b7 >>> 13;
    var b8 = b[8] | 0;
    var bl8 = b8 & 0x1fff;
    var bh8 = b8 >>> 13;
    var b9 = b[9] | 0;
    var bl9 = b9 & 0x1fff;
    var bh9 = b9 >>> 13;

    out.negative = self.negative ^ num.negative;
    out.length = 19;
    /* k = 0 */
    lo = Math.imul(al0, bl0);
    mid = Math.imul(al0, bh0);
    mid = (mid + Math.imul(ah0, bl0)) | 0;
    hi = Math.imul(ah0, bh0);
    var w0 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w0 >>> 26)) | 0;
    w0 &= 0x3ffffff;
    /* k = 1 */
    lo = Math.imul(al1, bl0);
    mid = Math.imul(al1, bh0);
    mid = (mid + Math.imul(ah1, bl0)) | 0;
    hi = Math.imul(ah1, bh0);
    lo = (lo + Math.imul(al0, bl1)) | 0;
    mid = (mid + Math.imul(al0, bh1)) | 0;
    mid = (mid + Math.imul(ah0, bl1)) | 0;
    hi = (hi + Math.imul(ah0, bh1)) | 0;
    var w1 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w1 >>> 26)) | 0;
    w1 &= 0x3ffffff;
    /* k = 2 */
    lo = Math.imul(al2, bl0);
    mid = Math.imul(al2, bh0);
    mid = (mid + Math.imul(ah2, bl0)) | 0;
    hi = Math.imul(ah2, bh0);
    lo = (lo + Math.imul(al1, bl1)) | 0;
    mid = (mid + Math.imul(al1, bh1)) | 0;
    mid = (mid + Math.imul(ah1, bl1)) | 0;
    hi = (hi + Math.imul(ah1, bh1)) | 0;
    lo = (lo + Math.imul(al0, bl2)) | 0;
    mid = (mid + Math.imul(al0, bh2)) | 0;
    mid = (mid + Math.imul(ah0, bl2)) | 0;
    hi = (hi + Math.imul(ah0, bh2)) | 0;
    var w2 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w2 >>> 26)) | 0;
    w2 &= 0x3ffffff;
    /* k = 3 */
    lo = Math.imul(al3, bl0);
    mid = Math.imul(al3, bh0);
    mid = (mid + Math.imul(ah3, bl0)) | 0;
    hi = Math.imul(ah3, bh0);
    lo = (lo + Math.imul(al2, bl1)) | 0;
    mid = (mid + Math.imul(al2, bh1)) | 0;
    mid = (mid + Math.imul(ah2, bl1)) | 0;
    hi = (hi + Math.imul(ah2, bh1)) | 0;
    lo = (lo + Math.imul(al1, bl2)) | 0;
    mid = (mid + Math.imul(al1, bh2)) | 0;
    mid = (mid + Math.imul(ah1, bl2)) | 0;
    hi = (hi + Math.imul(ah1, bh2)) | 0;
    lo = (lo + Math.imul(al0, bl3)) | 0;
    mid = (mid + Math.imul(al0, bh3)) | 0;
    mid = (mid + Math.imul(ah0, bl3)) | 0;
    hi = (hi + Math.imul(ah0, bh3)) | 0;
    var w3 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w3 >>> 26)) | 0;
    w3 &= 0x3ffffff;
    /* k = 4 */
    lo = Math.imul(al4, bl0);
    mid = Math.imul(al4, bh0);
    mid = (mid + Math.imul(ah4, bl0)) | 0;
    hi = Math.imul(ah4, bh0);
    lo = (lo + Math.imul(al3, bl1)) | 0;
    mid = (mid + Math.imul(al3, bh1)) | 0;
    mid = (mid + Math.imul(ah3, bl1)) | 0;
    hi = (hi + Math.imul(ah3, bh1)) | 0;
    lo = (lo + Math.imul(al2, bl2)) | 0;
    mid = (mid + Math.imul(al2, bh2)) | 0;
    mid = (mid + Math.imul(ah2, bl2)) | 0;
    hi = (hi + Math.imul(ah2, bh2)) | 0;
    lo = (lo + Math.imul(al1, bl3)) | 0;
    mid = (mid + Math.imul(al1, bh3)) | 0;
    mid = (mid + Math.imul(ah1, bl3)) | 0;
    hi = (hi + Math.imul(ah1, bh3)) | 0;
    lo = (lo + Math.imul(al0, bl4)) | 0;
    mid = (mid + Math.imul(al0, bh4)) | 0;
    mid = (mid + Math.imul(ah0, bl4)) | 0;
    hi = (hi + Math.imul(ah0, bh4)) | 0;
    var w4 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w4 >>> 26)) | 0;
    w4 &= 0x3ffffff;
    /* k = 5 */
    lo = Math.imul(al5, bl0);
    mid = Math.imul(al5, bh0);
    mid = (mid + Math.imul(ah5, bl0)) | 0;
    hi = Math.imul(ah5, bh0);
    lo = (lo + Math.imul(al4, bl1)) | 0;
    mid = (mid + Math.imul(al4, bh1)) | 0;
    mid = (mid + Math.imul(ah4, bl1)) | 0;
    hi = (hi + Math.imul(ah4, bh1)) | 0;
    lo = (lo + Math.imul(al3, bl2)) | 0;
    mid = (mid + Math.imul(al3, bh2)) | 0;
    mid = (mid + Math.imul(ah3, bl2)) | 0;
    hi = (hi + Math.imul(ah3, bh2)) | 0;
    lo = (lo + Math.imul(al2, bl3)) | 0;
    mid = (mid + Math.imul(al2, bh3)) | 0;
    mid = (mid + Math.imul(ah2, bl3)) | 0;
    hi = (hi + Math.imul(ah2, bh3)) | 0;
    lo = (lo + Math.imul(al1, bl4)) | 0;
    mid = (mid + Math.imul(al1, bh4)) | 0;
    mid = (mid + Math.imul(ah1, bl4)) | 0;
    hi = (hi + Math.imul(ah1, bh4)) | 0;
    lo = (lo + Math.imul(al0, bl5)) | 0;
    mid = (mid + Math.imul(al0, bh5)) | 0;
    mid = (mid + Math.imul(ah0, bl5)) | 0;
    hi = (hi + Math.imul(ah0, bh5)) | 0;
    var w5 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w5 >>> 26)) | 0;
    w5 &= 0x3ffffff;
    /* k = 6 */
    lo = Math.imul(al6, bl0);
    mid = Math.imul(al6, bh0);
    mid = (mid + Math.imul(ah6, bl0)) | 0;
    hi = Math.imul(ah6, bh0);
    lo = (lo + Math.imul(al5, bl1)) | 0;
    mid = (mid + Math.imul(al5, bh1)) | 0;
    mid = (mid + Math.imul(ah5, bl1)) | 0;
    hi = (hi + Math.imul(ah5, bh1)) | 0;
    lo = (lo + Math.imul(al4, bl2)) | 0;
    mid = (mid + Math.imul(al4, bh2)) | 0;
    mid = (mid + Math.imul(ah4, bl2)) | 0;
    hi = (hi + Math.imul(ah4, bh2)) | 0;
    lo = (lo + Math.imul(al3, bl3)) | 0;
    mid = (mid + Math.imul(al3, bh3)) | 0;
    mid = (mid + Math.imul(ah3, bl3)) | 0;
    hi = (hi + Math.imul(ah3, bh3)) | 0;
    lo = (lo + Math.imul(al2, bl4)) | 0;
    mid = (mid + Math.imul(al2, bh4)) | 0;
    mid = (mid + Math.imul(ah2, bl4)) | 0;
    hi = (hi + Math.imul(ah2, bh4)) | 0;
    lo = (lo + Math.imul(al1, bl5)) | 0;
    mid = (mid + Math.imul(al1, bh5)) | 0;
    mid = (mid + Math.imul(ah1, bl5)) | 0;
    hi = (hi + Math.imul(ah1, bh5)) | 0;
    lo = (lo + Math.imul(al0, bl6)) | 0;
    mid = (mid + Math.imul(al0, bh6)) | 0;
    mid = (mid + Math.imul(ah0, bl6)) | 0;
    hi = (hi + Math.imul(ah0, bh6)) | 0;
    var w6 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w6 >>> 26)) | 0;
    w6 &= 0x3ffffff;
    /* k = 7 */
    lo = Math.imul(al7, bl0);
    mid = Math.imul(al7, bh0);
    mid = (mid + Math.imul(ah7, bl0)) | 0;
    hi = Math.imul(ah7, bh0);
    lo = (lo + Math.imul(al6, bl1)) | 0;
    mid = (mid + Math.imul(al6, bh1)) | 0;
    mid = (mid + Math.imul(ah6, bl1)) | 0;
    hi = (hi + Math.imul(ah6, bh1)) | 0;
    lo = (lo + Math.imul(al5, bl2)) | 0;
    mid = (mid + Math.imul(al5, bh2)) | 0;
    mid = (mid + Math.imul(ah5, bl2)) | 0;
    hi = (hi + Math.imul(ah5, bh2)) | 0;
    lo = (lo + Math.imul(al4, bl3)) | 0;
    mid = (mid + Math.imul(al4, bh3)) | 0;
    mid = (mid + Math.imul(ah4, bl3)) | 0;
    hi = (hi + Math.imul(ah4, bh3)) | 0;
    lo = (lo + Math.imul(al3, bl4)) | 0;
    mid = (mid + Math.imul(al3, bh4)) | 0;
    mid = (mid + Math.imul(ah3, bl4)) | 0;
    hi = (hi + Math.imul(ah3, bh4)) | 0;
    lo = (lo + Math.imul(al2, bl5)) | 0;
    mid = (mid + Math.imul(al2, bh5)) | 0;
    mid = (mid + Math.imul(ah2, bl5)) | 0;
    hi = (hi + Math.imul(ah2, bh5)) | 0;
    lo = (lo + Math.imul(al1, bl6)) | 0;
    mid = (mid + Math.imul(al1, bh6)) | 0;
    mid = (mid + Math.imul(ah1, bl6)) | 0;
    hi = (hi + Math.imul(ah1, bh6)) | 0;
    lo = (lo + Math.imul(al0, bl7)) | 0;
    mid = (mid + Math.imul(al0, bh7)) | 0;
    mid = (mid + Math.imul(ah0, bl7)) | 0;
    hi = (hi + Math.imul(ah0, bh7)) | 0;
    var w7 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w7 >>> 26)) | 0;
    w7 &= 0x3ffffff;
    /* k = 8 */
    lo = Math.imul(al8, bl0);
    mid = Math.imul(al8, bh0);
    mid = (mid + Math.imul(ah8, bl0)) | 0;
    hi = Math.imul(ah8, bh0);
    lo = (lo + Math.imul(al7, bl1)) | 0;
    mid = (mid + Math.imul(al7, bh1)) | 0;
    mid = (mid + Math.imul(ah7, bl1)) | 0;
    hi = (hi + Math.imul(ah7, bh1)) | 0;
    lo = (lo + Math.imul(al6, bl2)) | 0;
    mid = (mid + Math.imul(al6, bh2)) | 0;
    mid = (mid + Math.imul(ah6, bl2)) | 0;
    hi = (hi + Math.imul(ah6, bh2)) | 0;
    lo = (lo + Math.imul(al5, bl3)) | 0;
    mid = (mid + Math.imul(al5, bh3)) | 0;
    mid = (mid + Math.imul(ah5, bl3)) | 0;
    hi = (hi + Math.imul(ah5, bh3)) | 0;
    lo = (lo + Math.imul(al4, bl4)) | 0;
    mid = (mid + Math.imul(al4, bh4)) | 0;
    mid = (mid + Math.imul(ah4, bl4)) | 0;
    hi = (hi + Math.imul(ah4, bh4)) | 0;
    lo = (lo + Math.imul(al3, bl5)) | 0;
    mid = (mid + Math.imul(al3, bh5)) | 0;
    mid = (mid + Math.imul(ah3, bl5)) | 0;
    hi = (hi + Math.imul(ah3, bh5)) | 0;
    lo = (lo + Math.imul(al2, bl6)) | 0;
    mid = (mid + Math.imul(al2, bh6)) | 0;
    mid = (mid + Math.imul(ah2, bl6)) | 0;
    hi = (hi + Math.imul(ah2, bh6)) | 0;
    lo = (lo + Math.imul(al1, bl7)) | 0;
    mid = (mid + Math.imul(al1, bh7)) | 0;
    mid = (mid + Math.imul(ah1, bl7)) | 0;
    hi = (hi + Math.imul(ah1, bh7)) | 0;
    lo = (lo + Math.imul(al0, bl8)) | 0;
    mid = (mid + Math.imul(al0, bh8)) | 0;
    mid = (mid + Math.imul(ah0, bl8)) | 0;
    hi = (hi + Math.imul(ah0, bh8)) | 0;
    var w8 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w8 >>> 26)) | 0;
    w8 &= 0x3ffffff;
    /* k = 9 */
    lo = Math.imul(al9, bl0);
    mid = Math.imul(al9, bh0);
    mid = (mid + Math.imul(ah9, bl0)) | 0;
    hi = Math.imul(ah9, bh0);
    lo = (lo + Math.imul(al8, bl1)) | 0;
    mid = (mid + Math.imul(al8, bh1)) | 0;
    mid = (mid + Math.imul(ah8, bl1)) | 0;
    hi = (hi + Math.imul(ah8, bh1)) | 0;
    lo = (lo + Math.imul(al7, bl2)) | 0;
    mid = (mid + Math.imul(al7, bh2)) | 0;
    mid = (mid + Math.imul(ah7, bl2)) | 0;
    hi = (hi + Math.imul(ah7, bh2)) | 0;
    lo = (lo + Math.imul(al6, bl3)) | 0;
    mid = (mid + Math.imul(al6, bh3)) | 0;
    mid = (mid + Math.imul(ah6, bl3)) | 0;
    hi = (hi + Math.imul(ah6, bh3)) | 0;
    lo = (lo + Math.imul(al5, bl4)) | 0;
    mid = (mid + Math.imul(al5, bh4)) | 0;
    mid = (mid + Math.imul(ah5, bl4)) | 0;
    hi = (hi + Math.imul(ah5, bh4)) | 0;
    lo = (lo + Math.imul(al4, bl5)) | 0;
    mid = (mid + Math.imul(al4, bh5)) | 0;
    mid = (mid + Math.imul(ah4, bl5)) | 0;
    hi = (hi + Math.imul(ah4, bh5)) | 0;
    lo = (lo + Math.imul(al3, bl6)) | 0;
    mid = (mid + Math.imul(al3, bh6)) | 0;
    mid = (mid + Math.imul(ah3, bl6)) | 0;
    hi = (hi + Math.imul(ah3, bh6)) | 0;
    lo = (lo + Math.imul(al2, bl7)) | 0;
    mid = (mid + Math.imul(al2, bh7)) | 0;
    mid = (mid + Math.imul(ah2, bl7)) | 0;
    hi = (hi + Math.imul(ah2, bh7)) | 0;
    lo = (lo + Math.imul(al1, bl8)) | 0;
    mid = (mid + Math.imul(al1, bh8)) | 0;
    mid = (mid + Math.imul(ah1, bl8)) | 0;
    hi = (hi + Math.imul(ah1, bh8)) | 0;
    lo = (lo + Math.imul(al0, bl9)) | 0;
    mid = (mid + Math.imul(al0, bh9)) | 0;
    mid = (mid + Math.imul(ah0, bl9)) | 0;
    hi = (hi + Math.imul(ah0, bh9)) | 0;
    var w9 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w9 >>> 26)) | 0;
    w9 &= 0x3ffffff;
    /* k = 10 */
    lo = Math.imul(al9, bl1);
    mid = Math.imul(al9, bh1);
    mid = (mid + Math.imul(ah9, bl1)) | 0;
    hi = Math.imul(ah9, bh1);
    lo = (lo + Math.imul(al8, bl2)) | 0;
    mid = (mid + Math.imul(al8, bh2)) | 0;
    mid = (mid + Math.imul(ah8, bl2)) | 0;
    hi = (hi + Math.imul(ah8, bh2)) | 0;
    lo = (lo + Math.imul(al7, bl3)) | 0;
    mid = (mid + Math.imul(al7, bh3)) | 0;
    mid = (mid + Math.imul(ah7, bl3)) | 0;
    hi = (hi + Math.imul(ah7, bh3)) | 0;
    lo = (lo + Math.imul(al6, bl4)) | 0;
    mid = (mid + Math.imul(al6, bh4)) | 0;
    mid = (mid + Math.imul(ah6, bl4)) | 0;
    hi = (hi + Math.imul(ah6, bh4)) | 0;
    lo = (lo + Math.imul(al5, bl5)) | 0;
    mid = (mid + Math.imul(al5, bh5)) | 0;
    mid = (mid + Math.imul(ah5, bl5)) | 0;
    hi = (hi + Math.imul(ah5, bh5)) | 0;
    lo = (lo + Math.imul(al4, bl6)) | 0;
    mid = (mid + Math.imul(al4, bh6)) | 0;
    mid = (mid + Math.imul(ah4, bl6)) | 0;
    hi = (hi + Math.imul(ah4, bh6)) | 0;
    lo = (lo + Math.imul(al3, bl7)) | 0;
    mid = (mid + Math.imul(al3, bh7)) | 0;
    mid = (mid + Math.imul(ah3, bl7)) | 0;
    hi = (hi + Math.imul(ah3, bh7)) | 0;
    lo = (lo + Math.imul(al2, bl8)) | 0;
    mid = (mid + Math.imul(al2, bh8)) | 0;
    mid = (mid + Math.imul(ah2, bl8)) | 0;
    hi = (hi + Math.imul(ah2, bh8)) | 0;
    lo = (lo + Math.imul(al1, bl9)) | 0;
    mid = (mid + Math.imul(al1, bh9)) | 0;
    mid = (mid + Math.imul(ah1, bl9)) | 0;
    hi = (hi + Math.imul(ah1, bh9)) | 0;
    var w10 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w10 >>> 26)) | 0;
    w10 &= 0x3ffffff;
    /* k = 11 */
    lo = Math.imul(al9, bl2);
    mid = Math.imul(al9, bh2);
    mid = (mid + Math.imul(ah9, bl2)) | 0;
    hi = Math.imul(ah9, bh2);
    lo = (lo + Math.imul(al8, bl3)) | 0;
    mid = (mid + Math.imul(al8, bh3)) | 0;
    mid = (mid + Math.imul(ah8, bl3)) | 0;
    hi = (hi + Math.imul(ah8, bh3)) | 0;
    lo = (lo + Math.imul(al7, bl4)) | 0;
    mid = (mid + Math.imul(al7, bh4)) | 0;
    mid = (mid + Math.imul(ah7, bl4)) | 0;
    hi = (hi + Math.imul(ah7, bh4)) | 0;
    lo = (lo + Math.imul(al6, bl5)) | 0;
    mid = (mid + Math.imul(al6, bh5)) | 0;
    mid = (mid + Math.imul(ah6, bl5)) | 0;
    hi = (hi + Math.imul(ah6, bh5)) | 0;
    lo = (lo + Math.imul(al5, bl6)) | 0;
    mid = (mid + Math.imul(al5, bh6)) | 0;
    mid = (mid + Math.imul(ah5, bl6)) | 0;
    hi = (hi + Math.imul(ah5, bh6)) | 0;
    lo = (lo + Math.imul(al4, bl7)) | 0;
    mid = (mid + Math.imul(al4, bh7)) | 0;
    mid = (mid + Math.imul(ah4, bl7)) | 0;
    hi = (hi + Math.imul(ah4, bh7)) | 0;
    lo = (lo + Math.imul(al3, bl8)) | 0;
    mid = (mid + Math.imul(al3, bh8)) | 0;
    mid = (mid + Math.imul(ah3, bl8)) | 0;
    hi = (hi + Math.imul(ah3, bh8)) | 0;
    lo = (lo + Math.imul(al2, bl9)) | 0;
    mid = (mid + Math.imul(al2, bh9)) | 0;
    mid = (mid + Math.imul(ah2, bl9)) | 0;
    hi = (hi + Math.imul(ah2, bh9)) | 0;
    var w11 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w11 >>> 26)) | 0;
    w11 &= 0x3ffffff;
    /* k = 12 */
    lo = Math.imul(al9, bl3);
    mid = Math.imul(al9, bh3);
    mid = (mid + Math.imul(ah9, bl3)) | 0;
    hi = Math.imul(ah9, bh3);
    lo = (lo + Math.imul(al8, bl4)) | 0;
    mid = (mid + Math.imul(al8, bh4)) | 0;
    mid = (mid + Math.imul(ah8, bl4)) | 0;
    hi = (hi + Math.imul(ah8, bh4)) | 0;
    lo = (lo + Math.imul(al7, bl5)) | 0;
    mid = (mid + Math.imul(al7, bh5)) | 0;
    mid = (mid + Math.imul(ah7, bl5)) | 0;
    hi = (hi + Math.imul(ah7, bh5)) | 0;
    lo = (lo + Math.imul(al6, bl6)) | 0;
    mid = (mid + Math.imul(al6, bh6)) | 0;
    mid = (mid + Math.imul(ah6, bl6)) | 0;
    hi = (hi + Math.imul(ah6, bh6)) | 0;
    lo = (lo + Math.imul(al5, bl7)) | 0;
    mid = (mid + Math.imul(al5, bh7)) | 0;
    mid = (mid + Math.imul(ah5, bl7)) | 0;
    hi = (hi + Math.imul(ah5, bh7)) | 0;
    lo = (lo + Math.imul(al4, bl8)) | 0;
    mid = (mid + Math.imul(al4, bh8)) | 0;
    mid = (mid + Math.imul(ah4, bl8)) | 0;
    hi = (hi + Math.imul(ah4, bh8)) | 0;
    lo = (lo + Math.imul(al3, bl9)) | 0;
    mid = (mid + Math.imul(al3, bh9)) | 0;
    mid = (mid + Math.imul(ah3, bl9)) | 0;
    hi = (hi + Math.imul(ah3, bh9)) | 0;
    var w12 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w12 >>> 26)) | 0;
    w12 &= 0x3ffffff;
    /* k = 13 */
    lo = Math.imul(al9, bl4);
    mid = Math.imul(al9, bh4);
    mid = (mid + Math.imul(ah9, bl4)) | 0;
    hi = Math.imul(ah9, bh4);
    lo = (lo + Math.imul(al8, bl5)) | 0;
    mid = (mid + Math.imul(al8, bh5)) | 0;
    mid = (mid + Math.imul(ah8, bl5)) | 0;
    hi = (hi + Math.imul(ah8, bh5)) | 0;
    lo = (lo + Math.imul(al7, bl6)) | 0;
    mid = (mid + Math.imul(al7, bh6)) | 0;
    mid = (mid + Math.imul(ah7, bl6)) | 0;
    hi = (hi + Math.imul(ah7, bh6)) | 0;
    lo = (lo + Math.imul(al6, bl7)) | 0;
    mid = (mid + Math.imul(al6, bh7)) | 0;
    mid = (mid + Math.imul(ah6, bl7)) | 0;
    hi = (hi + Math.imul(ah6, bh7)) | 0;
    lo = (lo + Math.imul(al5, bl8)) | 0;
    mid = (mid + Math.imul(al5, bh8)) | 0;
    mid = (mid + Math.imul(ah5, bl8)) | 0;
    hi = (hi + Math.imul(ah5, bh8)) | 0;
    lo = (lo + Math.imul(al4, bl9)) | 0;
    mid = (mid + Math.imul(al4, bh9)) | 0;
    mid = (mid + Math.imul(ah4, bl9)) | 0;
    hi = (hi + Math.imul(ah4, bh9)) | 0;
    var w13 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w13 >>> 26)) | 0;
    w13 &= 0x3ffffff;
    /* k = 14 */
    lo = Math.imul(al9, bl5);
    mid = Math.imul(al9, bh5);
    mid = (mid + Math.imul(ah9, bl5)) | 0;
    hi = Math.imul(ah9, bh5);
    lo = (lo + Math.imul(al8, bl6)) | 0;
    mid = (mid + Math.imul(al8, bh6)) | 0;
    mid = (mid + Math.imul(ah8, bl6)) | 0;
    hi = (hi + Math.imul(ah8, bh6)) | 0;
    lo = (lo + Math.imul(al7, bl7)) | 0;
    mid = (mid + Math.imul(al7, bh7)) | 0;
    mid = (mid + Math.imul(ah7, bl7)) | 0;
    hi = (hi + Math.imul(ah7, bh7)) | 0;
    lo = (lo + Math.imul(al6, bl8)) | 0;
    mid = (mid + Math.imul(al6, bh8)) | 0;
    mid = (mid + Math.imul(ah6, bl8)) | 0;
    hi = (hi + Math.imul(ah6, bh8)) | 0;
    lo = (lo + Math.imul(al5, bl9)) | 0;
    mid = (mid + Math.imul(al5, bh9)) | 0;
    mid = (mid + Math.imul(ah5, bl9)) | 0;
    hi = (hi + Math.imul(ah5, bh9)) | 0;
    var w14 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w14 >>> 26)) | 0;
    w14 &= 0x3ffffff;
    /* k = 15 */
    lo = Math.imul(al9, bl6);
    mid = Math.imul(al9, bh6);
    mid = (mid + Math.imul(ah9, bl6)) | 0;
    hi = Math.imul(ah9, bh6);
    lo = (lo + Math.imul(al8, bl7)) | 0;
    mid = (mid + Math.imul(al8, bh7)) | 0;
    mid = (mid + Math.imul(ah8, bl7)) | 0;
    hi = (hi + Math.imul(ah8, bh7)) | 0;
    lo = (lo + Math.imul(al7, bl8)) | 0;
    mid = (mid + Math.imul(al7, bh8)) | 0;
    mid = (mid + Math.imul(ah7, bl8)) | 0;
    hi = (hi + Math.imul(ah7, bh8)) | 0;
    lo = (lo + Math.imul(al6, bl9)) | 0;
    mid = (mid + Math.imul(al6, bh9)) | 0;
    mid = (mid + Math.imul(ah6, bl9)) | 0;
    hi = (hi + Math.imul(ah6, bh9)) | 0;
    var w15 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w15 >>> 26)) | 0;
    w15 &= 0x3ffffff;
    /* k = 16 */
    lo = Math.imul(al9, bl7);
    mid = Math.imul(al9, bh7);
    mid = (mid + Math.imul(ah9, bl7)) | 0;
    hi = Math.imul(ah9, bh7);
    lo = (lo + Math.imul(al8, bl8)) | 0;
    mid = (mid + Math.imul(al8, bh8)) | 0;
    mid = (mid + Math.imul(ah8, bl8)) | 0;
    hi = (hi + Math.imul(ah8, bh8)) | 0;
    lo = (lo + Math.imul(al7, bl9)) | 0;
    mid = (mid + Math.imul(al7, bh9)) | 0;
    mid = (mid + Math.imul(ah7, bl9)) | 0;
    hi = (hi + Math.imul(ah7, bh9)) | 0;
    var w16 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w16 >>> 26)) | 0;
    w16 &= 0x3ffffff;
    /* k = 17 */
    lo = Math.imul(al9, bl8);
    mid = Math.imul(al9, bh8);
    mid = (mid + Math.imul(ah9, bl8)) | 0;
    hi = Math.imul(ah9, bh8);
    lo = (lo + Math.imul(al8, bl9)) | 0;
    mid = (mid + Math.imul(al8, bh9)) | 0;
    mid = (mid + Math.imul(ah8, bl9)) | 0;
    hi = (hi + Math.imul(ah8, bh9)) | 0;
    var w17 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w17 >>> 26)) | 0;
    w17 &= 0x3ffffff;
    /* k = 18 */
    lo = Math.imul(al9, bl9);
    mid = Math.imul(al9, bh9);
    mid = (mid + Math.imul(ah9, bl9)) | 0;
    hi = Math.imul(ah9, bh9);
    var w18 = (((c + lo) | 0) + ((mid & 0x1fff) << 13)) | 0;
    c = (((hi + (mid >>> 13)) | 0) + (w18 >>> 26)) | 0;
    w18 &= 0x3ffffff;
    o[0] = w0;
    o[1] = w1;
    o[2] = w2;
    o[3] = w3;
    o[4] = w4;
    o[5] = w5;
    o[6] = w6;
    o[7] = w7;
    o[8] = w8;
    o[9] = w9;
    o[10] = w10;
    o[11] = w11;
    o[12] = w12;
    o[13] = w13;
    o[14] = w14;
    o[15] = w15;
    o[16] = w16;
    o[17] = w17;
    o[18] = w18;
    if (c !== 0) {
      o[19] = c;
      out.length++;
    }
    return out;
  };

  // Polyfill comb
  if (!Math.imul) {
    comb10MulTo = smallMulTo;
  }

  function bigMulTo (self, num, out) {
    out.negative = num.negative ^ self.negative;
    out.length = self.length + num.length;

    var carry = 0;
    var hncarry = 0;
    for (var k = 0; k < out.length - 1; k++) {
      // Sum all words with the same `i + j = k` and accumulate `ncarry`,
      // note that ncarry could be >= 0x3ffffff
      var ncarry = hncarry;
      hncarry = 0;
      var rword = carry & 0x3ffffff;
      var maxJ = Math.min(k, num.length - 1);
      for (var j = Math.max(0, k - self.length + 1); j <= maxJ; j++) {
        var i = k - j;
        var a = self.words[i] | 0;
        var b = num.words[j] | 0;
        var r = a * b;

        var lo = r & 0x3ffffff;
        ncarry = (ncarry + ((r / 0x4000000) | 0)) | 0;
        lo = (lo + rword) | 0;
        rword = lo & 0x3ffffff;
        ncarry = (ncarry + (lo >>> 26)) | 0;

        hncarry += ncarry >>> 26;
        ncarry &= 0x3ffffff;
      }
      out.words[k] = rword;
      carry = ncarry;
      ncarry = hncarry;
    }
    if (carry !== 0) {
      out.words[k] = carry;
    } else {
      out.length--;
    }

    return out.strip();
  }

  function jumboMulTo (self, num, out) {
    var fftm = new FFTM();
    return fftm.mulp(self, num, out);
  }

  BN.prototype.mulTo = function mulTo (num, out) {
    var res;
    var len = this.length + num.length;
    if (this.length === 10 && num.length === 10) {
      res = comb10MulTo(this, num, out);
    } else if (len < 63) {
      res = smallMulTo(this, num, out);
    } else if (len < 1024) {
      res = bigMulTo(this, num, out);
    } else {
      res = jumboMulTo(this, num, out);
    }

    return res;
  };

  // Cooley-Tukey algorithm for FFT
  // slightly revisited to rely on looping instead of recursion

  function FFTM (x, y) {
    this.x = x;
    this.y = y;
  }

  FFTM.prototype.makeRBT = function makeRBT (N) {
    var t = new Array(N);
    var l = BN.prototype._countBits(N) - 1;
    for (var i = 0; i < N; i++) {
      t[i] = this.revBin(i, l, N);
    }

    return t;
  };

  // Returns binary-reversed representation of `x`
  FFTM.prototype.revBin = function revBin (x, l, N) {
    if (x === 0 || x === N - 1) return x;

    var rb = 0;
    for (var i = 0; i < l; i++) {
      rb |= (x & 1) << (l - i - 1);
      x >>= 1;
    }

    return rb;
  };

  // Performs "tweedling" phase, therefore 'emulating'
  // behaviour of the recursive algorithm
  FFTM.prototype.permute = function permute (rbt, rws, iws, rtws, itws, N) {
    for (var i = 0; i < N; i++) {
      rtws[i] = rws[rbt[i]];
      itws[i] = iws[rbt[i]];
    }
  };

  FFTM.prototype.transform = function transform (rws, iws, rtws, itws, N, rbt) {
    this.permute(rbt, rws, iws, rtws, itws, N);

    for (var s = 1; s < N; s <<= 1) {
      var l = s << 1;

      var rtwdf = Math.cos(2 * Math.PI / l);
      var itwdf = Math.sin(2 * Math.PI / l);

      for (var p = 0; p < N; p += l) {
        var rtwdf_ = rtwdf;
        var itwdf_ = itwdf;

        for (var j = 0; j < s; j++) {
          var re = rtws[p + j];
          var ie = itws[p + j];

          var ro = rtws[p + j + s];
          var io = itws[p + j + s];

          var rx = rtwdf_ * ro - itwdf_ * io;

          io = rtwdf_ * io + itwdf_ * ro;
          ro = rx;

          rtws[p + j] = re + ro;
          itws[p + j] = ie + io;

          rtws[p + j + s] = re - ro;
          itws[p + j + s] = ie - io;

          /* jshint maxdepth : false */
          if (j !== l) {
            rx = rtwdf * rtwdf_ - itwdf * itwdf_;

            itwdf_ = rtwdf * itwdf_ + itwdf * rtwdf_;
            rtwdf_ = rx;
          }
        }
      }
    }
  };

  FFTM.prototype.guessLen13b = function guessLen13b (n, m) {
    var N = Math.max(m, n) | 1;
    var odd = N & 1;
    var i = 0;
    for (N = N / 2 | 0; N; N = N >>> 1) {
      i++;
    }

    return 1 << i + 1 + odd;
  };

  FFTM.prototype.conjugate = function conjugate (rws, iws, N) {
    if (N <= 1) return;

    for (var i = 0; i < N / 2; i++) {
      var t = rws[i];

      rws[i] = rws[N - i - 1];
      rws[N - i - 1] = t;

      t = iws[i];

      iws[i] = -iws[N - i - 1];
      iws[N - i - 1] = -t;
    }
  };

  FFTM.prototype.normalize13b = function normalize13b (ws, N) {
    var carry = 0;
    for (var i = 0; i < N / 2; i++) {
      var w = Math.round(ws[2 * i + 1] / N) * 0x2000 +
        Math.round(ws[2 * i] / N) +
        carry;

      ws[i] = w & 0x3ffffff;

      if (w < 0x4000000) {
        carry = 0;
      } else {
        carry = w / 0x4000000 | 0;
      }
    }

    return ws;
  };

  FFTM.prototype.convert13b = function convert13b (ws, len, rws, N) {
    var carry = 0;
    for (var i = 0; i < len; i++) {
      carry = carry + (ws[i] | 0);

      rws[2 * i] = carry & 0x1fff; carry = carry >>> 13;
      rws[2 * i + 1] = carry & 0x1fff; carry = carry >>> 13;
    }

    // Pad with zeroes
    for (i = 2 * len; i < N; ++i) {
      rws[i] = 0;
    }

    assert(carry === 0);
    assert((carry & ~0x1fff) === 0);
  };

  FFTM.prototype.stub = function stub (N) {
    var ph = new Array(N);
    for (var i = 0; i < N; i++) {
      ph[i] = 0;
    }

    return ph;
  };

  FFTM.prototype.mulp = function mulp (x, y, out) {
    var N = 2 * this.guessLen13b(x.length, y.length);

    var rbt = this.makeRBT(N);

    var _ = this.stub(N);

    var rws = new Array(N);
    var rwst = new Array(N);
    var iwst = new Array(N);

    var nrws = new Array(N);
    var nrwst = new Array(N);
    var niwst = new Array(N);

    var rmws = out.words;
    rmws.length = N;

    this.convert13b(x.words, x.length, rws, N);
    this.convert13b(y.words, y.length, nrws, N);

    this.transform(rws, _, rwst, iwst, N, rbt);
    this.transform(nrws, _, nrwst, niwst, N, rbt);

    for (var i = 0; i < N; i++) {
      var rx = rwst[i] * nrwst[i] - iwst[i] * niwst[i];
      iwst[i] = rwst[i] * niwst[i] + iwst[i] * nrwst[i];
      rwst[i] = rx;
    }

    this.conjugate(rwst, iwst, N);
    this.transform(rwst, iwst, rmws, _, N, rbt);
    this.conjugate(rmws, _, N);
    this.normalize13b(rmws, N);

    out.negative = x.negative ^ y.negative;
    out.length = x.length + y.length;
    return out.strip();
  };

  // Multiply `this` by `num`
  BN.prototype.mul = function mul (num) {
    var out = new BN(null);
    out.words = new Array(this.length + num.length);
    return this.mulTo(num, out);
  };

  // Multiply employing FFT
  BN.prototype.mulf = function mulf (num) {
    var out = new BN(null);
    out.words = new Array(this.length + num.length);
    return jumboMulTo(this, num, out);
  };

  // In-place Multiplication
  BN.prototype.imul = function imul (num) {
    return this.clone().mulTo(num, this);
  };

  BN.prototype.imuln = function imuln (num) {
    assert(typeof num === 'number');
    assert(num < 0x4000000);

    // Carry
    var carry = 0;
    for (var i = 0; i < this.length; i++) {
      var w = (this.words[i] | 0) * num;
      var lo = (w & 0x3ffffff) + (carry & 0x3ffffff);
      carry >>= 26;
      carry += (w / 0x4000000) | 0;
      // NOTE: lo is 27bit maximum
      carry += lo >>> 26;
      this.words[i] = lo & 0x3ffffff;
    }

    if (carry !== 0) {
      this.words[i] = carry;
      this.length++;
    }
    if (num === 0) {
      this.length = 1;
      this._normSign();
    }

    return this;
  };

  BN.prototype.muln = function muln (num) {
    return this.clone().imuln(num);
  };

  // `this` * `this`
  BN.prototype.sqr = function sqr () {
    return this.mul(this);
  };

  // `this` * `this` in-place
  BN.prototype.isqr = function isqr () {
    return this.imul(this.clone());
  };

  // Math.pow(`this`, `num`)
  BN.prototype.pow = function pow (num) {
    var w = toBitArray(num);
    if (w.length === 0) return new BN(1);

    // Skip leading zeroes
    var res = this;
    for (var i = 0; i < w.length; i++, res = res.sqr()) {
      if (w[i] !== 0) break;
    }

    if (++i < w.length) {
      for (var q = res.sqr(); i < w.length; i++, q = q.sqr()) {
        if (w[i] === 0) continue;

        res = res.mul(q);
      }
    }

    return res;
  };

  // Shift-left in-place
  BN.prototype.iushln = function iushln (bits) {
    assert(typeof bits === 'number' && bits >= 0);
    var r = bits % 26;
    var s = (bits - r) / 26;
    var carryMask = (0x3ffffff >>> (26 - r)) << (26 - r);
    var i;

    if (r !== 0) {
      var carry = 0;

      for (i = 0; i < this.length; i++) {
        var newCarry = this.words[i] & carryMask;
        var c = ((this.words[i] | 0) - newCarry) << r;
        this.words[i] = c | carry;
        carry = newCarry >>> (26 - r);
      }

      if (carry) {
        this.words[i] = carry;
        this.length++;
      }
    }

    if (s !== 0) {
      for (i = this.length - 1; i >= 0; i--) {
        this.words[i + s] = this.words[i];
      }

      for (i = 0; i < s; i++) {
        this.words[i] = 0;
      }

      this.length += s;
    }

    return this.strip();
  };

  BN.prototype.ishln = function ishln (bits) {
    // TODO(indutny): implement me
    assert(this.negative === 0);
    return this.iushln(bits);
  };

  // Shift-right in-place
  // NOTE: `hint` is a lowest bit before trailing zeroes
  // NOTE: if `extended` is present - it will be filled with destroyed bits
  BN.prototype.iushrn = function iushrn (bits, hint, extended) {
    assert(typeof bits === 'number' && bits >= 0);
    var h;
    if (hint) {
      h = (hint - (hint % 26)) / 26;
    } else {
      h = 0;
    }

    var r = bits % 26;
    var s = Math.min((bits - r) / 26, this.length);
    var mask = 0x3ffffff ^ ((0x3ffffff >>> r) << r);
    var maskedWords = extended;

    h -= s;
    h = Math.max(0, h);

    // Extended mode, copy masked part
    if (maskedWords) {
      for (var i = 0; i < s; i++) {
        maskedWords.words[i] = this.words[i];
      }
      maskedWords.length = s;
    }

    if (s === 0) {
      // No-op, we should not move anything at all
    } else if (this.length > s) {
      this.length -= s;
      for (i = 0; i < this.length; i++) {
        this.words[i] = this.words[i + s];
      }
    } else {
      this.words[0] = 0;
      this.length = 1;
    }

    var carry = 0;
    for (i = this.length - 1; i >= 0 && (carry !== 0 || i >= h); i--) {
      var word = this.words[i] | 0;
      this.words[i] = (carry << (26 - r)) | (word >>> r);
      carry = word & mask;
    }

    // Push carried bits as a mask
    if (maskedWords && carry !== 0) {
      maskedWords.words[maskedWords.length++] = carry;
    }

    if (this.length === 0) {
      this.words[0] = 0;
      this.length = 1;
    }

    return this.strip();
  };

  BN.prototype.ishrn = function ishrn (bits, hint, extended) {
    // TODO(indutny): implement me
    assert(this.negative === 0);
    return this.iushrn(bits, hint, extended);
  };

  // Shift-left
  BN.prototype.shln = function shln (bits) {
    return this.clone().ishln(bits);
  };

  BN.prototype.ushln = function ushln (bits) {
    return this.clone().iushln(bits);
  };

  // Shift-right
  BN.prototype.shrn = function shrn (bits) {
    return this.clone().ishrn(bits);
  };

  BN.prototype.ushrn = function ushrn (bits) {
    return this.clone().iushrn(bits);
  };

  // Test if n bit is set
  BN.prototype.testn = function testn (bit) {
    assert(typeof bit === 'number' && bit >= 0);
    var r = bit % 26;
    var s = (bit - r) / 26;
    var q = 1 << r;

    // Fast case: bit is much higher than all existing words
    if (this.length <= s) return false;

    // Check bit and return
    var w = this.words[s];

    return !!(w & q);
  };

  // Return only lowers bits of number (in-place)
  BN.prototype.imaskn = function imaskn (bits) {
    assert(typeof bits === 'number' && bits >= 0);
    var r = bits % 26;
    var s = (bits - r) / 26;

    assert(this.negative === 0, 'imaskn works only with positive numbers');

    if (this.length <= s) {
      return this;
    }

    if (r !== 0) {
      s++;
    }
    this.length = Math.min(s, this.length);

    if (r !== 0) {
      var mask = 0x3ffffff ^ ((0x3ffffff >>> r) << r);
      this.words[this.length - 1] &= mask;
    }

    if (this.length === 0) {
      this.words[0] = 0;
      this.length = 1;
    }

    return this.strip();
  };

  // Return only lowers bits of number
  BN.prototype.maskn = function maskn (bits) {
    return this.clone().imaskn(bits);
  };

  // Add plain number `num` to `this`
  BN.prototype.iaddn = function iaddn (num) {
    assert(typeof num === 'number');
    assert(num < 0x4000000);
    if (num < 0) return this.isubn(-num);

    // Possible sign change
    if (this.negative !== 0) {
      if (this.length === 1 && (this.words[0] | 0) < num) {
        this.words[0] = num - (this.words[0] | 0);
        this.negative = 0;
        return this;
      }

      this.negative = 0;
      this.isubn(num);
      this.negative = 1;
      return this;
    }

    // Add without checks
    return this._iaddn(num);
  };

  BN.prototype._iaddn = function _iaddn (num) {
    this.words[0] += num;

    // Carry
    for (var i = 0; i < this.length && this.words[i] >= 0x4000000; i++) {
      this.words[i] -= 0x4000000;
      if (i === this.length - 1) {
        this.words[i + 1] = 1;
      } else {
        this.words[i + 1]++;
      }
    }
    this.length = Math.max(this.length, i + 1);

    return this;
  };

  // Subtract plain number `num` from `this`
  BN.prototype.isubn = function isubn (num) {
    assert(typeof num === 'number');
    assert(num < 0x4000000);
    if (num < 0) return this.iaddn(-num);

    if (this.negative !== 0) {
      this.negative = 0;
      this.iaddn(num);
      this.negative = 1;
      return this;
    }

    this.words[0] -= num;

    if (this.length === 1 && this.words[0] < 0) {
      this.words[0] = -this.words[0];
      this.negative = 1;
    } else {
      // Carry
      for (var i = 0; i < this.length && this.words[i] < 0; i++) {
        this.words[i] += 0x4000000;
        this.words[i + 1] -= 1;
      }
    }

    return this.strip();
  };

  BN.prototype.addn = function addn (num) {
    return this.clone().iaddn(num);
  };

  BN.prototype.subn = function subn (num) {
    return this.clone().isubn(num);
  };

  BN.prototype.iabs = function iabs () {
    this.negative = 0;

    return this;
  };

  BN.prototype.abs = function abs () {
    return this.clone().iabs();
  };

  BN.prototype._ishlnsubmul = function _ishlnsubmul (num, mul, shift) {
    var len = num.length + shift;
    var i;

    this._expand(len);

    var w;
    var carry = 0;
    for (i = 0; i < num.length; i++) {
      w = (this.words[i + shift] | 0) + carry;
      var right = (num.words[i] | 0) * mul;
      w -= right & 0x3ffffff;
      carry = (w >> 26) - ((right / 0x4000000) | 0);
      this.words[i + shift] = w & 0x3ffffff;
    }
    for (; i < this.length - shift; i++) {
      w = (this.words[i + shift] | 0) + carry;
      carry = w >> 26;
      this.words[i + shift] = w & 0x3ffffff;
    }

    if (carry === 0) return this.strip();

    // Subtraction overflow
    assert(carry === -1);
    carry = 0;
    for (i = 0; i < this.length; i++) {
      w = -(this.words[i] | 0) + carry;
      carry = w >> 26;
      this.words[i] = w & 0x3ffffff;
    }
    this.negative = 1;

    return this.strip();
  };

  BN.prototype._wordDiv = function _wordDiv (num, mode) {
    var shift = this.length - num.length;

    var a = this.clone();
    var b = num;

    // Normalize
    var bhi = b.words[b.length - 1] | 0;
    var bhiBits = this._countBits(bhi);
    shift = 26 - bhiBits;
    if (shift !== 0) {
      b = b.ushln(shift);
      a.iushln(shift);
      bhi = b.words[b.length - 1] | 0;
    }

    // Initialize quotient
    var m = a.length - b.length;
    var q;

    if (mode !== 'mod') {
      q = new BN(null);
      q.length = m + 1;
      q.words = new Array(q.length);
      for (var i = 0; i < q.length; i++) {
        q.words[i] = 0;
      }
    }

    var diff = a.clone()._ishlnsubmul(b, 1, m);
    if (diff.negative === 0) {
      a = diff;
      if (q) {
        q.words[m] = 1;
      }
    }

    for (var j = m - 1; j >= 0; j--) {
      var qj = (a.words[b.length + j] | 0) * 0x4000000 +
        (a.words[b.length + j - 1] | 0);

      // NOTE: (qj / bhi) is (0x3ffffff * 0x4000000 + 0x3ffffff) / 0x2000000 max
      // (0x7ffffff)
      qj = Math.min((qj / bhi) | 0, 0x3ffffff);

      a._ishlnsubmul(b, qj, j);
      while (a.negative !== 0) {
        qj--;
        a.negative = 0;
        a._ishlnsubmul(b, 1, j);
        if (!a.isZero()) {
          a.negative ^= 1;
        }
      }
      if (q) {
        q.words[j] = qj;
      }
    }
    if (q) {
      q.strip();
    }
    a.strip();

    // Denormalize
    if (mode !== 'div' && shift !== 0) {
      a.iushrn(shift);
    }

    return {
      div: q || null,
      mod: a
    };
  };

  // NOTE: 1) `mode` can be set to `mod` to request mod only,
  //       to `div` to request div only, or be absent to
  //       request both div & mod
  //       2) `positive` is true if unsigned mod is requested
  BN.prototype.divmod = function divmod (num, mode, positive) {
    assert(!num.isZero());

    if (this.isZero()) {
      return {
        div: new BN(0),
        mod: new BN(0)
      };
    }

    var div, mod, res;
    if (this.negative !== 0 && num.negative === 0) {
      res = this.neg().divmod(num, mode);

      if (mode !== 'mod') {
        div = res.div.neg();
      }

      if (mode !== 'div') {
        mod = res.mod.neg();
        if (positive && mod.negative !== 0) {
          mod.iadd(num);
        }
      }

      return {
        div: div,
        mod: mod
      };
    }

    if (this.negative === 0 && num.negative !== 0) {
      res = this.divmod(num.neg(), mode);

      if (mode !== 'mod') {
        div = res.div.neg();
      }

      return {
        div: div,
        mod: res.mod
      };
    }

    if ((this.negative & num.negative) !== 0) {
      res = this.neg().divmod(num.neg(), mode);

      if (mode !== 'div') {
        mod = res.mod.neg();
        if (positive && mod.negative !== 0) {
          mod.isub(num);
        }
      }

      return {
        div: res.div,
        mod: mod
      };
    }

    // Both numbers are positive at this point

    // Strip both numbers to approximate shift value
    if (num.length > this.length || this.cmp(num) < 0) {
      return {
        div: new BN(0),
        mod: this
      };
    }

    // Very short reduction
    if (num.length === 1) {
      if (mode === 'div') {
        return {
          div: this.divn(num.words[0]),
          mod: null
        };
      }

      if (mode === 'mod') {
        return {
          div: null,
          mod: new BN(this.modn(num.words[0]))
        };
      }

      return {
        div: this.divn(num.words[0]),
        mod: new BN(this.modn(num.words[0]))
      };
    }

    return this._wordDiv(num, mode);
  };

  // Find `this` / `num`
  BN.prototype.div = function div (num) {
    return this.divmod(num, 'div', false).div;
  };

  // Find `this` % `num`
  BN.prototype.mod = function mod (num) {
    return this.divmod(num, 'mod', false).mod;
  };

  BN.prototype.umod = function umod (num) {
    return this.divmod(num, 'mod', true).mod;
  };

  // Find Round(`this` / `num`)
  BN.prototype.divRound = function divRound (num) {
    var dm = this.divmod(num);

    // Fast case - exact division
    if (dm.mod.isZero()) return dm.div;

    var mod = dm.mod.abs();

    var half = num.abs().iushrn(1);
    var r2 = num.words[0] & 1;
    var cmp = mod.cmp(half);

    // Round down
    if (cmp < 0 || r2 === 1 && cmp === 0) return dm.div;

    // Round up, away from zero
    var up = new BN(1);
    up.negative = this.negative ^ num.negative;
    return dm.div.iadd(up);
  };

  BN.prototype.modn = function modn (num) {
    assert(num <= 0x3ffffff);
    var p = (1 << 26) % num;

    var acc = 0;
    for (var i = this.length - 1; i >= 0; i--) {
      acc = (p * acc + (this.words[i] | 0)) % num;
    }

    return acc;
  };

  // In-place division by number
  BN.prototype.idivn = function idivn (num) {
    assert(num <= 0x3ffffff);

    var carry = 0;
    for (var i = this.length - 1; i >= 0; i--) {
      var w = (this.words[i] | 0) + carry * 0x4000000;
      this.words[i] = (w / num) | 0;
      carry = w % num;
    }

    return this.strip();
  };

  BN.prototype.divn = function divn (num) {
    return this.clone().idivn(num);
  };

  BN.prototype.egcd = function egcd (p) {
    assert(p.negative === 0);
    assert(!p.isZero());

    var x = this;
    var y = p.clone();

    if (x.negative !== 0) {
      x = x.umod(p);
    } else {
      x = x.clone();
    }

    // A * x + B * y = x
    var A = new BN(1);
    var B = new BN(0);

    // C * x + D * y = y
    var C = new BN(0);
    var D = new BN(1);

    var g = 0;

    while (x.isEven() && y.isEven()) {
      x.iushrn(1);
      y.iushrn(1);
      ++g;
    }

    var yp = y.clone();
    var xp = x.clone();

    while (!x.isZero()) {
      for (var i = 0, im = 1; (x.words[0] & im) === 0 && i < 26; ++i, im <<= 1);
      if (i > 0) {
        x.iushrn(i);
        while (i-- > 0) {
          if (A.isOdd() || B.isOdd()) {
            A.iadd(yp);
            B.isub(xp);
          }

          A.iushrn(1);
          B.iushrn(1);
        }
      }

      for (var j = 0, jm = 1; (y.words[0] & jm) === 0 && j < 26; ++j, jm <<= 1);
      if (j > 0) {
        y.iushrn(j);
        while (j-- > 0) {
          if (C.isOdd() || D.isOdd()) {
            C.iadd(yp);
            D.isub(xp);
          }

          C.iushrn(1);
          D.iushrn(1);
        }
      }

      if (x.cmp(y) >= 0) {
        x.isub(y);
        A.isub(C);
        B.isub(D);
      } else {
        y.isub(x);
        C.isub(A);
        D.isub(B);
      }
    }

    return {
      a: C,
      b: D,
      gcd: y.iushln(g)
    };
  };

  // This is reduced incarnation of the binary EEA
  // above, designated to invert members of the
  // _prime_ fields F(p) at a maximal speed
  BN.prototype._invmp = function _invmp (p) {
    assert(p.negative === 0);
    assert(!p.isZero());

    var a = this;
    var b = p.clone();

    if (a.negative !== 0) {
      a = a.umod(p);
    } else {
      a = a.clone();
    }

    var x1 = new BN(1);
    var x2 = new BN(0);

    var delta = b.clone();

    while (a.cmpn(1) > 0 && b.cmpn(1) > 0) {
      for (var i = 0, im = 1; (a.words[0] & im) === 0 && i < 26; ++i, im <<= 1);
      if (i > 0) {
        a.iushrn(i);
        while (i-- > 0) {
          if (x1.isOdd()) {
            x1.iadd(delta);
          }

          x1.iushrn(1);
        }
      }

      for (var j = 0, jm = 1; (b.words[0] & jm) === 0 && j < 26; ++j, jm <<= 1);
      if (j > 0) {
        b.iushrn(j);
        while (j-- > 0) {
          if (x2.isOdd()) {
            x2.iadd(delta);
          }

          x2.iushrn(1);
        }
      }

      if (a.cmp(b) >= 0) {
        a.isub(b);
        x1.isub(x2);
      } else {
        b.isub(a);
        x2.isub(x1);
      }
    }

    var res;
    if (a.cmpn(1) === 0) {
      res = x1;
    } else {
      res = x2;
    }

    if (res.cmpn(0) < 0) {
      res.iadd(p);
    }

    return res;
  };

  BN.prototype.gcd = function gcd (num) {
    if (this.isZero()) return num.abs();
    if (num.isZero()) return this.abs();

    var a = this.clone();
    var b = num.clone();
    a.negative = 0;
    b.negative = 0;

    // Remove common factor of two
    for (var shift = 0; a.isEven() && b.isEven(); shift++) {
      a.iushrn(1);
      b.iushrn(1);
    }

    do {
      while (a.isEven()) {
        a.iushrn(1);
      }
      while (b.isEven()) {
        b.iushrn(1);
      }

      var r = a.cmp(b);
      if (r < 0) {
        // Swap `a` and `b` to make `a` always bigger than `b`
        var t = a;
        a = b;
        b = t;
      } else if (r === 0 || b.cmpn(1) === 0) {
        break;
      }

      a.isub(b);
    } while (true);

    return b.iushln(shift);
  };

  // Invert number in the field F(num)
  BN.prototype.invm = function invm (num) {
    return this.egcd(num).a.umod(num);
  };

  BN.prototype.isEven = function isEven () {
    return (this.words[0] & 1) === 0;
  };

  BN.prototype.isOdd = function isOdd () {
    return (this.words[0] & 1) === 1;
  };

  // And first word and num
  BN.prototype.andln = function andln (num) {
    return this.words[0] & num;
  };

  // Increment at the bit position in-line
  BN.prototype.bincn = function bincn (bit) {
    assert(typeof bit === 'number');
    var r = bit % 26;
    var s = (bit - r) / 26;
    var q = 1 << r;

    // Fast case: bit is much higher than all existing words
    if (this.length <= s) {
      this._expand(s + 1);
      this.words[s] |= q;
      return this;
    }

    // Add bit and propagate, if needed
    var carry = q;
    for (var i = s; carry !== 0 && i < this.length; i++) {
      var w = this.words[i] | 0;
      w += carry;
      carry = w >>> 26;
      w &= 0x3ffffff;
      this.words[i] = w;
    }
    if (carry !== 0) {
      this.words[i] = carry;
      this.length++;
    }
    return this;
  };

  BN.prototype.isZero = function isZero () {
    return this.length === 1 && this.words[0] === 0;
  };

  BN.prototype.cmpn = function cmpn (num) {
    var negative = num < 0;

    if (this.negative !== 0 && !negative) return -1;
    if (this.negative === 0 && negative) return 1;

    this.strip();

    var res;
    if (this.length > 1) {
      res = 1;
    } else {
      if (negative) {
        num = -num;
      }

      assert(num <= 0x3ffffff, 'Number is too big');

      var w = this.words[0] | 0;
      res = w === num ? 0 : w < num ? -1 : 1;
    }
    if (this.negative !== 0) return -res | 0;
    return res;
  };

  // Compare two numbers and return:
  // 1 - if `this` > `num`
  // 0 - if `this` == `num`
  // -1 - if `this` < `num`
  BN.prototype.cmp = function cmp (num) {
    if (this.negative !== 0 && num.negative === 0) return -1;
    if (this.negative === 0 && num.negative !== 0) return 1;

    var res = this.ucmp(num);
    if (this.negative !== 0) return -res | 0;
    return res;
  };

  // Unsigned comparison
  BN.prototype.ucmp = function ucmp (num) {
    // At this point both numbers have the same sign
    if (this.length > num.length) return 1;
    if (this.length < num.length) return -1;

    var res = 0;
    for (var i = this.length - 1; i >= 0; i--) {
      var a = this.words[i] | 0;
      var b = num.words[i] | 0;

      if (a === b) continue;
      if (a < b) {
        res = -1;
      } else if (a > b) {
        res = 1;
      }
      break;
    }
    return res;
  };

  BN.prototype.gtn = function gtn (num) {
    return this.cmpn(num) === 1;
  };

  BN.prototype.gt = function gt (num) {
    return this.cmp(num) === 1;
  };

  BN.prototype.gten = function gten (num) {
    return this.cmpn(num) >= 0;
  };

  BN.prototype.gte = function gte (num) {
    return this.cmp(num) >= 0;
  };

  BN.prototype.ltn = function ltn (num) {
    return this.cmpn(num) === -1;
  };

  BN.prototype.lt = function lt (num) {
    return this.cmp(num) === -1;
  };

  BN.prototype.lten = function lten (num) {
    return this.cmpn(num) <= 0;
  };

  BN.prototype.lte = function lte (num) {
    return this.cmp(num) <= 0;
  };

  BN.prototype.eqn = function eqn (num) {
    return this.cmpn(num) === 0;
  };

  BN.prototype.eq = function eq (num) {
    return this.cmp(num) === 0;
  };

  //
  // A reduce context, could be using montgomery or something better, depending
  // on the `m` itself.
  //
  BN.red = function red (num) {
    return new Red(num);
  };

  BN.prototype.toRed = function toRed (ctx) {
    assert(!this.red, 'Already a number in reduction context');
    assert(this.negative === 0, 'red works only with positives');
    return ctx.convertTo(this)._forceRed(ctx);
  };

  BN.prototype.fromRed = function fromRed () {
    assert(this.red, 'fromRed works only with numbers in reduction context');
    return this.red.convertFrom(this);
  };

  BN.prototype._forceRed = function _forceRed (ctx) {
    this.red = ctx;
    return this;
  };

  BN.prototype.forceRed = function forceRed (ctx) {
    assert(!this.red, 'Already a number in reduction context');
    return this._forceRed(ctx);
  };

  BN.prototype.redAdd = function redAdd (num) {
    assert(this.red, 'redAdd works only with red numbers');
    return this.red.add(this, num);
  };

  BN.prototype.redIAdd = function redIAdd (num) {
    assert(this.red, 'redIAdd works only with red numbers');
    return this.red.iadd(this, num);
  };

  BN.prototype.redSub = function redSub (num) {
    assert(this.red, 'redSub works only with red numbers');
    return this.red.sub(this, num);
  };

  BN.prototype.redISub = function redISub (num) {
    assert(this.red, 'redISub works only with red numbers');
    return this.red.isub(this, num);
  };

  BN.prototype.redShl = function redShl (num) {
    assert(this.red, 'redShl works only with red numbers');
    return this.red.shl(this, num);
  };

  BN.prototype.redMul = function redMul (num) {
    assert(this.red, 'redMul works only with red numbers');
    this.red._verify2(this, num);
    return this.red.mul(this, num);
  };

  BN.prototype.redIMul = function redIMul (num) {
    assert(this.red, 'redMul works only with red numbers');
    this.red._verify2(this, num);
    return this.red.imul(this, num);
  };

  BN.prototype.redSqr = function redSqr () {
    assert(this.red, 'redSqr works only with red numbers');
    this.red._verify1(this);
    return this.red.sqr(this);
  };

  BN.prototype.redISqr = function redISqr () {
    assert(this.red, 'redISqr works only with red numbers');
    this.red._verify1(this);
    return this.red.isqr(this);
  };

  // Square root over p
  BN.prototype.redSqrt = function redSqrt () {
    assert(this.red, 'redSqrt works only with red numbers');
    this.red._verify1(this);
    return this.red.sqrt(this);
  };

  BN.prototype.redInvm = function redInvm () {
    assert(this.red, 'redInvm works only with red numbers');
    this.red._verify1(this);
    return this.red.invm(this);
  };

  // Return negative clone of `this` % `red modulo`
  BN.prototype.redNeg = function redNeg () {
    assert(this.red, 'redNeg works only with red numbers');
    this.red._verify1(this);
    return this.red.neg(this);
  };

  BN.prototype.redPow = function redPow (num) {
    assert(this.red && !num.red, 'redPow(normalNum)');
    this.red._verify1(this);
    return this.red.pow(this, num);
  };

  // Prime numbers with efficient reduction
  var primes = {
    k256: null,
    p224: null,
    p192: null,
    p25519: null
  };

  // Pseudo-Mersenne prime
  function MPrime (name, p) {
    // P = 2 ^ N - K
    this.name = name;
    this.p = new BN(p, 16);
    this.n = this.p.bitLength();
    this.k = new BN(1).iushln(this.n).isub(this.p);

    this.tmp = this._tmp();
  }

  MPrime.prototype._tmp = function _tmp () {
    var tmp = new BN(null);
    tmp.words = new Array(Math.ceil(this.n / 13));
    return tmp;
  };

  MPrime.prototype.ireduce = function ireduce (num) {
    // Assumes that `num` is less than `P^2`
    // num = HI * (2 ^ N - K) + HI * K + LO = HI * K + LO (mod P)
    var r = num;
    var rlen;

    do {
      this.split(r, this.tmp);
      r = this.imulK(r);
      r = r.iadd(this.tmp);
      rlen = r.bitLength();
    } while (rlen > this.n);

    var cmp = rlen < this.n ? -1 : r.ucmp(this.p);
    if (cmp === 0) {
      r.words[0] = 0;
      r.length = 1;
    } else if (cmp > 0) {
      r.isub(this.p);
    } else {
      if (r.strip !== undefined) {
        // r is BN v4 instance
        r.strip();
      } else {
        // r is BN v5 instance
        r._strip();
      }
    }

    return r;
  };

  MPrime.prototype.split = function split (input, out) {
    input.iushrn(this.n, 0, out);
  };

  MPrime.prototype.imulK = function imulK (num) {
    return num.imul(this.k);
  };

  function K256 () {
    MPrime.call(
      this,
      'k256',
      'ffffffff ffffffff ffffffff ffffffff ffffffff ffffffff fffffffe fffffc2f');
  }
  inherits(K256, MPrime);

  K256.prototype.split = function split (input, output) {
    // 256 = 9 * 26 + 22
    var mask = 0x3fffff;

    var outLen = Math.min(input.length, 9);
    for (var i = 0; i < outLen; i++) {
      output.words[i] = input.words[i];
    }
    output.length = outLen;

    if (input.length <= 9) {
      input.words[0] = 0;
      input.length = 1;
      return;
    }

    // Shift by 9 limbs
    var prev = input.words[9];
    output.words[output.length++] = prev & mask;

    for (i = 10; i < input.length; i++) {
      var next = input.words[i] | 0;
      input.words[i - 10] = ((next & mask) << 4) | (prev >>> 22);
      prev = next;
    }
    prev >>>= 22;
    input.words[i - 10] = prev;
    if (prev === 0 && input.length > 10) {
      input.length -= 10;
    } else {
      input.length -= 9;
    }
  };

  K256.prototype.imulK = function imulK (num) {
    // K = 0x1000003d1 = [ 0x40, 0x3d1 ]
    num.words[num.length] = 0;
    num.words[num.length + 1] = 0;
    num.length += 2;

    // bounded at: 0x40 * 0x3ffffff + 0x3d0 = 0x100000390
    var lo = 0;
    for (var i = 0; i < num.length; i++) {
      var w = num.words[i] | 0;
      lo += w * 0x3d1;
      num.words[i] = lo & 0x3ffffff;
      lo = w * 0x40 + ((lo / 0x4000000) | 0);
    }

    // Fast length reduction
    if (num.words[num.length - 1] === 0) {
      num.length--;
      if (num.words[num.length - 1] === 0) {
        num.length--;
      }
    }
    return num;
  };

  function P224 () {
    MPrime.call(
      this,
      'p224',
      'ffffffff ffffffff ffffffff ffffffff 00000000 00000000 00000001');
  }
  inherits(P224, MPrime);

  function P192 () {
    MPrime.call(
      this,
      'p192',
      'ffffffff ffffffff ffffffff fffffffe ffffffff ffffffff');
  }
  inherits(P192, MPrime);

  function P25519 () {
    // 2 ^ 255 - 19
    MPrime.call(
      this,
      '25519',
      '7fffffffffffffff ffffffffffffffff ffffffffffffffff ffffffffffffffed');
  }
  inherits(P25519, MPrime);

  P25519.prototype.imulK = function imulK (num) {
    // K = 0x13
    var carry = 0;
    for (var i = 0; i < num.length; i++) {
      var hi = (num.words[i] | 0) * 0x13 + carry;
      var lo = hi & 0x3ffffff;
      hi >>>= 26;

      num.words[i] = lo;
      carry = hi;
    }
    if (carry !== 0) {
      num.words[num.length++] = carry;
    }
    return num;
  };

  // Exported mostly for testing purposes, use plain name instead
  BN._prime = function prime (name) {
    // Cached version of prime
    if (primes[name]) return primes[name];

    var prime;
    if (name === 'k256') {
      prime = new K256();
    } else if (name === 'p224') {
      prime = new P224();
    } else if (name === 'p192') {
      prime = new P192();
    } else if (name === 'p25519') {
      prime = new P25519();
    } else {
      throw new Error('Unknown prime ' + name);
    }
    primes[name] = prime;

    return prime;
  };

  //
  // Base reduction engine
  //
  function Red (m) {
    if (typeof m === 'string') {
      var prime = BN._prime(m);
      this.m = prime.p;
      this.prime = prime;
    } else {
      assert(m.gtn(1), 'modulus must be greater than 1');
      this.m = m;
      this.prime = null;
    }
  }

  Red.prototype._verify1 = function _verify1 (a) {
    assert(a.negative === 0, 'red works only with positives');
    assert(a.red, 'red works only with red numbers');
  };

  Red.prototype._verify2 = function _verify2 (a, b) {
    assert((a.negative | b.negative) === 0, 'red works only with positives');
    assert(a.red && a.red === b.red,
      'red works only with red numbers');
  };

  Red.prototype.imod = function imod (a) {
    if (this.prime) return this.prime.ireduce(a)._forceRed(this);
    return a.umod(this.m)._forceRed(this);
  };

  Red.prototype.neg = function neg (a) {
    if (a.isZero()) {
      return a.clone();
    }

    return this.m.sub(a)._forceRed(this);
  };

  Red.prototype.add = function add (a, b) {
    this._verify2(a, b);

    var res = a.add(b);
    if (res.cmp(this.m) >= 0) {
      res.isub(this.m);
    }
    return res._forceRed(this);
  };

  Red.prototype.iadd = function iadd (a, b) {
    this._verify2(a, b);

    var res = a.iadd(b);
    if (res.cmp(this.m) >= 0) {
      res.isub(this.m);
    }
    return res;
  };

  Red.prototype.sub = function sub (a, b) {
    this._verify2(a, b);

    var res = a.sub(b);
    if (res.cmpn(0) < 0) {
      res.iadd(this.m);
    }
    return res._forceRed(this);
  };

  Red.prototype.isub = function isub (a, b) {
    this._verify2(a, b);

    var res = a.isub(b);
    if (res.cmpn(0) < 0) {
      res.iadd(this.m);
    }
    return res;
  };

  Red.prototype.shl = function shl (a, num) {
    this._verify1(a);
    return this.imod(a.ushln(num));
  };

  Red.prototype.imul = function imul (a, b) {
    this._verify2(a, b);
    return this.imod(a.imul(b));
  };

  Red.prototype.mul = function mul (a, b) {
    this._verify2(a, b);
    return this.imod(a.mul(b));
  };

  Red.prototype.isqr = function isqr (a) {
    return this.imul(a, a.clone());
  };

  Red.prototype.sqr = function sqr (a) {
    return this.mul(a, a);
  };

  Red.prototype.sqrt = function sqrt (a) {
    if (a.isZero()) return a.clone();

    var mod3 = this.m.andln(3);
    assert(mod3 % 2 === 1);

    // Fast case
    if (mod3 === 3) {
      var pow = this.m.add(new BN(1)).iushrn(2);
      return this.pow(a, pow);
    }

    // Tonelli-Shanks algorithm (Totally unoptimized and slow)
    //
    // Find Q and S, that Q * 2 ^ S = (P - 1)
    var q = this.m.subn(1);
    var s = 0;
    while (!q.isZero() && q.andln(1) === 0) {
      s++;
      q.iushrn(1);
    }
    assert(!q.isZero());

    var one = new BN(1).toRed(this);
    var nOne = one.redNeg();

    // Find quadratic non-residue
    // NOTE: Max is such because of generalized Riemann hypothesis.
    var lpow = this.m.subn(1).iushrn(1);
    var z = this.m.bitLength();
    z = new BN(2 * z * z).toRed(this);

    while (this.pow(z, lpow).cmp(nOne) !== 0) {
      z.redIAdd(nOne);
    }

    var c = this.pow(z, q);
    var r = this.pow(a, q.addn(1).iushrn(1));
    var t = this.pow(a, q);
    var m = s;
    while (t.cmp(one) !== 0) {
      var tmp = t;
      for (var i = 0; tmp.cmp(one) !== 0; i++) {
        tmp = tmp.redSqr();
      }
      assert(i < m);
      var b = this.pow(c, new BN(1).iushln(m - i - 1));

      r = r.redMul(b);
      c = b.redSqr();
      t = t.redMul(c);
      m = i;
    }

    return r;
  };

  Red.prototype.invm = function invm (a) {
    var inv = a._invmp(this.m);
    if (inv.negative !== 0) {
      inv.negative = 0;
      return this.imod(inv).redNeg();
    } else {
      return this.imod(inv);
    }
  };

  Red.prototype.pow = function pow (a, num) {
    if (num.isZero()) return new BN(1).toRed(this);
    if (num.cmpn(1) === 0) return a.clone();

    var windowSize = 4;
    var wnd = new Array(1 << windowSize);
    wnd[0] = new BN(1).toRed(this);
    wnd[1] = a;
    for (var i = 2; i < wnd.length; i++) {
      wnd[i] = this.mul(wnd[i - 1], a);
    }

    var res = wnd[0];
    var current = 0;
    var currentLen = 0;
    var start = num.bitLength() % 26;
    if (start === 0) {
      start = 26;
    }

    for (i = num.length - 1; i >= 0; i--) {
      var word = num.words[i];
      for (var j = start - 1; j >= 0; j--) {
        var bit = (word >> j) & 1;
        if (res !== wnd[0]) {
          res = this.sqr(res);
        }

        if (bit === 0 && current === 0) {
          currentLen = 0;
          continue;
        }

        current <<= 1;
        current |= bit;
        currentLen++;
        if (currentLen !== windowSize && (i !== 0 || j !== 0)) continue;

        res = this.mul(res, wnd[current]);
        currentLen = 0;
        current = 0;
      }
      start = 26;
    }

    return res;
  };

  Red.prototype.convertTo = function convertTo (num) {
    var r = num.umod(this.m);

    return r === num ? r.clone() : r;
  };

  Red.prototype.convertFrom = function convertFrom (num) {
    var res = num.clone();
    res.red = null;
    return res;
  };

  //
  // Montgomery method engine
  //

  BN.mont = function mont (num) {
    return new Mont(num);
  };

  function Mont (m) {
    Red.call(this, m);

    this.shift = this.m.bitLength();
    if (this.shift % 26 !== 0) {
      this.shift += 26 - (this.shift % 26);
    }

    this.r = new BN(1).iushln(this.shift);
    this.r2 = this.imod(this.r.sqr());
    this.rinv = this.r._invmp(this.m);

    this.minv = this.rinv.mul(this.r).isubn(1).div(this.m);
    this.minv = this.minv.umod(this.r);
    this.minv = this.r.sub(this.minv);
  }
  inherits(Mont, Red);

  Mont.prototype.convertTo = function convertTo (num) {
    return this.imod(num.ushln(this.shift));
  };

  Mont.prototype.convertFrom = function convertFrom (num) {
    var r = this.imod(num.mul(this.rinv));
    r.red = null;
    return r;
  };

  Mont.prototype.imul = function imul (a, b) {
    if (a.isZero() || b.isZero()) {
      a.words[0] = 0;
      a.length = 1;
      return a;
    }

    var t = a.imul(b);
    var c = t.maskn(this.shift).mul(this.minv).imaskn(this.shift).mul(this.m);
    var u = t.isub(c).iushrn(this.shift);
    var res = u;

    if (u.cmp(this.m) >= 0) {
      res = u.isub(this.m);
    } else if (u.cmpn(0) < 0) {
      res = u.iadd(this.m);
    }

    return res._forceRed(this);
  };

  Mont.prototype.mul = function mul (a, b) {
    if (a.isZero() || b.isZero()) return new BN(0)._forceRed(this);

    var t = a.mul(b);
    var c = t.maskn(this.shift).mul(this.minv).imaskn(this.shift).mul(this.m);
    var u = t.isub(c).iushrn(this.shift);
    var res = u;
    if (u.cmp(this.m) >= 0) {
      res = u.isub(this.m);
    } else if (u.cmpn(0) < 0) {
      res = u.iadd(this.m);
    }

    return res._forceRed(this);
  };

  Mont.prototype.invm = function invm (a) {
    // (AR)^-1 * R^2 = (A^-1 * R^-1) * R^2 = A^-1 * R
    var res = this.imod(a._invmp(this.m).mul(this.r2));
    return res._forceRed(this);
  };
})(typeof module === 'undefined' || module, this);

},{"buffer":undefined}],20:[function(require,module,exports){
/*! run-parallel. MIT License. Feross Aboukhadijeh <https://feross.org/opensource> */
module.exports = runParallel

function runParallel (tasks, cb) {
  var results, pending, keys
  var isSync = true

  if (Array.isArray(tasks)) {
    results = []
    pending = tasks.length
  } else {
    keys = Object.keys(tasks)
    results = {}
    pending = keys.length
  }

  function done (err) {
    function end () {
      if (cb) cb(err, results)
      cb = null
    }
    if (isSync) process.nextTick(end)
    else end()
  }

  function each (i, err, result) {
    results[i] = result
    if (--pending === 0 || err) {
      done(err)
    }
  }

  if (!pending) {
    // empty
    done(null)
  } else if (keys) {
    // object
    keys.forEach(function (key) {
      tasks[key](function (err, result) { each(key, err, result) })
    })
  } else {
    // array
    tasks.forEach(function (task, i) {
      task(function (err, result) { each(i, err, result) })
    })
  }

  isSync = false
}

},{}],21:[function(require,module,exports){
/*jshint es5:false, asi:true, quotmark:false, eqeqeq:false, forin: false */

/*
 * (c) 2011-14 Tim Becker, see file LICENSE for details
 */

/*
 * Provides functionality for bencoding and decoding as use in
 * bittorrent and described in: http://www.bittorrent.org/beps/bep_0003.html
 *
 * Encoding is as follows:
 *
 *    var benc  = require('bncode'),
 *        exmp = {}
 *
 *    exmp.bla = "blup"
 *    exmp.foo = "bar"
 *    exmp.one = 1
 *    exmp.woah = {}
 *    exmp.woah.arr = []
 *    exmp.woah.arr.push(1)
 *    exmp.woah.arr.push(2)
 *    exmp.woah.arr.push(3)
 *    exmp.str = new Buffer("Buffers work too")
 *
 *    var bencBuffer = benc.encode(exmp) i
 *
 *    // d3:bla4:blup3:foo3:bar3:onei1e4:woahd3:arr \
 *    // li1ei2ei3eee3:str16:Buffers work tooe
 *
 *
 * Decoding will work in progressively, e.g. if you're receiving partial
 * bencoded strings on the network:
 *
 *    var benc = require("bncode"),
 *        buf  = null
 *
 *    decoder = new bncode.decoder()
 *    while (buf = receiveData()) {
 *      decoder.decode(buf)
 *    }
 *
 *    log(decoder.result())
 *
 *
 * Or "all in one"
 *
 *    var benc = require("bncode"),
 *        buf  = getBuffer(),
 *        dec  = benc.decode(buf)
 *
 *    log(dec.bla)
 *
 *
 * There are some subtleties concerning bencoded strings. These are
 * decoded as Buffer objects because they are just strings of raw bytes
 * and as such would wreak havoc with multi byte strings in javascript.
 *
 * The exception to this is strings that appear as keys in bencoded
 * dicts. These are decoded as Javascript Strings, as they should always
 * be strings of (ascii) characters and if they weren't decoded as JS
 * Strings, dict's would map to Javascript objects with properties.
 *
 */

exports.encode  = Bencode
exports.decoder = Bdecode
exports.decode  = decode
exports.Stream  = Stream

var inherits = require('util').inherits
var Transform = require('stream').Transform

var I     = 'i'.charCodeAt(0)
var L     = 'l'.charCodeAt(0)
var E     = 'e'.charCodeAt(0)
var D     = 'd'.charCodeAt(0)
var COLON = ':'.charCodeAt(0)
var DASH  = '-'.charCodeAt(0)

var STATE_INITIAL          = 0
var STATE_STATE_STRING_LEN = STATE_INITIAL          + 1
var STATE_STRING           = STATE_STATE_STRING_LEN + 1
var STATE_COLON            = STATE_STRING           + 1
var STATE_STATE_INTEGER    = STATE_COLON            + 1
var STATE_INTEGER          = STATE_STATE_INTEGER    + 1

/*
 * This is the internal state machine for taking apart bencoded strings,
 * it's not exposed in the eports.  It's constructed with four callbacks
 * that get fired when:
 *
 * cb: a value (string or number) is encountered
 * cb_list: a begin list element is encountered
 * cb_dict: a beginning of dictionary is encountered.
 * cd_end: an end element, wheter dict or list is encountered
 *
 * Once constructed, the machine may be fed with buffers containing
 * partial bencoded string. Call `consistent` to check whether the
 * current state is consistent, e.g. not smack-dap in the middle of
 * a string or a number and if the dict, list and end calls balance
 *
 *
 * The functionality being so rudimentary requires some more state and
 * logic in the code executing the machine, for this see Context, below.
 *
 */

function BdecodeSMachine (cb, cb_list, cb_dict, cb_end) {
  var depth = 0
  var state = STATE_INITIAL

  this.consistent = function () {
    return state === STATE_INITIAL && depth === 0
  }

  var strLen = 0
  var str    = ''
  var _int   = 0
  var neg    = false

  this.parse = function (buffer, encoding) {
    if (typeof buffer === 'string') {
      buffer = new Buffer(buffer, encoding || 'utf8')
    }

    for (var pos = 0; pos !== buffer.length; ++pos) {
      switch (state) {
        case STATE_INITIAL:
          switch (buffer[pos]) {
            case 0x30:
            case 0x31:
            case 0x32:
            case 0x33:
            case 0x34:
            case 0x35:
            case 0x36:
            case 0x37:
            case 0x38:
            case 0x39:
              state = STATE_STATE_STRING_LEN
              strLen = 0
              strLen += buffer[pos] - 0x30
              break
            case I:
              state = STATE_STATE_INTEGER
              _int  = 0
              neg   = false
              break
            case L:
              state = STATE_INITIAL
              depth += 1; if (depth > 32) throw new Error("Bencode nesting limit")
              cb_list()
              break
            case D:
              state = STATE_INITIAL
              depth += 1; if (depth > 32) throw new Error("Bencode nesting limit")
              cb_dict()
              break
            case E:
              state = STATE_INITIAL
              depth -= 1
              if (depth < 0) {
                throw new Error('end with no beginning: ' + pos)
              } else {
                cb_end()
              }
              break
          }
          break
        case STATE_STATE_STRING_LEN:
          if (integer(buffer[pos])) {
            strLen *= 10
            strLen += buffer[pos] - 0x30
          } else {
            if (strLen > 262144) throw new Error("Bencode string limit");
            str = new Buffer(strLen)
            pos -=1
            state = STATE_COLON
          }
          break
        case STATE_COLON:
          if (buffer[pos] !== COLON) {
            throw new Error('not a colon at: ' + pos.toString(16))
          }
          state = STATE_STRING
          // in case this is a zero length string, there's
          // no bytes to be collected.
          if (0 === strLen) {
            cb(new Buffer(0))
            state = STATE_INITIAL
          }
          break
        case STATE_STRING:
          if (0 === strLen) {
            cb(str)
            state = STATE_INITIAL
          } else {
            //str += String.fromCharCode(buffer[pos]) // not unicode safe..
            str[str.length-strLen] = buffer[pos]
            strLen -= 1
            if (0 === strLen) {
              cb(str)
              state = STATE_INITIAL
            }
          }
          break
        case STATE_STATE_INTEGER:
          state = STATE_INTEGER
          if (buffer[pos] === DASH) {
            neg = true  // handle neg and zero within value.
            break
          } // else fall through
        case STATE_INTEGER:
          if (integer(buffer[pos])) {
            _int *= 10
            _int += buffer[pos] - 0x30
          } else if (buffer[pos] === E) {
            var ret = neg ? 0 - _int : _int
            cb(ret)
            state = STATE_INITIAL
          } else {
            throw new Error('not part of int at:'+pos.toString(16))
          }
          break
      } // switch state
    } // for buffer
  } // function parse

  function integer (value) {
    // check that value is a number and that
    // its value is ascii integer.
    if (typeof value !== 'number') {
      return false
    }
    return between(value, 0x30, 0x39)
  }
  function between (val, min, max) {
    return (min <= val  && val <= max)
  }

} // end BdecodeSMachine

/*
 * The exported decode functionality.
 */
function Bdecode () {
  // markers
  var DICTIONARY_START = {}
  var LIST_START       = {}

  var Context  = function () {
    var self  = this
    var stack = []

    this.cb = function (o) {
      stack.push(o)
    }
    this.cb_list = function () {
      self.cb(LIST_START)
    }
    this.cb_dict = function () {
      self.cb(DICTIONARY_START)
    }

    this.cb_end = function () {

      // unwind the stack until either a DICTIONARY_START or LIST_START is
      // found, create arr or hash, stick unwound stack on, push arr or hash
      // back onto stack

      var obj       = null
      var tmp_stack = []

      while ((obj = stack.pop()) !== undefined) {
        if (LIST_START === obj) {
          var obj2 = null
          var list = []
          while((obj2 = tmp_stack.pop()) !== undefined) {
            list.push(obj2)
          }
          self.cb(list)
          break
        } else if (DICTIONARY_START === obj) {
          var key = null
          var val = null
          var dic = Object.create(null)
          while ((key = tmp_stack.pop()) !== undefined && (val = tmp_stack.pop()) !== undefined) {
            dic[key.toString()] = val
          }

          if (key !== undefined && dic[key] === undefined) {
            throw new Error('uneven number of keys and values A')
          }
          self.cb(dic)
          break
        } else {
          tmp_stack.push(obj)
        }
      }
      if (tmp_stack.length > 0) {
        // could this case even occur?
        throw new Error('uneven number of keys and values B')
      }
    }
    this.result = function () {
      return stack
    }
  }

  var self     = this
  var ctx      = new Context()
  var smachine = new BdecodeSMachine(ctx.cb, ctx.cb_list, ctx.cb_dict, ctx.cb_end)

  this.result = function () {
    if (!smachine.consistent()) {
      throw new Error('not in consistent state. More bytes coming?')
    }
    return ctx.result()
  }

  this.decode = function (buf, encoding) {
    smachine.parse(buf, encoding)
  }
}

function Bencode (obj) {
  var self = this
  var to_encode = obj
  var buffer = null

  switch (typeof obj) {
    case 'string':
      return encodeString(obj)
    case 'number':
      return encodeNumber(obj)
    case 'object':
      if (obj instanceof Array) {
        return encodeList(obj)
      } else if (Buffer.isBuffer(obj)) {
        return encodeBuffer(obj)
      } else {
        // assume it's a hash
        return encodeDict(obj)
      }
  }

  function encodeString (obj) {
    var blen = Buffer.byteLength(obj)
    var len  = blen.toString(10)
    var buf  = new Buffer(len.length + 1 + blen)

    buf.write(len, 0, 'ascii')
    buf.write(':', len.length, 'ascii')
    buf.write(obj, len.length + 1, 'utf8')

    return buf
  }

  function encodeNumber (num) {
    var n   = num.toString(10)
    var buf = new Buffer(n.length + 2)

    buf.write('i', 0)
    buf.write(n, 1)
    buf.write('e', n.length + 1)

    return buf
  }

  function encodeDict (obj) {
    var func = function (obj, pos) {
      var keys = Object.keys(obj).sort()
      keys.forEach(function (key) {
        var val = new Bencode(obj[key])
        key = new Bencode(key)

        ensure(key.length + val.length, pos)
        key.copy(buffer, pos, 0)
        pos += key.length
        val.copy(buffer, pos, 0)
        pos += val.length
      })
      return pos
    }
    return assemble(obj, 'd', func)
  }

  function encodeList (obj) {
    var func = function(obj, pos) {
      obj.forEach(function (o) {
        var elem = new Bencode(o)

        ensure(elem.length, pos)
        elem.copy(buffer, pos, 0)
        pos += elem.length
      })
      return pos
    }
    return assemble(obj, 'l', func)
  }

  function encodeBuffer (obj) {
    var len = obj.length.toString(10)
    var buf = new Buffer(len.length + 1 + obj.length)

    buf.write(len, 0, 'ascii')
    buf.write(':', len.length, 'ascii')
    obj.copy(buf, len.length + 1, 0)

    return buf
  }

  function assemble (obj, prefix, func) {
    var pos = 0

    ensure(1024, 0)
    buffer.write(prefix, pos++)

    pos = func(obj, pos)
    ensure(1, pos)

    buffer.write('e', pos++)
    return buffer.slice(0, pos)
  }

  function ensure (num, pos) {
    if (!buffer) {
      buffer = new Buffer(num)
    } else {
      if (buffer.length > num + pos + 1) {
        return
      } else {
        var buf2 = new Buffer(buffer.length + num)
        buffer.copy(buf2, 0, 0)
        buffer = buf2
      }
    }
  }
}

function decode (buffer, encoding) {
  var decoder = new Bdecode()

  decoder.decode(buffer, encoding)
  return decoder.result()[0]
}

function Stream (options) {
  options = options || {}
  options.objectMode = true
  Transform.call(this, options)
  this._decoder = new Bdecode()
}

inherits(Stream, Transform)

Stream.prototype._transform = function (chunk, encoding, callback) {
  try {
    this._decoder.decode(chunk, encoding)
    callback(null)
  } catch(err) {
    callback(err)
  }
}

Stream.prototype._flush = function (callback) {
  this.push(this._decoder.result()[0])
  callback(null)
}

},{"stream":undefined,"util":undefined}],22:[function(require,module,exports){
function allocUnsafe (size) {
  if (typeof size !== 'number') {
    throw new TypeError('"size" argument must be a number')
  }

  if (size < 0) {
    throw new RangeError('"size" argument must not be negative')
  }

  if (Buffer.allocUnsafe) {
    return Buffer.allocUnsafe(size)
  } else {
    return new Buffer(size)
  }
}

module.exports = allocUnsafe

},{}],23:[function(require,module,exports){
var bufferFill = require('buffer-fill')
var allocUnsafe = require('buffer-alloc-unsafe')

module.exports = function alloc (size, fill, encoding) {
  if (typeof size !== 'number') {
    throw new TypeError('"size" argument must be a number')
  }

  if (size < 0) {
    throw new RangeError('"size" argument must not be negative')
  }

  if (Buffer.alloc) {
    return Buffer.alloc(size, fill, encoding)
  }

  var buffer = allocUnsafe(size)

  if (size === 0) {
    return buffer
  }

  if (fill === undefined) {
    return bufferFill(buffer, 0)
  }

  if (typeof encoding !== 'string') {
    encoding = undefined
  }

  return bufferFill(buffer, fill, encoding)
}

},{"buffer-alloc-unsafe":22,"buffer-fill":26}],24:[function(require,module,exports){
module.exports = function (a, b) { if (!Buffer.isBuffer(a) || !Buffer.isBuffer(b) || a.length !== b.length) return false; for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false; return true; };
},{}],25:[function(require,module,exports){
'use strict';
module.exports = function (a, b) {
	if (!Buffer.isBuffer(a) || !Buffer.isBuffer(b)) {
		throw new TypeError('Arguments must be Buffers');
	}

	if (a === b) {
		return true;
	}

	if (typeof a.equals === 'function') {
		return a.equals(b);
	}

	if (a.length !== b.length) {
		return false;
	}

	for (var i = 0; i < a.length; i++) {
		if (a[i] !== b[i]) {
			return false;
		}
	}

	return true;
};

},{}],26:[function(require,module,exports){
/* Node.js 6.4.0 and up has full support */
var hasFullSupport = (function () {
  try {
    if (!Buffer.isEncoding('latin1')) {
      return false
    }

    var buf = Buffer.alloc ? Buffer.alloc(4) : new Buffer(4)

    buf.fill('ab', 'ucs2')

    return (buf.toString('hex') === '61006200')
  } catch (_) {
    return false
  }
}())

function isSingleByte (val) {
  return (val.length === 1 && val.charCodeAt(0) < 256)
}

function fillWithNumber (buffer, val, start, end) {
  if (start < 0 || end > buffer.length) {
    throw new RangeError('Out of range index')
  }

  start = start >>> 0
  end = end === undefined ? buffer.length : end >>> 0

  if (end > start) {
    buffer.fill(val, start, end)
  }

  return buffer
}

function fillWithBuffer (buffer, val, start, end) {
  if (start < 0 || end > buffer.length) {
    throw new RangeError('Out of range index')
  }

  if (end <= start) {
    return buffer
  }

  start = start >>> 0
  end = end === undefined ? buffer.length : end >>> 0

  var pos = start
  var len = val.length
  while (pos <= (end - len)) {
    val.copy(buffer, pos)
    pos += len
  }

  if (pos !== end) {
    val.copy(buffer, pos, 0, end - pos)
  }

  return buffer
}

function fill (buffer, val, start, end, encoding) {
  if (hasFullSupport) {
    return buffer.fill(val, start, end, encoding)
  }

  if (typeof val === 'number') {
    return fillWithNumber(buffer, val, start, end)
  }

  if (typeof val === 'string') {
    if (typeof start === 'string') {
      encoding = start
      start = 0
      end = buffer.length
    } else if (typeof end === 'string') {
      encoding = end
      end = buffer.length
    }

    if (encoding !== undefined && typeof encoding !== 'string') {
      throw new TypeError('encoding must be a string')
    }

    if (encoding === 'latin1') {
      encoding = 'binary'
    }

    if (typeof encoding === 'string' && !Buffer.isEncoding(encoding)) {
      throw new TypeError('Unknown encoding: ' + encoding)
    }

    if (val === '') {
      return fillWithNumber(buffer, 0, start, end)
    }

    if (isSingleByte(val)) {
      return fillWithNumber(buffer, val.charCodeAt(0), start, end)
    }

    val = new Buffer(val, encoding)
  }

  if (Buffer.isBuffer(val)) {
    return fillWithBuffer(buffer, val, start, end)
  }

  // Other values (e.g. undefined, boolean, object) results in zero-fill
  return fillWithNumber(buffer, 0, start, end)
}

module.exports = fill

},{}],27:[function(require,module,exports){
/* eslint-disable node/no-deprecated-api */

var toString = Object.prototype.toString

var isModern = (
  typeof Buffer !== 'undefined' &&
  typeof Buffer.alloc === 'function' &&
  typeof Buffer.allocUnsafe === 'function' &&
  typeof Buffer.from === 'function'
)

function isArrayBuffer (input) {
  return toString.call(input).slice(8, -1) === 'ArrayBuffer'
}

function fromArrayBuffer (obj, byteOffset, length) {
  byteOffset >>>= 0

  var maxLength = obj.byteLength - byteOffset

  if (maxLength < 0) {
    throw new RangeError("'offset' is out of bounds")
  }

  if (length === undefined) {
    length = maxLength
  } else {
    length >>>= 0

    if (length > maxLength) {
      throw new RangeError("'length' is out of bounds")
    }
  }

  return isModern
    ? Buffer.from(obj.slice(byteOffset, byteOffset + length))
    : new Buffer(new Uint8Array(obj.slice(byteOffset, byteOffset + length)))
}

function fromString (string, encoding) {
  if (typeof encoding !== 'string' || encoding === '') {
    encoding = 'utf8'
  }

  if (!Buffer.isEncoding(encoding)) {
    throw new TypeError('"encoding" must be a valid string encoding')
  }

  return isModern
    ? Buffer.from(string, encoding)
    : new Buffer(string, encoding)
}

function bufferFrom (value, encodingOrOffset, length) {
  if (typeof value === 'number') {
    throw new TypeError('"value" argument must not be a number')
  }

  if (isArrayBuffer(value)) {
    return fromArrayBuffer(value, encodingOrOffset, length)
  }

  if (typeof value === 'string') {
    return fromString(value, encodingOrOffset)
  }

  return isModern
    ? Buffer.from(value)
    : new Buffer(value)
}

module.exports = bufferFrom

},{}],28:[function(require,module,exports){
var ipaddr = require('ipaddr.js');

var compact2string = function (buf) {
  switch(buf.length) {
  case 6:
    return buf[0] + "." + buf[1] + "." + buf[2] + "." + buf[3] + ":" + buf.readUInt16BE(4);
    break;
  case 18:
    var hexGroups = [];
    for(var i = 0; i < 8; i++) {
      hexGroups.push(buf.readUInt16BE(i * 2).toString(16));
    }
    var host = ipaddr.parse(hexGroups.join(":")).toString();
    return "[" + host + "]:" + buf.readUInt16BE(16);
  default:
    throw new Error("Invalid Compact IP/PORT, It should contain 6 or 18 bytes");
  }
};

compact2string.multi = function (buf) {
  if(buf.length % 6 !== 0)
    throw new Error("buf length isn't multiple of compact IP/PORTs (6 bytes)");

  var output = [];
  for (var i = 0; i <= buf.length - 1; i = i + 6) {
    output.push(compact2string(buf.slice(i, i + 6)));
  }

  return output;
};

compact2string.multi6 = function (buf) {
  if(buf.length % 18 !== 0)
    throw new Error("buf length isn't multiple of compact IP6/PORTs (18 bytes)");

  var output = [];
  for (var i = 0; i <= buf.length - 1; i = i + 18) {
    output.push(compact2string(buf.slice(i, i + 18)));
  }

  return output;
};

module.exports = compact2string;

},{"ipaddr.js":44}],29:[function(require,module,exports){
// Copyright Joyent, Inc. and other Node contributors.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the
// following conditions:
//
// The above copyright notice and this permission notice shall be included
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
// NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
// USE OR OTHER DEALINGS IN THE SOFTWARE.

// NOTE: These type checking functions intentionally don't use `instanceof`
// because it is fragile and can be easily faked with `Object.create()`.

function isArray(arg) {
  if (Array.isArray) {
    return Array.isArray(arg);
  }
  return objectToString(arg) === '[object Array]';
}
exports.isArray = isArray;

function isBoolean(arg) {
  return typeof arg === 'boolean';
}
exports.isBoolean = isBoolean;

function isNull(arg) {
  return arg === null;
}
exports.isNull = isNull;

function isNullOrUndefined(arg) {
  return arg == null;
}
exports.isNullOrUndefined = isNullOrUndefined;

function isNumber(arg) {
  return typeof arg === 'number';
}
exports.isNumber = isNumber;

function isString(arg) {
  return typeof arg === 'string';
}
exports.isString = isString;

function isSymbol(arg) {
  return typeof arg === 'symbol';
}
exports.isSymbol = isSymbol;

function isUndefined(arg) {
  return arg === void 0;
}
exports.isUndefined = isUndefined;

function isRegExp(re) {
  return objectToString(re) === '[object RegExp]';
}
exports.isRegExp = isRegExp;

function isObject(arg) {
  return typeof arg === 'object' && arg !== null;
}
exports.isObject = isObject;

function isDate(d) {
  return objectToString(d) === '[object Date]';
}
exports.isDate = isDate;

function isError(e) {
  return (objectToString(e) === '[object Error]' || e instanceof Error);
}
exports.isError = isError;

function isFunction(arg) {
  return typeof arg === 'function';
}
exports.isFunction = isFunction;

function isPrimitive(arg) {
  return arg === null ||
         typeof arg === 'boolean' ||
         typeof arg === 'number' ||
         typeof arg === 'string' ||
         typeof arg === 'symbol' ||  // ES6 symbol
         typeof arg === 'undefined';
}
exports.isPrimitive = isPrimitive;

exports.isBuffer = require('buffer').Buffer.isBuffer;

function objectToString(o) {
  return Object.prototype.toString.call(o);
}

},{"buffer":undefined}],30:[function(require,module,exports){
/**
 * This is the web browser implementation of `debug()`.
 *
 * Expose `debug()` as the module.
 */

exports = module.exports = require('./debug');
exports.log = log;
exports.formatArgs = formatArgs;
exports.save = save;
exports.load = load;
exports.useColors = useColors;
exports.storage = 'undefined' != typeof chrome
               && 'undefined' != typeof chrome.storage
                  ? chrome.storage.local
                  : localstorage();

/**
 * Colors.
 */

exports.colors = [
  'lightseagreen',
  'forestgreen',
  'goldenrod',
  'dodgerblue',
  'darkorchid',
  'crimson'
];

/**
 * Currently only WebKit-based Web Inspectors, Firefox >= v31,
 * and the Firebug extension (any Firefox version) are known
 * to support "%c" CSS customizations.
 *
 * TODO: add a `localStorage` variable to explicitly enable/disable colors
 */

function useColors() {
  // NB: In an Electron preload script, document will be defined but not fully
  // initialized. Since we know we're in Chrome, we'll just detect this case
  // explicitly
  if (typeof window !== 'undefined' && window.process && window.process.type === 'renderer') {
    return true;
  }

  // is webkit? http://stackoverflow.com/a/16459606/376773
  // document is undefined in react-native: https://github.com/facebook/react-native/pull/1632
  return (typeof document !== 'undefined' && document.documentElement && document.documentElement.style && document.documentElement.style.WebkitAppearance) ||
    // is firebug? http://stackoverflow.com/a/398120/376773
    (typeof window !== 'undefined' && window.console && (window.console.firebug || (window.console.exception && window.console.table))) ||
    // is firefox >= v31?
    // https://developer.mozilla.org/en-US/docs/Tools/Web_Console#Styling_messages
    (typeof navigator !== 'undefined' && navigator.userAgent && navigator.userAgent.toLowerCase().match(/firefox\/(\d+)/) && parseInt(RegExp.$1, 10) >= 31) ||
    // double check webkit in userAgent just in case we are in a worker
    (typeof navigator !== 'undefined' && navigator.userAgent && navigator.userAgent.toLowerCase().match(/applewebkit\/(\d+)/));
}

/**
 * Map %j to `JSON.stringify()`, since no Web Inspectors do that by default.
 */

exports.formatters.j = function(v) {
  try {
    return JSON.stringify(v);
  } catch (err) {
    return '[UnexpectedJSONParseError]: ' + err.message;
  }
};


/**
 * Colorize log arguments if enabled.
 *
 * @api public
 */

function formatArgs(args) {
  var useColors = this.useColors;

  args[0] = (useColors ? '%c' : '')
    + this.namespace
    + (useColors ? ' %c' : ' ')
    + args[0]
    + (useColors ? '%c ' : ' ')
    + '+' + exports.humanize(this.diff);

  if (!useColors) return;

  var c = 'color: ' + this.color;
  args.splice(1, 0, c, 'color: inherit')

  // the final "%c" is somewhat tricky, because there could be other
  // arguments passed either before or after the %c, so we need to
  // figure out the correct index to insert the CSS into
  var index = 0;
  var lastC = 0;
  args[0].replace(/%[a-zA-Z%]/g, function(match) {
    if ('%%' === match) return;
    index++;
    if ('%c' === match) {
      // we only are interested in the *last* %c
      // (the user may have provided their own)
      lastC = index;
    }
  });

  args.splice(lastC, 0, c);
}

/**
 * Invokes `console.log()` when available.
 * No-op when `console.log` is not a "function".
 *
 * @api public
 */

function log() {
  // this hackery is required for IE8/9, where
  // the `console.log` function doesn't have 'apply'
  return 'object' === typeof console
    && console.log
    && Function.prototype.apply.call(console.log, console, arguments);
}

/**
 * Save `namespaces`.
 *
 * @param {String} namespaces
 * @api private
 */

function save(namespaces) {
  try {
    if (null == namespaces) {
      exports.storage.removeItem('debug');
    } else {
      exports.storage.debug = namespaces;
    }
  } catch(e) {}
}

/**
 * Load `namespaces`.
 *
 * @return {String} returns the previously persisted debug modes
 * @api private
 */

function load() {
  var r;
  try {
    r = exports.storage.debug;
  } catch(e) {}

  // If debug isn't set in LS, and we're in Electron, try to load $DEBUG
  if (!r && typeof process !== 'undefined' && 'env' in process) {
    r = process.env.DEBUG;
  }

  return r;
}

/**
 * Enable namespaces listed in `localStorage.debug` initially.
 */

exports.enable(load());

/**
 * Localstorage attempts to return the localstorage.
 *
 * This is necessary because safari throws
 * when a user disables cookies/localstorage
 * and you attempt to access it.
 *
 * @return {LocalStorage}
 * @api private
 */

function localstorage() {
  try {
    return window.localStorage;
  } catch (e) {}
}

},{"./debug":31}],31:[function(require,module,exports){

/**
 * This is the common logic for both the Node.js and web browser
 * implementations of `debug()`.
 *
 * Expose `debug()` as the module.
 */

exports = module.exports = createDebug.debug = createDebug['default'] = createDebug;
exports.coerce = coerce;
exports.disable = disable;
exports.enable = enable;
exports.enabled = enabled;
exports.humanize = require('ms');

/**
 * The currently active debug mode names, and names to skip.
 */

exports.names = [];
exports.skips = [];

/**
 * Map of special "%n" handling functions, for the debug "format" argument.
 *
 * Valid key names are a single, lower or upper-case letter, i.e. "n" and "N".
 */

exports.formatters = {};

/**
 * Previous log timestamp.
 */

var prevTime;

/**
 * Select a color.
 * @param {String} namespace
 * @return {Number}
 * @api private
 */

function selectColor(namespace) {
  var hash = 0, i;

  for (i in namespace) {
    hash  = ((hash << 5) - hash) + namespace.charCodeAt(i);
    hash |= 0; // Convert to 32bit integer
  }

  return exports.colors[Math.abs(hash) % exports.colors.length];
}

/**
 * Create a debugger with the given `namespace`.
 *
 * @param {String} namespace
 * @return {Function}
 * @api public
 */

function createDebug(namespace) {

  function debug() {
    // disabled?
    if (!debug.enabled) return;

    var self = debug;

    // set `diff` timestamp
    var curr = +new Date();
    var ms = curr - (prevTime || curr);
    self.diff = ms;
    self.prev = prevTime;
    self.curr = curr;
    prevTime = curr;

    // turn the `arguments` into a proper Array
    var args = new Array(arguments.length);
    for (var i = 0; i < args.length; i++) {
      args[i] = arguments[i];
    }

    args[0] = exports.coerce(args[0]);

    if ('string' !== typeof args[0]) {
      // anything else let's inspect with %O
      args.unshift('%O');
    }

    // apply any `formatters` transformations
    var index = 0;
    args[0] = args[0].replace(/%([a-zA-Z%])/g, function(match, format) {
      // if we encounter an escaped % then don't increase the array index
      if (match === '%%') return match;
      index++;
      var formatter = exports.formatters[format];
      if ('function' === typeof formatter) {
        var val = args[index];
        match = formatter.call(self, val);

        // now we need to remove `args[index]` since it's inlined in the `format`
        args.splice(index, 1);
        index--;
      }
      return match;
    });

    // apply env-specific formatting (colors, etc.)
    exports.formatArgs.call(self, args);

    var logFn = debug.log || exports.log || console.log.bind(console);
    logFn.apply(self, args);
  }

  debug.namespace = namespace;
  debug.enabled = exports.enabled(namespace);
  debug.useColors = exports.useColors();
  debug.color = selectColor(namespace);

  // env-specific initialization logic for debug instances
  if ('function' === typeof exports.init) {
    exports.init(debug);
  }

  return debug;
}

/**
 * Enables a debug mode by namespaces. This can include modes
 * separated by a colon and wildcards.
 *
 * @param {String} namespaces
 * @api public
 */

function enable(namespaces) {
  exports.save(namespaces);

  exports.names = [];
  exports.skips = [];

  var split = (typeof namespaces === 'string' ? namespaces : '').split(/[\s,]+/);
  var len = split.length;

  for (var i = 0; i < len; i++) {
    if (!split[i]) continue; // ignore empty strings
    namespaces = split[i].replace(/\*/g, '.*?');
    if (namespaces[0] === '-') {
      exports.skips.push(new RegExp('^' + namespaces.substr(1) + '$'));
    } else {
      exports.names.push(new RegExp('^' + namespaces + '$'));
    }
  }
}

/**
 * Disable debug output.
 *
 * @api public
 */

function disable() {
  exports.enable('');
}

/**
 * Returns true if the given mode name is enabled, false otherwise.
 *
 * @param {String} name
 * @return {Boolean}
 * @api public
 */

function enabled(name) {
  var i, len;
  for (i = 0, len = exports.skips.length; i < len; i++) {
    if (exports.skips[i].test(name)) {
      return false;
    }
  }
  for (i = 0, len = exports.names.length; i < len; i++) {
    if (exports.names[i].test(name)) {
      return true;
    }
  }
  return false;
}

/**
 * Coerce `val`.
 *
 * @param {Mixed} val
 * @return {Mixed}
 * @api private
 */

function coerce(val) {
  if (val instanceof Error) return val.stack || val.message;
  return val;
}

},{"ms":54}],32:[function(require,module,exports){
/**
 * Detect Electron renderer process, which is node, but we should
 * treat as a browser.
 */

if (typeof process !== 'undefined' && process.type === 'renderer') {
  module.exports = require('./browser.js');
} else {
  module.exports = require('./node.js');
}

},{"./browser.js":30,"./node.js":33}],33:[function(require,module,exports){
/**
 * Module dependencies.
 */

var tty = require('tty');
var util = require('util');

/**
 * This is the Node.js implementation of `debug()`.
 *
 * Expose `debug()` as the module.
 */

exports = module.exports = require('./debug');
exports.init = init;
exports.log = log;
exports.formatArgs = formatArgs;
exports.save = save;
exports.load = load;
exports.useColors = useColors;

/**
 * Colors.
 */

exports.colors = [6, 2, 3, 4, 5, 1];

/**
 * Build up the default `inspectOpts` object from the environment variables.
 *
 *   $ DEBUG_COLORS=no DEBUG_DEPTH=10 DEBUG_SHOW_HIDDEN=enabled node script.js
 */

exports.inspectOpts = Object.keys(process.env).filter(function (key) {
  return /^debug_/i.test(key);
}).reduce(function (obj, key) {
  // camel-case
  var prop = key
    .substring(6)
    .toLowerCase()
    .replace(/_([a-z])/g, function (_, k) { return k.toUpperCase() });

  // coerce string value into JS value
  var val = process.env[key];
  if (/^(yes|on|true|enabled)$/i.test(val)) val = true;
  else if (/^(no|off|false|disabled)$/i.test(val)) val = false;
  else if (val === 'null') val = null;
  else val = Number(val);

  obj[prop] = val;
  return obj;
}, {});

/**
 * The file descriptor to write the `debug()` calls to.
 * Set the `DEBUG_FD` env variable to override with another value. i.e.:
 *
 *   $ DEBUG_FD=3 node script.js 3>debug.log
 */

var fd = parseInt(process.env.DEBUG_FD, 10) || 2;

if (1 !== fd && 2 !== fd) {
  util.deprecate(function(){}, 'except for stderr(2) and stdout(1), any other usage of DEBUG_FD is deprecated. Override debug.log if you want to use a different log function (https://git.io/debug_fd)')()
}

var stream = 1 === fd ? process.stdout :
             2 === fd ? process.stderr :
             createWritableStdioStream(fd);

/**
 * Is stdout a TTY? Colored output is enabled when `true`.
 */

function useColors() {
  return 'colors' in exports.inspectOpts
    ? Boolean(exports.inspectOpts.colors)
    : tty.isatty(fd);
}

/**
 * Map %o to `util.inspect()`, all on a single line.
 */

exports.formatters.o = function(v) {
  this.inspectOpts.colors = this.useColors;
  return util.inspect(v, this.inspectOpts)
    .split('\n').map(function(str) {
      return str.trim()
    }).join(' ');
};

/**
 * Map %o to `util.inspect()`, allowing multiple lines if needed.
 */

exports.formatters.O = function(v) {
  this.inspectOpts.colors = this.useColors;
  return util.inspect(v, this.inspectOpts);
};

/**
 * Adds ANSI color escape codes if enabled.
 *
 * @api public
 */

function formatArgs(args) {
  var name = this.namespace;
  var useColors = this.useColors;

  if (useColors) {
    var c = this.color;
    var prefix = '  \u001b[3' + c + ';1m' + name + ' ' + '\u001b[0m';

    args[0] = prefix + args[0].split('\n').join('\n' + prefix);
    args.push('\u001b[3' + c + 'm+' + exports.humanize(this.diff) + '\u001b[0m');
  } else {
    args[0] = new Date().toUTCString()
      + ' ' + name + ' ' + args[0];
  }
}

/**
 * Invokes `util.format()` with the specified arguments and writes to `stream`.
 */

function log() {
  return stream.write(util.format.apply(util, arguments) + '\n');
}

/**
 * Save `namespaces`.
 *
 * @param {String} namespaces
 * @api private
 */

function save(namespaces) {
  if (null == namespaces) {
    // If you set a process.env field to null or undefined, it gets cast to the
    // string 'null' or 'undefined'. Just delete instead.
    delete process.env.DEBUG;
  } else {
    process.env.DEBUG = namespaces;
  }
}

/**
 * Load `namespaces`.
 *
 * @return {String} returns the previously persisted debug modes
 * @api private
 */

function load() {
  return process.env.DEBUG;
}

/**
 * Copied from `node/src/node.js`.
 *
 * XXX: It's lame that node doesn't expose this API out-of-the-box. It also
 * relies on the undocumented `tty_wrap.guessHandleType()` which is also lame.
 */

function createWritableStdioStream (fd) {
  var stream;
  var tty_wrap = process.binding('tty_wrap');

  // Note stream._type is used for test-module-load-list.js

  switch (tty_wrap.guessHandleType(fd)) {
    case 'TTY':
      stream = new tty.WriteStream(fd);
      stream._type = 'tty';

      // Hack to have stream not keep the event loop alive.
      // See https://github.com/joyent/node/issues/1726
      if (stream._handle && stream._handle.unref) {
        stream._handle.unref();
      }
      break;

    case 'FILE':
      var fs = require('fs');
      stream = new fs.SyncWriteStream(fd, { autoClose: false });
      stream._type = 'fs';
      break;

    case 'PIPE':
    case 'TCP':
      var net = require('net');
      stream = new net.Socket({
        fd: fd,
        readable: false,
        writable: true
      });

      // FIXME Should probably have an option in net.Socket to create a
      // stream from an existing fd which is writable only. But for now
      // we'll just add this hack and set the `readable` member to false.
      // Test: ./node test/fixtures/echo.js < /etc/passwd
      stream.readable = false;
      stream.read = null;
      stream._type = 'pipe';

      // FIXME Hack to have stream not keep the event loop alive.
      // See https://github.com/joyent/node/issues/1726
      if (stream._handle && stream._handle.unref) {
        stream._handle.unref();
      }
      break;

    default:
      // Probably an error on in uv_guess_handle()
      throw new Error('Implement me. Unknown stream file type!');
  }

  // For supporting legacy API we put the FD here.
  stream.fd = fd;

  stream._isStdio = true;

  return stream;
}

/**
 * Init logic for `debug` instances.
 *
 * Create a new `inspectOpts` object in case `useColors` is set
 * differently for a particular `debug` instance.
 */

function init (debug) {
  debug.inspectOpts = {};

  var keys = Object.keys(exports.inspectOpts);
  for (var i = 0; i < keys.length; i++) {
    debug.inspectOpts[keys[i]] = exports.inspectOpts[keys[i]];
  }
}

/**
 * Enable namespaces listed in `process.env.DEBUG` initially.
 */

exports.enable(load());

},{"./debug":31,"fs":undefined,"net":undefined,"tty":undefined,"util":undefined}],34:[function(require,module,exports){
var once = require('once');

var noop = function() {};

var isRequest = function(stream) {
	return stream.setHeader && typeof stream.abort === 'function';
};

var eos = function(stream, opts, callback) {
	if (typeof opts === 'function') return eos(stream, null, opts);
	if (!opts) opts = {};

	callback = once(callback || noop);

	var ws = stream._writableState;
	var rs = stream._readableState;
	var readable = opts.readable || (opts.readable !== false && stream.readable);
	var writable = opts.writable || (opts.writable !== false && stream.writable);

	var onlegacyfinish = function() {
		if (!stream.writable) onfinish();
	};

	var onfinish = function() {
		writable = false;
		if (!readable) callback();
	};

	var onend = function() {
		readable = false;
		if (!writable) callback();
	};

	var onclose = function() {
		if (readable && !(rs && rs.ended)) return callback(new Error('premature close'));
		if (writable && !(ws && ws.ended)) return callback(new Error('premature close'));
	};

	var onrequest = function() {
		stream.req.on('finish', onfinish);
	};

	if (isRequest(stream)) {
		stream.on('complete', onfinish);
		stream.on('abort', onclose);
		if (stream.req) onrequest();
		else stream.on('request', onrequest);
	} else if (writable && !ws) { // legacy streams
		stream.on('end', onlegacyfinish);
		stream.on('close', onlegacyfinish);
	}

	stream.on('end', onend);
	stream.on('finish', onfinish);
	if (opts.error !== false) stream.on('error', callback);
	stream.on('close', onclose);

	return stream;
};

module.exports = eos;
},{"once":35}],35:[function(require,module,exports){
var wrappy = require('wrappy')
module.exports = wrappy(once)

once.proto = once(function () {
  Object.defineProperty(Function.prototype, 'once', {
    value: function () {
      return once(this)
    },
    configurable: true
  })
})

function once (fn) {
  var f = function () {
    if (f.called) return f.value
    f.called = true
    return f.value = fn.apply(this, arguments)
  }
  f.called = false
  return f
}

},{"wrappy":87}],36:[function(require,module,exports){
var Node = function(list, val) {
	this.prev = this.next = this;
	this.value = val;
	this.list = list;
};

Node.prototype.link = function(next) {
	this.next = next;
	next.prev = this;
	return next;
};

var FIFO = function() {
	if (!(this instanceof FIFO)) return new FIFO();
	this.node = null;
	this.length = 0;
};

FIFO.prototype.set = function(node, value) {
	if (!node || node.list !== this) return null;
	node.value = value;
	return node;
};

FIFO.prototype.get = function(node) {
	if (!node || node.list !== this) return null;
	return node.value;
};

FIFO.prototype.remove = function(node) {
	if (!node || node.list !== this) return null;
	this.length--;
	node.list = null;
	node.prev.link(node.next);
	if (node === this.node) this.node = node.next === node ? null : node.next;
	return node.value;
};

FIFO.prototype.unshift = function(value) {
	return this.node = this.push(value);
};

FIFO.prototype.push = function(value) {
	var node = new Node(this, value);
	this.length++;
	if (!this.node) return this.node = node;
	this.node.prev.link(node);
	node.link(this.node);
	return node;
};

FIFO.prototype.first = function() {
	return this.node && this.node.value;
};

FIFO.prototype.last = function() {
	return this.node && this.node.prev.value;
};

FIFO.prototype.shift = function() {
	return this.node && this.remove(this.node);
};

FIFO.prototype.pop = function() {
	return this.node && this.remove(this.node.prev);
};

module.exports = FIFO;
},{}],37:[function(require,module,exports){
module.exports = function flat(values) { var result = []; values.forEach(function (value) { if (Array.isArray(value)) result = result.concat(flat(value)); else result.push(value); }); return result; };
},{}],38:[function(require,module,exports){
module.exports = function (bits) { return require("crypto").randomBytes(Math.ceil(bits / 8)).toString("hex").slice(0, Math.ceil(bits / 4)); };
},{"crypto":undefined}],39:[function(require,module,exports){
module.exports = ImmediateStore

function ImmediateStore (store) {
  if (!(this instanceof ImmediateStore)) return new ImmediateStore(store)

  this.store = store
  this.chunkLength = store.chunkLength

  if (!this.store || !this.store.get || !this.store.put) {
    throw new Error('First argument must be abstract-chunk-store compliant')
  }

  this.mem = []
}

ImmediateStore.prototype.put = function (index, buf, cb) {
  var self = this
  self.mem[index] = buf
  self.store.put(index, buf, function (err) {
    self.mem[index] = null
    if (cb) cb(err)
  })
}

ImmediateStore.prototype.get = function (index, opts, cb) {
  if (typeof opts === 'function') return this.get(index, null, opts)

  var start = (opts && opts.offset) || 0
  var end = opts && opts.length && (start + opts.length)

  var buf = this.mem[index]
  if (buf) return nextTick(cb, null, opts ? buf.slice(start, end) : buf)

  this.store.get(index, opts, cb)
}

ImmediateStore.prototype.close = function (cb) {
  this.store.close(cb)
}

ImmediateStore.prototype.destroy = function (cb) {
  this.store.destroy(cb)
}

function nextTick (cb, err, val) {
  process.nextTick(function () {
    if (cb) cb(err, val)
  })
}

},{}],40:[function(require,module,exports){
try {
  var util = require('util');
  /* istanbul ignore next */
  if (typeof util.inherits !== 'function') throw '';
  module.exports = util.inherits;
} catch (e) {
  /* istanbul ignore next */
  module.exports = require('./inherits_browser.js');
}

},{"./inherits_browser.js":41,"util":undefined}],41:[function(require,module,exports){
if (typeof Object.create === 'function') {
  // implementation from standard node.js 'util' module
  module.exports = function inherits(ctor, superCtor) {
    if (superCtor) {
      ctor.super_ = superCtor
      ctor.prototype = Object.create(superCtor.prototype, {
        constructor: {
          value: ctor,
          enumerable: false,
          writable: true,
          configurable: true
        }
      })
    }
  };
} else {
  // old school shim for old browsers
  module.exports = function inherits(ctor, superCtor) {
    if (superCtor) {
      ctor.super_ = superCtor
      var TempCtor = function () {}
      TempCtor.prototype = superCtor.prototype
      ctor.prototype = new TempCtor()
      ctor.prototype.constructor = ctor
    }
  }
}

},{}],42:[function(require,module,exports){

var ip = require('ip')

function IPSet (start, end) {
  this.start = start
  this.end   = end
  this.max   = end
  this.depth = 1
  this.left  = null
  this.right = null
}

IPSet.prototype.add = function (start, end) {
  var d = start - this.start
  var update = false

  if (d === 0 && this.end < end) {
    this.end = end
    update = true
  } else if (d < 0) {
    if (this.left) {
      update = this.left.add(start, end)
      if (update) this._balance()
    } else {
      this.left = new IPSet(start, end)
      update = true
    }
  } else if (d > 0) {
    if (this.right) {
      update = this.right.add(start, end)
      if (update) this._balance()
    } else {
      this.right = new IPSet(start, end)
      update = true
    }
  }

  if (update) this._update()
  return update
}

IPSet.prototype.contains = function (addr) {
  var node = this
  while (node && !(addr >= node.start && addr <= node.end)) {
    if (node.left && node.left.max >= addr) node = node.left
    else node = node.right
  }
  return !!node
}

IPSet.prototype._balance = function () {
  var ldepth = this.left ? this.left.depth : 0
  var rdepth = this.right ? this.right.depth : 0

  if (ldepth > rdepth + 1) {
    var lldepth = this.left.left ? this.left.left.depth : 0
    var lrdepth = this.left.right ? this.left.right.depth : 0
    if (lldepth < lrdepth) this.left._rotateRR()
    this._rotateLL()
  } else if (ldepth + 1 < rdepth) {
    var rrdepth = this.right.right ? this.right.right.depth : 0
    var rldepth = this.right.left ? this.right.left.depth : 0
    if (rldepth > rrdepth) this.right._rotateLL()
    this._rotateRR()
  }
}

IPSet.prototype._rotateLL = function () {
  var _start = this.start
  var _end   = this.end
  var _right = this.right

  this.start = this.left.start
  this.end   = this.left.end
  this.right = this.left
  this.left  = this.left.left

  this.right.left  = this.right.right
  this.right.right = _right
  this.right.start = _start
  this.right.end   = _end

  this.right._update()
  this._update()
}

IPSet.prototype._rotateRR = function () {
  var _start = this.start
  var _end   = this.end
  var _left  = this.left

  this.start = this.right.start
  this.end   = this.right.end
  this.end   = this.right.end
  this.left  = this.right
  this.right = this.right.right

  this.left.right = this.left.left
  this.left.left  = _left
  this.left.start = _start
  this.left.end   = _end

  this.left._update()
  this._update()
}

IPSet.prototype._update = function () {
  this.depth = 1
  if (this.left) this.depth = this.left.depth + 1
  if (this.right && this.depth <= this.right.depth) this.depth = this.right.depth + 1
  this.max = Math.max(this.end, this.left ? this.left.max : 0, this.right ? this.right.max : 0)
}

module.exports = function (blocklist) {
  var tree = null
  var self = {}

  self.add = function (start, end) {
    if (!start) return
    if (typeof start === 'object') {
      end = start.end
      start = start.start
    }

    var cidrStr = /\/\d{1,2}/;
    if (typeof start === 'string' && cidrStr.test(start)) {
      var ipSubnet = ip.cidrSubnet(start);
      start = ipSubnet.networkAddress;
      end = ipSubnet.broadcastAddress;
    }
    if (typeof start !== 'number') start = ip.toLong(start)
    
    if (!end) end = start
    if (typeof end !== 'number') end = ip.toLong(end)

    if (start < 0 || end > 4294967295 || end < start) throw new Error('Invalid block range')

    if (tree) tree.add(start, end)
    else tree = new IPSet(start, end)
  }

  self.contains = function (addr) {
    if (!tree) return false
    if (typeof addr !== 'number') addr = ip.toLong(addr)
    return tree.contains(addr)
  }

  if (Array.isArray(blocklist)) {
    blocklist.forEach(function (block) {
      self.add(block)
    })
  }

  return self
}


},{"ip":43}],43:[function(require,module,exports){
'use strict';

var ip = exports;
var Buffer = require('buffer').Buffer;
var os = require('os');

ip.toBuffer = function(ip, buff, offset) {
  offset = ~~offset;

  var result;

  if (this.isV4Format(ip)) {
    result = buff || new Buffer(offset + 4);
    ip.split(/\./g).map(function(byte) {
      result[offset++] = parseInt(byte, 10) & 0xff;
    });
  } else if (this.isV6Format(ip)) {
    var sections = ip.split(':', 8);

    var i;
    for (i = 0; i < sections.length; i++) {
      var isv4 = this.isV4Format(sections[i]);
      var v4Buffer;

      if (isv4) {
        v4Buffer = this.toBuffer(sections[i]);
        sections[i] = v4Buffer.slice(0, 2).toString('hex');
      }

      if (v4Buffer && ++i < 8) {
        sections.splice(i, 0, v4Buffer.slice(2, 4).toString('hex'));
      }
    }

    if (sections[0] === '') {
      while (sections.length < 8) sections.unshift('0');
    } else if (sections[sections.length - 1] === '') {
      while (sections.length < 8) sections.push('0');
    } else if (sections.length < 8) {
      for (i = 0; i < sections.length && sections[i] !== ''; i++);
      var argv = [ i, 1 ];
      for (i = 9 - sections.length; i > 0; i--) {
        argv.push('0');
      }
      sections.splice.apply(sections, argv);
    }

    result = buff || new Buffer(offset + 16);
    for (i = 0; i < sections.length; i++) {
      var word = parseInt(sections[i], 16);
      result[offset++] = (word >> 8) & 0xff;
      result[offset++] = word & 0xff;
    }
  }

  if (!result) {
    throw Error('Invalid ip address: ' + ip);
  }

  return result;
};

ip.toString = function(buff, offset, length) {
  offset = ~~offset;
  length = length || (buff.length - offset);

  var result = [];
  if (length === 4) {
    // IPv4
    for (var i = 0; i < length; i++) {
      result.push(buff[offset + i]);
    }
    result = result.join('.');
  } else if (length === 16) {
    // IPv6
    for (var i = 0; i < length; i += 2) {
      result.push(buff.readUInt16BE(offset + i).toString(16));
    }
    result = result.join(':');
    result = result.replace(/(^|:)0(:0)*:0(:|$)/, '$1::$3');
    result = result.replace(/:{3,4}/, '::');
  }

  return result;
};

var ipv4Regex = /^(\d{1,3}\.){3,3}\d{1,3}$/;
var ipv6Regex =
    /^(::)?(((\d{1,3}\.){3}(\d{1,3}){1})?([0-9a-f]){0,4}:{0,2}){1,8}(::)?$/i;

ip.isV4Format = function(ip) {
  return ipv4Regex.test(ip);
};

ip.isV6Format = function(ip) {
  return ipv6Regex.test(ip);
};
function _normalizeFamily(family) {
  return family ? family.toLowerCase() : 'ipv4';
}

ip.fromPrefixLen = function(prefixlen, family) {
  if (prefixlen > 32) {
    family = 'ipv6';
  } else {
    family = _normalizeFamily(family);
  }

  var len = 4;
  if (family === 'ipv6') {
    len = 16;
  }
  var buff = new Buffer(len);

  for (var i = 0, n = buff.length; i < n; ++i) {
    var bits = 8;
    if (prefixlen < 8) {
      bits = prefixlen;
    }
    prefixlen -= bits;

    buff[i] = ~(0xff >> bits) & 0xff;
  }

  return ip.toString(buff);
};

ip.mask = function(addr, mask) {
  addr = ip.toBuffer(addr);
  mask = ip.toBuffer(mask);

  var result = new Buffer(Math.max(addr.length, mask.length));

  var i = 0;
  // Same protocol - do bitwise and
  if (addr.length === mask.length) {
    for (i = 0; i < addr.length; i++) {
      result[i] = addr[i] & mask[i];
    }
  } else if (mask.length === 4) {
    // IPv6 address and IPv4 mask
    // (Mask low bits)
    for (i = 0; i < mask.length; i++) {
      result[i] = addr[addr.length - 4  + i] & mask[i];
    }
  } else {
    // IPv6 mask and IPv4 addr
    for (var i = 0; i < result.length - 6; i++) {
      result[i] = 0;
    }

    // ::ffff:ipv4
    result[10] = 0xff;
    result[11] = 0xff;
    for (i = 0; i < addr.length; i++) {
      result[i + 12] = addr[i] & mask[i + 12];
    }
    i = i + 12;
  }
  for (; i < result.length; i++)
    result[i] = 0;

  return ip.toString(result);
};

ip.cidr = function(cidrString) {
  var cidrParts = cidrString.split('/');

  var addr = cidrParts[0];
  if (cidrParts.length !== 2)
    throw new Error('invalid CIDR subnet: ' + addr);

  var mask = ip.fromPrefixLen(parseInt(cidrParts[1], 10));

  return ip.mask(addr, mask);
};

ip.subnet = function(addr, mask) {
  var networkAddress = ip.toLong(ip.mask(addr, mask));

  // Calculate the mask's length.
  var maskBuffer = ip.toBuffer(mask);
  var maskLength = 0;

  for (var i = 0; i < maskBuffer.length; i++) {
    if (maskBuffer[i] === 0xff) {
      maskLength += 8;
    } else {
      var octet = maskBuffer[i] & 0xff;
      while (octet) {
        octet = (octet << 1) & 0xff;
        maskLength++;
      }
    }
  }

  var numberOfAddresses = Math.pow(2, 32 - maskLength);

  return {
    networkAddress: ip.fromLong(networkAddress),
    firstAddress: numberOfAddresses <= 2 ?
                    ip.fromLong(networkAddress) :
                    ip.fromLong(networkAddress + 1),
    lastAddress: numberOfAddresses <= 2 ?
                    ip.fromLong(networkAddress + numberOfAddresses - 1) :
                    ip.fromLong(networkAddress + numberOfAddresses - 2),
    broadcastAddress: ip.fromLong(networkAddress + numberOfAddresses - 1),
    subnetMask: mask,
    subnetMaskLength: maskLength,
    numHosts: numberOfAddresses <= 2 ?
                numberOfAddresses : numberOfAddresses - 2,
    length: numberOfAddresses,
    contains: function(other) {
      return networkAddress === ip.toLong(ip.mask(other, mask));
    }
  };
};

ip.cidrSubnet = function(cidrString) {
  var cidrParts = cidrString.split('/');

  var addr = cidrParts[0];
  if (cidrParts.length !== 2)
    throw new Error('invalid CIDR subnet: ' + addr);

  var mask = ip.fromPrefixLen(parseInt(cidrParts[1], 10));

  return ip.subnet(addr, mask);
};

ip.not = function(addr) {
  var buff = ip.toBuffer(addr);
  for (var i = 0; i < buff.length; i++) {
    buff[i] = 0xff ^ buff[i];
  }
  return ip.toString(buff);
};

ip.or = function(a, b) {
  a = ip.toBuffer(a);
  b = ip.toBuffer(b);

  // same protocol
  if (a.length === b.length) {
    for (var i = 0; i < a.length; ++i) {
      a[i] |= b[i];
    }
    return ip.toString(a);

  // mixed protocols
  } else {
    var buff = a;
    var other = b;
    if (b.length > a.length) {
      buff = b;
      other = a;
    }

    var offset = buff.length - other.length;
    for (var i = offset; i < buff.length; ++i) {
      buff[i] |= other[i - offset];
    }

    return ip.toString(buff);
  }
};

ip.isEqual = function(a, b) {
  a = ip.toBuffer(a);
  b = ip.toBuffer(b);

  // Same protocol
  if (a.length === b.length) {
    for (var i = 0; i < a.length; i++) {
      if (a[i] !== b[i]) return false;
    }
    return true;
  }

  // Swap
  if (b.length === 4) {
    var t = b;
    b = a;
    a = t;
  }

  // a - IPv4, b - IPv6
  for (var i = 0; i < 10; i++) {
    if (b[i] !== 0) return false;
  }

  var word = b.readUInt16BE(10);
  if (word !== 0 && word !== 0xffff) return false;

  for (var i = 0; i < 4; i++) {
    if (a[i] !== b[i + 12]) return false;
  }

  return true;
};

ip.isPrivate = function(addr) {
  return /^(::f{4}:)?10\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$/i
      .test(addr) ||
    /^(::f{4}:)?192\.168\.([0-9]{1,3})\.([0-9]{1,3})$/i.test(addr) ||
    /^(::f{4}:)?172\.(1[6-9]|2\d|30|31)\.([0-9]{1,3})\.([0-9]{1,3})$/i
      .test(addr) ||
    /^(::f{4}:)?127\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$/i.test(addr) ||
    /^(::f{4}:)?169\.254\.([0-9]{1,3})\.([0-9]{1,3})$/i.test(addr) ||
    /^f[cd][0-9a-f]{2}:/i.test(addr) ||
    /^fe80:/i.test(addr) ||
    /^::1$/.test(addr) ||
    /^::$/.test(addr);
};

ip.isPublic = function(addr) {
  return !ip.isPrivate(addr);
};

ip.isLoopback = function(addr) {
  return /^(::f{4}:)?127\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})/
      .test(addr) ||
    /^fe80::1$/.test(addr) ||
    /^::1$/.test(addr) ||
    /^::$/.test(addr);
};

ip.loopback = function(family) {
  //
  // Default to `ipv4`
  //
  family = _normalizeFamily(family);

  if (family !== 'ipv4' && family !== 'ipv6') {
    throw new Error('family must be ipv4 or ipv6');
  }

  return family === 'ipv4' ? '127.0.0.1' : 'fe80::1';
};

//
// ### function address (name, family)
// #### @name {string|'public'|'private'} **Optional** Name or security
//      of the network interface.
// #### @family {ipv4|ipv6} **Optional** IP family of the address (defaults
//      to ipv4).
//
// Returns the address for the network interface on the current system with
// the specified `name`:
//   * String: First `family` address of the interface.
//             If not found see `undefined`.
//   * 'public': the first public ip address of family.
//   * 'private': the first private ip address of family.
//   * undefined: First address with `ipv4` or loopback address `127.0.0.1`.
//
ip.address = function(name, family) {
  var interfaces = os.networkInterfaces();
  var all;

  //
  // Default to `ipv4`
  //
  family = _normalizeFamily(family);

  //
  // If a specific network interface has been named,
  // return the address.
  //
  if (name && name !== 'private' && name !== 'public') {
    var res = interfaces[name].filter(function(details) {
      var itemFamily = details.family.toLowerCase();
      return itemFamily === family;
    });
    if (res.length === 0)
      return undefined;
    return res[0].address;
  }

  var all = Object.keys(interfaces).map(function (nic) {
    //
    // Note: name will only be `public` or `private`
    // when this is called.
    //
    var addresses = interfaces[nic].filter(function (details) {
      details.family = details.family.toLowerCase();
      if (details.family !== family || ip.isLoopback(details.address)) {
        return false;
      } else if (!name) {
        return true;
      }

      return name === 'public' ? ip.isPrivate(details.address) :
          ip.isPublic(details.address);
    });

    return addresses.length ? addresses[0].address : undefined;
  }).filter(Boolean);

  return !all.length ? ip.loopback(family) : all[0];
};

ip.toLong = function(ip) {
  var ipl = 0;
  ip.split('.').forEach(function(octet) {
    ipl <<= 8;
    ipl += parseInt(octet);
  });
  return(ipl >>> 0);
};

ip.fromLong = function(ipl) {
  return ((ipl >>> 24) + '.' +
      (ipl >> 16 & 255) + '.' +
      (ipl >> 8 & 255) + '.' +
      (ipl & 255) );
};

},{"buffer":undefined,"os":undefined}],44:[function(require,module,exports){
(function() {
  var expandIPv6, ipaddr, ipv4Part, ipv4Regexes, ipv6Part, ipv6Regexes, matchCIDR, root, zoneIndex;

  ipaddr = {};

  root = this;

  if ((typeof module !== "undefined" && module !== null) && module.exports) {
    module.exports = ipaddr;
  } else {
    root['ipaddr'] = ipaddr;
  }

  matchCIDR = function(first, second, partSize, cidrBits) {
    var part, shift;
    if (first.length !== second.length) {
      throw new Error("ipaddr: cannot match CIDR for objects with different lengths");
    }
    part = 0;
    while (cidrBits > 0) {
      shift = partSize - cidrBits;
      if (shift < 0) {
        shift = 0;
      }
      if (first[part] >> shift !== second[part] >> shift) {
        return false;
      }
      cidrBits -= partSize;
      part += 1;
    }
    return true;
  };

  ipaddr.subnetMatch = function(address, rangeList, defaultName) {
    var k, len, rangeName, rangeSubnets, subnet;
    if (defaultName == null) {
      defaultName = 'unicast';
    }
    for (rangeName in rangeList) {
      rangeSubnets = rangeList[rangeName];
      if (rangeSubnets[0] && !(rangeSubnets[0] instanceof Array)) {
        rangeSubnets = [rangeSubnets];
      }
      for (k = 0, len = rangeSubnets.length; k < len; k++) {
        subnet = rangeSubnets[k];
        if (address.kind() === subnet[0].kind()) {
          if (address.match.apply(address, subnet)) {
            return rangeName;
          }
        }
      }
    }
    return defaultName;
  };

  ipaddr.IPv4 = (function() {
    function IPv4(octets) {
      var k, len, octet;
      if (octets.length !== 4) {
        throw new Error("ipaddr: ipv4 octet count should be 4");
      }
      for (k = 0, len = octets.length; k < len; k++) {
        octet = octets[k];
        if (!((0 <= octet && octet <= 255))) {
          throw new Error("ipaddr: ipv4 octet should fit in 8 bits");
        }
      }
      this.octets = octets;
    }

    IPv4.prototype.kind = function() {
      return 'ipv4';
    };

    IPv4.prototype.toString = function() {
      return this.octets.join(".");
    };

    IPv4.prototype.toNormalizedString = function() {
      return this.toString();
    };

    IPv4.prototype.toByteArray = function() {
      return this.octets.slice(0);
    };

    IPv4.prototype.match = function(other, cidrRange) {
      var ref;
      if (cidrRange === void 0) {
        ref = other, other = ref[0], cidrRange = ref[1];
      }
      if (other.kind() !== 'ipv4') {
        throw new Error("ipaddr: cannot match ipv4 address with non-ipv4 one");
      }
      return matchCIDR(this.octets, other.octets, 8, cidrRange);
    };

    IPv4.prototype.SpecialRanges = {
      unspecified: [[new IPv4([0, 0, 0, 0]), 8]],
      broadcast: [[new IPv4([255, 255, 255, 255]), 32]],
      multicast: [[new IPv4([224, 0, 0, 0]), 4]],
      linkLocal: [[new IPv4([169, 254, 0, 0]), 16]],
      loopback: [[new IPv4([127, 0, 0, 0]), 8]],
      carrierGradeNat: [[new IPv4([100, 64, 0, 0]), 10]],
      "private": [[new IPv4([10, 0, 0, 0]), 8], [new IPv4([172, 16, 0, 0]), 12], [new IPv4([192, 168, 0, 0]), 16]],
      reserved: [[new IPv4([192, 0, 0, 0]), 24], [new IPv4([192, 0, 2, 0]), 24], [new IPv4([192, 88, 99, 0]), 24], [new IPv4([198, 51, 100, 0]), 24], [new IPv4([203, 0, 113, 0]), 24], [new IPv4([240, 0, 0, 0]), 4]]
    };

    IPv4.prototype.range = function() {
      return ipaddr.subnetMatch(this, this.SpecialRanges);
    };

    IPv4.prototype.toIPv4MappedAddress = function() {
      return ipaddr.IPv6.parse("::ffff:" + (this.toString()));
    };

    IPv4.prototype.prefixLengthFromSubnetMask = function() {
      var cidr, i, k, octet, stop, zeros, zerotable;
      zerotable = {
        0: 8,
        128: 7,
        192: 6,
        224: 5,
        240: 4,
        248: 3,
        252: 2,
        254: 1,
        255: 0
      };
      cidr = 0;
      stop = false;
      for (i = k = 3; k >= 0; i = k += -1) {
        octet = this.octets[i];
        if (octet in zerotable) {
          zeros = zerotable[octet];
          if (stop && zeros !== 0) {
            return null;
          }
          if (zeros !== 8) {
            stop = true;
          }
          cidr += zeros;
        } else {
          return null;
        }
      }
      return 32 - cidr;
    };

    return IPv4;

  })();

  ipv4Part = "(0?\\d+|0x[a-f0-9]+)";

  ipv4Regexes = {
    fourOctet: new RegExp("^" + ipv4Part + "\\." + ipv4Part + "\\." + ipv4Part + "\\." + ipv4Part + "$", 'i'),
    longValue: new RegExp("^" + ipv4Part + "$", 'i')
  };

  ipaddr.IPv4.parser = function(string) {
    var match, parseIntAuto, part, shift, value;
    parseIntAuto = function(string) {
      if (string[0] === "0" && string[1] !== "x") {
        return parseInt(string, 8);
      } else {
        return parseInt(string);
      }
    };
    if (match = string.match(ipv4Regexes.fourOctet)) {
      return (function() {
        var k, len, ref, results;
        ref = match.slice(1, 6);
        results = [];
        for (k = 0, len = ref.length; k < len; k++) {
          part = ref[k];
          results.push(parseIntAuto(part));
        }
        return results;
      })();
    } else if (match = string.match(ipv4Regexes.longValue)) {
      value = parseIntAuto(match[1]);
      if (value > 0xffffffff || value < 0) {
        throw new Error("ipaddr: address outside defined range");
      }
      return ((function() {
        var k, results;
        results = [];
        for (shift = k = 0; k <= 24; shift = k += 8) {
          results.push((value >> shift) & 0xff);
        }
        return results;
      })()).reverse();
    } else {
      return null;
    }
  };

  ipaddr.IPv6 = (function() {
    function IPv6(parts, zoneId) {
      var i, k, l, len, part, ref;
      if (parts.length === 16) {
        this.parts = [];
        for (i = k = 0; k <= 14; i = k += 2) {
          this.parts.push((parts[i] << 8) | parts[i + 1]);
        }
      } else if (parts.length === 8) {
        this.parts = parts;
      } else {
        throw new Error("ipaddr: ipv6 part count should be 8 or 16");
      }
      ref = this.parts;
      for (l = 0, len = ref.length; l < len; l++) {
        part = ref[l];
        if (!((0 <= part && part <= 0xffff))) {
          throw new Error("ipaddr: ipv6 part should fit in 16 bits");
        }
      }
      if (zoneId) {
        this.zoneId = zoneId;
      }
    }

    IPv6.prototype.kind = function() {
      return 'ipv6';
    };

    IPv6.prototype.toString = function() {
      return this.toNormalizedString().replace(/((^|:)(0(:|$))+)/, '::');
    };

    IPv6.prototype.toRFC5952String = function() {
      var bestMatchIndex, bestMatchLength, match, regex, string;
      regex = /((^|:)(0(:|$)){2,})/g;
      string = this.toNormalizedString();
      bestMatchIndex = 0;
      bestMatchLength = -1;
      while ((match = regex.exec(string))) {
        if (match[0].length > bestMatchLength) {
          bestMatchIndex = match.index;
          bestMatchLength = match[0].length;
        }
      }
      if (bestMatchLength < 0) {
        return string;
      }
      return string.substring(0, bestMatchIndex) + '::' + string.substring(bestMatchIndex + bestMatchLength);
    };

    IPv6.prototype.toByteArray = function() {
      var bytes, k, len, part, ref;
      bytes = [];
      ref = this.parts;
      for (k = 0, len = ref.length; k < len; k++) {
        part = ref[k];
        bytes.push(part >> 8);
        bytes.push(part & 0xff);
      }
      return bytes;
    };

    IPv6.prototype.toNormalizedString = function() {
      var addr, part, suffix;
      addr = ((function() {
        var k, len, ref, results;
        ref = this.parts;
        results = [];
        for (k = 0, len = ref.length; k < len; k++) {
          part = ref[k];
          results.push(part.toString(16));
        }
        return results;
      }).call(this)).join(":");
      suffix = '';
      if (this.zoneId) {
        suffix = '%' + this.zoneId;
      }
      return addr + suffix;
    };

    IPv6.prototype.toFixedLengthString = function() {
      var addr, part, suffix;
      addr = ((function() {
        var k, len, ref, results;
        ref = this.parts;
        results = [];
        for (k = 0, len = ref.length; k < len; k++) {
          part = ref[k];
          results.push(part.toString(16).padStart(4, '0'));
        }
        return results;
      }).call(this)).join(":");
      suffix = '';
      if (this.zoneId) {
        suffix = '%' + this.zoneId;
      }
      return addr + suffix;
    };

    IPv6.prototype.match = function(other, cidrRange) {
      var ref;
      if (cidrRange === void 0) {
        ref = other, other = ref[0], cidrRange = ref[1];
      }
      if (other.kind() !== 'ipv6') {
        throw new Error("ipaddr: cannot match ipv6 address with non-ipv6 one");
      }
      return matchCIDR(this.parts, other.parts, 16, cidrRange);
    };

    IPv6.prototype.SpecialRanges = {
      unspecified: [new IPv6([0, 0, 0, 0, 0, 0, 0, 0]), 128],
      linkLocal: [new IPv6([0xfe80, 0, 0, 0, 0, 0, 0, 0]), 10],
      multicast: [new IPv6([0xff00, 0, 0, 0, 0, 0, 0, 0]), 8],
      loopback: [new IPv6([0, 0, 0, 0, 0, 0, 0, 1]), 128],
      uniqueLocal: [new IPv6([0xfc00, 0, 0, 0, 0, 0, 0, 0]), 7],
      ipv4Mapped: [new IPv6([0, 0, 0, 0, 0, 0xffff, 0, 0]), 96],
      rfc6145: [new IPv6([0, 0, 0, 0, 0xffff, 0, 0, 0]), 96],
      rfc6052: [new IPv6([0x64, 0xff9b, 0, 0, 0, 0, 0, 0]), 96],
      '6to4': [new IPv6([0x2002, 0, 0, 0, 0, 0, 0, 0]), 16],
      teredo: [new IPv6([0x2001, 0, 0, 0, 0, 0, 0, 0]), 32],
      reserved: [[new IPv6([0x2001, 0xdb8, 0, 0, 0, 0, 0, 0]), 32]]
    };

    IPv6.prototype.range = function() {
      return ipaddr.subnetMatch(this, this.SpecialRanges);
    };

    IPv6.prototype.isIPv4MappedAddress = function() {
      return this.range() === 'ipv4Mapped';
    };

    IPv6.prototype.toIPv4Address = function() {
      var high, low, ref;
      if (!this.isIPv4MappedAddress()) {
        throw new Error("ipaddr: trying to convert a generic ipv6 address to ipv4");
      }
      ref = this.parts.slice(-2), high = ref[0], low = ref[1];
      return new ipaddr.IPv4([high >> 8, high & 0xff, low >> 8, low & 0xff]);
    };

    IPv6.prototype.prefixLengthFromSubnetMask = function() {
      var cidr, i, k, part, stop, zeros, zerotable;
      zerotable = {
        0: 16,
        32768: 15,
        49152: 14,
        57344: 13,
        61440: 12,
        63488: 11,
        64512: 10,
        65024: 9,
        65280: 8,
        65408: 7,
        65472: 6,
        65504: 5,
        65520: 4,
        65528: 3,
        65532: 2,
        65534: 1,
        65535: 0
      };
      cidr = 0;
      stop = false;
      for (i = k = 7; k >= 0; i = k += -1) {
        part = this.parts[i];
        if (part in zerotable) {
          zeros = zerotable[part];
          if (stop && zeros !== 0) {
            return null;
          }
          if (zeros !== 16) {
            stop = true;
          }
          cidr += zeros;
        } else {
          return null;
        }
      }
      return 128 - cidr;
    };

    return IPv6;

  })();

  ipv6Part = "(?:[0-9a-f]+::?)+";

  zoneIndex = "%[0-9a-z]{1,}";

  ipv6Regexes = {
    zoneIndex: new RegExp(zoneIndex, 'i'),
    "native": new RegExp("^(::)?(" + ipv6Part + ")?([0-9a-f]+)?(::)?(" + zoneIndex + ")?$", 'i'),
    transitional: new RegExp(("^((?:" + ipv6Part + ")|(?:::)(?:" + ipv6Part + ")?)") + (ipv4Part + "\\." + ipv4Part + "\\." + ipv4Part + "\\." + ipv4Part) + ("(" + zoneIndex + ")?$"), 'i')
  };

  expandIPv6 = function(string, parts) {
    var colonCount, lastColon, part, replacement, replacementCount, zoneId;
    if (string.indexOf('::') !== string.lastIndexOf('::')) {
      return null;
    }
    zoneId = (string.match(ipv6Regexes['zoneIndex']) || [])[0];
    if (zoneId) {
      zoneId = zoneId.substring(1);
      string = string.replace(/%.+$/, '');
    }
    colonCount = 0;
    lastColon = -1;
    while ((lastColon = string.indexOf(':', lastColon + 1)) >= 0) {
      colonCount++;
    }
    if (string.substr(0, 2) === '::') {
      colonCount--;
    }
    if (string.substr(-2, 2) === '::') {
      colonCount--;
    }
    if (colonCount > parts) {
      return null;
    }
    replacementCount = parts - colonCount;
    replacement = ':';
    while (replacementCount--) {
      replacement += '0:';
    }
    string = string.replace('::', replacement);
    if (string[0] === ':') {
      string = string.slice(1);
    }
    if (string[string.length - 1] === ':') {
      string = string.slice(0, -1);
    }
    parts = (function() {
      var k, len, ref, results;
      ref = string.split(":");
      results = [];
      for (k = 0, len = ref.length; k < len; k++) {
        part = ref[k];
        results.push(parseInt(part, 16));
      }
      return results;
    })();
    return {
      parts: parts,
      zoneId: zoneId
    };
  };

  ipaddr.IPv6.parser = function(string) {
    var addr, k, len, match, octet, octets, zoneId;
    if (ipv6Regexes['native'].test(string)) {
      return expandIPv6(string, 8);
    } else if (match = string.match(ipv6Regexes['transitional'])) {
      zoneId = match[6] || '';
      addr = expandIPv6(match[1].slice(0, -1) + zoneId, 6);
      if (addr.parts) {
        octets = [parseInt(match[2]), parseInt(match[3]), parseInt(match[4]), parseInt(match[5])];
        for (k = 0, len = octets.length; k < len; k++) {
          octet = octets[k];
          if (!((0 <= octet && octet <= 255))) {
            return null;
          }
        }
        addr.parts.push(octets[0] << 8 | octets[1]);
        addr.parts.push(octets[2] << 8 | octets[3]);
        return {
          parts: addr.parts,
          zoneId: addr.zoneId
        };
      }
    }
    return null;
  };

  ipaddr.IPv4.isIPv4 = ipaddr.IPv6.isIPv6 = function(string) {
    return this.parser(string) !== null;
  };

  ipaddr.IPv4.isValid = function(string) {
    var e;
    try {
      new this(this.parser(string));
      return true;
    } catch (error1) {
      e = error1;
      return false;
    }
  };

  ipaddr.IPv4.isValidFourPartDecimal = function(string) {
    if (ipaddr.IPv4.isValid(string) && string.match(/^(0|[1-9]\d*)(\.(0|[1-9]\d*)){3}$/)) {
      return true;
    } else {
      return false;
    }
  };

  ipaddr.IPv6.isValid = function(string) {
    var addr, e;
    if (typeof string === "string" && string.indexOf(":") === -1) {
      return false;
    }
    try {
      addr = this.parser(string);
      new this(addr.parts, addr.zoneId);
      return true;
    } catch (error1) {
      e = error1;
      return false;
    }
  };

  ipaddr.IPv4.parse = function(string) {
    var parts;
    parts = this.parser(string);
    if (parts === null) {
      throw new Error("ipaddr: string is not formatted like ip address");
    }
    return new this(parts);
  };

  ipaddr.IPv6.parse = function(string) {
    var addr;
    addr = this.parser(string);
    if (addr.parts === null) {
      throw new Error("ipaddr: string is not formatted like ip address");
    }
    return new this(addr.parts, addr.zoneId);
  };

  ipaddr.IPv4.parseCIDR = function(string) {
    var maskLength, match, parsed;
    if (match = string.match(/^(.+)\/(\d+)$/)) {
      maskLength = parseInt(match[2]);
      if (maskLength >= 0 && maskLength <= 32) {
        parsed = [this.parse(match[1]), maskLength];
        Object.defineProperty(parsed, 'toString', {
          value: function() {
            return this.join('/');
          }
        });
        return parsed;
      }
    }
    throw new Error("ipaddr: string is not formatted like an IPv4 CIDR range");
  };

  ipaddr.IPv4.subnetMaskFromPrefixLength = function(prefix) {
    var filledOctetCount, j, octets;
    prefix = parseInt(prefix);
    if (prefix < 0 || prefix > 32) {
      throw new Error('ipaddr: invalid IPv4 prefix length');
    }
    octets = [0, 0, 0, 0];
    j = 0;
    filledOctetCount = Math.floor(prefix / 8);
    while (j < filledOctetCount) {
      octets[j] = 255;
      j++;
    }
    if (filledOctetCount < 4) {
      octets[filledOctetCount] = Math.pow(2, prefix % 8) - 1 << 8 - (prefix % 8);
    }
    return new this(octets);
  };

  ipaddr.IPv4.broadcastAddressFromCIDR = function(string) {
    var cidr, error, i, ipInterfaceOctets, octets, subnetMaskOctets;
    try {
      cidr = this.parseCIDR(string);
      ipInterfaceOctets = cidr[0].toByteArray();
      subnetMaskOctets = this.subnetMaskFromPrefixLength(cidr[1]).toByteArray();
      octets = [];
      i = 0;
      while (i < 4) {
        octets.push(parseInt(ipInterfaceOctets[i], 10) | parseInt(subnetMaskOctets[i], 10) ^ 255);
        i++;
      }
      return new this(octets);
    } catch (error1) {
      error = error1;
      throw new Error('ipaddr: the address does not have IPv4 CIDR format');
    }
  };

  ipaddr.IPv4.networkAddressFromCIDR = function(string) {
    var cidr, error, i, ipInterfaceOctets, octets, subnetMaskOctets;
    try {
      cidr = this.parseCIDR(string);
      ipInterfaceOctets = cidr[0].toByteArray();
      subnetMaskOctets = this.subnetMaskFromPrefixLength(cidr[1]).toByteArray();
      octets = [];
      i = 0;
      while (i < 4) {
        octets.push(parseInt(ipInterfaceOctets[i], 10) & parseInt(subnetMaskOctets[i], 10));
        i++;
      }
      return new this(octets);
    } catch (error1) {
      error = error1;
      throw new Error('ipaddr: the address does not have IPv4 CIDR format');
    }
  };

  ipaddr.IPv6.parseCIDR = function(string) {
    var maskLength, match, parsed;
    if (match = string.match(/^(.+)\/(\d+)$/)) {
      maskLength = parseInt(match[2]);
      if (maskLength >= 0 && maskLength <= 128) {
        parsed = [this.parse(match[1]), maskLength];
        Object.defineProperty(parsed, 'toString', {
          value: function() {
            return this.join('/');
          }
        });
        return parsed;
      }
    }
    throw new Error("ipaddr: string is not formatted like an IPv6 CIDR range");
  };

  ipaddr.isValid = function(string) {
    return ipaddr.IPv6.isValid(string) || ipaddr.IPv4.isValid(string);
  };

  ipaddr.parse = function(string) {
    if (ipaddr.IPv6.isValid(string)) {
      return ipaddr.IPv6.parse(string);
    } else if (ipaddr.IPv4.isValid(string)) {
      return ipaddr.IPv4.parse(string);
    } else {
      throw new Error("ipaddr: the address has neither IPv6 nor IPv4 format");
    }
  };

  ipaddr.parseCIDR = function(string) {
    var e;
    try {
      return ipaddr.IPv6.parseCIDR(string);
    } catch (error1) {
      e = error1;
      try {
        return ipaddr.IPv4.parseCIDR(string);
      } catch (error1) {
        e = error1;
        throw new Error("ipaddr: the address has neither IPv6 nor IPv4 CIDR format");
      }
    }
  };

  ipaddr.fromByteArray = function(bytes) {
    var length;
    length = bytes.length;
    if (length === 4) {
      return new ipaddr.IPv4(bytes);
    } else if (length === 16) {
      return new ipaddr.IPv6(bytes);
    } else {
      throw new Error("ipaddr: the binary input is neither an IPv6 nor IPv4 address");
    }
  };

  ipaddr.process = function(string) {
    var addr;
    addr = this.parse(string);
    if (addr.kind() === 'ipv6' && addr.isIPv4MappedAddress()) {
      return addr.toIPv4Address();
    } else {
      return addr;
    }
  };

}).call(this);

},{}],45:[function(require,module,exports){
/*

index.js - Kademlia DHT K-bucket implementation as a binary tree.

The MIT License (MIT)

Copyright (c) 2013-2015 Tristan Slominski

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.

*/
"use strict";

var assert = require('assert');
var bufferEqual = require('buffer-equal');
var crypto = require('crypto');
var events = require('events');
var inherits = require('inherits');

/*
  * `options`:
    * `arbiter`: _Function_ _(Default: vectorClock arbiter)_
        `function (incumbent, candidate) { return contact; }` An optional
        `arbiter` function that givent two `contact` objects with the same `id`
        returns the desired object to be used for updating the k-bucket. For
        more details, see [arbiter function](#arbiter-function).
    * `localNodeId`: _Buffer_ An optional Buffer representing the local node id.
        If not provided, a local node id will be created via
        `crypto.randomBytes(20)`.
    * `numberOfNodesPerKBucket`: _Integer_ _(Default: 20)_ The number of nodes
        that a k-bucket can contain before being full or split.
    * `numberOfNodesToPing`: _Integer_ _(Default: 3)_ The number of nodes to
        ping when a bucket that should not be split becomes full. KBucket will
        emit a `ping` event that contains `numberOfNodesToPing` nodes that have
        not been contacted the longest.
    * `root`: _Object_ _**CAUTION: reserved for internal use**_ Provides a
        reference to the root of the tree data structure as the k-bucket splits
        when new contacts are added.
*/
var KBucket = module.exports = function KBucket (options) {
    var self = this;
    options = options || {};
    events.EventEmitter.call(self);

    // use an arbiter from options or vectorClock arbiter by default
    self.arbiter = options.arbiter || function arbiter(incumbent, candidate) {
        if (incumbent.vectorClock > candidate.vectorClock) {
            return incumbent;
        }
        return candidate;
    };

    // the bucket array has least-recently-contacted at the "front/left" side
    // and the most-recently-contaced at the "back/right" side
    self.bucket = [];
    self.localNodeId = options.localNodeId || crypto.randomBytes(20);
    if (!Buffer.isBuffer(self.localNodeId)) {
        throw new Error("localNodeId is not a Buffer");
    }
    self.numberOfNodesPerKBucket = options.numberOfNodesPerKBucket || 20;
    self.numberOfNodesToPing = options.numberOfNodesToPing || 3;
    self.root = options.root || self;

    // V8 hints
    self.dontSplit = null;
    self.low = null;
    self.high = null;
};

inherits(KBucket, events.EventEmitter);

KBucket.distance = function distance (firstId, secondId) {
    var max = Math.max(firstId.length, secondId.length);
    var accumulator = '';
    for (var i = 0; i < max; i++) {
        var maxDistance = (firstId[i] === undefined || secondId[i] === undefined);
        if (maxDistance) {
            accumulator += (255).toString(16);
        } else {
            accumulator += (firstId[i] ^ secondId[i]).toString(16);
        }
    }
    return parseInt(accumulator, 16);
};

// contact: *required* the contact object to add
// bitIndex: the bitIndex to which bit to check in the Buffer for navigating
//           the binary tree
KBucket.prototype.add = function add (contact, bitIndex) {
    var self = this;

    // first check whether we are an inner node or a leaf (with bucket contents)
    if (!self.bucket) {
        // this is not a leaf node but an inner node with 'low' and 'high'
        // branches; we will check the appropriate bit of the identifier and
        // delegate to the appropriate node for further processing
        bitIndex = bitIndex || 0;

        if (self.determineBucket(contact.id, bitIndex++) < 0) {
            return self.low.add(contact, bitIndex);
        } else {
            return self.high.add(contact, bitIndex);
        }
    }

    // check if the contact already exists
    var index = self.indexOf(contact);
    if (index >= 0) {
        self.update(contact, index);
        return self;
    }

    if (self.bucket.length < self.numberOfNodesPerKBucket) {
        self.bucket.push(contact);
        return self;
    }

    // the bucket is full
    if (self.dontSplit) {
        // we are not allowed to split the bucket
        // we need to ping the first self.numberOfNodesToPing
        // in order to determine if they are alive
        // only if one of the pinged nodes does not respond, can the new contact
        // be added (this prevents DoS flodding with new invalid contacts)
        self.root.emit('ping',
            self.bucket.slice(0, self.numberOfNodesToPing),
            contact);
        return self;
    }

    return self.splitAndAdd(contact, bitIndex);
};

// contact: Object *required* contact object
//   id: Buffer *require* node id
// n: Integer *required* maximum number of closest contacts to return
// bitIndex: Integer (Default: 0)
// Return: Array of maximum of `n` closest contacts to the `contact`
KBucket.prototype.closest = function closest (contact, n, bitIndex) {
    var self = this;

    var contacts;

    if (!self.bucket) {
        bitIndex = bitIndex || 0;

        if (self.determineBucket(contact.id, bitIndex++) < 0) {
            contacts = self.low.closest(contact, n, bitIndex);
            if (contacts.length < n) {
                contacts = contacts.concat(self.high.closest(contact, n, bitIndex));
            }
        } else {
            contacts = self.high.closest(contact, n, bitIndex);
            if (contacts.length < n) {
                contacts = contacts.concat(self.low.closest(contact, n, bitIndex));
            }
        }
        return contacts.slice(0, n);
    }

    contacts = self.bucket.slice();
    contacts.forEach(function (storedContact) {
        storedContact.distance = KBucket.distance(storedContact.id, contact.id);
    });

    contacts.sort(function (a, b) {return a.distance - b.distance;});

    return contacts.slice(0, n);
};

// Counts the number of contacts recursively.
// If this is a leaf, just return the number of contacts contained. Otherwise,
// return the length of the high and low branches combined.
KBucket.prototype.count = function count () {
    var self = this;

    if (self.bucket) {
        return self.bucket.length;
    } else {
        return self.high.count() + self.low.count();
    }
};

// Determines whether the id at the bitIndex is 0 or 1. If 0, returns -1, else 1
// id: a Buffer to compare localNodeId with
// bitIndex: the bitIndex to which bit to check in the id Buffer
KBucket.prototype.determineBucket = function determineBucket (id, bitIndex) {
    var self = this;

    bitIndex = bitIndex || 0;

    // **NOTE** remember that id is a Buffer and has granularity of
    // bytes (8 bits), whereas the bitIndex is the _bit_ index (not byte)

    // id's that are too short are put in low bucket (1 byte = 8 bits)
    // parseInt(bitIndex / 8) finds how many bytes the bitIndex describes
    // bitIndex % 8 checks if we have extra bits beyond byte multiples
    // if number of bytes is <= no. of bytes described by bitIndex and there
    // are extra bits to consider, this means id has less bits than what
    // bitIndex describes, id therefore is too short, and will be put in low
    // bucket
    var bytesDescribedByBitIndex = parseInt(bitIndex / 8, 10);
    var bitIndexWithinByte = bitIndex % 8;
    if ((id.length <= bytesDescribedByBitIndex)
        && (bitIndexWithinByte != 0))
        return -1;

    var byteUnderConsideration = id[bytesDescribedByBitIndex];

    // byteUnderConsideration is an integer from 0 to 255 represented by 8 bits
    // where 255 is 11111111 and 0 is 00000000
    // in order to find out whether the bit at bitIndexWithinByte is set
    // we construct Math.pow(2, (7 - bitIndexWithinByte)) which will consist
    // of all bits being 0, with only one bit set to 1
    // for example, if bitIndexWithinByte is 3, we will construct 00010000 by
    // Math.pow(2, (7 - 3)) -> Math.pow(2, 4) -> 16
    if (byteUnderConsideration & Math.pow(2, (7 - bitIndexWithinByte))) {
        return 1;
    }

    return -1;
};

// Get a contact by its exact ID.
// If this is a leaf, loop through the bucket contents and return the correct
// contact if we have it or null if not. If this is an inner node, determine
// which branch of the tree to traverse and repeat.
// id: *required* a Buffer specifying the ID of the contact to fetch
// bitIndex: the bitIndex to which bit to check in the Buffer for navigating
//           the binary tree
KBucket.prototype.get = function get (id, bitIndex) {
    var self = this;

    if (!self.bucket) {
        bitIndex = bitIndex || 0;

        if (self.determineBucket(id, bitIndex++) < 0) {
            return self.low.get(id, bitIndex);
        } else {
            return self.high.get(id, bitIndex);
        }
    }

    var index = self.indexOf({id: id}); // index of uses contact.id for matching
    if (index < 0) {
        return null; // contact not found
    }

    return self.bucket[index];
};

// Returns the index of the contact if it exists
// **NOTE**: indexOf() does not compare vectorClock
KBucket.prototype.indexOf = function indexOf (contact) {
    var self = this;
    for (var i = 0; i < self.bucket.length; i++) {
        if (bufferEqual(self.bucket[i].id, contact.id)) return i;
    }
    return -1;
};

// contact: *required* the contact object to remove
// bitIndex: the bitIndex to which bit to check in the Buffer for navigating
//           the binary tree
KBucket.prototype.remove = function remove (contact, bitIndex) {
    var self = this;

    // first check whether we are an inner node or a leaf (with bucket contents)
    if (!self.bucket) {
        // this is not a leaf node but an inner node with 'low' and 'high'
        // branches; we will check the appropriate bit of the identifier and
        // delegate to the appropriate node for further processing
        bitIndex = bitIndex || 0;

        if (self.determineBucket(contact.id, bitIndex++) < 0) {
            return self.low.remove(contact, bitIndex);
        } else {
            return self.high.remove(contact, bitIndex);
        }
    }

    var index = self.indexOf(contact);
    if (index >= 0) self.bucket.splice(index, 1);
    return self;
};

// Splits the bucket, redistributes contacts to the new buckets, and marks the
// bucket that was split as an inner node of the binary tree of buckets by
// setting self.bucket = undefined;
// contact: *required* the contact object to add
// bitIndex: the bitIndex to which byte to check in the Buffer for navigating the
//          binary tree
KBucket.prototype.splitAndAdd = function splitAndAdd (contact, bitIndex) {
    var self = this;
    self.low = new KBucket({
        arbiter: self.arbiter,
        localNodeId: self.localNodeId,
        root: self.root,
        numberOfNodesPerKBucket: self.numberOfNodesPerKBucket,
        numberOfNodesToPing: self.numberOfNodesToPing
    });
    self.high = new KBucket({
        arbiter: self.arbiter,
        localNodeId: self.localNodeId,
        root: self.root,
        numberOfNodesPerKBucket: self.numberOfNodesPerKBucket,
        numberOfNodesToPing: self.numberOfNodesToPing
    });

    bitIndex = bitIndex || 0;

    // redistribute existing contacts amongst the two newly created buckets
    self.bucket.forEach(function (storedContact) {
        if (self.determineBucket(storedContact.id, bitIndex) < 0) {
            self.low.add(storedContact);
        } else {
            self.high.add(storedContact);
        }
    });

    self.bucket = undefined; // mark as inner tree node

    // don't split the "far away" bucket
    // we check where the local node would end up and mark the other one as
    // "dontSplit" (i.e. "far away")
    if (self.determineBucket(self.localNodeId, bitIndex) < 0) {
        // local node belongs to "low" bucket, so mark the other one
        self.high.dontSplit = true;
    } else {
        self.low.dontSplit = true;
    }

    // add the contact being added
    self.add(contact, bitIndex);

    return self;
};

// Returns all the contacts contained in the tree as an array.
// If self is a leaf, return a copy of the bucket. `slice` is used so that we
// don't accidentally leak an internal reference out that might be accidentally
// misused. If self is not a leaf, return the union of the low and high
// branches (themselves also as arrays).
KBucket.prototype.toArray = function toArray () {
    var self = this;

    if (self.bucket) {
        return self.bucket.slice(0);
    } else {
        return self.low.toArray().concat(self.high.toArray());
    }
};

// Updates the contact selected by the arbiter.
// If the selection is our old contact and the candidate is some new contact
// then the new contact is abandoned (not added).
// If the selection is our old contact and the candidate is our old contact
// then we are refreshing the contact and it is marked as most recently
// contacted (by being moved to the right/end of the bucket array).
// If the selection is our new contact, the old contact is removed and the new
// contact is marked as most recently contacted.
// contact: *required* the contact to update
// index: *required* the index in the bucket where contact exists
//        (index has already been computed in a previous calculation)
KBucket.prototype.update = function update (contact, index) {
    var self = this;
    // sanity check
    assert.ok(bufferEqual(self.bucket[index].id, contact.id),
        "indexOf() calculation resulted in wrong index");

    var incumbent = self.bucket[index];
    var selection = self.arbiter(incumbent, contact);
    if (selection === incumbent && incumbent !== contact) {
        // if the selection is our old contact and the candidate is some new
        // contact, then there is nothing to do
        return;
    }

    self.bucket.splice(index, 1); // remove old contact
    self.bucket.push(selection); // add more recent contact version
};

},{"assert":undefined,"buffer-equal":24,"crypto":undefined,"events":undefined,"inherits":40}],46:[function(require,module,exports){
var dgram = require('dgram')
var bencode = require('bencode')
var isIP = require('net').isIP
var dns = require('dns')
var util = require('util')
var events = require('events')

var ETIMEDOUT = new Error('Query timed out')
ETIMEDOUT.code = 'ETIMEDOUT'

var EUNEXPECTEDNODE = new Error('Unexpected node id')
EUNEXPECTEDNODE.code = 'EUNEXPECTEDNODE'

module.exports = RPC

function RPC (opts) {
  if (!(this instanceof RPC)) return new RPC(opts)
  if (!opts) opts = {}

  var self = this

  this.timeout = opts.timeout || 2000
  this.inflight = 0
  this.destroyed = false
  this.isIP = opts.isIP || isIP
  this.socket = opts.socket || dgram.createSocket('udp4')
  this.socket.on('message', onmessage)
  this.socket.on('error', onerror)
  this.socket.on('listening', onlistening)

  this._tick = 0
  this._ids = []
  this._reqs = []
  this._timer = setInterval(check, Math.floor(this.timeout / 4))

  events.EventEmitter.call(this)

  function check () {
    var missing = self.inflight
    if (!missing) return
    for (var i = 0; i < self._reqs.length; i++) {
      var req = self._reqs[i]
      if (!req) continue
      if (req.ttl) req.ttl--
      else self._cancel(i, ETIMEDOUT)
      if (!--missing) return
    }
  }

  function onlistening () {
    self.emit('listening')
  }

  function onerror (err) {
    if (err.code === 'EACCES' || err.code === 'EADDRINUSE') self.emit('error', err)
    else self.emit('warning', err)
  }

  function onmessage (buf, rinfo) {
    if (self.destroyed) return
    if (!rinfo.port) return // seems like a node bug that this is nessesary?

    try {
      var message = bencode.decode(buf)
    } catch (e) {
      return self.emit('warning', e)
    }

    var type = message && message.y && message.y.toString()

    if (type === 'r' || type === 'e') {
      if (!Buffer.isBuffer(message.t)) return

      try {
        var tid = message.t.readUInt16BE(0)
      } catch (err) {
        return self.emit('warning', err)
      }

      var index = self._ids.indexOf(tid)
      if (index === -1 || tid === 0) {
        self.emit('response', message, rinfo)
        self.emit('warning', new Error('Unexpected transaction id: ' + tid))
        return
      }

      var req = self._reqs[index]
      if (req.peer.host !== rinfo.address) {
        self.emit('response', message, rinfo)
        self.emit('warning', new Error('Out of order response'))
        return
      }

      self._ids[index] = 0
      self._reqs[index] = null
      self.inflight--

      if (type === 'e') {
        var isArray = Array.isArray(message.e)
        var err = new Error(isArray ? message.e.join(' ') : 'Unknown error')
        err.code = isArray && message.e.length && typeof message.e[0] === 'number' ? message.e[0] : 0
        req.callback(err, message, rinfo, req.message)
        self.emit('update')
        self.emit('postupdate')
        return
      }

      var rid = message.r && message.r.id
      if (req.peer && req.peer.id && rid && !req.peer.id.equals(rid)) {
        req.callback(EUNEXPECTEDNODE, null, rinfo)
        self.emit('update')
        self.emit('postupdate')
        return
      }

      req.callback(null, message, rinfo, req.message)
      self.emit('update')
      self.emit('postupdate')
      self.emit('response', message, rinfo)
    } else if (type === 'q') {
      self.emit('query', message, rinfo)
    } else {
      self.emit('warning', new Error('Unknown type: ' + type))
    }
  }
}

util.inherits(RPC, events.EventEmitter)

RPC.prototype.address = function () {
  return this.socket.address()
}

RPC.prototype.response = function (peer, req, res, cb) {
  this.send(peer, { t: req.t, y: 'r', r: res }, cb)
}

RPC.prototype.error = function (peer, req, error, cb) {
  this.send(peer, { t: req.t, y: 'e', e: [].concat(error.message || error) }, cb)
}

RPC.prototype.send = function (peer, message, cb) {
  if (require("../../network-policy.cjs").blockedAddress(peer.address || peer.host)) return process.nextTick(function () { if (cb) cb(new Error("DHT destination rejected")) })
  var buf = bencode.encode(message)
  this.socket.send(buf, 0, buf.length, peer.port, peer.address || peer.host, cb || noop)
}

// bind([port], [address], [callback])
RPC.prototype.bind = function () {
  this.socket.bind.apply(this.socket, arguments)
}

RPC.prototype.destroy = function (cb) {
  this.destroyed = true
  clearInterval(this._timer)
  if (cb) this.socket.on('close', cb)
  for (var i = 0; i < this._ids.length; i++) this._cancel(i)
  this.socket.close()
}

RPC.prototype.query = function (peer, query, cb) {
  if (!cb) cb = noop
  if (this.isIP(peer.host) && require("../../network-policy.cjs").blockedAddress(peer.host)) return process.nextTick(function () { cb(new Error("DHT destination rejected")) })
  if (!this.isIP(peer.host)) return this._resolveAndQuery(peer, query, cb)

  var message = {
    t: new Buffer(2),
    y: 'q',
    q: query.q,
    a: query.a
  }

  var req = {
    ttl: 4,
    peer: peer,
    message: message,
    callback: cb
  }

  if (this._tick === 65535) this._tick = 0
  var tid = ++this._tick

  var free = this._ids.indexOf(0)
  if (free === -1) free = this._ids.push(0) - 1
  this._ids[free] = tid
  while (this._reqs.length < free) this._reqs.push(null)
  this._reqs[free] = req

  this.inflight++
  message.t.writeUInt16BE(tid, 0)
  this.send(peer, message)
  return tid
}

RPC.prototype.cancel = function (tid, err) {
  var index = this._ids.indexOf(tid)
  if (index > -1) this._cancel(index, err)
}

RPC.prototype._cancel = function (index, err) {
  var req = this._reqs[index]
  this._ids[index] = 0
  this._reqs[index] = null
  if (req) {
    this.inflight--
    req.callback(err || new Error('Query was cancelled'), null, req.peer)
    this.emit('update')
    this.emit('postupdate')
  }
}

RPC.prototype._resolveAndQuery = function (peer, query, cb) {
  var self = this

  dns.lookup(peer.host, function (err, ip) {
    if (err) return cb(err)
    if (self.destroyed) return cb(new Error('k-rpc-socket is destroyed'))
    self.query({ host: ip, port: peer.port }, query, cb)
  })
}

function noop () {}

},{"../../network-policy.cjs":2,"bencode":49,"dgram":undefined,"dns":undefined,"events":undefined,"net":undefined,"util":undefined}],47:[function(require,module,exports){
/**
 * replaces parseInt(buffer.toString('ascii', start, end)).
 * For strings with less then ~30 charachters, this is actually a lot faster.
 *
 * @param {Buffer} data
 * @param {Number} start
 * @param {Number} end
 * @return {Number} calculated number
 */
function getIntFromBuffer (buffer, start, end) {
  var sum = 0
  var sign = 1

  for (var i = start; i < end; i++) {
    var num = buffer[i]

    if (num < 58 && num >= 48) {
      sum = sum * 10 + (num - 48)
      continue
    }

    if (i === start && num === 43) { // +
      continue
    }

    if (i === start && num === 45) { // -
      sign = -1
      continue
    }

    if (num === 46) { // .
      // its a float. break here.
      break
    }

    throw new Error('not a number: buffer[' + i + '] = ' + num)
  }

  return sum * sign
}

/**
 * Decodes bencoded data.
 *
 * @param  {Buffer} data
 * @param  {Number} start (optional)
 * @param  {Number} end (optional)
 * @param  {String} encoding (optional)
 * @return {Object|Array|Buffer|String|Number}
 */
function decode (data, start, end, encoding) {
  if (data == null || data.length === 0) {
    return null
  }

  if (typeof start !== 'number' && encoding == null) {
    encoding = start
    start = undefined
  }

  if (typeof end !== 'number' && encoding == null) {
    encoding = end
    end = undefined
  }

  decode.position = 0
  decode.encoding = encoding || null

  decode.data = !(Buffer.isBuffer(data))
    ? new Buffer(data)
    : data.slice(start, end)

  decode.bytes = decode.data.length

  return decode.next()
}

decode.bytes = 0
decode.position = 0
decode.data = null
decode.encoding = null

decode.next = function () {
  switch (decode.data[decode.position]) {
    case 0x64:
      return decode.dictionary()
    case 0x6C:
      return decode.list()
    case 0x69:
      return decode.integer()
    default:
      return decode.buffer()
  }
}

decode.find = function (chr) {
  var i = decode.position
  var c = decode.data.length
  var d = decode.data

  while (i < c) {
    if (d[i] === chr) return i
    i++
  }

  throw new Error(
    'Invalid data: Missing delimiter "' +
    String.fromCharCode(chr) + '" [0x' +
    chr.toString(16) + ']'
  )
}

decode.dictionary = function () {
  decode.position++

  var dict = {}

  while (decode.data[decode.position] !== 0x65) {
    dict[decode.buffer()] = decode.next()
  }

  decode.position++

  return dict
}

decode.list = function () {
  decode.position++

  var lst = []

  while (decode.data[decode.position] !== 0x65) {
    lst.push(decode.next())
  }

  decode.position++

  return lst
}

decode.integer = function () {
  var end = decode.find(0x65)
  var number = getIntFromBuffer(decode.data, decode.position + 1, end)

  decode.position += end + 1 - decode.position

  return number
}

decode.buffer = function () {
  var sep = decode.find(0x3A)
  var length = getIntFromBuffer(decode.data, decode.position, sep)
  var end = ++sep + length

  decode.position = end

  return decode.encoding
    ? decode.data.toString(decode.encoding, sep, end)
    : decode.data.slice(sep, end)
}

module.exports = decode

},{}],48:[function(require,module,exports){
/**
 * Encodes data in bencode.
 *
 * @param  {Buffer|Array|String|Object|Number|Boolean} data
 * @return {Buffer}
 */
function encode (data, buffer, offset) {
  var buffers = []
  var result = null

  encode._encode(buffers, data)
  result = Buffer.concat(buffers)
  encode.bytes = result.length

  if (Buffer.isBuffer(buffer)) {
    result.copy(buffer, offset)
    return buffer
  }

  return result
}

encode.bytes = -1
encode._floatConversionDetected = false

encode._encode = function (buffers, data) {
  if (Buffer.isBuffer(data)) {
    buffers.push(new Buffer(data.length + ':'))
    buffers.push(data)
    return
  }

  if (data == null) { return }

  switch (typeof data) {
    case 'string':
      encode.buffer(buffers, data)
      break
    case 'number':
      encode.number(buffers, data)
      break
    case 'object':
      data.constructor === Array
        ? encode.list(buffers, data)
        : encode.dict(buffers, data)
      break
    case 'boolean':
      encode.number(buffers, data ? 1 : 0)
      break
  }
}

var buffE = new Buffer('e')
var buffD = new Buffer('d')
var buffL = new Buffer('l')

encode.buffer = function (buffers, data) {
  buffers.push(new Buffer(Buffer.byteLength(data) + ':' + data))
}

encode.number = function (buffers, data) {
  var maxLo = 0x80000000
  var hi = (data / maxLo) << 0
  var lo = (data % maxLo) << 0
  var val = hi * maxLo + lo

  buffers.push(new Buffer('i' + val + 'e'))

  if (val !== data && !encode._floatConversionDetected) {
    encode._floatConversionDetected = true
    console.warn(
      'WARNING: Possible data corruption detected with value "' + data + '":',
      'Bencoding only defines support for integers, value was converted to "' + val + '"'
    )
    console.trace()
  }
}

encode.dict = function (buffers, data) {
  buffers.push(buffD)

  var j = 0
  var k
  // fix for issue #13 - sorted dicts
  var keys = Object.keys(data).sort()
  var kl = keys.length

  for (; j < kl; j++) {
    k = keys[j]
    if (data[k] == null) continue
    encode.buffer(buffers, k)
    encode._encode(buffers, data[k])
  }

  buffers.push(buffE)
}

encode.list = function (buffers, data) {
  var i = 0
  var c = data.length
  buffers.push(buffL)

  for (; i < c; i++) {
    if (data[i] == null) continue
    encode._encode(buffers, data[i])
  }

  buffers.push(buffE)
}

module.exports = encode

},{}],49:[function(require,module,exports){
var bencode = module.exports

bencode.encode = require('./encode')
bencode.decode = require('./decode')

/**
 * Determines the amount of bytes
 * needed to encode the given value
 * @param  {Object|Array|Buffer|String|Number|Boolean} value
 * @return {Number} byteCount
 */
bencode.byteLength = bencode.encodingLength = function (value) {
  return bencode.encode(value).length
}

},{"./decode":47,"./encode":48}],50:[function(require,module,exports){
var socket = require('k-rpc-socket')
var KBucket = require('k-bucket')
var equals = require('buffer-equals')
var crypto = require('crypto')
var events = require('events')
var util = require('util')

var K = 20
var MAX_CONCURRENCY = 16
var BOOTSTRAP_NODES = [
  {host: 'router.bittorrent.com', port: 6881},
  {host: 'router.utorrent.com', port: 6881},
  {host: 'dht.transmissionbt.com', port: 6881}
]

module.exports = RPC

function RPC (opts) {
  if (!(this instanceof RPC)) return new RPC(opts)
  if (!opts) opts = {}

  var self = this

  this.id = toBuffer(opts.id || opts.nodeId || crypto.randomBytes(20))
  this.socket = socket(opts)
  this.bootstrap = toBootstrapArray(opts.nodes || opts.bootstrap)
  this.concurrency = opts.concurrency || MAX_CONCURRENCY
  this.backgroundConcurrency = opts.backgroundConcurrency || (this.concurrency / 4) | 0
  this.k = opts.k || K
  this.destroyed = false

  this.pending = []
  this.nodes = null

  this.socket.on('query', onquery)
  this.socket.on('response', onresponse)
  this.socket.on('warning', onwarning)
  this.socket.on('error', onerror)
  this.socket.on('update', onupdate)
  this.socket.on('listening', onlistening)

  events.EventEmitter.call(this)
  this.clear()

  function onupdate () {
    while (self.pending.length && self.socket.inflight < self.concurrency) {
      var next = self.pending.shift()
      self.query(next[0], next[1], next[2])
    }
  }

  function onerror (err) {
    self.emit('error', err)
  }

  function onlistening () {
    self.emit('listening')
  }

  function onwarning (err) {
    self.emit('warning', err)
  }

  function onquery (query, peer) {
    addNode(query.a, peer)
    self.emit('query', query, peer)
  }

  function onresponse (reply, peer) {
    addNode(reply.r, peer)
  }

  function addNode (data, peer) {
    if (data && isNodeId(data.id) && !self.nodes.get(data.id)) {
      self._addNode({
        id: data.id,
        host: peer.address || peer.host,
        port: peer.port,
        distance: 0
      })
    }
  }
}

util.inherits(RPC, events.EventEmitter)

RPC.prototype.response = function (node, query, response, nodes, cb) {
  if (typeof nodes === 'function') {
    cb = nodes
    nodes = null
  }

  if (!response.id) response.id = this.id
  if (nodes) response.nodes = encodeNodes(nodes)
  this.socket.response(node, query, response, cb)
}

RPC.prototype.error = function (node, query, error, cb) {
  this.socket.error(node, query, error, cb)
}

RPC.prototype.bind = function (port, cb) {
  this.socket.bind(port, cb)
}

RPC.prototype.address = function () {
  return this.socket.address()
}

RPC.prototype.queryAll = function (nodes, message, visit, cb) {
  if (!message.a) message.a = {}
  if (!message.a.id) message.a.id = this.id

  var stop = false
  var missing = nodes.length
  var hits = 0
  var error = null

  if (!missing) return cb(new Error('No nodes to query'), 0)

  for (var i = 0; i < nodes.length; i++) {
    this.query(nodes[i], message, done)
  }

  function done (err, res, peer) {
    if (!err) hits++
    else if (err.code >= 300 && err.code < 400) error = err
    if (!err && !stop) {
      if (visit && visit(res, peer) === false) stop = true
    }
    if (!--missing) cb(hits ? null : error || new Error('All queries failed'), hits)
  }
}

RPC.prototype.query = function (node, message, cb) {
  if (this.socket.inflight >= this.concurrency) {
    this.pending.push([node, message, cb])
  } else {
    if (!message.a) message.a = {}
    if (!message.a.id) message.a.id = this.id
    if (node.token) message.a.token = node.token
    this.socket.query(node, message, cb)
  }
}

RPC.prototype.destroy = function (cb) {
  this.destroyed = true
  this.socket.destroy(cb)
}

RPC.prototype.clear = function () {
  var self = this

  this.nodes = new KBucket({
    localNodeId: this.id,
    numberOfNodesPerKBucket: this.k,
    numberOfNodesToPing: this.concurrency,
    ping: onping
  })

  function onping (older, newer) {
    self.emit('ping', older, newer)
  }
}

RPC.prototype.populate = function (target, message, cb) {
  this._closest(target, message, true, null, cb)
}

RPC.prototype.closest = function (target, message, visit, cb) {
  this._closest(target, message, false, visit, cb)
}

RPC.prototype._addNode = function (node) {
  var old = this.nodes.get(node.id)
  this.nodes.add(node)
  if (!old) this.emit('node', node)
}

RPC.prototype._closest = function (target, message, background, visit, cb) {
  if (!cb) cb = noop

  var self = this
  var count = 0
  var queried = {}
  var pending = 0
  var once = true
  var stop = false

  if (!message.a) message.a = {}
  if (!message.a.id) message.a.id = this.id

  var table = new KBucket({
    localNodeId: target,
    numberOfNodesPerKBucket: this.k,
    numberOfNodesToPing: this.concurrency
  })

  var evt = background ? 'postupdate' : 'update'
  this.socket.on(evt, kick)
  kick()

  function kick () {
    if (self.destroyed || self.socket.inflight >= self.concurrency) return

    var otherInflight = self.pending.length + self.socket.inflight - pending
    if (background && self.socket.inflight >= self.backgroundConcurrency && otherInflight) return

    var closest = table.closest({id: target}, self.k)
    if (!closest.length || closest.length < self.bootstrap.length) {
      closest = self.nodes.closest({id: target}, self.k)
      if (!closest.length || closest.length < self.bootstrap.length) bootstrap()
    }

    for (var i = 0; i < closest.length; i++) {
      if (stop) break
      if (self.socket.inflight >= self.concurrency) return

      var peer = closest[i]
      var id = peer.host + ':' + peer.port
      if (queried[id]) continue
      queried[id] = true

      pending++
      self.socket.query(peer, message, afterQuery)
    }

    if (!pending) {
      self.socket.removeListener(evt, kick)
      process.nextTick(done)
    }
  }

  function done () {
    cb(null, count)
  }

  function bootstrap () {
    if (!once) return
    once = false
    self.bootstrap.forEach(function (peer) {
      pending++
      self.socket.query(peer, message, afterQuery)
    })
  }

  function afterQuery (err, res, peer) {
    pending--
    if (peer) queried[peer.address + ':' + peer.port] = true // need this for bootstrap nodes

    var r = res && res.r
    if (!r) return

    if (peer && peer.id && self.nodes.get(peer.id)) {
      if (err && err.code === 'ETIMEDOUT') self.nodes.remove(peer)
    }

    if (!err && isNodeId(r.id)) {
      count++
      add({
        id: r.id,
        port: peer.port,
        host: peer.host || peer.address,
        distance: 0
      })
    }

    var nodes = r.nodes ? parseNodes(r.nodes) : []
    for (var i = 0; i < nodes.length; i++) add(nodes[i])

    if (visit && visit(res, peer) === false) stop = true

    kick()
  }

  function add (node) {
    if (equals(node.id, self.id)) return
    table.add(node)
  }
}

function toBootstrapArray (val) {
  if (val === false) return []
  if (val === true) return BOOTSTRAP_NODES
  return [].concat(val || BOOTSTRAP_NODES).map(parsePeer)
}

function isNodeId (id) {
  return id && Buffer.isBuffer(id) && id.length === 20
}

function encodeNodes (nodes) {
  var buf = new Buffer(nodes.length * 26)
  var ptr = 0

  for (var i = 0; i < nodes.length; i++) {
    var node = nodes[i]
    if (!isNodeId(node.id)) continue
    node.id.copy(buf, ptr)
    ptr += 20
    var ip = (node.host || node.address).split('.')
    for (var j = 0; j < 4; j++) buf[ptr++] = parseInt(ip[j] || 0, 10)
    buf.writeUInt16BE(node.port, ptr)
    ptr += 2
  }

  if (ptr === buf.length) return buf
  return buf.slice(0, ptr)
}

function parseNodes (buf) {
  var contacts = []

  try {
    for (var i = 0; i < buf.length; i += 26) {
      var port = buf.readUInt16BE(i + 24)
      if (!port) continue
      contacts.push({
        id: buf.slice(i, i + 20),
        host: parseIp(buf, i + 20),
        port: port,
        distance: 0,
        token: null
      })
    }
  } catch (err) {
    // do nothing
  }

  return contacts
}

function parseIp (buf, offset) {
  return buf[offset++] + '.' + buf[offset++] + '.' + buf[offset++] + '.' + buf[offset++]
}

function parsePeer (peer) {
  if (typeof peer === 'string') return {host: peer.split(':')[0], port: Number(peer.split(':')[1])}
  return peer
}

function noop () {}

function toBuffer (str) {
  if (typeof str === 'string') return new Buffer(str, 'hex')
  if (Buffer.isBuffer(str)) return str
  throw new Error('Pass a buffer or a string')
}

},{"buffer-equals":25,"crypto":undefined,"events":undefined,"k-bucket":51,"k-rpc-socket":46,"util":undefined}],51:[function(require,module,exports){
/*

index.js - Kademlia DHT K-bucket implementation as a binary tree.

The MIT License (MIT)

Copyright (c) 2013-2015 Tristan Slominski

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.

*/
"use strict";

var bufferEqual = require('buffer-equal');
var randomBytes = require('randombytes');

/*
  * `options`:
    * `arbiter`: _Function_ _(Default: vectorClock arbiter)_
        `function (incumbent, candidate) { return contact; }` An optional
        `arbiter` function that givent two `contact` objects with the same `id`
        returns the desired object to be used for updating the k-bucket. For
        more details, see [arbiter function](#arbiter-function).
    * `localNodeId`: _Buffer_ An optional Buffer representing the local node id.
        If not provided, a local node id will be created via
        `crypto.randomBytes(20)`.
    * `numberOfNodesPerKBucket`: _Integer_ _(Default: 20)_ The number of nodes
        that a k-bucket can contain before being full or split.
    * `numberOfNodesToPing`: _Integer_ _(Default: 3)_ The number of nodes to
        ping when a bucket that should not be split becomes full. KBucket will
        call the `ping` callback that contains `numberOfNodesToPing` nodes that have
        not been contacted the longest.
    * `ping`: _Function_ _(Default: no-op)_
        `function (oldContacts, newContact) {}` Callback called every time a
        contact is added that would exceed the capacity of a don't split k-bucket it belongs to.
        * `oldContacts`: _Array_ The array of contacts to ping.
        * `newContact`: _Object_ The new contact to be added if one of old contacts
            does not respond.
*/
var KBucket = module.exports = function KBucket (options) {
    var self = this;
    options = options || {};

    // use an arbiter from options or vectorClock arbiter by default
    self.arbiter = options.arbiter || function arbiter(incumbent, candidate) {
        if (incumbent.vectorClock > candidate.vectorClock) {
            return incumbent;
        }
        return candidate;
    };

    // the bucket array has least-recently-contacted at the "front/left" side
    // and the most-recently-contaced at the "back/right" side
    self.bucket = [];
    self.localNodeId = options.localNodeId || randomBytes(20);
    if (!Buffer.isBuffer(self.localNodeId)) {
        throw new TypeError("localNodeId is not a Buffer");
    }
    self.numberOfNodesPerKBucket = options.numberOfNodesPerKBucket || 20;
    self.numberOfNodesToPing = options.numberOfNodesToPing || 3;
    self.ping = options.ping || function() {};

    // V8 hints
    self.dontSplit = null;
    self.low = null;
    self.high = null;
};

KBucket.distance = function distance (firstId, secondId) {
    var distance = 0;
    var min = Math.min(firstId.length, secondId.length);
    var max = Math.max(firstId.length, secondId.length);
    for (var i = 0; i < min; ++i) {
        distance = distance * 256 + (firstId[i] ^ secondId[i]);
    }
    for (; i < max; ++i) {
        distance = distance * 256 + 255;
    }
    return distance;
};

// contact: *required* the contact object to add
// bitIndex: the bitIndex to which bit to check in the Buffer for navigating
//           the binary tree
KBucket.prototype.add = function add (contact, bitIndex) {
    var self = this;

    // first check whether we are an inner node or a leaf (with bucket contents)
    if (!self.bucket) {
        // this is not a leaf node but an inner node with 'low' and 'high'
        // branches; we will check the appropriate bit of the identifier and
        // delegate to the appropriate node for further processing
        bitIndex = bitIndex || 0;

        if (self.determineBucket(contact.id, bitIndex++) < 0) {
            return self.low.add(contact, bitIndex);
        } else {
            return self.high.add(contact, bitIndex);
        }
    }

    // check if the contact already exists
    var index = self.indexOf(contact);
    if (index >= 0) {
        self.update(contact, index);
        return self;
    }

    if (self.bucket.length < self.numberOfNodesPerKBucket) {
        self.bucket.push(contact);
        return self;
    }

    // the bucket is full
    if (self.dontSplit) {
        // we are not allowed to split the bucket
        // we need to ping the first self.numberOfNodesToPing
        // in order to determine if they are alive
        // only if one of the pinged nodes does not respond, can the new contact
        // be added (this prevents DoS flodding with new invalid contacts)
        self.ping(self.bucket.slice(0, self.numberOfNodesToPing), contact);
        return self;
    }

    return self.splitAndAdd(contact, bitIndex);
};

// contact: Object *required* contact object
//   id: Buffer *require* node id
// n: Integer *required* maximum number of closest contacts to return
// bitIndex: Integer (Default: 0)
// Return: Array of maximum of `n` closest contacts to the `contact`
KBucket.prototype.closest = function closest (contact, n, bitIndex) {
    var self = this;

    var contacts;

    if (!self.bucket) {
        bitIndex = bitIndex || 0;

        if (self.determineBucket(contact.id, bitIndex++) < 0) {
            contacts = self.low.closest(contact, n, bitIndex);
            if (contacts.length < n) {
                contacts = contacts.concat(self.high.closest(contact, n, bitIndex));
            }
        } else {
            contacts = self.high.closest(contact, n, bitIndex);
            if (contacts.length < n) {
                contacts = contacts.concat(self.low.closest(contact, n, bitIndex));
            }
        }
        return contacts.slice(0, n);
    }

    contacts = self.bucket.slice();
    contacts.forEach(function (storedContact) {
        storedContact.distance = KBucket.distance(storedContact.id, contact.id);
    });

    contacts.sort(function (a, b) {return a.distance - b.distance;});

    return contacts.slice(0, n);
};

// Counts the number of contacts recursively.
// If this is a leaf, just return the number of contacts contained. Otherwise,
// return the length of the high and low branches combined.
KBucket.prototype.count = function count () {
    var self = this;

    if (self.bucket) {
        return self.bucket.length;
    } else {
        return self.high.count() + self.low.count();
    }
};

// Determines whether the id at the bitIndex is 0 or 1. If 0, returns -1, else 1
// id: a Buffer to compare localNodeId with
// bitIndex: the bitIndex to which bit to check in the id Buffer
KBucket.prototype.determineBucket = function determineBucket (id, bitIndex) {
    var self = this;

    bitIndex = bitIndex || 0;

    // **NOTE** remember that id is a Buffer and has granularity of
    // bytes (8 bits), whereas the bitIndex is the _bit_ index (not byte)

    // id's that are too short are put in low bucket (1 byte = 8 bits)
    // parseInt(bitIndex / 8) finds how many bytes the bitIndex describes
    // bitIndex % 8 checks if we have extra bits beyond byte multiples
    // if number of bytes is <= no. of bytes described by bitIndex and there
    // are extra bits to consider, this means id has less bits than what
    // bitIndex describes, id therefore is too short, and will be put in low
    // bucket
    var bytesDescribedByBitIndex = parseInt(bitIndex / 8, 10);
    var bitIndexWithinByte = bitIndex % 8;
    if ((id.length <= bytesDescribedByBitIndex)
        && (bitIndexWithinByte != 0))
        return -1;

    var byteUnderConsideration = id[bytesDescribedByBitIndex];

    // byteUnderConsideration is an integer from 0 to 255 represented by 8 bits
    // where 255 is 11111111 and 0 is 00000000
    // in order to find out whether the bit at bitIndexWithinByte is set
    // we construct Math.pow(2, (7 - bitIndexWithinByte)) which will consist
    // of all bits being 0, with only one bit set to 1
    // for example, if bitIndexWithinByte is 3, we will construct 00010000 by
    // Math.pow(2, (7 - 3)) -> Math.pow(2, 4) -> 16
    if (byteUnderConsideration & Math.pow(2, (7 - bitIndexWithinByte))) {
        return 1;
    }

    return -1;
};

// Get a contact by its exact ID.
// If this is a leaf, loop through the bucket contents and return the correct
// contact if we have it or null if not. If this is an inner node, determine
// which branch of the tree to traverse and repeat.
// id: *required* a Buffer specifying the ID of the contact to fetch
// bitIndex: the bitIndex to which bit to check in the Buffer for navigating
//           the binary tree
KBucket.prototype.get = function get (id, bitIndex) {
    var self = this;

    if (!self.bucket) {
        bitIndex = bitIndex || 0;

        if (self.determineBucket(id, bitIndex++) < 0) {
            return self.low.get(id, bitIndex);
        } else {
            return self.high.get(id, bitIndex);
        }
    }

    var index = self.indexOf({id: id}); // index of uses contact.id for matching
    if (index < 0) {
        return null; // contact not found
    }

    return self.bucket[index];
};

// Returns the index of the contact if it exists
// **NOTE**: indexOf() does not compare vectorClock
KBucket.prototype.indexOf = function indexOf (contact) {
    var self = this;
    for (var i = 0; i < self.bucket.length; i++) {
        if (bufferEqual(self.bucket[i].id, contact.id)) return i;
    }
    return -1;
};

// contact: *required* the contact object to remove
// bitIndex: the bitIndex to which bit to check in the Buffer for navigating
//           the binary tree
KBucket.prototype.remove = function remove (contact, bitIndex) {
    var self = this;

    // first check whether we are an inner node or a leaf (with bucket contents)
    if (!self.bucket) {
        // this is not a leaf node but an inner node with 'low' and 'high'
        // branches; we will check the appropriate bit of the identifier and
        // delegate to the appropriate node for further processing
        bitIndex = bitIndex || 0;

        if (self.determineBucket(contact.id, bitIndex++) < 0) {
            return self.low.remove(contact, bitIndex);
        } else {
            return self.high.remove(contact, bitIndex);
        }
    }

    var index = self.indexOf(contact);
    if (index >= 0) self.bucket.splice(index, 1);
    return self;
};

// Splits the bucket, redistributes contacts to the new buckets, and marks the
// bucket that was split as an inner node of the binary tree of buckets by
// setting self.bucket = undefined;
// contact: *required* the contact object to add
// bitIndex: the bitIndex to which byte to check in the Buffer for navigating the
//          binary tree
KBucket.prototype.splitAndAdd = function splitAndAdd (contact, bitIndex) {
    var self = this;
    self.low = new KBucket({
        arbiter: self.arbiter,
        localNodeId: self.localNodeId,
        numberOfNodesPerKBucket: self.numberOfNodesPerKBucket,
        numberOfNodesToPing: self.numberOfNodesToPing,
        ping: self.ping
    });
    self.high = new KBucket({
        arbiter: self.arbiter,
        localNodeId: self.localNodeId,
        numberOfNodesPerKBucket: self.numberOfNodesPerKBucket,
        numberOfNodesToPing: self.numberOfNodesToPing,
        ping: self.ping
    });

    bitIndex = bitIndex || 0;

    // redistribute existing contacts amongst the two newly created buckets
    self.bucket.forEach(function (storedContact) {
        if (self.determineBucket(storedContact.id, bitIndex) < 0) {
            self.low.add(storedContact);
        } else {
            self.high.add(storedContact);
        }
    });

    self.bucket = undefined; // mark as inner tree node

    // don't split the "far away" bucket
    // we check where the local node would end up and mark the other one as
    // "dontSplit" (i.e. "far away")
    if (self.determineBucket(self.localNodeId, bitIndex) < 0) {
        // local node belongs to "low" bucket, so mark the other one
        self.high.dontSplit = true;
    } else {
        self.low.dontSplit = true;
    }

    // add the contact being added
    self.add(contact, bitIndex);

    return self;
};

// Returns all the contacts contained in the tree as an array.
// If self is a leaf, return a copy of the bucket. `slice` is used so that we
// don't accidentally leak an internal reference out that might be accidentally
// misused. If self is not a leaf, return the union of the low and high
// branches (themselves also as arrays).
KBucket.prototype.toArray = function toArray () {
    var self = this;

    if (self.bucket) {
        return self.bucket.slice(0);
    } else {
        return self.low.toArray().concat(self.high.toArray());
    }
};

// Updates the contact selected by the arbiter.
// If the selection is our old contact and the candidate is some new contact
// then the new contact is abandoned (not added).
// If the selection is our old contact and the candidate is our old contact
// then we are refreshing the contact and it is marked as most recently
// contacted (by being moved to the right/end of the bucket array).
// If the selection is our new contact, the old contact is removed and the new
// contact is marked as most recently contacted.
// contact: *required* the contact to update
// index: *required* the index in the bucket where contact exists
//        (index has already been computed in a previous calculation)
KBucket.prototype.update = function update (contact, index) {
    var self = this;
    // sanity check
    if (!bufferEqual(self.bucket[index].id, contact.id)) {
        throw new Error("indexOf() calculation resulted in wrong index")
    }

    var incumbent = self.bucket[index];
    var selection = self.arbiter(incumbent, contact);
    if (selection === incumbent && incumbent !== contact) {
        // if the selection is our old contact and the candidate is some new
        // contact, then there is nothing to do
        return;
    }

    self.bucket.splice(index, 1); // remove old contact
    self.bucket.push(selection); // add more recent contact version
};

},{"buffer-equal":24,"randombytes":73}],52:[function(require,module,exports){
var events = require('events')
var inherits = require('inherits')

module.exports = LRU

function LRU (opts) {
  if (!(this instanceof LRU)) return new LRU(opts)
  if (typeof opts === 'number') opts = {max: opts}
  if (!opts) opts = {}
  events.EventEmitter.call(this)
  this.cache = {}
  this.head = this.tail = null
  this.length = 0
  this.max = opts.max || 1000
  this.maxAge = opts.maxAge || 0
}

inherits(LRU, events.EventEmitter)

LRU.prototype.remove = function (key) {
  if (typeof key !== 'string') key = '' + key
  if (!this.cache.hasOwnProperty(key)) return

  var element = this.cache[key]
  delete this.cache[key]
  this._unlink(key, element.prev, element.next)
  return element.value
}

LRU.prototype._unlink = function (key, prev, next) {
  this.length--

  if (this.length === 0) {
    this.head = this.tail = null
  } else {
    if (this.head === key) {
      this.head = prev
      this.cache[this.head].next = null
    } else if (this.tail === key) {
      this.tail = next
      this.cache[this.tail].prev = null
    } else {
      this.cache[prev].next = next
      this.cache[next].prev = prev
    }
  }
}

LRU.prototype.peek = function (key) {
  return this.cache.hasOwnProperty(key) ? this.cache[key].value : null
}

LRU.prototype.set = function (key, value) {
  if (typeof key !== 'string') key = '' + key

  var element

  if (this.cache.hasOwnProperty(key)) {
    element = this.cache[key]
    element.value = value
    if (this.maxAge) element.modified = Date.now()

    // If it's already the head, there's nothing more to do:
    if (key === this.head) return value
    this._unlink(key, element.prev, element.next)
  } else {
    element = {value: value, modified: 0, next: null, prev: null}
    if (this.maxAge) element.modified = Date.now()
    this.cache[key] = element

    // Eviction is only possible if the key didn't already exist:
    if (this.length === this.max) this.evict()
  }

  this.length++
  element.next = null
  element.prev = this.head

  if (this.head) this.cache[this.head].next = key
  this.head = key

  if (!this.tail) this.tail = key
  return value
}

LRU.prototype.get = function (key) {
  if (typeof key !== 'string') key = '' + key
  if (!this.cache.hasOwnProperty(key)) return

  var element = this.cache[key]

  if (this.maxAge && (Date.now() - element.modified) > this.maxAge) {
    this.remove(key)
    this.emit('evict', {key: key, value: element.value})
    return
  }

  if (this.head !== key) {
    if (key === this.tail) {
      this.tail = element.next
      this.cache[this.tail].prev = null
    } else {
      // Set prev.next -> element.next:
      this.cache[element.prev].next = element.next
    }

    // Set element.next.prev -> element.prev:
    this.cache[element.next].prev = element.prev

    // Element is the new head
    this.cache[this.head].next = key
    element.prev = this.head
    element.next = null
    this.head = key
  }

  return element.value
}

LRU.prototype.evict = function () {
  if (!this.tail) return
  var key = this.tail
  var value = this.remove(this.tail)
  this.emit('evict', {key: key, value: value})
}

},{"events":undefined,"inherits":40}],53:[function(require,module,exports){
module.exports = magnetURIDecode
module.exports.decode = magnetURIDecode
module.exports.encode = magnetURIEncode

var base32 = require('thirty-two')
var extend = require('xtend')
var flatten = require('flatten')

/**
 * Parse a magnet URI and return an object of keys/values
 *
 * @param  {string} uri
 * @return {Object} parsed uri
 */
function magnetURIDecode (uri) {
  var result = {}
  var data = uri.split('magnet:?')[1]

  var params = (data && data.length >= 0)
    ? data.split('&')
    : []

  params.forEach(function (param) {
    var keyval = param.split('=')

    // This keyval is invalid, skip it
    if (keyval.length !== 2) return

    var key = keyval[0]
    var val = keyval[1]

    // Clean up torrent name
    if (key === 'dn') val = decodeURIComponent(val).replace(/\+/g, ' ')

    // Address tracker (tr), exact source (xs), and acceptable source (as) are encoded
    // URIs, so decode them
    if (key === 'tr' || key === 'xs' || key === 'as' || key === 'ws') {
      val = decodeURIComponent(val)
    }

    // Return keywords as an array
    if (key === 'kt') val = decodeURIComponent(val).split('+')

    // If there are repeated parameters, return an array of values
    if (result[key]) {
      if (Array.isArray(result[key])) {
        result[key].push(val)
      } else {
        var old = result[key]
        result[key] = [old, val]
      }
    } else {
      result[key] = val
    }
  })

  // Convenience properties for parity with `parse-torrent-file` module
  var m
  if (result.xt) {
    var xts = Array.isArray(result.xt) ? result.xt : [ result.xt ]
    xts.forEach(function (xt) {
      if ((m = xt.match(/^urn:btih:(.{40})/))) {
        result.infoHash = new Buffer(m[1], 'hex').toString('hex')
      } else if ((m = xt.match(/^urn:btih:(.{32})/))) {
        var decodedStr = base32.decode(m[1])
        result.infoHash = new Buffer(decodedStr, 'binary').toString('hex')
      }
    })
  }

  if (result.dn) result.name = result.dn
  if (result.kt) result.keywords = result.kt

  if (typeof result.tr === 'string') result.announce = [ result.tr ]
  else if (Array.isArray(result.tr)) result.announce = result.tr
  else result.announce = []

  result.announceList = result.announce.map(function (url) {
    return [ url ]
  })

  result.urlList = []
  if (typeof result.as === 'string' || Array.isArray(result.as)) {
    result.urlList = result.urlList.concat(result.as)
  }
  if (typeof result.ws === 'string' || Array.isArray(result.ws)) {
    result.urlList = result.urlList.concat(result.ws)
  }

  return result
}

function magnetURIEncode (obj) {
  obj = extend(obj) // clone obj, so we can mutate it

  // support official magnet key names and convenience names
  // (example: `infoHash` for `xt`, `name` for `dn`)
  if (obj.infoHash) obj.xt = 'urn:btih:' + obj.infoHash
  if (obj.name) obj.dn = obj.name
  if (obj.keywords) obj.kt = obj.keywords
  if (obj.announce) obj.tr = obj.announce
  if (obj.announceList) obj.tr = flatten(obj.announceList)
  if (obj.urlList) {
    obj.ws = flatten(obj.urlList)
    delete obj.as
  }

  var result = 'magnet:?'
  Object.keys(obj)
    .filter(function (key) {
      return key.length === 2
    })
    .forEach(function (key, i) {
      var values = Array.isArray(obj[key]) ? obj[key] : [ obj[key] ]
      values.forEach(function (val, j) {
        if ((i > 0 || j > 0) && (key !== 'kt' || j === 0)) result += '&'

        if (key === 'dn') val = encodeURIComponent(val).replace(/%20/g, '+')
        if (key === 'tr' || key === 'xs' || key === 'as' || key === 'ws') {
          val = encodeURIComponent(val)
        }
        if (key === 'kt') val = encodeURIComponent(val)

        if (key === 'kt' && j > 0) result += '+' + val
        else result += key + '=' + val
      })
    })

  return result
}

},{"flatten":37,"thirty-two":78,"xtend":88}],54:[function(require,module,exports){
/**
 * Helpers.
 */

var s = 1000;
var m = s * 60;
var h = m * 60;
var d = h * 24;
var y = d * 365.25;

/**
 * Parse or format the given `val`.
 *
 * Options:
 *
 *  - `long` verbose formatting [false]
 *
 * @param {String|Number} val
 * @param {Object} [options]
 * @throws {Error} throw an error if val is not a non-empty string or a number
 * @return {String|Number}
 * @api public
 */

module.exports = function(val, options) {
  options = options || {};
  var type = typeof val;
  if (type === 'string' && val.length > 0) {
    return parse(val);
  } else if (type === 'number' && isNaN(val) === false) {
    return options.long ? fmtLong(val) : fmtShort(val);
  }
  throw new Error(
    'val is not a non-empty string or a valid number. val=' +
      JSON.stringify(val)
  );
};

/**
 * Parse the given `str` and return milliseconds.
 *
 * @param {String} str
 * @return {Number}
 * @api private
 */

function parse(str) {
  str = String(str);
  if (str.length > 100) {
    return;
  }
  var match = /^((?:\d+)?\.?\d+) *(milliseconds?|msecs?|ms|seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h|days?|d|years?|yrs?|y)?$/i.exec(
    str
  );
  if (!match) {
    return;
  }
  var n = parseFloat(match[1]);
  var type = (match[2] || 'ms').toLowerCase();
  switch (type) {
    case 'years':
    case 'year':
    case 'yrs':
    case 'yr':
    case 'y':
      return n * y;
    case 'days':
    case 'day':
    case 'd':
      return n * d;
    case 'hours':
    case 'hour':
    case 'hrs':
    case 'hr':
    case 'h':
      return n * h;
    case 'minutes':
    case 'minute':
    case 'mins':
    case 'min':
    case 'm':
      return n * m;
    case 'seconds':
    case 'second':
    case 'secs':
    case 'sec':
    case 's':
      return n * s;
    case 'milliseconds':
    case 'millisecond':
    case 'msecs':
    case 'msec':
    case 'ms':
      return n;
    default:
      return undefined;
  }
}

/**
 * Short format for `ms`.
 *
 * @param {Number} ms
 * @return {String}
 * @api private
 */

function fmtShort(ms) {
  if (ms >= d) {
    return Math.round(ms / d) + 'd';
  }
  if (ms >= h) {
    return Math.round(ms / h) + 'h';
  }
  if (ms >= m) {
    return Math.round(ms / m) + 'm';
  }
  if (ms >= s) {
    return Math.round(ms / s) + 's';
  }
  return ms + 'ms';
}

/**
 * Long format for `ms`.
 *
 * @param {Number} ms
 * @return {String}
 * @api private
 */

function fmtLong(ms) {
  return plural(ms, d, 'day') ||
    plural(ms, h, 'hour') ||
    plural(ms, m, 'minute') ||
    plural(ms, s, 'second') ||
    ms + ' ms';
}

/**
 * Pluralization helper.
 */

function plural(ms, n, name) {
  if (ms < n) {
    return;
  }
  if (ms < n * 1.5) {
    return Math.floor(ms / n) + ' ' + name;
  }
  return Math.ceil(ms / n) + ' ' + name + 's';
}

},{}],55:[function(require,module,exports){
var wrappy = require('wrappy')
module.exports = wrappy(once)
module.exports.strict = wrappy(onceStrict)

once.proto = once(function () {
  Object.defineProperty(Function.prototype, 'once', {
    value: function () {
      return once(this)
    },
    configurable: true
  })

  Object.defineProperty(Function.prototype, 'onceStrict', {
    value: function () {
      return onceStrict(this)
    },
    configurable: true
  })
})

function once (fn) {
  var f = function () {
    if (f.called) return f.value
    f.called = true
    return f.value = fn.apply(this, arguments)
  }
  f.called = false
  return f
}

function onceStrict (fn) {
  var f = function () {
    if (f.called)
      throw new Error(f.onceError)
    f.called = true
    return f.value = fn.apply(this, arguments)
  }
  var name = fn.name || 'Function wrapped with `once`'
  f.onceError = name + " shouldn't be called more than once"
  f.called = false
  return f
}

},{"wrappy":87}],56:[function(require,module,exports){
module.exports = decodeTorrentFile
module.exports.decode = decodeTorrentFile
module.exports.encode = encodeTorrentFile

var bencode = require('bencode')
var path = require('path')
var sha1 = require('simple-sha1')

/**
 * Parse a torrent. Throws an exception if the torrent is missing required fields.
 * @param  {Buffer|Object} torrent
 * @return {Object}        parsed torrent
 */
function decodeTorrentFile (torrent) {
  if (Buffer.isBuffer(torrent)) {
    torrent = bencode.decode(torrent)
  }

  // sanity check
  ensure(torrent.info, 'info')
  ensure(torrent.info.name, 'info.name')
  ensure(torrent.info['piece length'], 'info[\'piece length\']')
  ensure(torrent.info.pieces, 'info.pieces')

  if (torrent.info.files) {
    torrent.info.files.forEach(function (file) {
      ensure(typeof file.length === 'number', 'info.files[0].length')
      ensure(file.path, 'info.files[0].path')
    })
  } else {
    ensure(typeof torrent.info.length === 'number', 'info.length')
  }

  var result = {}
  result.info = torrent.info
  result.infoBuffer = bencode.encode(torrent.info)
  result.infoHash = sha1.sync(result.infoBuffer)

  result.name = torrent.info.name.toString()
  result.private = !!torrent.info.private

  if (torrent['creation date']) result.created = new Date(torrent['creation date'] * 1000)

  if (Buffer.isBuffer(torrent.comment)) result.comment = torrent.comment.toString()

  // announce/announce-list may be missing if metadata fetched via ut_metadata extension
  var announce = torrent['announce-list']
  if (!announce) {
    if (torrent.announce) {
      announce = [[torrent.announce]]
    } else {
      announce = []
    }
  }

  result.announceList = announce.map(function (urls) {
    return urls.map(function (url) {
      return url.toString()
    })
  })

  result.announce = [].concat.apply([], result.announceList)

  // handle url-list (BEP19 / web seeding)
  if (Buffer.isBuffer(torrent['url-list'])) {
    // some clients set url-list to empty string
    torrent['url-list'] = torrent['url-list'].length > 0
      ? [ torrent['url-list'] ]
      : []
  }
  result.urlList = (torrent['url-list'] || []).map(function (url) {
    return url.toString()
  })

  var files = torrent.info.files || [torrent.info]
  result.files = files.map(function (file, i) {
    var parts = [].concat(file.name || result.name, file.path || []).map(function (p) {
      return p.toString()
    })
    return {
      path: path.join.apply(null, [path.sep].concat(parts)).slice(1),
      name: parts[parts.length - 1],
      length: file.length,
      offset: files.slice(0, i).reduce(sumLength, 0)
    }
  })

  result.length = files.reduce(sumLength, 0)

  var lastFile = result.files[result.files.length - 1]

  result.pieceLength = torrent.info['piece length']
  result.lastPieceLength = ((lastFile.offset + lastFile.length) % result.pieceLength) || result.pieceLength
  result.pieces = splitPieces(torrent.info.pieces)

  return result
}

/**
 * Convert a parsed torrent object back into a .torrent file buffer.
 * @param  {Object} parsed parsed torrent
 * @return {Buffer}
 */
function encodeTorrentFile (parsed) {
  var torrent = {
    info: parsed.info
  }

  if (parsed.announce && parsed.announce[0]) {
    torrent.announce = parsed.announce[0]
  }

  if (parsed.announceList) {
    torrent['announce-list'] = parsed.announceList.map(function (urls) {
      return urls.map(function (url) {
        url = new Buffer(url, 'utf8')
        if (!torrent.announce) {
          torrent.announce = url
        }
        return url
      })
    })
  }

  if (parsed.created) {
    torrent['creation date'] = (parsed.created.getTime() / 1000) | 0
  }
  return bencode.encode(torrent)
}

function sumLength (sum, file) {
  return sum + file.length
}

function splitPieces (buf) {
  var pieces = []
  for (var i = 0; i < buf.length; i += 20) {
    pieces.push(buf.slice(i, i + 20).toString('hex'))
  }
  return pieces
}

function ensure (bool, fieldName) {
  if (!bool) throw new Error('Torrent is missing required field: ' + fieldName)
}

},{"bencode":57,"path":undefined,"simple-sha1":76}],57:[function(require,module,exports){
arguments[4][5][0].apply(exports,arguments)
},{"./lib/decode":58,"./lib/encode":60,"dup":5}],58:[function(require,module,exports){
arguments[4][6][0].apply(exports,arguments)
},{"./dict":59,"dup":6}],59:[function(require,module,exports){
arguments[4][7][0].apply(exports,arguments)
},{"dup":7}],60:[function(require,module,exports){
arguments[4][8][0].apply(exports,arguments)
},{"dup":8}],61:[function(require,module,exports){
var magnet = require('magnet-uri')
var parseTorrentFile = require('parse-torrent-file')

/**
 * Parse a torrent identifier (magnet uri, .torrent file, info hash)
 * @param  {string|Buffer|Object} torrentId
 * @return {Object}
 */
module.exports = function parseTorrent (torrentId) {
  var len = torrentId && torrentId.length
  if (typeof torrentId === 'string' && /magnet:/.test(torrentId)) {
    // magnet uri (string)
    return magnet(torrentId)
  } else if (typeof torrentId === 'string' && (len === 40 || len === 32)) {
    // info hash (hex/base-32 string)
    return magnet('magnet:?xt=urn:btih:' + torrentId)
  } else if (Buffer.isBuffer(torrentId) && len === 20) {
    // info hash (buffer)
    return magnet('magnet:?xt=urn:btih:' + torrentId.toString('hex'))
  } else if (Buffer.isBuffer(torrentId)) {
    // .torrent file (buffer)
    return parseTorrentFile(torrentId) // might throw
  } else if (torrentId && torrentId.infoHash) {
    // parsed torrent (from `parse-torrent`, `parse-torrent-file`, or `magnet-uri`)
    return torrentId
  } else {
    throw new Error('Invalid torrent identifier')
  }
}

module.exports.toMagnetURI = magnet.encode
module.exports.toTorrentFile = parseTorrentFile.encode

},{"magnet-uri":53,"parse-torrent-file":56}],62:[function(require,module,exports){
var Duplex = require('stream').Duplex || require('readable-stream').Duplex;
var EventEmitter = require('events').EventEmitter;
var bitfield = require('bitfield');
var util = require('util');
var bncode = require('bncode');
var speedometer = require('speedometer');
var bufferFrom = require('buffer-from');
var bufferAlloc = require('buffer-alloc');

var MESSAGE_PROTOCOL     = bufferFrom([0x13,0x42,0x69,0x74,0x54,0x6f,0x72,0x72,0x65,0x6e,0x74,0x20,0x70,0x72,0x6f,0x74,0x6f,0x63,0x6f,0x6c]);
var MESSAGE_KEEP_ALIVE   = bufferFrom([0x00,0x00,0x00,0x00]);
var MESSAGE_CHOKE        = bufferFrom([0x00,0x00,0x00,0x01,0x00]);
var MESSAGE_UNCHOKE      = bufferFrom([0x00,0x00,0x00,0x01,0x01]);
var MESSAGE_INTERESTED   = bufferFrom([0x00,0x00,0x00,0x01,0x02]);
var MESSAGE_UNINTERESTED = bufferFrom([0x00,0x00,0x00,0x01,0x03]);
var MESSAGE_RESERVED     = [0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00];
var MESSAGE_PORT         = [0x00,0x00,0x00,0x03,0x09,0x00,0x00];

var noop = function() {};

var pull = function(requests, piece, offset, length) {
	for (var i = 0; i < requests.length; i++) {
		var req = requests[i];
		if (req.piece !== piece || req.offset !== offset || req.length !== length) continue;
		if (i === 0) requests.shift();
		else requests.splice(i, 1);
		return req;
	}
	return null;
};

var Request = function(piece, offset, length, callback) {
	this.piece = piece;
	this.offset = offset;
	this.length = length;
	this.callback = callback;
	this.timeout = null;
};

var Wire = function(opts) {
	if (!(this instanceof Wire)) return new Wire(opts);
	if (!opts) opts = {};
	Duplex.call(this);
	var self = this;

	this.amChoking = true;
	this.amInterested = false;
	this.peerChoking = true;
	this.peerInterested = false;
	this.peerPieces = [];
	this.peerExtensions = {};
	this.peerAddress = null; // external

	this.uploaded = 0;
	this.downloaded = 0;

	this.uploadSpeed = speedometer(opts.speed);
	this.downloadSpeed = speedometer(opts.speed);

	this.requests = [];
	this.peerRequests = [];

	this._keepAlive = null;
	this._finished = false;

	this.on('finish', function() {
		self._finished = true;
		self.push(null); // cannot be half open
		clearInterval(self._keepAlive);
		self._parse(Number.MAX_VALUE, noop);
		while (self.peerRequests.length) self.peerRequests.pop();
		while (self.requests.length) self._callback(self.requests.shift(), new Error('wire is closed'), null);
	});

	var ontimeout = function() {
		self._callback(self.requests.shift(), new Error('request has timed out'), null);
		self.emit('timeout');
	};

	this._timeout = 0;
	this._ontimeout = ontimeout;

	var onmessagelength = function(buffer) {
		var length = buffer.readUInt32BE(0);
		if (length > 262144) return self.destroy();
		if (length) return self._parse(length, onmessage);
		self._parse(4, onmessagelength);
		self.emit('keep-alive');
	};

	var onmessage = function(buffer) {
		self._parse(4, onmessagelength);
		var minimum = { 4: 5, 6: 13, 7: 9, 8: 13, 9: 3, 20: 2 };
		if (minimum[buffer[0]] && buffer.length < minimum[buffer[0]]) return self.destroy();
		switch (buffer[0]) {
			case 0:
			return self._onchoke();
			case 1:
			return self._onunchoke();
			case 2:
			return self._oninterested();
			case 3:
			return self._onuninterested();
			case 4:
			return self._onhave(buffer.readUInt32BE(1));
			case 5:
			return self._onbitfield(buffer.slice(1));
			case 6:
			return self._onrequest(buffer.readUInt32BE(1), buffer.readUInt32BE(5), buffer.readUInt32BE(9));
			case 7:
			return self._onpiece(buffer.readUInt32BE(1), buffer.readUInt32BE(5), buffer.slice(9));
			case 8:
			return self._oncancel(buffer.readUInt32BE(1), buffer.readUInt32BE(5), buffer.readUInt32BE(9));
			case 9:
			return self._onport(buffer.readUInt16BE(1));
			case 20:
			return self._onextended(buffer.readUInt8(1), buffer.slice(2));
		}
		self.emit('unknownmessage', buffer);
	};

	this._buffer = [];
	this._bufferSize = 0;
	this._parser = null;
	this._parserSize = 0;

	this._parse(1, function(buffer) {
		var pstrlen = buffer.readUInt8(0);
		self._parse(pstrlen + 48, function(handshake) {
			handshake = handshake.slice(pstrlen);
			self._onhandshake(handshake.slice(8, 28), handshake.slice(28, 48), {
				dht: !!(handshake[7] & 1),
				extended: !!(handshake[5] & 0x10)
			});
			self._parse(4, onmessagelength);
		});
	});
};

util.inherits(Wire, Duplex);

Wire.prototype.handshake = function(infoHash, peerId, extensions) {
	if (typeof infoHash === 'string') infoHash = bufferFrom(infoHash, 'hex');
	if (typeof peerId === 'string') peerId = bufferFrom(peerId);
	if (infoHash.length !== 20 || peerId.length !== 20) throw new Error('infoHash and peerId MUST have length 20');

	var reserved = bufferFrom(MESSAGE_RESERVED);
	if (extensions && extensions.dht) reserved[7] |= 1;
	reserved[5] |= 0x10; // enable extended message

	this._push(Buffer.concat([MESSAGE_PROTOCOL, reserved, infoHash, peerId], MESSAGE_PROTOCOL.length+48));
};

Wire.prototype.choke = function() {
	if (this.amChoking) return;
	this.amChoking = true;
	while (this.peerRequests.length) this.peerRequests.pop();
	this._push(MESSAGE_CHOKE);
};

Wire.prototype.unchoke = function() {
	if (!this.amChoking) return;
	this.amChoking = false;
	this._push(MESSAGE_UNCHOKE);
};

Wire.prototype.interested = function() {
	if (this.amInterested) return;
	this.amInterested = true;
	this._push(MESSAGE_INTERESTED);
};

Wire.prototype.uninterested = function() {
	if (!this.amInterested) return;
	this.amInterested = false;
	this._push(MESSAGE_UNINTERESTED);
};

Wire.prototype.have = function(i) {
	this._message(4, [i], null);
};

Wire.prototype.bitfield = function(bitfield) {
	if (bitfield.buffer) bitfield = bitfield.buffer; // support bitfield objects
	this._message(5, [], bitfield);
};

Wire.prototype.request = function(i, offset, length, callback) {
	if (!callback) callback = noop;
	if (this._finished)   return callback(new Error('wire is closed'));
	if (this.peerChoking) return callback(new Error('peer is choking'));
	this.requests.push(new Request(i, offset, length, callback));
	this._updateTimeout();
	this._message(6, [i, offset, length], null);
};

Wire.prototype.piece = function(i, offset, buffer) {
	this.uploaded += buffer.length;
	this.uploadSpeed(buffer.length);
	this.emit('upload', buffer.length);
	this._message(7, [i, offset], buffer);
};

Wire.prototype.cancel = function(i, offset, length) {
	this._callback(pull(this.requests, i, offset, length), new Error('request was cancelled'), null);
	this._message(8, [i, offset, length], null);
};

Wire.prototype.extended = function(id, msg) {
	this._message(20, [], Buffer.concat([bufferFrom([id]), Buffer.isBuffer(msg) ? msg : bncode.encode(msg)]));
};

Wire.prototype.port = function(port) {
	var message = bufferFrom(MESSAGE_PORT);
	message.writeUInt16BE(port, 5);
	this._push(message);
};

Wire.prototype.setKeepAlive = function(bool) {
	clearInterval(this._keepAlive);
	if (bool === false) return;
	this._keepAlive = setInterval(this._push.bind(this, MESSAGE_KEEP_ALIVE), 60000);
};

Wire.prototype.setTimeout = function(ms, fn) {
	if (this.requests.length) clearTimeout(this.requests[0].timeout);
	this._timeout = ms;
	this._updateTimeout();
	if (fn) this.on('timeout', fn);
};

Wire.prototype.destroy = function() {
	this.emit('close');
	this.end();
};

// inbound

Wire.prototype._onhandshake = function(infoHash, peerId, extensions) {
	this.peerExtensions = extensions;
	this.emit('handshake', infoHash, peerId, extensions);
};

Wire.prototype._oninterested = function() {
	this.peerInterested = true;
	this.emit('interested');
};

Wire.prototype._onuninterested = function() {
	this.peerInterested = false;
	this.emit('uninterested');
};

Wire.prototype._onchoke = function() {
	this.peerChoking = true;
	this.emit('choke');
	while (this.requests.length) this._callback(this.requests.shift(), new Error('peer is choking'), null);
};

Wire.prototype._onunchoke = function() {
	this.peerChoking = false;
	this.emit('unchoke');
};

Wire.prototype._onbitfield = function(buffer) {
	if (buffer.length > 2048) return this.destroy();
	var pieces = bitfield(buffer);
	for (var i = 0; i < 8 * buffer.length; i++) {
		this.peerPieces[i] = pieces.get(i);
	}
	this.emit('bitfield', buffer);
};

Wire.prototype._onhave = function(i) {
	if (i >= 16384) return this.destroy();
	this.peerPieces[i] = true;
	this.emit('have', i);
};

Wire.prototype._onrequest = function(i, offset, length) {
	if (this.amChoking) return;

	var self = this;
	var respond = function(err, buffer) {
		if (request !== pull(self.peerRequests, i, offset, length)) return;
		if (err) return;
		self.piece(i, offset, buffer);
	};

	var request = new Request(i, offset, length, respond);
	this.peerRequests.push(request);
	this.emit('request', i, offset, length, respond);
};

Wire.prototype._oncancel = function(i, offset, length) {
	pull(this.peerRequests, i, offset, length);
	this.emit('cancel', i, offset, length);
};

Wire.prototype._onpiece = function(i, offset, buffer) {
	this._callback(pull(this.requests, i, offset, buffer.length), null, buffer);
	this.downloaded += buffer.length;
	this.downloadSpeed(buffer.length);
	this.emit('download', buffer.length);
	this.emit('piece', i, offset, buffer);
};

Wire.prototype._onport = function(port) {
	this.emit('port', port);
};

Wire.prototype._onextended = function(id, ext) {
	this.emit('extended', id, ext);
};

// helpers and streams

Wire.prototype._callback = function(request, err, buffer) {
	if (!request) return;
	if (request.timeout) clearTimeout(request.timeout);
	if (!this.peerChoking && !this._finished) this._updateTimeout();
	request.callback(err, buffer);
};

Wire.prototype._updateTimeout = function() {
	if (!this._timeout || !this.requests.length || this.requests[0].timeout) return;
	this.requests[0].timeout = setTimeout(this._ontimeout, this._timeout);
};

Wire.prototype._message = function(id, numbers, data) {
	var dataLength = data ? data.length : 0;
	var buffer = bufferAlloc(5 + 4 * numbers.length);

	buffer.writeUInt32BE(buffer.length + dataLength - 4, 0);
	buffer[4] = id;
	for (var i = 0; i < numbers.length; i++) {
		buffer.writeUInt32BE(numbers[i], 5 + 4 * i);
	}

	this._push(buffer);
	if (data) this._push(data);
};

Wire.prototype._push = function(data) {
	if (this._finished) return;
	this.push(data);
};

Wire.prototype._parse = function(size, parser) {
	this._parserSize = size;
	this._parser = parser;
};

Wire.prototype._write = function(data, encoding, callback) {
	this._bufferSize += data.length;
	this._buffer.push(data);

	while (this._bufferSize >= this._parserSize) {
		var buffer = this._buffer.length === 1 ? this._buffer[0] : Buffer.concat(this._buffer, this._bufferSize);
		this._bufferSize -= this._parserSize;
		this._buffer = this._bufferSize ? [buffer.slice(this._parserSize)] : [];
		this._parser(buffer.slice(0, this._parserSize));
	}

	callback();
};

Wire.prototype._read = noop;

module.exports = Wire;

},{"bitfield":3,"bncode":63,"buffer-alloc":23,"buffer-from":27,"events":undefined,"readable-stream":70,"speedometer":77,"stream":undefined,"util":undefined}],63:[function(require,module,exports){
/*
 * (c) 2011 Tim Becker, see file LICENSE for details
 */

/*
 * Provides functionality for bencoding and decoding as use in
 * bittorrent and described in:
 *  http://www.bittorrent.org/beps/bep_0003.html
 * 
 * Encoding is as follows:
 *
 *    var benc  = require("bencode"),
 *        exmp = {}
 *
 *    exmp.bla = "blup"
 *    exmp.foo = "bar"
 *    exmp.one = 1
 *    exmp.woah = {}
 *    exmp.woah.arr = []
 *    exmp.woah.arr.push(1)
 *    exmp.woah.arr.push(2)
 *    exmp.woah.arr.push(3)
 *    exmp.str = new Buffer("Buffers work too")
 *
 *    var bencBuffer = benc.encode(exmp) i
 *
 *    // d3:bla4:blup3:foo3:bar3:onei1e4:woahd3:arr \
 *    // li1ei2ei3eee3:str16:Buffers work tooe
 *
 *
 * Decoding will work in progressively, e.g. if you're receiving partial
 * bencoded strings on the network:
 *
 *    var benc = require("bencode"),
 *        buf  = null
 *
 *    decoder = new bencode.decoder()
 *    while (buf = receiveData()) {
 *      decoder.decode(buf)
 *    }
 *    
 *    log(decoder.result())
 *
 *
 * Or "all in one"
 *
 *    var benc = require("bencode"),
 *        buf  = getBuffer(),
 *        dec  = benc.decode(buf)
 *
 *    log(dec.bla)
 *
 *
 * There are some subtleties concerning bencoded strings. These are
 * decoded as Buffer objects because they are just strings of raw bytes
 * and as such would wreak havoc with multi byte strings in javascript.
 *
 * The exception to this is strings that appear as keys in bencoded
 * dicts. These are decoded as Javascript Strings, as they should always
 * be strings of (ascii) characters and if they weren't decoded as JS
 * Strings, dict's would map to Javascript objects with properties.
 *
 */



var I     = "i".charCodeAt(0)
var L     = "l".charCodeAt(0)
var E     = "e".charCodeAt(0)
var D     = "d".charCodeAt(0)
var COLON = ":".charCodeAt(0)

var INITIAL    = 0
var STRING_LEN = INITIAL    + 1
var STRING     = STRING_LEN + 1
var COLON      = STRING     + 1
var SINTEGER   = COLON      + 1
var INTEGER    = SINTEGER   + 1


function log (m) {console.log(m); process.stdout.flush()}

/*
 * This is the internal state machine for taking apart bencoded strings,
 * it's not exposed in the eports.  It's constructed with four callbacks
 * that get fired when:
 *
 * cb: a value (string or number) is encountered 
 * cb_list: a begin list element is encountered 
 * cb_dict: a beginning of dictionary is encountered.  
 * cd_end: an end element, wheter dict or list is encountered
 *
 * Once constructed, the machine may be fed with buffers containing
 * partial bencoded string. Call `consistent` to check whether the
 * current state is consistent, e.g. not smack-dap in the middle of 
 * a string or a number and if the dict, list and end calls balance
 *
 *
 * The functionality being so rudimentary requires some more state and
 * logic in the code executing the machine, for this see Context, below.
 *
 */
 
var BdecodeSMachine = function (cb, cb_list, cb_dict, cb_end) {
  var self    = this
  var pos     = 0
  var depth   = 0
  var state   = INITIAL
  var cb      = cb
  var cb_list = cb_list
  var cb_dict = cb_dict
  var cb_end  = cb_end
  
  this.consistent = function() {
    return (state === INITIAL && depth === 0)
  }
  
  var strLen = 0
  var str    = ""
  var _int   = 0
  var neg    = false
  var strLen = 0

  this.parse = function (buffer, encoding) {
    encoding = encoding ? encoding : "UTF-8"
    if ("string" === typeof(buffer) ) {
      buffer = new Buffer(buffer, encoding)
    }
    for (var pos = 0; pos != buffer.length; ++pos) {
        
      switch(state) {
        case INITIAL:
          switch(buffer[pos]){
            case 0x30:
            case 0x31:
            case 0x32:
            case 0x33:
            case 0x34:
            case 0x35:
            case 0x36:
            case 0x37:
            case 0x38:
            case 0x39:
              state = STRING_LEN
              strLen = 0
              strLen += buffer[pos] - 0x30
              break
            case I:
              state = SINTEGER
              _int  = 0
              neg   = false
              break
            case L:
              state = INITIAL
              depth += 1; if (depth > 32) throw new Error("Bencode nesting limit")
              cb_list()
              break
            case D:
              state = INITIAL
              depth += 1; if (depth > 32) throw new Error("Bencode nesting limit")
              cb_dict()
              break
            case E:
              state = INITIAL
              depth -= 1
              if (depth < 0) {
                throw new Error("end with no beginning: "+pos)
              } else {
                cb_end()
              }
              break
          }
          break;
        case STRING_LEN:
          if (integer(buffer[pos])) {
            strLen *= 10
            strLen += buffer[pos] - 0x30
          } else {
            if (strLen > 262144) throw new Error("Bencode string limit");
            str = new Buffer(strLen)
            pos -=1
            state = COLON
          }
          break
        case COLON:
          if (buffer[pos] != ":".charCodeAt(0)) {
            throw new Error("not a colon at:"+pos.toString(16))
          }
          state = STRING
          // in case this is a zero length string, there's
          // no bytes to be collected.
          if (0 === strLen) {
            cb(new Buffer(0))
            state = INITIAL
          }
          break
        case STRING:
          if (0 === strLen) {
            cb(str)
            state = INITIAL
          } else {
            //str += String.fromCharCode(buffer[pos]) // not unicode safe..
            str[str.length-strLen] = buffer[pos]
            strLen -= 1
            if (0 === strLen) {
              cb(str)
              state = INITIAL
            }
          }
          break
        case SINTEGER:
          state = INTEGER
          if (buffer[pos] == "-".charCodeAt(0)) {
            neg = true  // handle neg and zero within value.
            break
          } // else fall through 
        case INTEGER:
          if (integer(buffer[pos])) {
            _int *= 10
            _int += buffer[pos] - 0x30
          } else if (buffer[pos] == "e".charCodeAt(0)) {
            var ret = neg ? 0 - _int : _int
            cb(ret)
            state = INITIAL
          } else {
            throw new Error("not part of int at:"+pos.toString(16))
          }
          break;
      } // switch state
    } // for buffer  
  } // function parse

  
  var integer = function (value) {
    // check that value is a number and that
    // its value is ascii integer.
    if (! (typeof(value)==='number') ) {
      return false 
    }
    return between(value, 0x30, 0x39)
  }
  var between = function(val, min, max) {
    return (min <= val  && val <= max)
  }


} // end BdecodeSMachine

/*
 * The exported decode functionality.
 */
var Bdecode = function () {
  // markers
  var DICTIONARY_START = {}
  var LIST_START       = {}

  var Context  = function() {
    var self  = this
    var stack = []

    this.cb   = function(o){
      stack.push(o)
    }
    this.cb_list = function(){
      self.cb(LIST_START)
    }
    this.cb_dict = function(){
      self.cb(DICTIONARY_START)
    }

    this.cb_end  = function(){
      
      // unwind the stack until either a DICTIONARY_START or LIST_START is
      // found, create arr or hash, stick unwound stack on, push arr or hash
      // back onto stack
      
      var obj       = null,
          tmp_stack = []

      while ( undefined !== (obj = stack.pop()) ) {
        if (LIST_START === obj) {
          var obj2 = null
          var list = []
          while( undefined !== (obj2 = tmp_stack.pop()) ) {
            list.push(obj2)
          }
          self.cb(list)
          break
        } else if (DICTIONARY_START === obj) {
          var key = null,
              val = null,
              dic = Object.create(null)
          while ( (undefined !== (key = tmp_stack.pop())) && (undefined !== (val = tmp_stack.pop())) ) {
            dic[key.toString()] = val
          }

          if (undefined !== key && undefined === dic[key]) {
            throw new Error("uneven number of keys and values A")
          }
          self.cb(dic)
          break
        } else {
          tmp_stack.push(obj)
        }
      }
      if (tmp_stack.length > 0) {
        // could this case even occur?
        throw new Error("uneven number of keys and values B")
      }
    }
    this.result = function () {
      return stack
    }
  }
 
  var self      = this,
      ctx       = new Context(),
      smachine  = new BdecodeSMachine(ctx.cb, ctx.cb_list, ctx.cb_dict, ctx.cb_end)

  this.result    = function () {
    if (!smachine.consistent()) {
      throw new Error("not in consistent state. More bytes coming?")
    }
    return ctx.result()
  }

  this.decode = function(buf, encoding) {
    smachine.parse(buf, encoding)
  }
} 

var Bencode = function(obj) {
  var self = this
  var to_encode = obj

  switch (typeof (obj) ) {
    case "string":
      return encodeString(obj)
    case "number":
      return encodeNumber(obj)
      break
    case "object":
      if (obj instanceof Array) {
        return encodeList(obj) 
      } else if (obj instanceof Buffer) {
        return encodeBuffer(obj)
      }
      
      {
        // assume it's a hash
        return encodeDict(obj)
      }
  }

  function encodeString(obj) {
    var blen = Buffer.byteLength(obj),
        len  = blen.toString(10),
        buf  = new Buffer(len.length + 1 + blen)
    
    buf.write(len, 0, "ascii")
    buf.write(":", len.length, "ascii")
    buf.write(obj, len.length+1, "utf8")

    return buf
  }

  function encodeNumber(num) {
    var n   = num.toString(10),
        buf = new Buffer(n.length+2)

    buf.write("i", 0)
    buf.write(n,   1)
    buf.write("e", n.length+1)

    return buf
  }

  function encodeDict(obj) {
    
    var func = function (obj, pos) {
      for (var p in obj) {
        var key = Bencode(p),
            val = Bencode(obj[p])
        ensure(key.length + val.length, pos)
        key.copy(buffer, pos, 0)
        pos += key.length
        val.copy(buffer, pos, 0)
        pos += val.length
      }
      return pos
    }
    return assemble(obj, "d", func)
  }

  function encodeList(obj) {
    var func = function(obj, pos) {
      obj.forEach (function(o){
        var elem = Bencode(o)

        ensure(elem.length, pos)
        elem.copy(buffer, pos, 0)
        pos += elem.length
      })
      return pos
    }
    return assemble(obj, "l", func)
  }

  function encodeBuffer(obj) {
    var len = obj.length.toString(10),
        buf = new Buffer(len.length+1+obj.length);

    buf.write(len, 0, "ascii")
    buf.write(":", len.length, "ascii")
    obj.copy(buf, len.length+1, 0)
    return buf
      
  }

  var buffer = null
  function assemble (obj, prefix, func) {
    var pos = 0

    ensure(1024, 0)
    buffer.write(prefix, pos++)
    
    pos = func(obj, pos)
    ensure(1, pos)

    buffer.write("e", pos++)
    return buffer.slice(0, pos)
  }

  function ensure (num, pos) {
    if (!buffer) {
      buffer = new Buffer(num)
    } else {
      if (buffer.length > num+pos+1) {
        return
      } else {
        var buf2 = new Buffer(buffer.length + num) 
        buffer.copy(buf2, 0, 0)
        buffer = buf2
      }
    }
  }
}

function decode (buffer, encoding) {
  var decoder = new Bdecode()

  decoder.decode(buffer, encoding)
  return decoder.result()[0]


}

exports.encode  = Bencode
exports.decoder = Bdecode
exports.decode  = decode


 
 




},{}],64:[function(require,module,exports){
module.exports = Array.isArray || function (arr) {
  return Object.prototype.toString.call(arr) == '[object Array]';
};

},{}],65:[function(require,module,exports){
// Copyright Joyent, Inc. and other Node contributors.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the
// following conditions:
//
// The above copyright notice and this permission notice shall be included
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
// NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
// USE OR OTHER DEALINGS IN THE SOFTWARE.

// a duplex stream is just a stream that is both readable and writable.
// Since JS doesn't have multiple prototypal inheritance, this class
// prototypally inherits from Readable, and then parasitically from
// Writable.

module.exports = Duplex;

/*<replacement>*/
var objectKeys = Object.keys || function (obj) {
  var keys = [];
  for (var key in obj) keys.push(key);
  return keys;
}
/*</replacement>*/


/*<replacement>*/
var util = require('core-util-is');
util.inherits = require('inherits');
/*</replacement>*/

var Readable = require('./_stream_readable');
var Writable = require('./_stream_writable');

util.inherits(Duplex, Readable);

forEach(objectKeys(Writable.prototype), function(method) {
  if (!Duplex.prototype[method])
    Duplex.prototype[method] = Writable.prototype[method];
});

function Duplex(options) {
  if (!(this instanceof Duplex))
    return new Duplex(options);

  Readable.call(this, options);
  Writable.call(this, options);

  if (options && options.readable === false)
    this.readable = false;

  if (options && options.writable === false)
    this.writable = false;

  this.allowHalfOpen = true;
  if (options && options.allowHalfOpen === false)
    this.allowHalfOpen = false;

  this.once('end', onend);
}

// the no-half-open enforcer
function onend() {
  // if we allow half-open state, or if the writable side ended,
  // then we're ok.
  if (this.allowHalfOpen || this._writableState.ended)
    return;

  // no more data can be written.
  // But allow more writes to happen in this tick.
  process.nextTick(this.end.bind(this));
}

function forEach (xs, f) {
  for (var i = 0, l = xs.length; i < l; i++) {
    f(xs[i], i);
  }
}

},{"./_stream_readable":67,"./_stream_writable":69,"core-util-is":29,"inherits":40}],66:[function(require,module,exports){
// Copyright Joyent, Inc. and other Node contributors.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the
// following conditions:
//
// The above copyright notice and this permission notice shall be included
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
// NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
// USE OR OTHER DEALINGS IN THE SOFTWARE.

// a passthrough stream.
// basically just the most minimal sort of Transform stream.
// Every written chunk gets output as-is.

module.exports = PassThrough;

var Transform = require('./_stream_transform');

/*<replacement>*/
var util = require('core-util-is');
util.inherits = require('inherits');
/*</replacement>*/

util.inherits(PassThrough, Transform);

function PassThrough(options) {
  if (!(this instanceof PassThrough))
    return new PassThrough(options);

  Transform.call(this, options);
}

PassThrough.prototype._transform = function(chunk, encoding, cb) {
  cb(null, chunk);
};

},{"./_stream_transform":68,"core-util-is":29,"inherits":40}],67:[function(require,module,exports){
// Copyright Joyent, Inc. and other Node contributors.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the
// following conditions:
//
// The above copyright notice and this permission notice shall be included
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
// NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
// USE OR OTHER DEALINGS IN THE SOFTWARE.

module.exports = Readable;

/*<replacement>*/
var isArray = require('isarray');
/*</replacement>*/


/*<replacement>*/
var Buffer = require('buffer').Buffer;
/*</replacement>*/

Readable.ReadableState = ReadableState;

var EE = require('events').EventEmitter;

/*<replacement>*/
if (!EE.listenerCount) EE.listenerCount = function(emitter, type) {
  return emitter.listeners(type).length;
};
/*</replacement>*/

var Stream = require('stream');

/*<replacement>*/
var util = require('core-util-is');
util.inherits = require('inherits');
/*</replacement>*/

var StringDecoder;


/*<replacement>*/
var debug = require('util');
if (debug && debug.debuglog) {
  debug = debug.debuglog('stream');
} else {
  debug = function () {};
}
/*</replacement>*/


util.inherits(Readable, Stream);

function ReadableState(options, stream) {
  var Duplex = require('./_stream_duplex');

  options = options || {};

  // the point at which it stops calling _read() to fill the buffer
  // Note: 0 is a valid value, means "don't call _read preemptively ever"
  var hwm = options.highWaterMark;
  var defaultHwm = options.objectMode ? 16 : 16 * 1024;
  this.highWaterMark = (hwm || hwm === 0) ? hwm : defaultHwm;

  // cast to ints.
  this.highWaterMark = ~~this.highWaterMark;

  this.buffer = [];
  this.length = 0;
  this.pipes = null;
  this.pipesCount = 0;
  this.flowing = null;
  this.ended = false;
  this.endEmitted = false;
  this.reading = false;

  // a flag to be able to tell if the onwrite cb is called immediately,
  // or on a later tick.  We set this to true at first, because any
  // actions that shouldn't happen until "later" should generally also
  // not happen before the first write call.
  this.sync = true;

  // whenever we return null, then we set a flag to say
  // that we're awaiting a 'readable' event emission.
  this.needReadable = false;
  this.emittedReadable = false;
  this.readableListening = false;


  // object stream flag. Used to make read(n) ignore n and to
  // make all the buffer merging and length checks go away
  this.objectMode = !!options.objectMode;

  if (stream instanceof Duplex)
    this.objectMode = this.objectMode || !!options.readableObjectMode;

  // Crypto is kind of old and crusty.  Historically, its default string
  // encoding is 'binary' so we have to make this configurable.
  // Everything else in the universe uses 'utf8', though.
  this.defaultEncoding = options.defaultEncoding || 'utf8';

  // when piping, we only care about 'readable' events that happen
  // after read()ing all the bytes and not getting any pushback.
  this.ranOut = false;

  // the number of writers that are awaiting a drain event in .pipe()s
  this.awaitDrain = 0;

  // if true, a maybeReadMore has been scheduled
  this.readingMore = false;

  this.decoder = null;
  this.encoding = null;
  if (options.encoding) {
    if (!StringDecoder)
      StringDecoder = require('string_decoder/').StringDecoder;
    this.decoder = new StringDecoder(options.encoding);
    this.encoding = options.encoding;
  }
}

function Readable(options) {
  var Duplex = require('./_stream_duplex');

  if (!(this instanceof Readable))
    return new Readable(options);

  this._readableState = new ReadableState(options, this);

  // legacy
  this.readable = true;

  Stream.call(this);
}

// Manually shove something into the read() buffer.
// This returns true if the highWaterMark has not been hit yet,
// similar to how Writable.write() returns true if you should
// write() some more.
Readable.prototype.push = function(chunk, encoding) {
  var state = this._readableState;

  if (util.isString(chunk) && !state.objectMode) {
    encoding = encoding || state.defaultEncoding;
    if (encoding !== state.encoding) {
      chunk = new Buffer(chunk, encoding);
      encoding = '';
    }
  }

  return readableAddChunk(this, state, chunk, encoding, false);
};

// Unshift should *always* be something directly out of read()
Readable.prototype.unshift = function(chunk) {
  var state = this._readableState;
  return readableAddChunk(this, state, chunk, '', true);
};

function readableAddChunk(stream, state, chunk, encoding, addToFront) {
  var er = chunkInvalid(state, chunk);
  if (er) {
    stream.emit('error', er);
  } else if (util.isNullOrUndefined(chunk)) {
    state.reading = false;
    if (!state.ended)
      onEofChunk(stream, state);
  } else if (state.objectMode || chunk && chunk.length > 0) {
    if (state.ended && !addToFront) {
      var e = new Error('stream.push() after EOF');
      stream.emit('error', e);
    } else if (state.endEmitted && addToFront) {
      var e = new Error('stream.unshift() after end event');
      stream.emit('error', e);
    } else {
      if (state.decoder && !addToFront && !encoding)
        chunk = state.decoder.write(chunk);

      if (!addToFront)
        state.reading = false;

      // if we want the data now, just emit it.
      if (state.flowing && state.length === 0 && !state.sync) {
        stream.emit('data', chunk);
        stream.read(0);
      } else {
        // update the buffer info.
        state.length += state.objectMode ? 1 : chunk.length;
        if (addToFront)
          state.buffer.unshift(chunk);
        else
          state.buffer.push(chunk);

        if (state.needReadable)
          emitReadable(stream);
      }

      maybeReadMore(stream, state);
    }
  } else if (!addToFront) {
    state.reading = false;
  }

  return needMoreData(state);
}



// if it's past the high water mark, we can push in some more.
// Also, if we have no data yet, we can stand some
// more bytes.  This is to work around cases where hwm=0,
// such as the repl.  Also, if the push() triggered a
// readable event, and the user called read(largeNumber) such that
// needReadable was set, then we ought to push more, so that another
// 'readable' event will be triggered.
function needMoreData(state) {
  return !state.ended &&
         (state.needReadable ||
          state.length < state.highWaterMark ||
          state.length === 0);
}

// backwards compatibility.
Readable.prototype.setEncoding = function(enc) {
  if (!StringDecoder)
    StringDecoder = require('string_decoder/').StringDecoder;
  this._readableState.decoder = new StringDecoder(enc);
  this._readableState.encoding = enc;
  return this;
};

// Don't raise the hwm > 128MB
var MAX_HWM = 0x800000;
function roundUpToNextPowerOf2(n) {
  if (n >= MAX_HWM) {
    n = MAX_HWM;
  } else {
    // Get the next highest power of 2
    n--;
    for (var p = 1; p < 32; p <<= 1) n |= n >> p;
    n++;
  }
  return n;
}

function howMuchToRead(n, state) {
  if (state.length === 0 && state.ended)
    return 0;

  if (state.objectMode)
    return n === 0 ? 0 : 1;

  if (isNaN(n) || util.isNull(n)) {
    // only flow one buffer at a time
    if (state.flowing && state.buffer.length)
      return state.buffer[0].length;
    else
      return state.length;
  }

  if (n <= 0)
    return 0;

  // If we're asking for more than the target buffer level,
  // then raise the water mark.  Bump up to the next highest
  // power of 2, to prevent increasing it excessively in tiny
  // amounts.
  if (n > state.highWaterMark)
    state.highWaterMark = roundUpToNextPowerOf2(n);

  // don't have that much.  return null, unless we've ended.
  if (n > state.length) {
    if (!state.ended) {
      state.needReadable = true;
      return 0;
    } else
      return state.length;
  }

  return n;
}

// you can override either this method, or the async _read(n) below.
Readable.prototype.read = function(n) {
  debug('read', n);
  var state = this._readableState;
  var nOrig = n;

  if (!util.isNumber(n) || n > 0)
    state.emittedReadable = false;

  // if we're doing read(0) to trigger a readable event, but we
  // already have a bunch of data in the buffer, then just trigger
  // the 'readable' event and move on.
  if (n === 0 &&
      state.needReadable &&
      (state.length >= state.highWaterMark || state.ended)) {
    debug('read: emitReadable', state.length, state.ended);
    if (state.length === 0 && state.ended)
      endReadable(this);
    else
      emitReadable(this);
    return null;
  }

  n = howMuchToRead(n, state);

  // if we've ended, and we're now clear, then finish it up.
  if (n === 0 && state.ended) {
    if (state.length === 0)
      endReadable(this);
    return null;
  }

  // All the actual chunk generation logic needs to be
  // *below* the call to _read.  The reason is that in certain
  // synthetic stream cases, such as passthrough streams, _read
  // may be a completely synchronous operation which may change
  // the state of the read buffer, providing enough data when
  // before there was *not* enough.
  //
  // So, the steps are:
  // 1. Figure out what the state of things will be after we do
  // a read from the buffer.
  //
  // 2. If that resulting state will trigger a _read, then call _read.
  // Note that this may be asynchronous, or synchronous.  Yes, it is
  // deeply ugly to write APIs this way, but that still doesn't mean
  // that the Readable class should behave improperly, as streams are
  // designed to be sync/async agnostic.
  // Take note if the _read call is sync or async (ie, if the read call
  // has returned yet), so that we know whether or not it's safe to emit
  // 'readable' etc.
  //
  // 3. Actually pull the requested chunks out of the buffer and return.

  // if we need a readable event, then we need to do some reading.
  var doRead = state.needReadable;
  debug('need readable', doRead);

  // if we currently have less than the highWaterMark, then also read some
  if (state.length === 0 || state.length - n < state.highWaterMark) {
    doRead = true;
    debug('length less than watermark', doRead);
  }

  // however, if we've ended, then there's no point, and if we're already
  // reading, then it's unnecessary.
  if (state.ended || state.reading) {
    doRead = false;
    debug('reading or ended', doRead);
  }

  if (doRead) {
    debug('do read');
    state.reading = true;
    state.sync = true;
    // if the length is currently zero, then we *need* a readable event.
    if (state.length === 0)
      state.needReadable = true;
    // call internal read method
    this._read(state.highWaterMark);
    state.sync = false;
  }

  // If _read pushed data synchronously, then `reading` will be false,
  // and we need to re-evaluate how much data we can return to the user.
  if (doRead && !state.reading)
    n = howMuchToRead(nOrig, state);

  var ret;
  if (n > 0)
    ret = fromList(n, state);
  else
    ret = null;

  if (util.isNull(ret)) {
    state.needReadable = true;
    n = 0;
  }

  state.length -= n;

  // If we have nothing in the buffer, then we want to know
  // as soon as we *do* get something into the buffer.
  if (state.length === 0 && !state.ended)
    state.needReadable = true;

  // If we tried to read() past the EOF, then emit end on the next tick.
  if (nOrig !== n && state.ended && state.length === 0)
    endReadable(this);

  if (!util.isNull(ret))
    this.emit('data', ret);

  return ret;
};

function chunkInvalid(state, chunk) {
  var er = null;
  if (!util.isBuffer(chunk) &&
      !util.isString(chunk) &&
      !util.isNullOrUndefined(chunk) &&
      !state.objectMode) {
    er = new TypeError('Invalid non-string/buffer chunk');
  }
  return er;
}


function onEofChunk(stream, state) {
  if (state.decoder && !state.ended) {
    var chunk = state.decoder.end();
    if (chunk && chunk.length) {
      state.buffer.push(chunk);
      state.length += state.objectMode ? 1 : chunk.length;
    }
  }
  state.ended = true;

  // emit 'readable' now to make sure it gets picked up.
  emitReadable(stream);
}

// Don't emit readable right away in sync mode, because this can trigger
// another read() call => stack overflow.  This way, it might trigger
// a nextTick recursion warning, but that's not so bad.
function emitReadable(stream) {
  var state = stream._readableState;
  state.needReadable = false;
  if (!state.emittedReadable) {
    debug('emitReadable', state.flowing);
    state.emittedReadable = true;
    if (state.sync)
      process.nextTick(function() {
        emitReadable_(stream);
      });
    else
      emitReadable_(stream);
  }
}

function emitReadable_(stream) {
  debug('emit readable');
  stream.emit('readable');
  flow(stream);
}


// at this point, the user has presumably seen the 'readable' event,
// and called read() to consume some data.  that may have triggered
// in turn another _read(n) call, in which case reading = true if
// it's in progress.
// However, if we're not ended, or reading, and the length < hwm,
// then go ahead and try to read some more preemptively.
function maybeReadMore(stream, state) {
  if (!state.readingMore) {
    state.readingMore = true;
    process.nextTick(function() {
      maybeReadMore_(stream, state);
    });
  }
}

function maybeReadMore_(stream, state) {
  var len = state.length;
  while (!state.reading && !state.flowing && !state.ended &&
         state.length < state.highWaterMark) {
    debug('maybeReadMore read 0');
    stream.read(0);
    if (len === state.length)
      // didn't get any data, stop spinning.
      break;
    else
      len = state.length;
  }
  state.readingMore = false;
}

// abstract method.  to be overridden in specific implementation classes.
// call cb(er, data) where data is <= n in length.
// for virtual (non-string, non-buffer) streams, "length" is somewhat
// arbitrary, and perhaps not very meaningful.
Readable.prototype._read = function(n) {
  this.emit('error', new Error('not implemented'));
};

Readable.prototype.pipe = function(dest, pipeOpts) {
  var src = this;
  var state = this._readableState;

  switch (state.pipesCount) {
    case 0:
      state.pipes = dest;
      break;
    case 1:
      state.pipes = [state.pipes, dest];
      break;
    default:
      state.pipes.push(dest);
      break;
  }
  state.pipesCount += 1;
  debug('pipe count=%d opts=%j', state.pipesCount, pipeOpts);

  var doEnd = (!pipeOpts || pipeOpts.end !== false) &&
              dest !== process.stdout &&
              dest !== process.stderr;

  var endFn = doEnd ? onend : cleanup;
  if (state.endEmitted)
    process.nextTick(endFn);
  else
    src.once('end', endFn);

  dest.on('unpipe', onunpipe);
  function onunpipe(readable) {
    debug('onunpipe');
    if (readable === src) {
      cleanup();
    }
  }

  function onend() {
    debug('onend');
    dest.end();
  }

  // when the dest drains, it reduces the awaitDrain counter
  // on the source.  This would be more elegant with a .once()
  // handler in flow(), but adding and removing repeatedly is
  // too slow.
  var ondrain = pipeOnDrain(src);
  dest.on('drain', ondrain);

  function cleanup() {
    debug('cleanup');
    // cleanup event handlers once the pipe is broken
    dest.removeListener('close', onclose);
    dest.removeListener('finish', onfinish);
    dest.removeListener('drain', ondrain);
    dest.removeListener('error', onerror);
    dest.removeListener('unpipe', onunpipe);
    src.removeListener('end', onend);
    src.removeListener('end', cleanup);
    src.removeListener('data', ondata);

    // if the reader is waiting for a drain event from this
    // specific writer, then it would cause it to never start
    // flowing again.
    // So, if this is awaiting a drain, then we just call it now.
    // If we don't know, then assume that we are waiting for one.
    if (state.awaitDrain &&
        (!dest._writableState || dest._writableState.needDrain))
      ondrain();
  }

  src.on('data', ondata);
  function ondata(chunk) {
    debug('ondata');
    var ret = dest.write(chunk);
    if (false === ret) {
      debug('false write response, pause',
            src._readableState.awaitDrain);
      src._readableState.awaitDrain++;
      src.pause();
    }
  }

  // if the dest has an error, then stop piping into it.
  // however, don't suppress the throwing behavior for this.
  function onerror(er) {
    debug('onerror', er);
    unpipe();
    dest.removeListener('error', onerror);
    if (EE.listenerCount(dest, 'error') === 0)
      dest.emit('error', er);
  }
  // This is a brutally ugly hack to make sure that our error handler
  // is attached before any userland ones.  NEVER DO THIS.
  if (!dest._events || !dest._events.error)
    dest.on('error', onerror);
  else if (isArray(dest._events.error))
    dest._events.error.unshift(onerror);
  else
    dest._events.error = [onerror, dest._events.error];



  // Both close and finish should trigger unpipe, but only once.
  function onclose() {
    dest.removeListener('finish', onfinish);
    unpipe();
  }
  dest.once('close', onclose);
  function onfinish() {
    debug('onfinish');
    dest.removeListener('close', onclose);
    unpipe();
  }
  dest.once('finish', onfinish);

  function unpipe() {
    debug('unpipe');
    src.unpipe(dest);
  }

  // tell the dest that it's being piped to
  dest.emit('pipe', src);

  // start the flow if it hasn't been started already.
  if (!state.flowing) {
    debug('pipe resume');
    src.resume();
  }

  return dest;
};

function pipeOnDrain(src) {
  return function() {
    var state = src._readableState;
    debug('pipeOnDrain', state.awaitDrain);
    if (state.awaitDrain)
      state.awaitDrain--;
    if (state.awaitDrain === 0 && EE.listenerCount(src, 'data')) {
      state.flowing = true;
      flow(src);
    }
  };
}


Readable.prototype.unpipe = function(dest) {
  var state = this._readableState;

  // if we're not piping anywhere, then do nothing.
  if (state.pipesCount === 0)
    return this;

  // just one destination.  most common case.
  if (state.pipesCount === 1) {
    // passed in one, but it's not the right one.
    if (dest && dest !== state.pipes)
      return this;

    if (!dest)
      dest = state.pipes;

    // got a match.
    state.pipes = null;
    state.pipesCount = 0;
    state.flowing = false;
    if (dest)
      dest.emit('unpipe', this);
    return this;
  }

  // slow case. multiple pipe destinations.

  if (!dest) {
    // remove all.
    var dests = state.pipes;
    var len = state.pipesCount;
    state.pipes = null;
    state.pipesCount = 0;
    state.flowing = false;

    for (var i = 0; i < len; i++)
      dests[i].emit('unpipe', this);
    return this;
  }

  // try to find the right one.
  var i = indexOf(state.pipes, dest);
  if (i === -1)
    return this;

  state.pipes.splice(i, 1);
  state.pipesCount -= 1;
  if (state.pipesCount === 1)
    state.pipes = state.pipes[0];

  dest.emit('unpipe', this);

  return this;
};

// set up data events if they are asked for
// Ensure readable listeners eventually get something
Readable.prototype.on = function(ev, fn) {
  var res = Stream.prototype.on.call(this, ev, fn);

  // If listening to data, and it has not explicitly been paused,
  // then call resume to start the flow of data on the next tick.
  if (ev === 'data' && false !== this._readableState.flowing) {
    this.resume();
  }

  if (ev === 'readable' && this.readable) {
    var state = this._readableState;
    if (!state.readableListening) {
      state.readableListening = true;
      state.emittedReadable = false;
      state.needReadable = true;
      if (!state.reading) {
        var self = this;
        process.nextTick(function() {
          debug('readable nexttick read 0');
          self.read(0);
        });
      } else if (state.length) {
        emitReadable(this, state);
      }
    }
  }

  return res;
};
Readable.prototype.addListener = Readable.prototype.on;

// pause() and resume() are remnants of the legacy readable stream API
// If the user uses them, then switch into old mode.
Readable.prototype.resume = function() {
  var state = this._readableState;
  if (!state.flowing) {
    debug('resume');
    state.flowing = true;
    if (!state.reading) {
      debug('resume read 0');
      this.read(0);
    }
    resume(this, state);
  }
  return this;
};

function resume(stream, state) {
  if (!state.resumeScheduled) {
    state.resumeScheduled = true;
    process.nextTick(function() {
      resume_(stream, state);
    });
  }
}

function resume_(stream, state) {
  state.resumeScheduled = false;
  stream.emit('resume');
  flow(stream);
  if (state.flowing && !state.reading)
    stream.read(0);
}

Readable.prototype.pause = function() {
  debug('call pause flowing=%j', this._readableState.flowing);
  if (false !== this._readableState.flowing) {
    debug('pause');
    this._readableState.flowing = false;
    this.emit('pause');
  }
  return this;
};

function flow(stream) {
  var state = stream._readableState;
  debug('flow', state.flowing);
  if (state.flowing) {
    do {
      var chunk = stream.read();
    } while (null !== chunk && state.flowing);
  }
}

// wrap an old-style stream as the async data source.
// This is *not* part of the readable stream interface.
// It is an ugly unfortunate mess of history.
Readable.prototype.wrap = function(stream) {
  var state = this._readableState;
  var paused = false;

  var self = this;
  stream.on('end', function() {
    debug('wrapped end');
    if (state.decoder && !state.ended) {
      var chunk = state.decoder.end();
      if (chunk && chunk.length)
        self.push(chunk);
    }

    self.push(null);
  });

  stream.on('data', function(chunk) {
    debug('wrapped data');
    if (state.decoder)
      chunk = state.decoder.write(chunk);
    if (!chunk || !state.objectMode && !chunk.length)
      return;

    var ret = self.push(chunk);
    if (!ret) {
      paused = true;
      stream.pause();
    }
  });

  // proxy all the other methods.
  // important when wrapping filters and duplexes.
  for (var i in stream) {
    if (util.isFunction(stream[i]) && util.isUndefined(this[i])) {
      this[i] = function(method) { return function() {
        return stream[method].apply(stream, arguments);
      }}(i);
    }
  }

  // proxy certain important events.
  var events = ['error', 'close', 'destroy', 'pause', 'resume'];
  forEach(events, function(ev) {
    stream.on(ev, self.emit.bind(self, ev));
  });

  // when we try to consume some more bytes, simply unpause the
  // underlying stream.
  self._read = function(n) {
    debug('wrapped _read', n);
    if (paused) {
      paused = false;
      stream.resume();
    }
  };

  return self;
};



// exposed for testing purposes only.
Readable._fromList = fromList;

// Pluck off n bytes from an array of buffers.
// Length is the combined lengths of all the buffers in the list.
function fromList(n, state) {
  var list = state.buffer;
  var length = state.length;
  var stringMode = !!state.decoder;
  var objectMode = !!state.objectMode;
  var ret;

  // nothing in the list, definitely empty.
  if (list.length === 0)
    return null;

  if (length === 0)
    ret = null;
  else if (objectMode)
    ret = list.shift();
  else if (!n || n >= length) {
    // read it all, truncate the array.
    if (stringMode)
      ret = list.join('');
    else
      ret = Buffer.concat(list, length);
    list.length = 0;
  } else {
    // read just some of it.
    if (n < list[0].length) {
      // just take a part of the first list item.
      // slice is the same for buffers and strings.
      var buf = list[0];
      ret = buf.slice(0, n);
      list[0] = buf.slice(n);
    } else if (n === list[0].length) {
      // first list is a perfect match
      ret = list.shift();
    } else {
      // complex case.
      // we have enough to cover it, but it spans past the first buffer.
      if (stringMode)
        ret = '';
      else
        ret = new Buffer(n);

      var c = 0;
      for (var i = 0, l = list.length; i < l && c < n; i++) {
        var buf = list[0];
        var cpy = Math.min(n - c, buf.length);

        if (stringMode)
          ret += buf.slice(0, cpy);
        else
          buf.copy(ret, c, 0, cpy);

        if (cpy < buf.length)
          list[0] = buf.slice(cpy);
        else
          list.shift();

        c += cpy;
      }
    }
  }

  return ret;
}

function endReadable(stream) {
  var state = stream._readableState;

  // If we get here before consuming all the bytes, then that is a
  // bug in node.  Should never happen.
  if (state.length > 0)
    throw new Error('endReadable called on non-empty stream');

  if (!state.endEmitted) {
    state.ended = true;
    process.nextTick(function() {
      // Check that we didn't get one last unshift.
      if (!state.endEmitted && state.length === 0) {
        state.endEmitted = true;
        stream.readable = false;
        stream.emit('end');
      }
    });
  }
}

function forEach (xs, f) {
  for (var i = 0, l = xs.length; i < l; i++) {
    f(xs[i], i);
  }
}

function indexOf (xs, x) {
  for (var i = 0, l = xs.length; i < l; i++) {
    if (xs[i] === x) return i;
  }
  return -1;
}

},{"./_stream_duplex":65,"buffer":undefined,"core-util-is":29,"events":undefined,"inherits":40,"isarray":64,"stream":undefined,"string_decoder/":71,"util":undefined}],68:[function(require,module,exports){
// Copyright Joyent, Inc. and other Node contributors.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the
// following conditions:
//
// The above copyright notice and this permission notice shall be included
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
// NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
// USE OR OTHER DEALINGS IN THE SOFTWARE.


// a transform stream is a readable/writable stream where you do
// something with the data.  Sometimes it's called a "filter",
// but that's not a great name for it, since that implies a thing where
// some bits pass through, and others are simply ignored.  (That would
// be a valid example of a transform, of course.)
//
// While the output is causally related to the input, it's not a
// necessarily symmetric or synchronous transformation.  For example,
// a zlib stream might take multiple plain-text writes(), and then
// emit a single compressed chunk some time in the future.
//
// Here's how this works:
//
// The Transform stream has all the aspects of the readable and writable
// stream classes.  When you write(chunk), that calls _write(chunk,cb)
// internally, and returns false if there's a lot of pending writes
// buffered up.  When you call read(), that calls _read(n) until
// there's enough pending readable data buffered up.
//
// In a transform stream, the written data is placed in a buffer.  When
// _read(n) is called, it transforms the queued up data, calling the
// buffered _write cb's as it consumes chunks.  If consuming a single
// written chunk would result in multiple output chunks, then the first
// outputted bit calls the readcb, and subsequent chunks just go into
// the read buffer, and will cause it to emit 'readable' if necessary.
//
// This way, back-pressure is actually determined by the reading side,
// since _read has to be called to start processing a new chunk.  However,
// a pathological inflate type of transform can cause excessive buffering
// here.  For example, imagine a stream where every byte of input is
// interpreted as an integer from 0-255, and then results in that many
// bytes of output.  Writing the 4 bytes {ff,ff,ff,ff} would result in
// 1kb of data being output.  In this case, you could write a very small
// amount of input, and end up with a very large amount of output.  In
// such a pathological inflating mechanism, there'd be no way to tell
// the system to stop doing the transform.  A single 4MB write could
// cause the system to run out of memory.
//
// However, even in such a pathological case, only a single written chunk
// would be consumed, and then the rest would wait (un-transformed) until
// the results of the previous transformed chunk were consumed.

module.exports = Transform;

var Duplex = require('./_stream_duplex');

/*<replacement>*/
var util = require('core-util-is');
util.inherits = require('inherits');
/*</replacement>*/

util.inherits(Transform, Duplex);


function TransformState(options, stream) {
  this.afterTransform = function(er, data) {
    return afterTransform(stream, er, data);
  };

  this.needTransform = false;
  this.transforming = false;
  this.writecb = null;
  this.writechunk = null;
}

function afterTransform(stream, er, data) {
  var ts = stream._transformState;
  ts.transforming = false;

  var cb = ts.writecb;

  if (!cb)
    return stream.emit('error', new Error('no writecb in Transform class'));

  ts.writechunk = null;
  ts.writecb = null;

  if (!util.isNullOrUndefined(data))
    stream.push(data);

  if (cb)
    cb(er);

  var rs = stream._readableState;
  rs.reading = false;
  if (rs.needReadable || rs.length < rs.highWaterMark) {
    stream._read(rs.highWaterMark);
  }
}


function Transform(options) {
  if (!(this instanceof Transform))
    return new Transform(options);

  Duplex.call(this, options);

  this._transformState = new TransformState(options, this);

  // when the writable side finishes, then flush out anything remaining.
  var stream = this;

  // start out asking for a readable event once data is transformed.
  this._readableState.needReadable = true;

  // we have implemented the _read method, and done the other things
  // that Readable wants before the first _read call, so unset the
  // sync guard flag.
  this._readableState.sync = false;

  this.once('prefinish', function() {
    if (util.isFunction(this._flush))
      this._flush(function(er) {
        done(stream, er);
      });
    else
      done(stream);
  });
}

Transform.prototype.push = function(chunk, encoding) {
  this._transformState.needTransform = false;
  return Duplex.prototype.push.call(this, chunk, encoding);
};

// This is the part where you do stuff!
// override this function in implementation classes.
// 'chunk' is an input chunk.
//
// Call `push(newChunk)` to pass along transformed output
// to the readable side.  You may call 'push' zero or more times.
//
// Call `cb(err)` when you are done with this chunk.  If you pass
// an error, then that'll put the hurt on the whole operation.  If you
// never call cb(), then you'll never get another chunk.
Transform.prototype._transform = function(chunk, encoding, cb) {
  throw new Error('not implemented');
};

Transform.prototype._write = function(chunk, encoding, cb) {
  var ts = this._transformState;
  ts.writecb = cb;
  ts.writechunk = chunk;
  ts.writeencoding = encoding;
  if (!ts.transforming) {
    var rs = this._readableState;
    if (ts.needTransform ||
        rs.needReadable ||
        rs.length < rs.highWaterMark)
      this._read(rs.highWaterMark);
  }
};

// Doesn't matter what the args are here.
// _transform does all the work.
// That we got here means that the readable side wants more data.
Transform.prototype._read = function(n) {
  var ts = this._transformState;

  if (!util.isNull(ts.writechunk) && ts.writecb && !ts.transforming) {
    ts.transforming = true;
    this._transform(ts.writechunk, ts.writeencoding, ts.afterTransform);
  } else {
    // mark that we need a transform, so that any data that comes in
    // will get processed, now that we've asked for it.
    ts.needTransform = true;
  }
};


function done(stream, er) {
  if (er)
    return stream.emit('error', er);

  // if there's nothing in the write buffer, then that means
  // that nothing more will ever be provided
  var ws = stream._writableState;
  var ts = stream._transformState;

  if (ws.length)
    throw new Error('calling transform done when ws.length != 0');

  if (ts.transforming)
    throw new Error('calling transform done when still transforming');

  return stream.push(null);
}

},{"./_stream_duplex":65,"core-util-is":29,"inherits":40}],69:[function(require,module,exports){
// Copyright Joyent, Inc. and other Node contributors.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the
// following conditions:
//
// The above copyright notice and this permission notice shall be included
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
// NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
// USE OR OTHER DEALINGS IN THE SOFTWARE.

// A bit simpler than readable streams.
// Implement an async ._write(chunk, cb), and it'll handle all
// the drain event emission and buffering.

module.exports = Writable;

/*<replacement>*/
var Buffer = require('buffer').Buffer;
/*</replacement>*/

Writable.WritableState = WritableState;


/*<replacement>*/
var util = require('core-util-is');
util.inherits = require('inherits');
/*</replacement>*/

var Stream = require('stream');

util.inherits(Writable, Stream);

function WriteReq(chunk, encoding, cb) {
  this.chunk = chunk;
  this.encoding = encoding;
  this.callback = cb;
}

function WritableState(options, stream) {
  var Duplex = require('./_stream_duplex');

  options = options || {};

  // the point at which write() starts returning false
  // Note: 0 is a valid value, means that we always return false if
  // the entire buffer is not flushed immediately on write()
  var hwm = options.highWaterMark;
  var defaultHwm = options.objectMode ? 16 : 16 * 1024;
  this.highWaterMark = (hwm || hwm === 0) ? hwm : defaultHwm;

  // object stream flag to indicate whether or not this stream
  // contains buffers or objects.
  this.objectMode = !!options.objectMode;

  if (stream instanceof Duplex)
    this.objectMode = this.objectMode || !!options.writableObjectMode;

  // cast to ints.
  this.highWaterMark = ~~this.highWaterMark;

  this.needDrain = false;
  // at the start of calling end()
  this.ending = false;
  // when end() has been called, and returned
  this.ended = false;
  // when 'finish' is emitted
  this.finished = false;

  // should we decode strings into buffers before passing to _write?
  // this is here so that some node-core streams can optimize string
  // handling at a lower level.
  var noDecode = options.decodeStrings === false;
  this.decodeStrings = !noDecode;

  // Crypto is kind of old and crusty.  Historically, its default string
  // encoding is 'binary' so we have to make this configurable.
  // Everything else in the universe uses 'utf8', though.
  this.defaultEncoding = options.defaultEncoding || 'utf8';

  // not an actual buffer we keep track of, but a measurement
  // of how much we're waiting to get pushed to some underlying
  // socket or file.
  this.length = 0;

  // a flag to see when we're in the middle of a write.
  this.writing = false;

  // when true all writes will be buffered until .uncork() call
  this.corked = 0;

  // a flag to be able to tell if the onwrite cb is called immediately,
  // or on a later tick.  We set this to true at first, because any
  // actions that shouldn't happen until "later" should generally also
  // not happen before the first write call.
  this.sync = true;

  // a flag to know if we're processing previously buffered items, which
  // may call the _write() callback in the same tick, so that we don't
  // end up in an overlapped onwrite situation.
  this.bufferProcessing = false;

  // the callback that's passed to _write(chunk,cb)
  this.onwrite = function(er) {
    onwrite(stream, er);
  };

  // the callback that the user supplies to write(chunk,encoding,cb)
  this.writecb = null;

  // the amount that is being written when _write is called.
  this.writelen = 0;

  this.buffer = [];

  // number of pending user-supplied write callbacks
  // this must be 0 before 'finish' can be emitted
  this.pendingcb = 0;

  // emit prefinish if the only thing we're waiting for is _write cbs
  // This is relevant for synchronous Transform streams
  this.prefinished = false;

  // True if the error was already emitted and should not be thrown again
  this.errorEmitted = false;
}

function Writable(options) {
  var Duplex = require('./_stream_duplex');

  // Writable ctor is applied to Duplexes, though they're not
  // instanceof Writable, they're instanceof Readable.
  if (!(this instanceof Writable) && !(this instanceof Duplex))
    return new Writable(options);

  this._writableState = new WritableState(options, this);

  // legacy.
  this.writable = true;

  Stream.call(this);
}

// Otherwise people can pipe Writable streams, which is just wrong.
Writable.prototype.pipe = function() {
  this.emit('error', new Error('Cannot pipe. Not readable.'));
};


function writeAfterEnd(stream, state, cb) {
  var er = new Error('write after end');
  // TODO: defer error events consistently everywhere, not just the cb
  stream.emit('error', er);
  process.nextTick(function() {
    cb(er);
  });
}

// If we get something that is not a buffer, string, null, or undefined,
// and we're not in objectMode, then that's an error.
// Otherwise stream chunks are all considered to be of length=1, and the
// watermarks determine how many objects to keep in the buffer, rather than
// how many bytes or characters.
function validChunk(stream, state, chunk, cb) {
  var valid = true;
  if (!util.isBuffer(chunk) &&
      !util.isString(chunk) &&
      !util.isNullOrUndefined(chunk) &&
      !state.objectMode) {
    var er = new TypeError('Invalid non-string/buffer chunk');
    stream.emit('error', er);
    process.nextTick(function() {
      cb(er);
    });
    valid = false;
  }
  return valid;
}

Writable.prototype.write = function(chunk, encoding, cb) {
  var state = this._writableState;
  var ret = false;

  if (util.isFunction(encoding)) {
    cb = encoding;
    encoding = null;
  }

  if (util.isBuffer(chunk))
    encoding = 'buffer';
  else if (!encoding)
    encoding = state.defaultEncoding;

  if (!util.isFunction(cb))
    cb = function() {};

  if (state.ended)
    writeAfterEnd(this, state, cb);
  else if (validChunk(this, state, chunk, cb)) {
    state.pendingcb++;
    ret = writeOrBuffer(this, state, chunk, encoding, cb);
  }

  return ret;
};

Writable.prototype.cork = function() {
  var state = this._writableState;

  state.corked++;
};

Writable.prototype.uncork = function() {
  var state = this._writableState;

  if (state.corked) {
    state.corked--;

    if (!state.writing &&
        !state.corked &&
        !state.finished &&
        !state.bufferProcessing &&
        state.buffer.length)
      clearBuffer(this, state);
  }
};

function decodeChunk(state, chunk, encoding) {
  if (!state.objectMode &&
      state.decodeStrings !== false &&
      util.isString(chunk)) {
    chunk = new Buffer(chunk, encoding);
  }
  return chunk;
}

// if we're already writing something, then just put this
// in the queue, and wait our turn.  Otherwise, call _write
// If we return false, then we need a drain event, so set that flag.
function writeOrBuffer(stream, state, chunk, encoding, cb) {
  chunk = decodeChunk(state, chunk, encoding);
  if (util.isBuffer(chunk))
    encoding = 'buffer';
  var len = state.objectMode ? 1 : chunk.length;

  state.length += len;

  var ret = state.length < state.highWaterMark;
  // we must ensure that previous needDrain will not be reset to false.
  if (!ret)
    state.needDrain = true;

  if (state.writing || state.corked)
    state.buffer.push(new WriteReq(chunk, encoding, cb));
  else
    doWrite(stream, state, false, len, chunk, encoding, cb);

  return ret;
}

function doWrite(stream, state, writev, len, chunk, encoding, cb) {
  state.writelen = len;
  state.writecb = cb;
  state.writing = true;
  state.sync = true;
  if (writev)
    stream._writev(chunk, state.onwrite);
  else
    stream._write(chunk, encoding, state.onwrite);
  state.sync = false;
}

function onwriteError(stream, state, sync, er, cb) {
  if (sync)
    process.nextTick(function() {
      state.pendingcb--;
      cb(er);
    });
  else {
    state.pendingcb--;
    cb(er);
  }

  stream._writableState.errorEmitted = true;
  stream.emit('error', er);
}

function onwriteStateUpdate(state) {
  state.writing = false;
  state.writecb = null;
  state.length -= state.writelen;
  state.writelen = 0;
}

function onwrite(stream, er) {
  var state = stream._writableState;
  var sync = state.sync;
  var cb = state.writecb;

  onwriteStateUpdate(state);

  if (er)
    onwriteError(stream, state, sync, er, cb);
  else {
    // Check if we're actually ready to finish, but don't emit yet
    var finished = needFinish(stream, state);

    if (!finished &&
        !state.corked &&
        !state.bufferProcessing &&
        state.buffer.length) {
      clearBuffer(stream, state);
    }

    if (sync) {
      process.nextTick(function() {
        afterWrite(stream, state, finished, cb);
      });
    } else {
      afterWrite(stream, state, finished, cb);
    }
  }
}

function afterWrite(stream, state, finished, cb) {
  if (!finished)
    onwriteDrain(stream, state);
  state.pendingcb--;
  cb();
  finishMaybe(stream, state);
}

// Must force callback to be called on nextTick, so that we don't
// emit 'drain' before the write() consumer gets the 'false' return
// value, and has a chance to attach a 'drain' listener.
function onwriteDrain(stream, state) {
  if (state.length === 0 && state.needDrain) {
    state.needDrain = false;
    stream.emit('drain');
  }
}


// if there's something in the buffer waiting, then process it
function clearBuffer(stream, state) {
  state.bufferProcessing = true;

  if (stream._writev && state.buffer.length > 1) {
    // Fast case, write everything using _writev()
    var cbs = [];
    for (var c = 0; c < state.buffer.length; c++)
      cbs.push(state.buffer[c].callback);

    // count the one we are adding, as well.
    // TODO(isaacs) clean this up
    state.pendingcb++;
    doWrite(stream, state, true, state.length, state.buffer, '', function(err) {
      for (var i = 0; i < cbs.length; i++) {
        state.pendingcb--;
        cbs[i](err);
      }
    });

    // Clear buffer
    state.buffer = [];
  } else {
    // Slow case, write chunks one-by-one
    for (var c = 0; c < state.buffer.length; c++) {
      var entry = state.buffer[c];
      var chunk = entry.chunk;
      var encoding = entry.encoding;
      var cb = entry.callback;
      var len = state.objectMode ? 1 : chunk.length;

      doWrite(stream, state, false, len, chunk, encoding, cb);

      // if we didn't call the onwrite immediately, then
      // it means that we need to wait until it does.
      // also, that means that the chunk and cb are currently
      // being processed, so move the buffer counter past them.
      if (state.writing) {
        c++;
        break;
      }
    }

    if (c < state.buffer.length)
      state.buffer = state.buffer.slice(c);
    else
      state.buffer.length = 0;
  }

  state.bufferProcessing = false;
}

Writable.prototype._write = function(chunk, encoding, cb) {
  cb(new Error('not implemented'));

};

Writable.prototype._writev = null;

Writable.prototype.end = function(chunk, encoding, cb) {
  var state = this._writableState;

  if (util.isFunction(chunk)) {
    cb = chunk;
    chunk = null;
    encoding = null;
  } else if (util.isFunction(encoding)) {
    cb = encoding;
    encoding = null;
  }

  if (!util.isNullOrUndefined(chunk))
    this.write(chunk, encoding);

  // .end() fully uncorks
  if (state.corked) {
    state.corked = 1;
    this.uncork();
  }

  // ignore unnecessary end() calls.
  if (!state.ending && !state.finished)
    endWritable(this, state, cb);
};


function needFinish(stream, state) {
  return (state.ending &&
          state.length === 0 &&
          !state.finished &&
          !state.writing);
}

function prefinish(stream, state) {
  if (!state.prefinished) {
    state.prefinished = true;
    stream.emit('prefinish');
  }
}

function finishMaybe(stream, state) {
  var need = needFinish(stream, state);
  if (need) {
    if (state.pendingcb === 0) {
      prefinish(stream, state);
      state.finished = true;
      stream.emit('finish');
    } else
      prefinish(stream, state);
  }
  return need;
}

function endWritable(stream, state, cb) {
  state.ending = true;
  finishMaybe(stream, state);
  if (cb) {
    if (state.finished)
      process.nextTick(cb);
    else
      stream.once('finish', cb);
  }
  state.ended = true;
}

},{"./_stream_duplex":65,"buffer":undefined,"core-util-is":29,"inherits":40,"stream":undefined}],70:[function(require,module,exports){
exports = module.exports = require('./lib/_stream_readable.js');
exports.Stream = require('stream');
exports.Readable = exports;
exports.Writable = require('./lib/_stream_writable.js');
exports.Duplex = require('./lib/_stream_duplex.js');
exports.Transform = require('./lib/_stream_transform.js');
exports.PassThrough = require('./lib/_stream_passthrough.js');
if (!process.browser && process.env.READABLE_STREAM === 'disable') {
  module.exports = require('stream');
}

},{"./lib/_stream_duplex.js":65,"./lib/_stream_passthrough.js":66,"./lib/_stream_readable.js":67,"./lib/_stream_transform.js":68,"./lib/_stream_writable.js":69,"stream":undefined}],71:[function(require,module,exports){
// Copyright Joyent, Inc. and other Node contributors.
//
// Permission is hereby granted, free of charge, to any person obtaining a
// copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the
// following conditions:
//
// The above copyright notice and this permission notice shall be included
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
// NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
// USE OR OTHER DEALINGS IN THE SOFTWARE.

var Buffer = require('buffer').Buffer;

var isBufferEncoding = Buffer.isEncoding
  || function(encoding) {
       switch (encoding && encoding.toLowerCase()) {
         case 'hex': case 'utf8': case 'utf-8': case 'ascii': case 'binary': case 'base64': case 'ucs2': case 'ucs-2': case 'utf16le': case 'utf-16le': case 'raw': return true;
         default: return false;
       }
     }


function assertEncoding(encoding) {
  if (encoding && !isBufferEncoding(encoding)) {
    throw new Error('Unknown encoding: ' + encoding);
  }
}

// StringDecoder provides an interface for efficiently splitting a series of
// buffers into a series of JS strings without breaking apart multi-byte
// characters. CESU-8 is handled as part of the UTF-8 encoding.
//
// @TODO Handling all encodings inside a single object makes it very difficult
// to reason about this code, so it should be split up in the future.
// @TODO There should be a utf8-strict encoding that rejects invalid UTF-8 code
// points as used by CESU-8.
var StringDecoder = exports.StringDecoder = function(encoding) {
  this.encoding = (encoding || 'utf8').toLowerCase().replace(/[-_]/, '');
  assertEncoding(encoding);
  switch (this.encoding) {
    case 'utf8':
      // CESU-8 represents each of Surrogate Pair by 3-bytes
      this.surrogateSize = 3;
      break;
    case 'ucs2':
    case 'utf16le':
      // UTF-16 represents each of Surrogate Pair by 2-bytes
      this.surrogateSize = 2;
      this.detectIncompleteChar = utf16DetectIncompleteChar;
      break;
    case 'base64':
      // Base-64 stores 3 bytes in 4 chars, and pads the remainder.
      this.surrogateSize = 3;
      this.detectIncompleteChar = base64DetectIncompleteChar;
      break;
    default:
      this.write = passThroughWrite;
      return;
  }

  // Enough space to store all bytes of a single character. UTF-8 needs 4
  // bytes, but CESU-8 may require up to 6 (3 bytes per surrogate).
  this.charBuffer = new Buffer(6);
  // Number of bytes received for the current incomplete multi-byte character.
  this.charReceived = 0;
  // Number of bytes expected for the current incomplete multi-byte character.
  this.charLength = 0;
};


// write decodes the given buffer and returns it as JS string that is
// guaranteed to not contain any partial multi-byte characters. Any partial
// character found at the end of the buffer is buffered up, and will be
// returned when calling write again with the remaining bytes.
//
// Note: Converting a Buffer containing an orphan surrogate to a String
// currently works, but converting a String to a Buffer (via `new Buffer`, or
// Buffer#write) will replace incomplete surrogates with the unicode
// replacement character. See https://codereview.chromium.org/121173009/ .
StringDecoder.prototype.write = function(buffer) {
  var charStr = '';
  // if our last write ended with an incomplete multibyte character
  while (this.charLength) {
    // determine how many remaining bytes this buffer has to offer for this char
    var available = (buffer.length >= this.charLength - this.charReceived) ?
        this.charLength - this.charReceived :
        buffer.length;

    // add the new bytes to the char buffer
    buffer.copy(this.charBuffer, this.charReceived, 0, available);
    this.charReceived += available;

    if (this.charReceived < this.charLength) {
      // still not enough chars in this buffer? wait for more ...
      return '';
    }

    // remove bytes belonging to the current character from the buffer
    buffer = buffer.slice(available, buffer.length);

    // get the character that was split
    charStr = this.charBuffer.slice(0, this.charLength).toString(this.encoding);

    // CESU-8: lead surrogate (D800-DBFF) is also the incomplete character
    var charCode = charStr.charCodeAt(charStr.length - 1);
    if (charCode >= 0xD800 && charCode <= 0xDBFF) {
      this.charLength += this.surrogateSize;
      charStr = '';
      continue;
    }
    this.charReceived = this.charLength = 0;

    // if there are no more bytes in this buffer, just emit our char
    if (buffer.length === 0) {
      return charStr;
    }
    break;
  }

  // determine and set charLength / charReceived
  this.detectIncompleteChar(buffer);

  var end = buffer.length;
  if (this.charLength) {
    // buffer the incomplete character bytes we got
    buffer.copy(this.charBuffer, 0, buffer.length - this.charReceived, end);
    end -= this.charReceived;
  }

  charStr += buffer.toString(this.encoding, 0, end);

  var end = charStr.length - 1;
  var charCode = charStr.charCodeAt(end);
  // CESU-8: lead surrogate (D800-DBFF) is also the incomplete character
  if (charCode >= 0xD800 && charCode <= 0xDBFF) {
    var size = this.surrogateSize;
    this.charLength += size;
    this.charReceived += size;
    this.charBuffer.copy(this.charBuffer, size, 0, size);
    buffer.copy(this.charBuffer, 0, 0, size);
    return charStr.substring(0, end);
  }

  // or just emit the charStr
  return charStr;
};

// detectIncompleteChar determines if there is an incomplete UTF-8 character at
// the end of the given buffer. If so, it sets this.charLength to the byte
// length that character, and sets this.charReceived to the number of bytes
// that are available for this character.
StringDecoder.prototype.detectIncompleteChar = function(buffer) {
  // determine how many bytes we have to check at the end of this buffer
  var i = (buffer.length >= 3) ? 3 : buffer.length;

  // Figure out if one of the last i bytes of our buffer announces an
  // incomplete char.
  for (; i > 0; i--) {
    var c = buffer[buffer.length - i];

    // See http://en.wikipedia.org/wiki/UTF-8#Description

    // 110XXXXX
    if (i == 1 && c >> 5 == 0x06) {
      this.charLength = 2;
      break;
    }

    // 1110XXXX
    if (i <= 2 && c >> 4 == 0x0E) {
      this.charLength = 3;
      break;
    }

    // 11110XXX
    if (i <= 3 && c >> 3 == 0x1E) {
      this.charLength = 4;
      break;
    }
  }
  this.charReceived = i;
};

StringDecoder.prototype.end = function(buffer) {
  var res = '';
  if (buffer && buffer.length)
    res = this.write(buffer);

  if (this.charReceived) {
    var cr = this.charReceived;
    var buf = this.charBuffer;
    var enc = this.encoding;
    res += buf.slice(0, cr).toString(enc);
  }

  return res;
};

function passThroughWrite(buffer) {
  return buffer.toString(this.encoding);
}

function utf16DetectIncompleteChar(buffer) {
  this.charReceived = buffer.length % 2;
  this.charLength = this.charReceived ? 2 : 0;
}

function base64DetectIncompleteChar(buffer) {
  this.charReceived = buffer.length % 3;
  this.charLength = this.charReceived ? 3 : 0;
}

},{"buffer":undefined}],72:[function(require,module,exports){
var utp = null;
var net = require('net');
var fifo = require('fifo');
var once = require('once');
var speedometer = require('speedometer');
var peerWireProtocol = require('peer-wire-protocol');
var EventEmitter = require('events').EventEmitter;
var util = require('util');
var bufferFrom = require('buffer-from')

var HANDSHAKE_TIMEOUT = 25000;
var CONNECTION_TIMEOUT = 3000;
var RECONNECT_WAIT = [1000, 5000, 15000];
var DEFAULT_SIZE = 100;

var toBuffer = function(str, encoding) {
	return Buffer.isBuffer(str) ? str : bufferFrom(str, encoding);
};

var toAddress = function(wire) {
	if (typeof wire === 'string') return wire;
	return wire.peerAddress;
};

var onwire = function(swarm, connection, onhandshake, isServer) {
	var wire = peerWireProtocol(swarm._pwp);

	var destroy = function() {
		connection.destroy();
		connection.emit('timeout');
	};

	var connectTimeout = !isServer && setTimeout(destroy, swarm.connectTimeout);
	var handshakeTimeout = setTimeout(destroy, swarm.handshakeTimeout);

	if (handshakeTimeout.unref) handshakeTimeout.unref();
	if (connectTimeout.unref) connectTimeout.unref();

	connection.on('connect', function() {
		clearTimeout(connectTimeout);
	});

	wire.once('handshake', function(infoHash, peerId) {
		clearTimeout(handshakeTimeout);
		onhandshake(infoHash, peerId);
	});

	connection.on('end', function() {
		connection.destroy();
	});

	connection.on('error', function() {
		connection.destroy();
	});

	connection.on('close', function() {
		clearTimeout(connectTimeout);
		clearTimeout(handshakeTimeout);
		wire.destroy();
	});

	connection.pipe(wire).pipe(connection);
	return wire;
};

var pools = {};

var leave = function(port, swarm) {
	if (!pools[port]) return;
	delete pools[port].swarms[swarm.infoHash.toString('hex')];

	if (Object.keys(pools[port].swarms).length) return;
	pools[port].servers.forEach(function(server) {
		server.close();
	});
	delete pools[port];
};

var join = function(port, swarm) {
	var pool = pools[port];

	if (!pool) {
		var swarms = {};
		var servers = [];

		var onconnection = function(connection) {
			var wire = onwire(swarm, connection, function(infoHash, peerId) {
				var swarm = swarms[infoHash.toString('hex')];
				if (!swarm) return connection.destroy();
				swarm._onincoming(connection, wire);
			}, true);
		}

		servers.push(net.createServer(onconnection));
		if (swarm.utp) servers.push(utp.createServer(onconnection));

		var loop = function(i) {
			if (i < servers.length) return servers[i].listen(port, loop.bind(null, i+1))
			pool.listening = true;
			Object.keys(swarms).forEach(function(infoHash) {
				swarms[infoHash].emit('listening');
			});
		}

		loop(0)

		pool = pools[port] = {
			servers: servers,
			swarms: swarms,
			listening: false
		};
	}

	var infoHash = swarm.infoHash.toString('hex');

	if (pool.listening) {
		process.nextTick(function() {
			swarm.emit('listening');
		});
	}
	if (pool.swarms[infoHash]) {
		process.nextTick(function() {
			swarm.emit('error', new Error('port and info hash already in use'));
		});
		return;
	}

	pool.swarms[infoHash] = swarm;
};

var Swarm = function(infoHash, peerId, options) {
	if (!(this instanceof Swarm)) return new Swarm(infoHash, peerId, options);
	EventEmitter.call(this);

	options = options || {};
	this.handshake = options.handshake;

	this.port = 0;
	this.size = options.size || DEFAULT_SIZE;
	this.utp = options.utp || false;
	this.handshakeTimeout = options.handshakeTimeout || HANDSHAKE_TIMEOUT;
	this.connectTimeout = options.connectTimeout || CONNECTION_TIMEOUT;
	
	this.infoHash = toBuffer(infoHash, 'hex');
	this.peerId = toBuffer(peerId, 'utf-8');

	this.downloaded = 0;
	this.uploaded = 0;
	this.connections = [];
	this.wires = [];
	this.paused = false;

	this.uploaded = 0;
	this.downloaded = 0;

	this.downloadSpeed = speedometer();
	this.uploadSpeed = speedometer();

	this._destroyed = false;
	this._queues = [fifo()];
	this._peers = {};
	this._pwp = {speed:options.speed};
};

util.inherits(Swarm, EventEmitter);

Swarm.prototype.__defineGetter__('queued', function() {
	return this._queues.reduce(function(prev, queue) {
		return prev + queue.length;
	}, 0);
});

Swarm.prototype.pause = function() {
	this.paused = true;
};

Swarm.prototype.resume = function() {
	this.paused = false;
	this._drain();
};

Swarm.prototype.priority = function(addr, level) {
	addr = toAddress(addr);
	var peer = this._peers[addr];

	if (!peer) return 0;
	if (typeof level !== 'number' || peer.priority === level) return level;

	if (!this._queues[level]) this._queues[level] = fifo();

	if (peer.node) {
		this._queues[peer.priority].remove(peer.node);
		peer.node = this._queues[level].push(addr);
	}

	return peer.priority = level;
};

Swarm.prototype.add = function(addr) {
	if (this._destroyed || this._peers[addr] || Object.keys(this._peers).length >= 128) return;

	var port = Number(addr.split(':')[1]);
	if (!(port > 0 && port < 65535)) return;

	this._peers[addr] = {
		node: this._queues[0].push(addr),
		wire: null,
		timeout: null,
		reconnect: false,
		priority: 0,
		retries: 0,
		noUtp: false,
	};

	this._drain();
};

Swarm.prototype.remove = function(addr) {
	this._remove(toAddress(addr));
	this._drain();
};

Swarm.prototype.listen = function(port, onlistening) {
	if (onlistening) this.once('listening', onlistening);
	this.port = port;
	join(this.port, this);
};

Swarm.prototype.destroy = function() {
	this._destroyed = true;

	var self = this;
	Object.keys(this._peers).forEach(function(addr) {
		self._remove(addr);
	});

	this.wires.forEach(function (wire) {
		wire.destroy();
	});

	leave(this.port, this);
	process.nextTick(function() {
		self.emit('close');
	});
};

Swarm.prototype._remove = function(addr) {
	var peer = this._peers[addr];
	if (!peer) return;
	delete this._peers[addr];
	if (peer.node) this._queues[peer.priority].remove(peer.node);
	if (peer.timeout) clearTimeout(peer.timeout);
	if (peer.wire) peer.wire.destroy();
};

Swarm.prototype._drain = function() {
	if (this.connections.length >= this.size || this.paused) return;

	var self = this;
	var addr = this._shift();
	if (!addr) return;

	var peer = this._peers[addr];
	if (!peer) return;

	var repush = function() {
		peer.node = self._queues[peer.priority].push(addr);
		self._drain();
	};

	var parts = addr.split(':');
	var connection;

	if(this.utp && !peer.noUtp) {
		connection = utp.connect(parts[1], parts[0]);
		connection.on('timeout', function() {
			// Unable to connect to peer with uTP
			// Assume it doesn't support it.
			peer.noUtp = true;
			repush();
		});
	} else {
		connection = net.connect(parts[1], parts[0]);
	}

	if (peer.timeout) clearTimeout(peer.timeout);

	peer.node = null;
	peer.timeout = null;

	var wire = onwire(this, connection, function(infoHash) {
		if (infoHash.toString('hex') !== self.infoHash.toString('hex')) return connection.destroy();
		peer.reconnect = true;
		peer.retries = 0;
		self._onwire(connection, wire);
	});

	wire.on('end', function() {
		peer.wire = null;
		if (!peer.reconnect || self._destroyed || peer.retries >= RECONNECT_WAIT.length) return self._remove(addr);
		peer.timeout = setTimeout(repush, RECONNECT_WAIT[peer.retries++]);
	});

	peer.wire = wire;
	self._onconnection(connection);

	wire.peerAddress = addr;
	wire.handshake(this.infoHash, this.peerId, this.handshake);
};

Swarm.prototype._shift = function() {
	for (var i = this._queues.length-1; i >= 0; i--) {
		if (this._queues[i] && this._queues[i].length) return this._queues[i].shift();
	}
	return null;
};

Swarm.prototype._onincoming = function(connection, wire) {
	wire.peerAddress = connection.address().address + ':' + connection.address().port;
	wire.handshake(this.infoHash, this.peerId, this.handshake);

	this._onconnection(connection);
	this._onwire(connection, wire);
};

Swarm.prototype._onconnection = function(connection) {
	var self = this;

	connection.once('close', function() {
		self.connections.splice(self.connections.indexOf(connection), 1);
		self._drain();
	});

	this.connections.push(connection);
};

Swarm.prototype._onwire = function(connection, wire) {
	var self = this;

	wire.on('download', function(downloaded) {
		self.downloaded += downloaded;
		self.downloadSpeed(downloaded);
		self.emit('download', downloaded);
	});

	wire.on('upload', function(uploaded) {
		self.uploaded += uploaded;
		self.uploadSpeed(uploaded);
		self.emit('upload', uploaded);
	});

	var cleanup = once(function() {
		self.emit('wire-disconnect', wire, connection);
		self.wires.splice(self.wires.indexOf(wire), 1);
		connection.destroy();
	});

	connection.on('close', cleanup);
	connection.on('error', cleanup);
	connection.on('end', cleanup);
	wire.on('end', cleanup);
	wire.on('close', cleanup);
	wire.on('finish', cleanup);

	this.wires.push(wire);
	this.emit('wire', wire, connection);
};

module.exports = Swarm;

},{"buffer-from":27,"events":undefined,"fifo":36,"net":undefined,"once":55,"peer-wire-protocol":62,"speedometer":77,"util":undefined}],73:[function(require,module,exports){
module.exports = require('crypto').randomBytes

},{"crypto":undefined}],74:[function(require,module,exports){
module.exports = reemit
module.exports.filter = filter

var EventEmitter = require('events').EventEmitter

function reemit (source, target, events) {
  if (!Array.isArray(events)) events = [ events ]

  var listeners = []
  events.forEach(function (event) {
    var listener = function () {
      var args = [].slice.call(arguments)
      args.unshift(event)
      target.emit.apply(target, args)
    }
    listeners.push(listener)
    source.on(event, listener)
  })

  return function cancel () {
    events.forEach(function (event, i) {
      source.removeListener(event, listeners[i])
    })
  }
}

function filter (source, events) {
  var emitter = new EventEmitter()
  reemit(source, emitter, events)
  return emitter
}

},{"events":undefined}],75:[function(require,module,exports){
module.exports = require("../../tracker-http.cjs");
},{"../../tracker-http.cjs":91}],76:[function(require,module,exports){
function hash(value) { return require("crypto").createHash("sha1").update(value).digest("hex"); }
function asyncHash(value, callback) { var result = hash(value); process.nextTick(function () { callback(result); }); }
module.exports = asyncHash; module.exports.sync = hash;
},{"crypto":undefined}],77:[function(require,module,exports){
var tick = 1
var maxTick = 65535
var resolution = 4
var inc = function() {
  tick = (tick + 1) & maxTick
}

var timer = setInterval(inc, (1000 / resolution) | 0)
if (timer.unref) timer.unref()

module.exports = function(seconds) {
  var size = resolution * (seconds || 5)
  var buffer = [0]
  var pointer = 1
  var last = (tick-1) & maxTick

  return function(delta) {
    var dist = (tick - last) & maxTick
    if (dist > size) dist = size
    last = tick

    while (dist--) {
      if (pointer === size) pointer = 0
      buffer[pointer] = buffer[pointer === 0 ? size-1 : pointer-1]
      pointer++
    }

    if (delta) buffer[pointer-1] += delta

    var top = buffer[pointer-1]
    var btm = buffer.length < size ? 0 : buffer[pointer === size ? 0 : pointer]

    return buffer.length < resolution ? top : (top - btm) * resolution / buffer.length
  }
}
},{}],78:[function(require,module,exports){
/*                                                                              
Copyright (c) 2011, Chris Umbel

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in      
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN 
THE SOFTWARE.
*/

var base32 = require('./thirty-two');

exports.encode = base32.encode;
exports.decode = base32.decode;

},{"./thirty-two":79}],79:[function(require,module,exports){
/*                                                                              
Copyright (c) 2011, Chris Umbel

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.                                                                   
*/

var charTable = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
var byteTable = [
    0xff, 0xff, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
    0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
    0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e,
    0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
    0x17, 0x18, 0x19, 0xff, 0xff, 0xff, 0xff, 0xff,
    0xff, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
    0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e,
    0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
    0x17, 0x18, 0x19, 0xff, 0xff, 0xff, 0xff, 0xff
];

function quintetCount(buff) {
    var quintets = Math.floor(buff.length / 5);
    return buff.length % 5 == 0 ? quintets: quintets + 1;
}

exports.encode = function(plain) {
    var i = 0;
    var j = 0;
    var shiftIndex = 0;
    var digit = 0;
    var encoded = new Buffer(quintetCount(plain) * 8);
    if(!Buffer.isBuffer(plain)){
    	plain = new Buffer(plain);
    }

    /* byte by byte isn't as pretty as quintet by quintet but tests a bit
        faster. will have to revisit. */
    while(i < plain.length) {
        var current = plain[i];
    
        if(shiftIndex > 3) {
            digit = current & (0xff >> shiftIndex);
            shiftIndex = (shiftIndex + 5) % 8;
            digit = (digit << shiftIndex) | ((i + 1 < plain.length) ?
                plain[i + 1] : 0) >> (8 - shiftIndex);
            i++;
        } else {
            digit = (current >> (8 - (shiftIndex + 5))) & 0x1f;
            shiftIndex = (shiftIndex + 5) % 8;            
            if(shiftIndex == 0) i++;
        }
        
        encoded[j] = charTable.charCodeAt(digit);
        j++;
    }

    for(i = j; i < encoded.length; i++)
        encoded[i] = 0x3d; //'='.charCodeAt(0)
        
    return encoded;
};

exports.decode = function(encoded) {
    var shiftIndex = 0;
    var plainDigit = 0;
    var plainChar;
    var plainPos = 0;
    if(!Buffer.isBuffer(encoded)){
    	encoded = new Buffer(encoded);
    }
    var decoded = new Buffer(Math.ceil(encoded.length * 5 / 8));
    
    /* byte by byte isn't as pretty as octet by octet but tests a bit
        faster. will have to revisit. */    
    for(var i = 0; i < encoded.length; i++) {
    	if(encoded[i] == 0x3d){ //'='
    		break;
    	}
    		
        var encodedByte = encoded[i] - 0x30;
        
        if(encodedByte < byteTable.length) {
            plainDigit = byteTable[encodedByte];
            
            if(shiftIndex <= 3) {
                shiftIndex = (shiftIndex + 5) % 8;
                
                if(shiftIndex == 0) {
                    plainChar |= plainDigit;
                    decoded[plainPos] = plainChar;
                    plainPos++;
                    plainChar = 0;
                } else {
                    plainChar |= 0xff & (plainDigit << (8 - shiftIndex));
                }
            } else {
                shiftIndex = (shiftIndex + 5) % 8;
                plainChar |= 0xff & (plainDigit >>> shiftIndex);
                decoded[plainPos] = plainChar;
                plainPos++;

                plainChar = 0xff & (plainDigit << (8 - shiftIndex));
            }
        } else {
        	throw new Error('Invalid input - it is not base32 encoded string');
        }
    }
    return decoded.slice(0, plainPos);
};

},{}],80:[function(require,module,exports){
module.exports = Discovery

var debug = require('debug')('torrent-discovery')
var DHT = require('bittorrent-dht/client') // empty object in browser
var EventEmitter = require('events').EventEmitter
var extend = require('xtend')
var inherits = require('inherits')
var parallel = require('run-parallel')
var reemit = require('re-emitter')
var Tracker = require('bittorrent-tracker/client')

inherits(Discovery, EventEmitter)

function Discovery (opts) {
  var self = this
  if (!(self instanceof Discovery)) return new Discovery(opts)
  EventEmitter.call(self)

  self.announce = opts.announce || []
  self.rtcConfig = opts.rtcConfig // browser only
  self.peerId = opts.peerId
  self.port = opts.port || 0 // torrent port
  self.wrtc = opts.wrtc
  self.intervalMs = opts.intervalMs || (15 * 60 * 1000)
  self.destroyed = false

  if (!self.peerId) throw new Error('peerId required')
  if (!process.browser && !self.port) throw new Error('port required')

  self.infoHash = null
  self.infoHashBuffer = null
  self.torrent = null

  self._dhtAnnouncing = false
  self._dhtTimeout = false
  self._internalDHT = false // is the DHT created internally?

  if (opts.tracker === false) {
    self.tracker = false
  } else {
    self.tracker = true
  }

  if (opts.dht === false) {
    self.dht = false
  } else if (typeof opts.dht === 'object') {
    self.dht = opts.dht
  } else {
    self.dht = createDHT()
  }

  if (self.dht) {
    self.dht.on('peer', onPeer)
  }

  function createDHT () {
    if (typeof DHT !== 'function') return false
    self._internalDHT = true
    var dht = new DHT()
    reemit(dht, self, ['error', 'warning'])
    dht.listen(opts.dhtPort)
    return dht
  }

  function onPeer (peer, infoHash) {
    if (infoHash.toString('hex') !== self.infoHash) return
    self.emit('peer', peer.host + ':' + peer.port)
  }
}

Discovery.prototype.setTorrent = function (torrent) {
  var self = this

  if (!self.infoHash && (typeof torrent === 'string' || Buffer.isBuffer(torrent))) {
    self.infoHash = typeof torrent === 'string'
      ? torrent
      : torrent.toString('hex')
  } else if (!self.torrent && torrent && torrent.infoHash) {
    self.torrent = torrent
    self.infoHash = typeof torrent.infoHash === 'string'
      ? torrent.infoHash
      : torrent.infoHash.toString('hex')
  } else {
    return
  }
  self.infoHashBuffer = new Buffer(self.infoHash, 'hex')

  debug('setTorrent %s', self.infoHash)

  // If tracker exists, then it was created with just infoHash. Set torrent length
  // so client can report correct information about uploads.
  if (self.tracker && self.tracker !== true) {
    self.tracker.torrentLength = torrent.length
  } else {
    self._createTracker()
  }

  self._dhtAnnounce()
}

Discovery.prototype.updatePort = function (port) {
  var self = this
  if (port === self.port) return
  self.port = port

  self._dhtAnnounce()

  if (self.tracker && self.tracker !== true) {
    self.tracker.stop()
    self.tracker.destroy(function () {
      self._createTracker()
    })
  }
}

Discovery.prototype.stop = function (cb) {
  var self = this
  self.destroyed = true
  clearTimeout(self._dhtTimeout)

  var tasks = []

  if (self.tracker && self.tracker !== true) {
    self.tracker.stop()
    tasks.push(function (cb) {
      self.tracker.destroy(cb)
    })
  }

  if (self._internalDHT) {
    tasks.push(function (cb) {
      self.dht.destroy(cb)
    })
  }
  parallel(tasks, cb)
}

Discovery.prototype._createTracker = function () {
  var self = this
  if (!self.tracker) return

  var torrent = self.torrent
    ? extend({ announce: [] }, self.torrent)
    : { infoHash: self.infoHash, announce: [] }

  if (self.announce) torrent.announce = torrent.announce.concat(self.announce)

  var trackerOpts = {
    rtcConfig: self.rtcConfig,
    wrtc: self.wrtc
  }

  self.tracker = new Tracker(self.peerId, self.port, torrent, trackerOpts)
  reemit(self.tracker, self, ['peer', 'warning', 'error'])
  self.tracker.setInterval(self.intervalMs)
  self.tracker.on('update', onUpdate)
  self.tracker.start()

  function onUpdate (data) {
    self.emit('trackerAnnounce', data)
  }
}

Discovery.prototype._dhtAnnounce = function () {
  var self = this
  if (!self.port || !self.infoHash || !self.dht || self._dhtAnnouncing) return
  debug('dht announce')

  self._dhtAnnouncing = true
  clearTimeout(self._dhtTimeout)

  self.dht.announce(self.infoHash, self.port, function (err) {
    self._dhtAnnouncing = false
    debug('dht announce complete')

    if (err) self.emit('warning', err)
    self.emit('dhtAnnounce')

    if (!self.destroyed) {
      self._dhtTimeout = setTimeout(function () {
        self._dhtAnnounce()
      }, getRandomTimeout())
    }
  })

  // Returns timeout interval, with some random jitter
  function getRandomTimeout () {
    return self.intervalMs + Math.floor(Math.random() * self.intervalMs / 5)
  }
}

},{"bittorrent-dht/client":4,"bittorrent-tracker/client":9,"debug":32,"events":undefined,"inherits":40,"re-emitter":74,"run-parallel":81,"xtend":88}],81:[function(require,module,exports){
arguments[4][20][0].apply(exports,arguments)
},{"dup":20}],82:[function(require,module,exports){
module.exports = Piece

var BLOCK_LENGTH = 1 << 14

function Piece (length) {
  if (!(this instanceof Piece)) return new Piece(length)

  this.length = length
  this.missing = length
  this.sources = null

  this._chunks = Math.ceil(length / BLOCK_LENGTH)
  this._remainder = (length % BLOCK_LENGTH) || BLOCK_LENGTH
  this._buffered = 0
  this._buffer = null
  this._cancellations = null
  this._reservations = 0
  this._flushed = false
}

Piece.BLOCK_LENGTH = BLOCK_LENGTH

Piece.prototype.chunkLength = function (i) {
  return i === this._chunks - 1 ? this._remainder : BLOCK_LENGTH
}

Piece.prototype.chunkLengthRemaining = function (i) {
  return this.length - (i * BLOCK_LENGTH)
}

Piece.prototype.chunkOffset = function (i) {
  return i * BLOCK_LENGTH
}

Piece.prototype.reserve = function () {
  if (!this.init()) return -1
  if (this._cancellations.length) return this._cancellations.pop()
  if (this._reservations < this._chunks) return this._reservations++
  return -1
}

Piece.prototype.reserveRemaining = function () {
  if (!this.init()) return -1
  if (this._reservations < this._chunks) {
    var min = this._reservations
    this._reservations = this._chunks
    return min
  }
  return -1
}

Piece.prototype.cancel = function (i) {
  if (!this.init()) return
  this._cancellations.push(i)
}

Piece.prototype.cancelRemaining = function (i) {
  if (!this.init()) return
  this._reservations = i
}

Piece.prototype.get = function (i) {
  if (!this.init()) return null
  return this._buffer[i]
}

Piece.prototype.set = function (i, data, source) {
  if (!this.init()) return false
  var len = data.length
  var blocks = Math.ceil(len / BLOCK_LENGTH)
  for (var j = 0; j < blocks; j++) {
    if (!this._buffer[i + j]) {
      var offset = j * BLOCK_LENGTH
      var splitData = data.slice(offset, offset + BLOCK_LENGTH)
      this._buffered++
      this._buffer[i + j] = splitData
      this.missing -= splitData.length
      if (this.sources.indexOf(source) === -1) {
        this.sources.push(source)
      }
    }
  }
  return this._buffered === this._chunks
}

Piece.prototype.flush = function () {
  if (!this._buffer || this._chunks !== this._buffered) return null
  var buffer = Buffer.concat(this._buffer, this.length)
  this._buffer = null
  this._cancellations = null
  this.sources = null
  this._flushed = true
  return buffer
}

Piece.prototype.init = function () {
  if (this._flushed) return false
  if (this._buffer) return true
  this._buffer = new Array(this._chunks)
  this._cancellations = []
  this.sources = []
  return true
}

},{}],83:[function(require,module,exports){
var hat = require('hat')
var pws = require('peer-wire-swarm')
var bncode = require('bncode')
var crypto = require('crypto')
var bitfield = require('bitfield')
var parseTorrent = require('parse-torrent')
var mkdirp = null
var events = require('events')
var path = require('path')
var fs = require('fs')
var os = require('os')
var eos = require('end-of-stream')
var piece = require('torrent-piece')
var rimraf = null
var FSChunkStore = null
var ImmediateChunkStore = require('immediate-chunk-store')
var peerDiscovery = require('torrent-discovery')
var bufferFrom = require('buffer-from')

var blocklist = require('ip-set')
var exchangeMetadata = require('./lib/exchange-metadata')
var fileStream = require('./lib/file-stream')

var MAX_REQUESTS = 5
var CHOKE_TIMEOUT = 5000
var REQUEST_TIMEOUT = 30000
var SPEED_THRESHOLD = 3 * piece.BLOCK_LENGTH
var DEFAULT_PORT = 6881

var BAD_PIECE_STRIKES_MAX = 3
var BAD_PIECE_STRIKES_DURATION = 120000 // 2 minutes

var RECHOKE_INTERVAL = 10000
var RECHOKE_OPTIMISTIC_DURATION = 2

var TMP = fs.existsSync('/tmp') ? '/tmp' : (os.tmpdir ? os.tmpdir() : os.tmpDir())

var noop = function () {}

var sha1 = function (data) {
  return crypto.createHash('sha1').update(data).digest('hex')
}

var thruthy = function () {
  return true
}

var falsy = function () {
  return false
}

var toNumber = function (val) {
  return val === true ? 1 : (val || 0)
}

var torrentStream = function (link, opts, cb) {
  if (typeof opts === 'function') return torrentStream(link, null, opts)

  link = parseTorrent(link)
  var metadata = link.infoBuffer || null
  var infoHash = link.infoHash

  if (!opts) opts = {}
  if (!opts.id) opts.id = '-TS0008-' + hat(48)
  if (!opts.tmp) opts.tmp = TMP
  if (!opts.name) opts.name = 'torrent-stream'
  if (!opts.flood) opts.flood = 0 // Pulse defaults:
  if (!opts.pulse) opts.pulse = Number.MAX_SAFE_INTEGER // Do not pulse

  var usingTmp = false
  var destroyed = false

  if (!opts.path) {
    usingTmp = true
    opts.path = path.join(opts.tmp, opts.name, infoHash)
  }

  var engine = new events.EventEmitter()
  var swarm = pws(infoHash, opts.id, { size: (opts.connections || opts.size), speed: 10 })
  var torrentPath = path.join(opts.tmp, opts.name, infoHash + '.torrent')

  if (cb) engine.on('ready', cb.bind(null, engine))

  engine.ready = function (cb) {
    if (engine.torrent) cb()
    else engine.once('ready', cb)
  }

  var wires = swarm.wires
  var critical = []
  var refresh = noop

  var rechokeSlots = (opts.uploads === false || opts.uploads === 0) ? 0 : (+opts.uploads || 10)
  var rechokeOptimistic = null
  var rechokeOptimisticTime = 0
  var rechokeIntervalId

  engine.infoHash = infoHash
  engine.metadata = metadata
  engine.path = opts.path
  engine.files = []
  engine.selection = []
  engine.torrent = null
  engine.bitfield = null
  engine.amInterested = false
  engine.store = null
  engine.swarm = swarm
  engine._flood = opts.flood
  engine._pulse = opts.pulse

  var discovery = peerDiscovery({
    peerId: bufferFrom(opts.id),
    dht: (opts.dht !== undefined) ? opts.dht : true,
    tracker: (opts.tracker !== undefined) ? opts.tracker : true,
    port: DEFAULT_PORT,
    announce: opts.trackers
  })
  var blocked = blocklist(opts.blocklist), blockedCount = 0

  discovery.on('error', function (err) { engine.emit('error', err) })
  discovery.on('peer', function (addr) {
    if (blocked.contains(addr.split(':')[0])) {
      engine.emit('blocked-peer', addr)
    } else {
      engine.emit('peer', addr)
      engine.connect(addr)
    }
  })

  var ontorrent = function (torrent) {
    if (destroyed) return
    try { if (opts.validateTorrent) opts.validateTorrent(torrent) } catch (err) { engine.emit("error", err); return }
    var storage = opts.storage || FSChunkStore
    engine.store = ImmediateChunkStore(storage(torrent.pieceLength, {
      files: torrent.files.map(function (file) {
        return {
          path: path.join(opts.path, file.path),
          length: file.length,
          offset: file.offset
        }
      })
    }))
    engine.torrent = torrent
    engine.bitfield = bitfield(torrent.pieces.length)

    var pieceLength = torrent.pieceLength
    var pieceRemainder = (torrent.length % pieceLength) || pieceLength

    var pieces = torrent.pieces.map(function (hash, i) {
      return piece(i === torrent.pieces.length - 1 ? pieceRemainder : pieceLength)
    })
    engine.invalidate = function (index) {
      if (destroyed || pieces[index]) return
      pieces[index] = piece(index === torrent.pieces.length - 1 ? pieceRemainder : pieceLength)
      reservations[index] = []
      engine.bitfield.set(index, false)
      engine.selection.forEach(function (s) { if (index >= s.from && index <= s.to) s.offset = Math.min(s.offset, index - s.from) })
      refresh()
    }
    var reservations = torrent.pieces.map(function () {
      return []
    })

    process.nextTick(function () {
      // Gives the user a chance to call engine.listen(PORT) on the same tick,
      // so discovery will start using the correct torrent port.
      discovery.setTorrent(torrent)
    })

    engine.files = torrent.files.map(function (file) {
      file = Object.create(file)
      var offsetPiece = (file.offset / torrent.pieceLength) | 0
      var endPiece = ((file.offset + file.length - 1) / torrent.pieceLength) | 0

      file.deselect = function () {
        engine.deselect(offsetPiece, endPiece, false)
      }

      file.select = function () {
        engine.select(offsetPiece, endPiece, false)
      }

      file.createReadStream = function (opts) {
        var stream = fileStream(engine, file, opts)

        var notify = stream.notify.bind(stream)
        engine.select(stream.startPiece, stream.endPiece, true, notify)
        eos(stream, function () {
          engine.deselect(stream.startPiece, stream.endPiece, true, notify)
        })

        return stream
      }

      return file
    })

    engine.releasePending = function () {
      pieces.forEach(function (p, i) { if (p && p._buffer) { pieces[i] = piece(p.length); reservations[i] = [] } })
      refresh()
    }
    var oninterestchange = function () {
      var prev = engine.amInterested
      engine.amInterested = !!engine.selection.length

      wires.forEach(function (wire) {
        if (engine.amInterested) wire.interested()
        else wire.uninterested()
      })

      if (prev === engine.amInterested) return
      if (engine.amInterested) engine.emit('interested')
      else engine.emit('uninterested')
    }

    var gc = function () {
      for (var i = 0; i < engine.selection.length; i++) {
        var s = engine.selection[i]
        var oldOffset = s.offset

        while (!pieces[s.from + s.offset] && s.from + s.offset < s.to) s.offset++

        if (oldOffset !== s.offset) s.notify()
        if (s.to !== s.from + s.offset) continue
        if (pieces[s.from + s.offset]) continue

        engine.selection.splice(i, 1)
        i-- // -1 to offset splice
        s.notify()
        oninterestchange()
      }

      if (!engine.selection.length) engine.emit('idle')
    }

    var onpiececomplete = function (index, buffer) {
      if (!pieces[index]) return

      pieces[index] = null
      reservations[index] = null
      engine.bitfield.set(index, true)

      for (var i = 0; i < wires.length; i++) wires[i].have(index)

      engine.emit('verify', index)
      engine.emit('download', index, buffer)

      engine.store.put(index, buffer)
      gc()
    }

    var onhotswap = opts.hotswap === false ? falsy : function (wire, index) {
      var speed = wire.downloadSpeed()
      if (speed < piece.BLOCK_LENGTH) return
      if (!reservations[index] || !pieces[index]) return

      var r = reservations[index]
      var minSpeed = Infinity
      var min

      for (var i = 0; i < r.length; i++) {
        var other = r[i]
        if (!other || other === wire) continue

        var otherSpeed = other.downloadSpeed()
        if (otherSpeed >= SPEED_THRESHOLD) continue
        if (2 * otherSpeed > speed || otherSpeed > minSpeed) continue

        min = other
        minSpeed = otherSpeed
      }

      if (!min) return false

      for (i = 0; i < r.length; i++) {
        if (r[i] === min) r[i] = null
      }

      for (i = 0; i < min.requests.length; i++) {
        var req = min.requests[i]
        if (req.piece !== index) continue
        pieces[index].cancel((req.offset / piece.BLOCK_SIZE) | 0)
      }

      engine.emit('hotswap', min, wire, index)
      return true
    }

    var onupdatetick = function () {
      process.nextTick(onupdate)
    }

    var onrequest = function (wire, index, hotswap) {
      if (opts.allowedPiece && !opts.allowedPiece(index)) return false
    if (!pieces[index]) return false

      var p = pieces[index]
      var reservation = p.reserve()

      if (reservation === -1 && hotswap && onhotswap(wire, index)) reservation = p.reserve()
      if (reservation === -1) return false

      var r = reservations[index] || []
      var offset = p.chunkOffset(reservation)
      var size = p.chunkLength(reservation)

      var i = r.indexOf(null)
      if (i === -1) i = r.length
      r[i] = wire

      wire.request(index, offset, size, function (err, block) {
        if (r[i] === wire) r[i] = null

        if (p !== pieces[index]) return onupdatetick()

        if (err) {
          p.cancel(reservation)
          onupdatetick()
          return
        }

        if (!p.set(reservation, block, wire)) return onupdatetick()

        var sources = p.sources
        var buffer = p.flush()

        if (sha1(buffer) !== torrent.pieces[index]) {
          pieces[index] = piece(p.length)
          engine.emit('invalid-piece', index, buffer)
          onupdatetick()

          sources.forEach(function (wire) {
            var now = Date.now()

            wire.badPieceStrikes = wire.badPieceStrikes.filter(function (strike) {
              return (now - strike) < BAD_PIECE_STRIKES_DURATION
            })

            wire.badPieceStrikes.push(now)

            if (wire.badPieceStrikes.length > BAD_PIECE_STRIKES_MAX) {
              engine.block(wire.peerAddress)
            }
          })

          return
        }

        onpiececomplete(index, buffer)
        onupdatetick()
      })

      return true
    }

    var onvalidatewire = function (wire) {
      if (wire.requests.length) return

      for (var i = engine.selection.length - 1; i >= 0; i--) {
        var next = engine.selection[i]
        for (var j = next.to; j >= next.from + next.offset; j--) {
          if (!wire.peerPieces[j]) continue
          if (onrequest(wire, j, false)) return
        }
      }
    }

    var speedRanker = function (wire) {
      var speed = wire.downloadSpeed() || 1
      if (speed > SPEED_THRESHOLD) return thruthy

      var secs = MAX_REQUESTS * piece.BLOCK_LENGTH / speed
      var tries = 10
      var ptr = 0

      return function (index) {
        if (!tries || !pieces[index]) return true

        var missing = pieces[index].missing
        for (; ptr < wires.length; ptr++) {
          var other = wires[ptr]
          var otherSpeed = other.downloadSpeed()

          if (otherSpeed < SPEED_THRESHOLD) continue
          if (otherSpeed <= speed || !other.peerPieces[index]) continue
          if ((missing -= otherSpeed * secs) > 0) continue

          tries--
          return false
        }

        return true
      }
    }

    var shufflePriority = function (i) {
      var last = i
      for (var j = i; j < engine.selection.length && engine.selection[j].priority; j++) {
        last = j
      }
      var tmp = engine.selection[i]
      engine.selection[i] = engine.selection[last]
      engine.selection[last] = tmp
    }

    var select = function (wire, hotswap) {
      if (wire.requests.length >= MAX_REQUESTS) return true

      // Pulse, or flood (default)
      if (swarm.downloaded > engine._flood && swarm.downloadSpeed() > engine._pulse) {
        return true
      }

      var rank = speedRanker(wire)

      for (var i = 0; i < engine.selection.length; i++) {
        var next = engine.selection[i]
        for (var j = next.from + next.offset; j <= next.to; j++) {
          if (!wire.peerPieces[j] || !rank(j)) continue
          while (wire.requests.length < MAX_REQUESTS && onrequest(wire, j, critical[j] || hotswap)) {}
          if (wire.requests.length < MAX_REQUESTS) continue
          if (next.priority) shufflePriority(i)
          return true
        }
      }

      return false
    }

    var onupdatewire = function (wire) {
      if (wire.peerChoking) return
      if (!wire.downloaded) return onvalidatewire(wire)
      select(wire, false) || select(wire, true)
    }

    var onupdate = function () {
      wires.forEach(onupdatewire)
    }

    var onwire = function (wire) {
      wire.setTimeout(opts.timeout || REQUEST_TIMEOUT, function () {
        engine.emit('timeout', wire)
        wire.destroy()
      })

      if (engine.selection.length) wire.interested()

      var timeout = CHOKE_TIMEOUT
      var id

      var onchoketimeout = function () {
        if (swarm.queued > 2 * (swarm.size - swarm.wires.length) && wire.amInterested) return wire.destroy()
        id = setTimeout(onchoketimeout, timeout)
      }

      wire.on('close', function () {
        clearTimeout(id)
      })

      wire.on('choke', function () {
        clearTimeout(id)
        id = setTimeout(onchoketimeout, timeout)
      })

      wire.on('unchoke', function () {
        clearTimeout(id)
      })

      wire.on('request', function (index, offset, length, cb) {
        if (pieces[index]) return
        engine.store.get(index, { offset: offset, length: length }, function (err, buffer) {
          if (err) return cb(err)
          engine.emit('upload', index, offset, length)
          cb(null, buffer)
        })
      })

      wire.on('unchoke', onupdate)
      wire.on('bitfield', onupdate)
      wire.on('have', onupdate)

      wire.isSeeder = false

      var i = 0
      var checkseeder = function () {
        if (wire.peerPieces.length !== torrent.pieces.length) return
        for (; i < torrent.pieces.length; ++i) {
          if (!wire.peerPieces[i]) return
        }
        wire.isSeeder = true
      }

      wire.on('bitfield', checkseeder)
      wire.on('have', checkseeder)
      checkseeder()

      wire.badPieceStrikes = []

      id = setTimeout(onchoketimeout, timeout)
    }

    var rechokeSort = function (a, b) {
      // Prefer higher download speed
      if (a.downSpeed !== b.downSpeed) return a.downSpeed > b.downSpeed ? -1 : 1
      // Prefer higher upload speed
      if (a.upSpeed !== b.upSpeed) return a.upSpeed > b.upSpeed ? -1 : 1
      // Prefer unchoked
      if (a.wasChoked !== b.wasChoked) return a.wasChoked ? 1 : -1
      // Random order
      return a.salt - b.salt
    }

    var onrechoke = function () {
      if (rechokeOptimisticTime > 0) --rechokeOptimisticTime
      else rechokeOptimistic = null

      var peers = []

      wires.forEach(function (wire) {
        if (wire.isSeeder) {
          if (!wire.amChoking) wire.choke()
        } else if (wire !== rechokeOptimistic) {
          peers.push({
            wire: wire,
            downSpeed: wire.downloadSpeed(),
            upSpeed: wire.uploadSpeed(),
            salt: Math.random(),
            interested: wire.peerInterested,
            wasChoked: wire.amChoking,
            isChoked: true
          })
        }
      })

      peers.sort(rechokeSort)

      var i = 0
      var unchokeInterested = 0
      for (; i < peers.length && unchokeInterested < rechokeSlots; ++i) {
        peers[i].isChoked = false
        if (peers[i].interested) ++unchokeInterested
      }

      if (!rechokeOptimistic && i < peers.length && rechokeSlots) {
        var candidates = peers.slice(i).filter(function (peer) { return peer.interested })
        var optimistic = candidates[(Math.random() * candidates.length) | 0]

        if (optimistic) {
          optimistic.isChoked = false
          rechokeOptimistic = optimistic.wire
          rechokeOptimisticTime = RECHOKE_OPTIMISTIC_DURATION
        }
      }

      peers.forEach(function (peer) {
        if (peer.wasChoked !== peer.isChoked) {
          if (peer.isChoked) peer.wire.choke()
          else peer.wire.unchoke()
        }
      })
    }

    var onready = function () {
      if (destroyed) return
      swarm.on('wire', onwire)
      swarm.wires.forEach(onwire)

      refresh = function () {
        process.nextTick(gc)
        oninterestchange()
        onupdate()
      }

      rechokeIntervalId = setInterval(onrechoke, RECHOKE_INTERVAL)

      process.nextTick(function () {
        engine.emit('torrent', torrent)
        engine.emit('ready')
        refresh()
      })
    }

    if (opts.verify === false) return onready()

    engine.emit('verifying')

    var loop = function (i) {
      if (i >= torrent.pieces.length) return onready()
      engine.store.get(i, function (_, buf) {
        if (!buf || sha1(buf) !== torrent.pieces[i] || !pieces[i]) return loop(i + 1)
        pieces[i] = null
        engine.bitfield.set(i, true)
        engine.emit('verify', i)
        loop(i + 1)
      })
    }

    loop(0)
  }

  var exchange = exchangeMetadata(engine, function (metadata) {
    var buf = bncode.encode({
      info: bncode.decode(metadata),
      'announce-list': []
    })

    try { ontorrent(parseTorrent(buf)) } catch (err) { return engine.emit("error", err) }
    if (opts.memoryOnly) return

    mkdirp(path.dirname(torrentPath), function (err) {
      if (err) return engine.emit('error', err)
      fs.writeFile(torrentPath, buf, function (err) {
        if (err) engine.emit('error', err)
      })
    })
  })

  swarm.on('wire', function (wire) {
    engine.emit('wire', wire)
    exchange(wire)
    if (engine.bitfield) wire.bitfield(engine.bitfield)
  })

  swarm.pause()

  if (link.files && engine.metadata) {
    swarm.resume()
    ontorrent(link)
  } else {
    if (opts.memoryOnly) {
      process.nextTick(function () { if (!destroyed) { swarm.resume(); discovery.setTorrent(link) } })
    } else fs.readFile(torrentPath, function (_, buf) {
      if (destroyed) return
      swarm.resume()

      // We know only infoHash here, not full infoDictionary.
      // But infoHash is enough to connect to trackers and get peers.
      if (!buf) return discovery.setTorrent(link)

      var torrent = parseTorrent(buf)

      // Bad cache file - fetch it again
      if (torrent.infoHash !== infoHash) return discovery.setTorrent(link)

      if (!torrent.announce || !torrent.announce.length) {
        opts.trackers = [].concat(opts.trackers || []).concat(link.announce || [])
      }

      engine.metadata = torrent.infoBuffer
      ontorrent(torrent)
    })
  }

  engine.critical = function (piece, width) {
    for (var i = 0; i < (width || 1); i++) critical[piece + i] = true
  }

  engine.select = function (from, to, priority, notify) {
    engine.selection.push({
      from: from,
      to: to,
      offset: 0,
      priority: toNumber(priority),
      notify: notify || noop
    })

    engine.selection.sort(function (a, b) {
      return b.priority - a.priority
    })

    refresh()
  }

  engine.deselect = function (from, to, priority, notify) {
    notify = notify || noop
    for (var i = 0; i < engine.selection.length; i++) {
      var s = engine.selection[i]
      if (s.from !== from || s.to !== to) continue
      if (s.priority !== toNumber(priority)) continue
      if (s.notify !== notify) continue
      engine.selection.splice(i, 1)
      i--
      break
    }

    refresh()
  }

  engine.setPulse = function (bps) {
    // Set minimum byte/second pulse starting now (dynamic)
    // Eg. Start pulsing at minimum 312 KBps:
    // engine.setPulse(312*1024)

    engine._pulse = bps
  }

  engine.setFlood = function (b) {
    // Set bytes to flood starting now (dynamic)
    // Eg. Start flooding for next 10 MB:
    // engine.setFlood(10*1024*1024)

    engine._flood = b + swarm.downloaded
  }

  engine.setFloodedPulse = function (b, bps) {
    // Set bytes to flood before starting a minimum byte/second pulse (dynamic)
    // Eg. Start flooding for next 10 MB, then start pulsing at minimum 312 KBps:
    // engine.setFloodedPulse(10*1024*1024, 312*1024)

    engine.setFlood(b)
    engine.setPulse(bps)
  }

  engine.flood = function () {
    // Reset flood/pulse values to default (dynamic)
    // Eg. Flood the network starting now:
    // engine.flood()

    engine._flood = 0
    engine._pulse = Number.MAX_SAFE_INTEGER
  }

  engine.connect = function (addr) {
    if (!opts.validPeer || opts.validPeer(addr)) swarm.add(addr)
  }

  engine.disconnect = function (addr) {
    swarm.remove(addr)
  }

  engine.block = function (addr) {
    if (blockedCount < 1024 && !blocked.contains(addr.split(':')[0])) { blocked.add(addr.split(':')[0]); blockedCount++ }
    engine.disconnect(addr)
    engine.emit('blocking', addr)
  }

  var removeTorrent = function (cb) {
    fs.unlink(torrentPath, function (err) {
      if (err) return cb(err)
      fs.rmdir(path.dirname(torrentPath), function (err) {
        if (err && err.code !== 'ENOTEMPTY') return cb(err)
        cb()
      })
    })
  }

  var removeTmp = function (cb) {
    if (opts.memoryOnly) return process.nextTick(cb || noop)
    if (!usingTmp) return removeTorrent(cb)
    rimraf(opts.path, function (err) {
      if (err) return cb(err)
      removeTorrent(cb)
    })
  }

  engine.remove = function (keepPieces, cb) {
    if (typeof keepPieces === 'function') {
      cb = keepPieces
      keepPieces = false
    }

    if (keepPieces || !engine.store || !engine.store.destroy) return removeTmp(cb)

    engine.store.destroy(function (err) {
      if (err) return cb(err)
      removeTmp(cb)
    })
  }

  engine.destroy = function (cb) {
    destroyed = true
    swarm.destroy()
    clearInterval(rechokeIntervalId)
    discovery.stop()
    if (engine.store && engine.store.close) {
      engine.store.close(cb)
    } else if (cb) {
      process.nextTick(cb)
    }
  }

  var findPort = function (def, cb) {
    var net = require('net')
    var s = net.createServer()

    s.on('error', function () {
      findPort(0, cb)
    })

    s.listen(def, function () {
      var port = s.address().port
      s.close(function () {
        engine.listen(port, cb)
      })
    })
  }

  engine.listen = function (port, cb) {
    if (typeof port === 'function') return engine.listen(0, port)
    if (!port) return findPort(opts.port || DEFAULT_PORT, cb)
    engine.port = port
    swarm.listen(engine.port, cb)
    discovery.updatePort(engine.port)
  }

  return engine
}

module.exports = torrentStream

},{"./lib/exchange-metadata":84,"./lib/file-stream":85,"bitfield":3,"bncode":21,"buffer-from":27,"crypto":undefined,"end-of-stream":34,"events":undefined,"fs":undefined,"hat":38,"immediate-chunk-store":39,"ip-set":42,"net":undefined,"os":undefined,"parse-torrent":61,"path":undefined,"peer-wire-swarm":72,"torrent-discovery":80,"torrent-piece":82}],84:[function(require,module,exports){
var bncode = require('bncode')
var crypto = require('crypto')

var METADATA_BLOCK_SIZE = 1 << 14
var METADATA_MAX_SIZE = 256 * 1024
var EXTENSIONS = {
  m: {
    ut_metadata: 1
  }
}

var sha1 = function (data) {
  return crypto.createHash('sha1').update(data).digest('hex')
}

module.exports = function (engine, callback) {
  var metadataPieces = []

  return function (wire) {
    var metadata = engine.metadata
    wire.once('extended', function (id, handshake) {
      try {
        handshake = bncode.decode(handshake)
      } catch (err) {
        return
      }

      if (id || !handshake.m || handshake.m.ut_metadata === undefined) return

      var channel = handshake.m.ut_metadata
      var size = handshake.metadata_size
      if (typeof size !== "number" || size % 1 || size <= 0 || size > METADATA_MAX_SIZE) return

      wire.on('extended', function (id, ext) {
        if (id !== EXTENSIONS.m.ut_metadata) return

        var metadata = engine.metadata
        var delimiter, message, piece
        try {
          delimiter = ext.toString('ascii').indexOf('ee')
          message = bncode.decode(ext.slice(0, delimiter === -1 ? ext.length : delimiter + 2))
          piece = message.piece
        } catch (err) {
          return
        }

        if (typeof piece !== "number" || piece % 1 || piece < 0 || piece >= Math.ceil(size / METADATA_BLOCK_SIZE)) return
        if (ext.length > METADATA_BLOCK_SIZE + 1024) return
        if (message.msg_type === 2) return

        if (message.msg_type === 0) {
          if (!metadata) return wire.extended(channel, {msg_type: 2, piece: piece})
          var offset = piece * METADATA_BLOCK_SIZE
          var buf = metadata.slice(offset, offset + METADATA_BLOCK_SIZE)
          wire.extended(channel, Buffer.concat([bncode.encode({msg_type: 1, piece: piece}), buf]))
          return
        }

        if (message.msg_type === 1 && !metadata) {
          var payload = ext.slice(delimiter + 2)
          if (payload.length !== Math.min(METADATA_BLOCK_SIZE, size - piece * METADATA_BLOCK_SIZE)) return
          metadataPieces[piece] = payload
          for (var i = 0; i * METADATA_BLOCK_SIZE < size; i++) {
            if (!metadataPieces[i]) return
          }

          metadata = Buffer.concat(metadataPieces)

          if (engine.infoHash !== sha1(metadata)) {
            metadataPieces = []
            metadata = null
            return
          }

          try { callback(engine.metadata = metadata) } catch (err) { engine.emit("error", err) }
        }
      })

      if (size > METADATA_MAX_SIZE) return
      if (!size || metadata) return

      for (var i = 0; i * METADATA_BLOCK_SIZE < size; i++) {
        if (metadataPieces[i]) continue
        wire.extended(channel, {msg_type: 0, piece: i})
      }
    })

    if (!wire.peerExtensions.extended) return
    wire.extended(0, metadata ? {m: {ut_metadata: 1}, metadata_size: metadata.length} : {m: {ut_metadata: 1}})
  }
}

},{"bncode":21,"crypto":undefined}],85:[function(require,module,exports){
var stream = require('stream')
var util = require('util')

var FileStream = function (engine, file, opts) {
  if (!(this instanceof FileStream)) return new FileStream(engine, file, opts)
  stream.Readable.call(this)

  if (!opts) opts = {}
  if (!opts.start) opts.start = 0
  if (!opts.end && typeof opts.end !== 'number') opts.end = file.length - 1

  var offset = opts.start + file.offset
  var pieceLength = engine.torrent.pieceLength

  this.length = opts.end - opts.start + 1
  this.startPiece = (offset / pieceLength) | 0
  this.endPiece = ((opts.end + file.offset) / pieceLength) | 0
  this._destroyed = false
  this._engine = engine
  this._piece = this.startPiece
  this._missing = this.length
  this._reading = false
  this._notifying = false
  this._critical = Math.min(1024 * 1024 / pieceLength, 2) | 0
  this._offset = offset - this.startPiece * pieceLength
}

util.inherits(FileStream, stream.Readable)

FileStream.prototype._read = function () {
  if (this._reading) return
  this._reading = true
  this.notify()
}

FileStream.prototype.notify = function () {
  if (!this._reading || !this._missing) return
  if (!this._engine.bitfield.get(this._piece)) return this._engine.critical(this._piece, this._critical)

  var self = this

  if (this._notifying) return
  this._notifying = true
  this._engine.store.get(this._piece++, function (err, buffer) {
    self._notifying = false

    if (self._destroyed || !self._reading) return

    if (err) return self.destroy(err)

    if (self._offset) {
      buffer = buffer.slice(self._offset)
      self._offset = 0
    }

    if (self._missing < buffer.length) buffer = buffer.slice(0, self._missing)

    self._missing -= buffer.length

    if (!self._missing) {
      self.push(buffer)
      self.push(null)
      return
    }

    self._reading = false
    self.push(buffer)
  })
}

FileStream.prototype.destroy = function () {
  if (this._destroyed) return
  this._destroyed = true
  this.emit('close')
}

module.exports = FileStream

},{"stream":undefined,"util":undefined}],86:[function(require,module,exports){
"use strict"

function unique_pred(list, compare) {
  var ptr = 1
    , len = list.length
    , a=list[0], b=list[0]
  for(var i=1; i<len; ++i) {
    b = a
    a = list[i]
    if(compare(a, b)) {
      if(i === ptr) {
        ptr++
        continue
      }
      list[ptr++] = a
    }
  }
  list.length = ptr
  return list
}

function unique_eq(list) {
  var ptr = 1
    , len = list.length
    , a=list[0], b = list[0]
  for(var i=1; i<len; ++i, b=a) {
    b = a
    a = list[i]
    if(a !== b) {
      if(i === ptr) {
        ptr++
        continue
      }
      list[ptr++] = a
    }
  }
  list.length = ptr
  return list
}

function unique(list, compare, sorted) {
  if(list.length === 0) {
    return list
  }
  if(compare) {
    if(!sorted) {
      list.sort(compare)
    }
    return unique_pred(list, compare)
  }
  if(!sorted) {
    list.sort()
  }
  return unique_eq(list)
}

module.exports = unique

},{}],87:[function(require,module,exports){
// Returns a wrapper function that returns a wrapped callback
// The wrapper function should do some stuff, and return a
// presumably different callback function.
// This makes sure that own properties are retained, so that
// decorations and such are not lost along the way.
module.exports = wrappy
function wrappy (fn, cb) {
  if (fn && cb) return wrappy(fn)(cb)

  if (typeof fn !== 'function')
    throw new TypeError('need wrapper function')

  Object.keys(fn).forEach(function (k) {
    wrapper[k] = fn[k]
  })

  return wrapper

  function wrapper() {
    var args = new Array(arguments.length)
    for (var i = 0; i < args.length; i++) {
      args[i] = arguments[i]
    }
    var ret = fn.apply(this, args)
    var cb = args[args.length-1]
    if (typeof ret === 'function' && ret !== cb) {
      Object.keys(cb).forEach(function (k) {
        ret[k] = cb[k]
      })
    }
    return ret
  }
}

},{}],88:[function(require,module,exports){
module.exports = extend

var hasOwnProperty = Object.prototype.hasOwnProperty;

function extend() {
    var target = {}

    for (var i = 0; i < arguments.length; i++) {
        var source = arguments[i]

        for (var key in source) {
            if (hasOwnProperty.call(source, key)) {
                target[key] = source[key]
            }
        }
    }

    return target
}

},{}],89:[function(require,module,exports){
module.exports = extend

var hasOwnProperty = Object.prototype.hasOwnProperty;

function extend(target) {
    for (var i = 1; i < arguments.length; i++) {
        var source = arguments[i]

        for (var key in source) {
            if (hasOwnProperty.call(source, key)) {
                target[key] = source[key]
            }
        }
    }

    return target
}

},{}],90:[function(require,module,exports){
'use strict';
// Copyright (c) 2026 Starlit contributors. SPDX-License-Identifier: MIT
var http = require('http'), url = require('url'), crypto = require('crypto'), Readable = require('stream').Readable;
var torrentStream = require('torrent-stream'), createCache = require('./bounded-store.cjs');
var networkPolicy = require('./network-policy.cjs'), net = require('net');
var MAX_CACHE = 48 * 1024 * 1024, MAX_PIECE = 2 * 1024 * 1024;
var PROBE = new Buffer('starlit-range-probe-v1');
function start(options, callback) {
  options = options || {};
  if (!/^[a-f0-9]{64}$/.test(options.controlToken || '')) throw new Error('Trusted bridge control token required');
  var port = options.port === undefined ? 2710 : options.port;
  var active = null, server, sockets = [], stopping = false, generation = 0;
  function json(res, status, data) { if (res.finished) return; var body = JSON.stringify(data); res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), 'Cache-Control': 'no-store' }); res.end(body); }
  function cancelStream(entry) { if (entry && entry.stream) { var s = entry.stream; entry.stream = null; s.destroy(); } }
  function remove(cb) {
    generation++;
    var old = active; active = null;
    if (!old) return process.nextTick(cb || function () {});
    cancelStream(old); clearTimeout(old.timer);
    if (old.pending) { old.pending.forEach(function (res) { json(res, 409, { error: 'Torrent removed' }); }); old.pending = []; }
    old.engine.destroy(cb || function () {});
  }
  function listing(entry, requestedIndex) {
    var files = entry.engine.files.map(function (f) { return { name: f.name, path: f.path, length: f.length }; });
    var guessed = 0;
    files.forEach(function (f, i) { if (/\.(mkv|mp4|webm|avi|m4v|ts|mov)$/i.test(f.name) && (!/\.(mkv|mp4|webm|avi|m4v|ts|mov)$/i.test(files[guessed].name) || f.length > files[guessed].length)) guessed = i; });
    var selected = typeof requestedIndex === 'number' && files[requestedIndex] ? requestedIndex : guessed;
    if (!entry.tickets[selected]) entry.tickets[selected] = crypto.randomBytes(32).toString('hex');
    return { infoHash: entry.hash, files: files, guessedFileIdx: guessed, fileIdx: selected, filename: files[selected].name, playbackTicket: entry.tickets[selected], ticketFileIdx: selected, memoryOnly: true };
  }
  function validate(t) {
    if (!t || !t.files || !t.files.length || t.files.length > 256 || !t.pieces || !t.pieces.length || t.pieces.length > 16384 || t.pieceLength < 16384 || t.pieceLength > MAX_PIECE || t.pieceLength % 1 || !isFinite(t.length) || t.length < 1 || t.length > 1024 * 1024 * 1024 * 1024) throw new Error('Torrent exceeds streaming limits');
    t.files.forEach(function (f) { if (!isFinite(f.length) || f.length < 0 || f.length % 1 || !isFinite(f.offset) || f.offset < 0 || typeof f.path !== 'string' || f.path.length > 2048 || f.name.length > 512) throw new Error('Invalid torrent file'); });
  }
  function create(hash, body, res) {
    if (!body.torrent || body.torrent.infoHash !== hash) return json(res, 400, { error: 'Info hash mismatch' });
    if (body.fileIdx !== undefined && (typeof body.fileIdx !== 'number' || body.fileIdx < 0 || body.fileIdx % 1 || body.fileIdx >= 256)) return json(res, 400, { error: 'Invalid file index' });
    res._starlitFileIdx = body.fileIdx;
    if (active && active.hash === hash) { if (active.ready) return json(res, 200, listing(active, body.fileIdx)); if (active.pending.length >= 8) return json(res, 429, { error: 'Create already pending' }); active.pending.push(res); return; }
    var serial = generation + 1;
    remove(function () {
      if (stopping || res.finished) return;
      if (generation !== serial) return json(res, 409, { error: 'Create superseded' });
      var sources = body.peerSearch && body.peerSearch.sources || [];
      if (!Array.isArray(sources) || sources.length > 9) return json(res, 400, { error: 'Too many trackers' });
      var trackers = [];
      try {
        sources.forEach(function (source) {
          if (typeof source !== 'string' || source.length > 2048) throw new Error('Invalid tracker');
          if (/^dht:/.test(source)) return;
          var tracker = source.replace(/^tracker:/, ''), parsed = url.parse(tracker);
          if (!/^(https?:|udp:)$/.test(parsed.protocol) || !parsed.hostname || parsed.auth || /[\r\n]/.test(tracker)) throw new Error('Invalid tracker');
          trackers.push(tracker);
        });
      } catch (err) { return json(res, 400, { error: err.message }); }
      var entry = { hash: hash, tickets: Object.create(null), pending: [res], ready: false, stream: null, timer: null, engine: null, cache: null };
      var cache = createCache(Math.min(options.cacheBytes || MAX_CACHE, MAX_CACHE), function (index) { if (entry.engine && entry.engine.invalidate) entry.engine.invalidate(index); });
      entry.cache = cache; active = entry;
      try {
        entry.engine = torrentStream('magnet:?xt=urn:btih:' + hash, { storage: cache.factory, memoryOnly: true, validateTorrent: validate, verify: false, connections: 12, uploads: false, trackers: trackers, dht: options.dht !== false, tracker: trackers.length > 0, validPeer: function (address) { var host = address.split(':')[0]; return options.allowPrivatePeers === true || (net.isIP(host) === 4 && !networkPolicy.blockedAddress(host)); } });
      } catch (err) { active = null; return json(res, 400, { error: err.message }); }
      entry.engine.on('error', function (err) {
        entry.error = String(err.message || err);
        if (entry.pending) { entry.pending.forEach(function (r) { json(r, 502, { error: entry.error }); }); entry.pending = []; }
        if (active === entry) remove();
      });
      entry.engine.on('ready', function () {
        if (active !== entry) return;
        clearTimeout(entry.timer); entry.ready = true;
        entry.pending.forEach(function (r) { json(r, 200, listing(entry, r._starlitFileIdx)); }); entry.pending = [];
      });
      entry.timer = setTimeout(function () { if (active !== entry) return; entry.pending.forEach(function (r) { json(r, 504, { error: 'Torrent metadata timeout' }); }); entry.pending = []; remove(); }, options.metadataTimeout || 50000);
      // Integration-test hook is never populated by HTTP input or production startup.
      if (options.onEngine) options.onEngine(entry.engine);
    });
  }
  function range(header, length) {
    if (!header) return { start: 0, end: length - 1, partial: false };
    var m = /^bytes=(\d*)-(\d*)$/.exec(header);
    if (!m || (!m[1] && !m[2])) return null;
    var a = m[1] ? Number(m[1]) : Math.max(0, length - Number(m[2]));
    var b = m[1] ? (m[2] ? Math.min(length - 1, Number(m[2])) : length - 1) : length - 1;
    if (!isFinite(a) || !isFinite(b) || a < 0 || b < a || a >= length || (!m[1] && !Number(m[2]))) return null;
    return { start: a, end: b, partial: true };
  }
  function headers(res, req, length, mime) {
    var r = range(req.headers.range, length);
    if (!r) { res.writeHead(416, { 'Content-Range': 'bytes */' + length, 'Content-Length': 0 }); res.end(); return null; }
    var h = { 'Content-Type': mime, 'Content-Length': r.end - r.start + 1, 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store' };
    if (r.partial) h['Content-Range'] = 'bytes ' + r.start + '-' + r.end + '/' + length;
    res.writeHead(r.partial ? 206 : 200, h); return r;
  }
  function media(req, res, entry, index) {
    var file = entry.engine.files[index];
    if (!file || !file.length) return json(res, 404, { error: 'File unavailable' });
    var extension = /\.([^.]*)$/.exec(file.name), mime = { mp4: 'video/mp4', m4v: 'video/mp4', mkv: 'video/x-matroska', ts: 'video/mp2t', webm: 'video/webm', mov: 'video/quicktime', avi: 'video/x-msvideo' };
    var r = headers(res, req, file.length, mime[extension && extension[1].toLowerCase()] || 'application/octet-stream');
    if (!r) return;
    if (req.method === 'HEAD') return res.end();
    cancelStream(entry);
    var engine = entry.engine, position = file.offset + r.start, end = file.offset + r.end, waiting = false, selected = -1, dead = false;
    var stream = new Readable(); entry.stream = stream;
    function deselect() { if (selected >= 0) { engine.deselect(selected, selected, true, notify); selected = -1; } }
    function notify() {
      if (dead || !waiting || selected < 0 || !engine.bitfield.get(selected)) return;
      var piece = selected; waiting = false;
      engine.store.get(piece, function (err, buffer) {
        if (dead) return;
        if (err) { engine.invalidate(piece); waiting = true; return engine.select(piece, piece, true, notify); }
        deselect();
        var offset = position - piece * engine.torrent.pieceLength;
        var count = Math.min(buffer.length - offset, end - position + 1);
        position += count; stream.push(buffer.slice(offset, offset + count));
        if (position > end) stream.push(null);
      });
    }
    stream._read = function () {
      if (dead || waiting || position > end) return;
      waiting = true; selected = Math.floor(position / engine.torrent.pieceLength);
      engine.select(selected, selected, true, notify); notify();
    };
    stream.destroy = function () {
      if (dead) return; dead = true; deselect();
      if (entry.stream === stream) entry.stream = null;
      stream.unpipe(res); stream.emit('close'); if (!res.finished) res.destroy();
      // Discard partial pieces when seeking; late wire callbacks use the old piece identity.
      if (engine.releasePending) engine.releasePending();
    };
    stream.on('error', function () { stream.destroy(); });
    res.on('close', stream.destroy); stream.on('end', function () { dead = true; deselect(); if (entry.stream === stream) entry.stream = null; });
    stream.pipe(res);
  }
  server = http.createServer(function (req, res) {
    var host = req.headers.host, origin = req.headers.origin, local = req.socket.remoteAddress;
    var expectedPort = server.address().port;
    if ((local !== '127.0.0.1' && local !== '::ffff:127.0.0.1' && local !== '::1') || (host !== '127.0.0.1:' + expectedPort && host !== 'localhost:' + expectedPort)) return json(res, 403, { error: 'Loopback host required' });
    if (origin && origin !== 'http://' + host && !(origin === 'null' && options.allowNullOrigin === true)) return json(res, 403, { error: 'Origin rejected' });
    if (req.headers['sec-fetch-site'] === 'cross-site' && !(origin === 'null' && options.allowNullOrigin === true)) return json(res, 403, { error: 'Cross-site request rejected' });
    if (origin) { res.setHeader('Access-Control-Allow-Origin', origin); res.setHeader('Access-Control-Expose-Headers', 'Accept-Ranges, Content-Length, Content-Range'); res.setHeader('Vary', 'Origin'); }
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Starlit-Local, X-Starlit-Token, Range'); res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, POST, OPTIONS');
    if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
    var pathname = url.parse(req.url).pathname;
    if (options.proxyHandler && options.proxyHandler(req, res)) return;
    if (pathname === '/settings' && req.method === 'GET') return json(res, 200, { values: { cacheSize: MAX_CACHE }, version: 'starlit-torrent-1', memoryOnly: true, capabilities: { torrent: true, httpRange: true, offline: false } });
    if (pathname === '/range-probe' && (req.method === 'GET' || req.method === 'HEAD')) { var probe = headers(res, req, PROBE.length, 'application/octet-stream'); if (probe) res.end(req.method === 'HEAD' ? undefined : PROBE.slice(probe.start, probe.end + 1)); return; }
    if ((pathname === '/removeAll' || /\/[a-f0-9]{40}\/remove$/.test(pathname)) && req.method === 'POST') {
      if (req.headers['x-starlit-token'] !== options.controlToken) return json(res, 403, { error: 'Trusted control token required' });
      var hash = pathname.split('/')[1];
      if (hash !== 'removeAll' && active && hash !== active.hash) return json(res, 200, { removed: false });
      return remove(function () { json(res, 200, { removed: true }); });
    }
    pathname = pathname.replace(/\/(\d+)\/stats\.json$/, '/stats');
    var m = /^\/([a-f0-9]{40})\/(create|stats|\d+)$/.exec(pathname);
    if (!m) return json(res, 404, { error: 'Route unavailable' });
    if (m[2] === 'create' && req.method === 'POST') {
      if (req.headers['x-starlit-token'] !== options.controlToken) return json(res, 403, { error: 'Trusted control token required' });
      var chunks = [], size = 0, failed = false;
      req.on('data', function (chunk) { size += chunk.length; if (size > 16384) { failed = true; chunks = []; json(res, 413, { error: 'Request too large' }); } else if (!failed) chunks.push(chunk); });
      req.on('end', function () { if (failed) return; try { create(m[1], JSON.parse(Buffer.concat(chunks).toString()), res); } catch (err) { json(res, 400, { error: 'Invalid create request' }); } }); return;
    }
    if (!active || active.hash !== m[1] || !active.ready) return json(res, 404, { error: 'Torrent unavailable' });
    if (m[2] === 'stats' && req.method === 'GET') { if (req.headers['x-starlit-token'] !== options.controlToken) return json(res, 403, { error: 'Trusted control token required' }); return json(res, 200, { infoHash: active.hash, peers: active.engine.swarm.wires.length, downloaded: active.engine.swarm.downloaded, downloadSpeed: active.engine.swarm.downloadSpeed(), cache: active.cache.stats }); }
    if (/^\d+$/.test(m[2]) && (req.method === 'GET' || req.method === 'HEAD')) { var suppliedTicket = url.parse(req.url, true).query.ticket; if (!suppliedTicket || suppliedTicket !== active.tickets[Number(m[2])]) return json(res, 403, { error: 'Playback ticket required' }); return media(req, res, active, Number(m[2])); }
    json(res, 405, { error: 'Method unavailable' });
  });
  server.timeout = 60000;
  server.maxHeadersCount = 32;
  server.on('connection', function (socket) { if (sockets.length >= 16) return socket.destroy(); sockets.push(socket); socket.on('close', function () { var i = sockets.indexOf(socket); if (i >= 0) sockets.splice(i, 1); }); });
  server.on('clientError', function (err, socket) { socket.destroy(); });
  server.listen(port, '127.0.0.1', callback);
  return { server: server, stop: function (cb) { stopping = true; remove(function () { sockets.slice().forEach(function (s) { s.destroy(); }); server.close(cb); }); } };
}
module.exports = { start: start, version: 'starlit-torrent-1' };

},{"./bounded-store.cjs":1,"./network-policy.cjs":2,"crypto":undefined,"http":undefined,"net":undefined,"stream":undefined,"torrent-stream":83,"url":undefined}],91:[function(require,module,exports){
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

},{"./network-policy.cjs":2,"http":undefined,"https":undefined,"url":undefined}]},{},[90])(90)
});
