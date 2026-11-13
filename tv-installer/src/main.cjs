"use strict";
const path = require("node:path");
const { app, BrowserWindow, dialog, ipcMain, session } = require("electron");
const { operate } = require("./installer.cjs");

let mainWindow;
async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1100, height: 820, minWidth: 800, minHeight: 650,
    backgroundColor: "#0b101b", autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      sandbox: true, contextIsolation: true, nodeIntegration: false
    }
  });
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  mainWindow.webContents.on("will-navigate", event => event.preventDefault());
  await mainWindow.loadFile(path.join(__dirname, "index.html"));
}
app.whenReady().then(async () => {
  session.defaultSession.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
  ipcMain.handle("tv:choose-ipk", async event => {
    if (event.sender !== mainWindow?.webContents) throw new Error("Untrusted window");
    const choice = await dialog.showOpenDialog(mainWindow, {
      title: "Choose a Starlit webOS IPK",
      properties: ["openFile"],
      filters: [{ name: "LG webOS package", extensions: ["ipk"] }]
    });
    return choice.canceled ? "" : choice.filePaths[0] || "";
  });
  ipcMain.handle("tv:operate", async (event, request) => {
    if (event.sender !== mainWindow?.webContents) return { ok: false, error: "Untrusted window" };
    try {
      const result = await operate(request, {
        onStatus: message => event.sender.send("tv:log", String(message).slice(0, 500))
      });
      return { ok: true, result };
    } catch (error) {
      return { ok: false, error: String(error.message || error).slice(0, 650) };
    }
  });
  await createWindow();
});
app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
