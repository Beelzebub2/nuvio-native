'use strict';
// Copyright (c) 2026 Starlit contributors. SPDX-License-Identifier: MIT.
// O(1) recency tracking for numeric torrent piece indices on legacy Node 4.
module.exports = function () {
  var nodes = Object.create(null), oldest = null, newest = null, count = 0;
  function unlink(node) {
    if (node.previous) node.previous.next = node.next;
    else oldest = node.next;
    if (node.next) node.next.previous = node.previous;
    else newest = node.previous;
    node.previous = null;
    node.next = null;
  }
  function touch(index) {
    var key = String(index);
    var node = nodes[key];
    if (node === newest) return;
    if (node) unlink(node);
    else {
      node = { key: index, previous: null, next: null };
      nodes[key] = node;
      count++;
    }
    node.previous = newest;
    if (newest) newest.next = node;
    else oldest = node;
    newest = node;
  }
  function remove(index) {
    var key = String(index);
    var node = nodes[key];
    if (!node) return false;
    unlink(node);
    delete nodes[key];
    count--;
    return true;
  }
  function clear() {
    nodes = Object.create(null);
    oldest = null;
    newest = null;
    count = 0;
  }
  return {
    touch: touch,
    remove: remove,
    oldest: function () { return oldest ? oldest.key : null; },
    length: function () { return count; },
    clear: clear
  };
};
