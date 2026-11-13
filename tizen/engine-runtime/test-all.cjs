'use strict';
var spawn = require('child_process').spawnSync, path = require('path');
['test-limits.cjs', 'test-large-metadata.cjs', 'test-runtime.cjs', 'test-network.cjs', 'test-discovery.cjs'].forEach(function (file) {
  var result = spawn(process.execPath, [path.join(__dirname, file)], { stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
});
