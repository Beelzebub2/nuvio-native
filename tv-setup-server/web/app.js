const copy = {
  en: {
    header:"TV setup", eyebrow:"Connect a device", title:"Manage Starlit TV from your phone.",
    intro:"Enter the code shown on your television, or scan the QR code with your phone.",
    codeLabel:"TV pairing code", connect:"Connect to TV",
    safety:"You must approve the connection on your television. This session cannot change other devices.",
    request:"Pairing request", check:"Verify on your television", approve:"Confirm this matching code on the TV, then choose Approve.",
    cancel:"Cancel", connected:"Connected to", disconnect:"Disconnect", addons:"Add-ons", plugins:"Plugins",
    addonUrl:"Add-on manifest URL", pluginUrl:"Plugin repository URL", preview:"Preview",
    installedAddons:"Installed add-ons", installedPlugins:"Repositories", providers:"Providers",
    expired:"Connection ended", reconnect:"Start pairing again from Starlit on your TV to continue.",
    again:"Enter another code", footer:"Changes apply only to the selected TV profile.", privacy:"Privacy",
    profile:"Profile", install:"Install", remove:"Remove", enable:"Enable", disable:"Disable",
    unsupported:"This television cannot execute JavaScript plugins. You can still manage add-ons.",
    noAddons:"No add-ons installed on this profile.", noPlugins:"No plugin repositories installed.",
    noProviders:"No providers available.", waiting:"Waiting for TV approval…", working:"Waiting for TV…",
    installed:"Installed on TV", removed:"Removed from TV", updated:"Updated on TV",
    error:"Could not complete the action.", codeNotFound:"Check the code and try again.",
    confirmRemove:"Remove this item from Starlit TV?", unreviewed:"Only install extensions from publishers you trust.",
    pluginProviders:"Providers in repository", version:"Review the source and providers before installing.",
    requestPending:"Another phone is already requesting access. Start a new session on the TV.",
    notConnected:"The TV has not connected yet.", previewReady:"Ready to install", codeEntry:"Enter the code on your TV",
    documentTitle:"Connect to Starlit TV", homeAria:"Starlit home", extensionsAria:"Extensions"
  },
  pt: {
    header:"Configuração da TV", eyebrow:"Ligar dispositivo", title:"Gere o Starlit TV a partir do telemóvel.",
    intro:"Introduz o código apresentado na televisão ou lê o código QR com o telemóvel.",
    codeLabel:"Código de emparelhamento da TV", connect:"Ligar à TV",
    safety:"Tens de aprovar a ligação na televisão. Esta sessão não pode alterar outros dispositivos.",
    request:"Pedido de emparelhamento", check:"Confirma na televisão", approve:"Confirma que este código corresponde ao da TV e seleciona Aprovar.",
    cancel:"Cancelar", connected:"Ligado a", disconnect:"Desligar", addons:"Add-ons", plugins:"Plugins",
    addonUrl:"URL do manifesto do add-on", pluginUrl:"URL do repositório de plugins", preview:"Pré-visualizar",
    installedAddons:"Add-ons instalados", installedPlugins:"Repositórios", providers:"Fornecedores",
    expired:"Ligação terminada", reconnect:"Inicia novamente o emparelhamento na TV para continuares.",
    again:"Introduzir outro código", footer:"As alterações aplicam-se apenas ao perfil selecionado na TV.", privacy:"Privacidade",
    profile:"Perfil", install:"Instalar", remove:"Remover", enable:"Ativar", disable:"Desativar",
    unsupported:"Esta televisão não consegue executar plugins JavaScript. Podes continuar a gerir add-ons.",
    noAddons:"Não existem add-ons instalados neste perfil.", noPlugins:"Não existem repositórios de plugins instalados.",
    noProviders:"Não existem fornecedores disponíveis.", waiting:"À espera de aprovação na TV…", working:"À espera da TV…",
    installed:"Instalado na TV", removed:"Removido da TV", updated:"Atualizado na TV",
    error:"Não foi possível concluir a ação.", codeNotFound:"Verifica o código e tenta novamente.",
    confirmRemove:"Queres remover este elemento do Starlit TV?", unreviewed:"Instala apenas extensões de editores em quem confies.",
    pluginProviders:"Fornecedores do repositório", version:"Revê a origem e os fornecedores antes de instalar.",
    requestPending:"Outro telemóvel já pediu acesso. Inicia uma nova sessão na TV.",
    notConnected:"A TV ainda não está ligada.", previewReady:"Pronto para instalar", codeEntry:"Introduz o código da TV",
    documentTitle:"Ligar à Starlit TV", homeAria:"Página inicial do Starlit", extensionsAria:"Extensões"
  }
};
// Translation bundles are same-origin static assets; no remote translation service at runtime.
Object.assign(copy, globalThis.STARLIT_TV_SETUP_TRANSLATIONS || {});
function selectLocale(preferred = navigator.languages?.length ? navigator.languages : [navigator.language]) {
  for (const candidate of preferred) {
    const raw = String(candidate || "").trim().toLowerCase().replace(/_/g, "-");
    const normalized = raw === "pt-pt" ? "pt" : raw.startsWith("zh-hant") || raw === "zh-hk" ? "zh-tw" :
      raw.startsWith("zh-hans") || raw.startsWith("zh-") || raw === "zh" ? "zh-cn" :
      raw === "sr" || raw.startsWith("sr-") ? "sr-latn" :
      raw === "nb" || raw.startsWith("nb-") ? "no" :
      raw === "iw" || raw.startsWith("iw-") ? "he" :
      raw === "in" || raw.startsWith("in-") ? "id" : raw;
    if (copy[normalized]) return normalized;
    if (normalized.startsWith("pt-")) return normalized.startsWith("pt-br") ? "pt-br" : "pt";
    if (normalized.startsWith("es-") && normalized !== "es-es") return "es-419";
    const language = normalized.split("-", 1)[0];
    if (copy[language]) return language;
  }
  return "en";
}
const locale = selectLocale();
const T = copy[locale];
document.documentElement.lang = locale === "pt" ? "pt-PT" : locale;
document.documentElement.dir = ["ar", "he"].includes(locale) ? "rtl" : "ltr";
document.title = T.documentTitle || copy.en.documentTitle;
document.querySelector(".brand")?.setAttribute("aria-label", T.homeAria || copy.en.homeAria);
document.querySelector(".tabs")?.setAttribute("aria-label", T.extensionsAria || copy.en.extensionsAria);
document.querySelectorAll("[data-i18n]").forEach(el => { el.textContent = T[el.dataset.i18n] || copy.en[el.dataset.i18n]; });
const el = (id) => document.getElementById(id);
const sections = ["connect", "pending", "manage", "error"];
let session = null;
let state = null;
let stateSignature = "";
let pollTimer = 0;
let pendingAction = null;
let statusUntil = 0;

function screen(name) {
  sections.forEach(id => { el(id + "-screen").hidden = id !== name; });
}
function status(text = "", duration = 0) {
  el("status").textContent = text;
  statusUntil = duration ? Date.now() + duration : 0;
}
function node(tag, text, className = "") {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}
function itemRow(item, kind) {
  const row = node("div", undefined, "item-row");
  const info = node("div", undefined, "item-copy");
  info.append(node("div", item.name, "item-name"));
  if (kind === "provider") info.append(node("div", T.providers, "item-detail"));
  const actions = node("div", undefined, "item-actions");
  const enabled = node("button", item.enabled ? T.disable : T.enable);
  enabled.disabled = kind === "addon" ? !state.canEditAddons : !state.canEditPlugins;
  enabled.addEventListener("click", () => submit(
    kind === "addon" ? "setAddonEnabled" : kind === "provider" ? "setProviderEnabled" : "setRepositoryEnabled",
    { target: item.id, enabled: !item.enabled, revision: state.revision }
  ));
  actions.append(enabled);
  if (kind !== "provider") {
    const remove = node("button", T.remove, "destructive");
    remove.disabled = kind === "addon" ? !state.canEditAddons : !state.canEditPlugins;
    remove.addEventListener("click", () => {
      if (window.confirm(T.confirmRemove)) submit(kind === "addon" ? "removeAddon" : "removePlugin",
        { target: item.id, revision: state.revision });
    });
    actions.append(remove);
  }
  row.append(info, actions);
  return row;
}
function renderList(id, items, kind, empty) {
  const root = el(id);
  root.replaceChildren();
  if (!items.length) root.append(node("p", empty, "empty"));
  else items.forEach(item => root.append(itemRow(item, kind)));
}
function renderState(data) {
  state = data;
  const signature = JSON.stringify(data);
  if (signature === stateSignature) return;
  stateSignature = signature;
  el("profile").textContent = T.profile + " " + (data.profile || "1");
  el("addon-count").textContent = String(data.addons.length);
  el("plugin-count").textContent = String(data.repositories.length);
  renderList("addons-list", data.addons, "addon", T.noAddons);
  renderList("plugins-list", data.repositories, "repository", T.noPlugins);
  renderList("providers-list", data.providers, "provider", T.noProviders);
  el("addon-form").querySelector("button").disabled = !data.canEditAddons;
  el("plugin-form").querySelector("button").disabled = !data.canEditPlugins || !data.pluginExecutable;
  const limitation = el("plugin-limitation");
  limitation.hidden = data.pluginExecutable;
  limitation.textContent = data.pluginReason || T.unsupported;
}
function tab(name) {
  for (const part of ["addon", "plugin"]) {
    const active = part + "s" === name;
    el(part + "-panel").hidden = !active;
    el("tab-" + part + "s").classList.toggle("selected", active);
    el("tab-" + part + "s").setAttribute("aria-selected", String(active));
  }
}
async function api(path, method = "GET", payload) {
  const response = await fetch("/tv/api/" + path, {
    method, credentials: "omit", cache: "no-store",
    headers: {
      ...(method === "POST" ? { "Content-Type": "application/json" } : {}),
      ...(session?.claimToken ? { "X-Phone-Token": session.claimToken } : {})
    },
    ...(payload === undefined ? {} : { body: JSON.stringify(payload) })
  });
  const data = await response.json();
  if (!response.ok) {
    const error = new Error(data.error || T.error);
    error.status = response.status;
    throw error;
  }
  return data;
}
async function connect(code) {
  clearTimeout(pollTimer);
  state = null;
  stateSignature = "";
  pendingAction = null;
  status(T.working);
  el("connect-button").disabled = true;
  try {
    const result = await api("claim", "POST", { code });
    session = { id: result.id, claimToken: result.claimToken };
    el("verify-code").textContent = result.verification;
    el("requested-tv").textContent = result.name + " · " + T.profile + " " + result.profile;
    el("manage-title").textContent = result.name;
    screen("pending");
    status(T.waiting);
    poll();
  } catch (error) {
    status(error.status === 404 ? T.codeNotFound : error.status === 409 ? T.requestPending : T.error);
  } finally {
    el("connect-button").disabled = false;
  }
}
function preview(name, data, type) {
  const host = el("preview-" + type);
  host.replaceChildren();
  host.hidden = false;
  host.append(node("h2", data?.name || T.previewReady));
  if (data?.description) host.append(node("p", data.description));
  if (data?.items?.length) {
    host.append(node("p", type === "addon" ? T.version : T.pluginProviders));
    const list = node("ul");
    data.items.forEach(item => list.append(node("li", item)));
    host.append(list);
  }
  host.append(node("p", T.unreviewed));
  const install = node("button", T.install);
  install.addEventListener("click", () => submit(type === "addon" ? "installAddon" : "installPlugin",
    { url: name, revision: state.revision }));
  host.append(install);
}
async function submit(op, args) {
  if (!session || !state || pendingAction) return;
  try {
    const result = await api("command?id=" + encodeURIComponent(session.id), "POST", { op, ...args });
    pendingAction = { id: result.id, op, url: args.url };
    status(T.working);
  } catch (error) { status(error.message || T.error); }
}
function actionResult(result) {
  if (!pendingAction || result.id !== pendingAction.id) return;
  const old = pendingAction;
  pendingAction = null;
  status(result.ok ? (result.message || T.updated) : (result.message || T.error), 8000);
  if (result.ok && (old.op === "previewAddon" || old.op === "previewPlugin"))
    preview(old.url, result.detail, old.op === "previewAddon" ? "addon" : "plugin");
  if (result.ok && (old.op === "installAddon" || old.op === "installPlugin")) {
    el(old.op === "installAddon" ? "preview-addon" : "preview-plugin").hidden = true;
  }
}
async function poll() {
  if (!session) return;
  try {
    const data = await api("phone?id=" + encodeURIComponent(session.id));
    if (data.approved) {
      if (el("manage-screen").hidden) screen("manage");
      if (data.state) renderState(data.state);
      data.results?.forEach(actionResult);
      if (!pendingAction && Date.now() > statusUntil) status("");
    } else status(T.waiting);
  } catch (error) {
    if (error.status === 410 || error.status === 403) {
      session = null;
      screen("error");
      status("");
      return;
    }
    status(T.error);
  }
  pollTimer = setTimeout(poll, 2200);
}
async function leave() {
  const target = session;
  session = null;
  clearTimeout(pollTimer);
  if (target) {
    try { await fetch("/tv/api/leave?id=" + encodeURIComponent(target.id), {
      method:"POST", headers:{ "Content-Type":"application/json", "X-Phone-Token":target.claimToken },
      body:"{}", credentials:"omit"
    }); } catch { /* TV will also expire or revoke the session. */ }
  }
  screen("error");
  status("");
}
el("connect-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const value = el("pair-code").value.trim();
  if (value) connect(value);
});
el("addon-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (!state) return;
  el("preview-addon").hidden = true;
  submit("previewAddon", { url:el("addon-url").value.trim(), revision:state.revision });
});
el("plugin-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (!state) return;
  el("preview-plugin").hidden = true;
  submit("previewPlugin", { url:el("plugin-url").value.trim(), revision:state.revision });
});
el("tab-addons").addEventListener("click", () => tab("addons"));
el("tab-plugins").addEventListener("click", () => tab("plugins"));
el("cancel-request").addEventListener("click", leave);
el("disconnect").addEventListener("click", leave);
el("back-to-pair").addEventListener("click", () => { screen("connect"); status(""); });
const parameters = new URLSearchParams(location.hash.replace(/^#/, ""));
const qrCode = parameters.get("code");
if (qrCode) {
  history.replaceState(null, "", location.pathname + location.search);
  el("pair-code").value = qrCode;
  connect(qrCode);
}
