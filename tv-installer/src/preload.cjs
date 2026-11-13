"use strict";
const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("starlitTV", Object.freeze({
  operate: request => ipcRenderer.invoke("tv:operate", request),
  chooseIpk: () => ipcRenderer.invoke("tv:choose-ipk"),
  onLog: callback => {
    const handler = (_event, message) => {
      if (typeof message === "string") callback(message);
    };
    ipcRenderer.on("tv:log", handler);
    return () => ipcRenderer.removeListener("tv:log", handler);
  }
}));
