'use strict';
// MIT. Bounded piece cache; eviction restores download eligibility in the engine.
var createRecency = require('./recency-list.cjs');
module.exports = function (limit, evict) {
  var items = Object.create(null), order = createRecency(), bytes = 0, closed = false;
  var stats = { bytes: 0, limit: limit, evictions: 0, hits: 0, misses: 0 };
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
          items[index] = buf; bytes += buf.length; order.touch(index);
          while (bytes > limit) {
            var old = order.oldest();
            order.remove(old);
            bytes -= items[old].length; delete items[old];
            stats.evictions++; evict(old);
          }
          stats.bytes = bytes; done(cb);
        },
        get: function (index, opts, cb) {
          if (typeof opts === 'function') { cb = opts; opts = null; }
          var buf = items[index];
          if (!buf || closed) { stats.misses++; return done(cb, new Error('Piece absent')); }
          stats.hits++; order.touch(index);
          var start = opts && opts.offset || 0;
          done(cb, null, opts ? buf.slice(start, opts.length ? start + opts.length : undefined) : buf);
        },
        close: function (cb) { closed = true; items = Object.create(null); order.clear(); bytes = stats.bytes = 0; done(cb); },
        destroy: function (cb) { this.close(cb); }
      };
    }
  };
};
