"use strict";
const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("starlitTV", Object.freeze({
  operate: request => ipcRenderer.invoke("tv:operate", request),
  choosePackage: platform => ipcRenderer.invoke("tv:choose-package", platform),
  localIps: () => ipcRenderer.invoke("tv:local-ips"),
  onLog: callback => {
    const handler = (_event, message) => {
      if (typeof message === "string") callback(message);
    };
    ipcRenderer.on("tv:log", handler);
    return () => ipcRenderer.removeListener("tv:log", handler);
  }
}));
