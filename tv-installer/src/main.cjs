"use strict";
const path = require("node:path");
const { app, BrowserWindow, dialog, ipcMain, session } = require("electron");
const { operate } = require("./installer.cjs");
const { discoverTvs } = require("./discovery.cjs");
const { platformInfo, localLanIps } = require("./platforms.cjs");

let mainWindow;
let scanning = false;
async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1100, height: 820, minWidth: 800, minHeight: 650,
    title: "Starlit TV Installer", backgroundColor: "#0d0d0d", autoHideMenuBar: true,
    // The page draws the title bar; Windows keeps its own caption buttons.
    titleBarStyle: "hidden",
    titleBarOverlay: { color: "#0d0d0d", symbolColor: "#f5f7f8", height: 43 },
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
  ipcMain.handle("tv:local-ips", event => {
    if (event.sender !== mainWindow?.webContents) throw new Error("Untrusted window");
    return localLanIps();
  });
  ipcMain.handle("tv:discover", async event => {
    if (event.sender !== mainWindow?.webContents) return { ok: false, error: "Untrusted window" };
    if (scanning) return { ok: false, error: "A TV scan is already running" };
    scanning = true;
    try {
      const result = await discoverTvs({
        samsungOptions: { toolsDir: path.join(app.getPath("userData"), "tools") },
        onStatus: message => {
          if (!event.sender.isDestroyed()) event.sender.send("tv:log", String(message).slice(0, 500));
        }
      });
      return { ok: true, result };
    } catch (error) {
      return { ok: false, error: String(error.message || error).slice(0, 300) };
    } finally {
      scanning = false;
    }
  });
  ipcMain.handle("tv:choose-package", async (event, platform) => {
    if (event.sender !== mainWindow?.webContents) throw new Error("Untrusted window");
    const spec = platformInfo(platform);
    const choice = await dialog.showOpenDialog(mainWindow, {
      title: "Choose a Starlit " + spec.label + " package",
      properties: ["openFile"],
      filters: [{ name: spec.label + " " + spec.extension + " package", extensions: [spec.extension.slice(1)] }]
    });
    return choice.canceled ? "" : choice.filePaths[0] || "";
  });
  ipcMain.handle("tv:operate", async (event, request) => {
    if (event.sender !== mainWindow?.webContents) return { ok: false, error: "Untrusted window" };
    try {
      const result = await operate(request, {
        samsungOps: { toolsDir: path.join(app.getPath("userData"), "tools") },
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
