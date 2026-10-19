'use strict';

// Talks to setup.cpp over chrome.webview: the page sends "command|arg|..."
// strings and receives JSON messages (init, dir, progress, done, error, shown).
(() => {
  const params = new URLSearchParams(window.location.search);
  // ?preview opens the page in a plain browser with a fake host, for design work.
  const host = params.has('preview') ? null : window.chrome && window.chrome.webview;
  const $ = (id) => document.getElementById(id);
  const app = $('app');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let send = (...parts) => host && host.postMessage(parts.join('|'));

  const RAILS = {
    install: [['welcome', 'Welcome'], ['options', 'Options'], ['progress', 'Install'], ['done', 'Done']],
    update: [['welcome', 'Welcome'], ['options', 'Options'], ['progress', 'Update'], ['done', 'Done']],
    uninstall: [['confirm', 'Confirm'], ['progress', 'Remove'], ['done', 'Done']],
  };

  const PHASES = {
    idle: 'Getting ready…',
    closing: 'Closing the app…',
    extracting: 'Unpacking files…',
    legacy: 'Removing the previous version…',
    finishing: 'Creating shortcuts…',
    removing: 'Removing files…',
    done: 'Done',
  };

  const state = {
    mode: 'install',
    name: 'Starlit',
    publisher: '',
    version: '',
    previousVersion: '',
    dir: '',
    startMenu: true,
    desktop: true,
    required: 0,
    free: 0,
    running: false,
    sandbox: false,
    damaged: false,
    problem: '',
    removeData: false,
    busy: false,
  };

  const steps = {};
  document.querySelectorAll('.step').forEach((step) => { steps[step.dataset.step] = step; });
  let current = null;
  let currentName = '';
  let introPending = true;

  // ------------------------------------------------------------ formatting

  const mb = (bytes) => `${Math.max(0, Math.round(bytes / 1048576)).toLocaleString('en-US')} MB`;
  const size = (bytes) => (bytes >= 1073741824 ? `${(bytes / 1073741824).toFixed(bytes >= 10737418240 ? 0 : 1)} GB` : mb(bytes));
  const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  // Frames stop while the window is hidden or minimized, so never wait on one alone.
  const nextFrame = () => new Promise((resolve) => {
    requestAnimationFrame(() => resolve());
    setTimeout(resolve, 100);
  });

  // Keep the drive and the last two folders, which are the parts people check.
  function shortPath(path) {
    const parts = path.split('\\');
    return parts.length <= 5 ? path : [parts[0], parts[1], '…', ...parts.slice(-2)].join('\\');
  }

  // ------------------------------------------------------------ steps

  function buildRail() {
    const rail = $('rail');
    rail.textContent = '';
    for (const [id, label] of RAILS[state.mode]) {
      const item = document.createElement('li');
      item.dataset.id = id;
      item.textContent = label;
      rail.appendChild(item);
    }
    const indicator = document.createElement('span');
    indicator.className = 'rail-indicator';
    indicator.setAttribute('aria-hidden', 'true');
    rail.appendChild(indicator);
  }

  function updateRail(name) {
    const key = name === 'error' ? 'progress' : name;
    const items = [...$('rail').querySelectorAll('li')];
    const index = items.findIndex((item) => item.dataset.id === key);
    items.forEach((item, i) => {
      item.classList.toggle('is-past', i < index);
      item.classList.toggle('is-current', i === index);
      if (i === index) item.setAttribute('aria-current', 'step');
      else item.removeAttribute('aria-current');
    });
    const indicator = $('rail').querySelector('.rail-indicator');
    const active = items[index];
    if (indicator && active) {
      indicator.style.width = `${active.offsetWidth}px`;
      indicator.style.transform = `translateX(${active.offsetLeft}px)`;
    }
  }

  function enter(step, direction, baseDelay) {
    const distance = reduceMotion ? 0 : 22;
    step.querySelectorAll('[data-enter]').forEach((part, i) => {
      part.animate(
        [{ opacity: 0, transform: `translateX(${distance * direction}px)` }, { opacity: 1, transform: 'none' }],
        {
          duration: reduceMotion ? 160 : 460,
          delay: baseDelay + (reduceMotion ? 0 : i * 45),
          easing: 'cubic-bezier(.2, .8, .2, 1)',
          fill: 'backwards',
        },
      );
    });
  }

  function show(name, direction = 1) {
    const next = steps[name];
    if (!next || next === current) return;
    const previous = current;
    current = next;
    currentName = name;
    updateRail(name);
    if (previous) {
      previous.style.pointerEvents = 'none';
      const leave = previous.animate(
        [{ opacity: 1, transform: 'none' }, { opacity: 0, transform: `translateX(${(reduceMotion ? 0 : -22) * direction}px)` }],
        { duration: reduceMotion ? 120 : 200, easing: 'cubic-bezier(.4, 0, 1, 1)', fill: 'forwards' },
      );
      leave.onfinish = () => {
        previous.hidden = true;
        previous.style.pointerEvents = '';
        leave.cancel();
      };
    }
    next.hidden = false;
    // Before the window is shown, the intro animation plays the first step in.
    if (!introPending) enter(next, direction, previous ? 120 : 0);
    focusPrimary(next);
  }

  function focusPrimary(step) {
    const primary = [...step.querySelectorAll('.button.primary')].find((button) => !button.hidden && !button.disabled);
    // Enter works straight away; the ring only appears once someone uses the keyboard.
    if (primary) primary.focus({ preventScroll: true, focusVisible: false });
  }

  function setSwitch(element, on) {
    element.setAttribute('aria-checked', on ? 'true' : 'false');
  }

  function setBusy(busy) {
    state.busy = busy;
    app.classList.toggle('is-busy', busy);
    $('close').disabled = busy;
  }

  // ------------------------------------------------------------ rendering

  function renderWelcome() {
    const facts = $('welcome-facts');
    facts.textContent = '';
    const addFact = (label, value, title) => {
      const term = document.createElement('dt');
      term.textContent = label;
      const detail = document.createElement('dd');
      detail.textContent = value;
      if (title) detail.title = title;
      facts.append(term, detail);
    };
    if (state.mode === 'update') {
      $('welcome-title').textContent = `Update ${state.name}`;
      $('welcome-lead').textContent = state.previousVersion
        ? `Version ${state.previousVersion} is installed. Updating keeps your settings, profiles and library.`
        : `${state.name} is already installed. Updating keeps your settings, profiles and library.`;
      if (state.previousVersion) addFact('Installed', state.previousVersion);
      addFact('New version', state.version);
      addFact('Location', shortPath(state.dir), state.dir);
      $('welcome-options').hidden = false;
      $('welcome-next').textContent = 'Update';
      $('welcome-next').disabled = Boolean(state.problem);
    } else {
      $('welcome-title').textContent = `Install ${state.name}`;
      $('welcome-lead').textContent = 'Browse, organize, and play media from sources you add.';
      addFact('Version', state.version);
      addFact('Size', mb(state.required));
      addFact('Access', 'Just your account, no admin rights needed');
      if (state.publisher) addFact('Made by', state.publisher);
      addFact('Based on', 'Nuvio, by Tapframe and friends');
      $('welcome-options').hidden = true;
      $('welcome-next').textContent = 'Continue';
      $('welcome-next').disabled = false;
    }
    const notice = $('running-notice');
    notice.hidden = !state.running;
    notice.textContent = `${state.name} is open. Setup will close it before ${state.mode === 'update' ? 'updating' : 'installing'}.`;
  }

  function renderOptions() {
    const path = $('path-text');
    path.textContent = shortPath(state.dir);
    path.title = state.dir;
    const hint = $('space-hint');
    hint.classList.toggle('is-problem', Boolean(state.problem));
    hint.textContent = state.problem || `Needs ${mb(state.required)}. ${size(state.free)} free on this drive.`;
    setSwitch($('opt-start'), state.startMenu);
    setSwitch($('opt-desktop'), state.desktop);
    $('options-install').textContent = state.mode === 'update' ? 'Update' : 'Install';
    $('options-install').disabled = Boolean(state.problem);
  }

  function renderConfirm() {
    $('confirm-title').textContent = `Uninstall ${state.name}`;
    const version = state.version ? ` ${state.version}` : '';
    $('confirm-lead').textContent = `This removes ${state.name}${version}, its shortcuts and its entry in Installed apps.`;
    $('data-label').textContent = `Also delete my ${state.name} data`;
    setSwitch($('opt-data'), state.removeData);
  }

  function renderDone() {
    const launch = $('done-launch');
    const close = $('done-close');
    if (state.mode === 'uninstall') {
      $('done-title').textContent = `${state.name} is uninstalled`;
      $('done-lead').textContent = state.removeData
        ? `Your ${state.name} data on this PC was deleted too.`
        : 'Your settings, profiles and cache are still on this PC for a reinstall.';
      launch.hidden = true;
      close.className = 'button primary';
      return;
    }
    const where = state.startMenu && state.desktop ? 'Find it in Start and on your desktop.'
      : state.startMenu ? 'Find it in Start.'
        : state.desktop ? 'Find it on your desktop.'
          : `It's in ${state.dir}.`;
    $('done-title').textContent = `${state.name} is ${state.mode === 'update' ? 'updated' : 'installed'}`;
    launch.textContent = `Launch ${state.name}`;
    $('done-lead').textContent = state.mode === 'update' && state.version ? `You're on version ${state.version}. ${where}` : where;
  }

  // ------------------------------------------------------------ progress

  const progress = { reported: 0, shown: 0, phase: 'idle', done: 0, total: 0, last: 0, running: false };

  function paintProgress() {
    const scrub = $('scrub');
    scrub.style.setProperty('--shown', progress.shown.toFixed(4));
    scrub.style.setProperty('--reported', progress.reported.toFixed(4));
    app.style.setProperty('--p', progress.shown.toFixed(3));
    const percent = Math.round(progress.shown * 100);
    $('progress-percent').textContent = `${percent}%`;
    scrub.setAttribute('aria-valuenow', String(percent));
    $('progress-bytes').textContent = progress.total && progress.done ? `${mb(progress.done)} of ${mb(progress.total)}` : '';
  }

  function tick(now) {
    const dt = Math.min(64, now - progress.last);
    progress.last = now;
    // Removing the old MSI reports nothing while it runs; ease forward inside its slice.
    if (progress.phase === 'legacy') progress.reported = Math.min(0.955, progress.reported + dt * 0.000004);
    const target = progress.reported;
    progress.shown += (target - progress.shown) * (1 - Math.exp(-dt / 160));
    if (Math.abs(target - progress.shown) < 0.0004) progress.shown = target;
    paintProgress();
    if (state.busy || progress.shown !== target) requestAnimationFrame(tick);
    else progress.running = false;
  }

  function animateProgress() {
    if (progress.running) return;
    progress.running = true;
    progress.last = performance.now();
    requestAnimationFrame(tick);
  }

  function onProgress(message) {
    progress.reported = Math.max(progress.reported, message.value);
    progress.phase = message.phase;
    progress.done = message.done;
    progress.total = message.total;
    $('progress-status').textContent = message.phase === 'closing' ? `Closing ${state.name}…` : PHASES[message.phase] || PHASES.idle;
    $('progress-file').textContent = message.phase === 'extracting' ? message.file : '';
    animateProgress();
  }

  function begin() {
    if (state.busy) return;
    setBusy(true);
    Object.assign(progress, { reported: 0, shown: 0, phase: 'idle', done: 0, total: 0 });
    paintProgress();
    const titles = { install: 'Installing', update: 'Updating', uninstall: 'Uninstalling' };
    $('progress-title').textContent = `${titles[state.mode]} ${state.name}`;
    $('progress-status').textContent = PHASES.idle;
    $('progress-file').textContent = '';
    show('progress');
    if (state.mode === 'uninstall') send('uninstall', state.removeData ? '1' : '0');
    else send('install', state.dir, state.startMenu ? '1' : '0', state.desktop ? '1' : '0');
  }

  async function onDone() {
    setBusy(false);
    progress.reported = 1;
    animateProgress();
    const started = performance.now();
    while (progress.shown < 0.999 && performance.now() - started < 2500) await nextFrame();
    $('progress-status').textContent = PHASES.done;
    await delay(reduceMotion ? 0 : 280);
    renderDone();
    show('done');
    app.classList.add('is-complete');
  }

  function onError(message, fatal = false) {
    setBusy(false);
    const titles = { install: 'Installation stopped', update: 'Update stopped', uninstall: 'Uninstall stopped' };
    $('error-title').textContent = fatal ? "This installer can't run" : titles[state.mode];
    $('error-message').textContent = message;
    $('error-retry').hidden = fatal;
    $('error-close').className = fatal ? 'button primary' : 'button secondary';
    show('error');
  }

  // ------------------------------------------------------------ host messages

  async function onInit(message) {
    Object.assign(state, message);
    app.dataset.mode = state.mode;
    buildRail();
    $('stage-version').textContent = state.version ? `Version ${state.version}` : '';
    $('sandbox-pill').hidden = !state.sandbox;
    renderWelcome();
    renderOptions();
    renderConfirm();
    if (state.damaged) onError(state.problem, true);
    else show(state.mode === 'uninstall' ? 'confirm' : 'welcome');
    // The window is still hidden here, so no animation frames: wait for the
    // fonts, re-measure the rail with them, then ask to be shown.
    try { await document.fonts.ready; } catch { /* fall back to system fonts */ }
    updateRail(currentName);
    send('ready');
  }

  function onShown() {
    if (!introPending) return;
    introPending = false;
    app.classList.remove('is-intro');
    if (current) enter(current, 1, reduceMotion ? 0 : 260);
  }

  function onMessage(message) {
    switch (message.type) {
      case 'init': onInit(message); break;
      case 'shown': onShown(); break;
      case 'dir':
        state.dir = message.dir;
        state.free = message.free;
        state.problem = message.problem;
        renderOptions();
        renderWelcome();
        break;
      case 'progress': onProgress(message); break;
      case 'done': onDone(); break;
      case 'error': onError(message.message); break;
      default: break;
    }
  }

  // ------------------------------------------------------------ wiring

  $('welcome-next').addEventListener('click', () => (state.mode === 'update' ? begin() : show('options')));
  $('welcome-options').addEventListener('click', () => show('options'));
  $('options-back').addEventListener('click', () => show('welcome', -1));
  $('options-install').addEventListener('click', begin);
  $('browse').addEventListener('click', () => send('browse'));
  $('confirm-cancel').addEventListener('click', () => send('close'));
  $('confirm-uninstall').addEventListener('click', begin);
  $('done-launch').addEventListener('click', () => send('launch'));
  $('done-close').addEventListener('click', () => send('close'));
  $('error-close').addEventListener('click', () => send('close'));
  $('error-retry').addEventListener('click', begin);
  $('error-log').addEventListener('click', () => send('log'));
  $('minimize').addEventListener('click', () => send('minimize'));
  $('close').addEventListener('click', () => send('close'));

  const toggles = { 'opt-start': 'startMenu', 'opt-desktop': 'desktop', 'opt-data': 'removeData' };
  for (const [id, key] of Object.entries(toggles)) {
    $(id).addEventListener('click', () => {
      state[key] = !state[key];
      setSwitch($(id), state[key]);
    });
  }

  document.querySelectorAll('[data-drag]').forEach((region) => {
    region.addEventListener('mousedown', (event) => {
      if (event.button !== 0 || event.target.closest('button')) return;
      event.preventDefault();
      send('drag');
    });
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !state.busy) send('close');
  });
  window.addEventListener('dragover', (event) => event.preventDefault());
  window.addEventListener('drop', (event) => event.preventDefault());

  if (host) {
    host.addEventListener('message', (event) => onMessage(event.data));
    send('hello');
    return;
  }

  // Fake host for ?preview: &mode=install|update|uninstall &step=options|progress|done|error &running &sandbox
  const mode = params.get('mode') || 'install';
  let timer = 0;
  send = (command) => {
    if (command === 'ready') setTimeout(() => onMessage({ type: 'shown' }), 50);
    if (command !== 'install' && command !== 'uninstall') return;
    let value = 0;
    clearInterval(timer);
    timer = setInterval(() => {
      value = Math.min(1, value + 0.012 + Math.random() * 0.01);
      const phase = mode === 'uninstall' ? 'removing' : value < 0.03 ? 'closing' : value < 0.9 ? 'extracting' : 'finishing';
      onMessage({ type: 'progress', phase, value, done: Math.min(1, value / 0.9) * 282e6, total: mode === 'uninstall' ? 0 : 282e6, file: 'app\\composeApp-desktop-20a2ba6c5cd9521184e6d2cc2d427acb.jar' });
      if (params.get('step') === 'progress' && value > 0.58) clearInterval(timer);
      else if (value >= 1) {
        clearInterval(timer);
        if (params.get('step') === 'error') onMessage({ type: 'error', message: 'Some files in C:\\Users\\you\\AppData\\Local\\Starlit are in use, so setup couldn\'t replace them. Close Starlit, then select Try again.' });
        else onMessage({ type: 'done' });
      }
    }, 60);
  };
  onMessage({
    type: 'init',
    mode,
    name: 'Starlit',
    publisher: 'Beelzebub2',
    version: '1.0.15',
    previousVersion: mode === 'update' ? '1.0.14' : '',
    dir: 'C:\\Users\\you\\AppData\\Local\\Starlit',
    startMenu: true,
    desktop: true,
    required: 282e6,
    free: 128e9,
    running: params.has('running'),
    sandbox: params.has('sandbox'),
    damaged: false,
    problem: '',
  });
  const step = params.get('step');
  if (step === 'options') setTimeout(() => show('options'), 600);
  if (step === 'progress' || step === 'done' || step === 'error') setTimeout(begin, 600);
})();
