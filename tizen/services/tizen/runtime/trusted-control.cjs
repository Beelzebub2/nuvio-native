"use strict";
module.exports = function createTrustedControl(options) {
  var api = typeof tizen !== "undefined" ? tizen : null;
  if (!api || !api.messageport) { try { api = require("tizen"); } catch (_) {} }
  var messageport = api && api.messageport;
  if (!messageport || typeof messageport.requestTrustedLocalMessagePort !== "function") throw new Error("STARLIT_TRUSTED_CONTROL_UNAVAILABLE");
  var token = require("crypto").randomBytes(32).toString("hex");
  var port = messageport.requestTrustedLocalMessagePort("STARLIT_ENGINE_CONTROL");
  var listener = port.addMessagePortListener(function (data, remote) {
    if (!remote || !remote.isTrusted || remote.appId !== options.uiAppId) return;
    var nonce = "";
    (Array.isArray(data) ? data : []).forEach(function (item) { if(item.key === "nonce") nonce = String(item.value || ""); });
    if (!/^[a-f0-9]{48}$/.test(nonce)) return;
    remote.sendMessage([{key:"nonce",value:nonce},{key:"token",value:token}],port);
  });
  return { token: token, close: function () { try { port.removeMessagePortListener(listener); } catch (_) {} } };
};
