// One SQLite Durable Object per account. Every RPC runs in one synchronous transaction, so an
// account's writes are serialized (like the Supabase backend's advisory lock) and a failed
// mutation rolls back completely. The function contract mirrors backend/supabase/migrations.
import { DurableObject } from 'cloudflare:workers';
import { PgError, pgBody, jsonType, byteLength, canonicalJson, stripNulls, textOf, nowIso } from './shared.mjs';

const invalid = message => new PgError('22023', message);
const MAX_LONG = 9223372036854775807;
const ITEM_KINDS = ['library', 'watch_progress', 'watched_items'];
const TIMESTAMP_FIELD = { library: 'added_at', watch_progress: 'last_watched', watched_items: 'watched_at' };
const COLOR = /^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$/;
const PROVIDER = /^(tmdb|mdblist|animeskip|introdb|debrid:[a-z0-9_-]{1,64})$/;
const PIN_UNSUPPORTED = 'Profile PINs are not supported by this Starlit server';
const SETTINGS_SECRETS = [
  ['player_settings', 'animeskip_client_id'], ['player_settings', 'introdb_api_key'],
  ['debrid_settings', 'debrid_torbox_api_key'], ['debrid_settings', 'debrid_premiumize_api_key'],
  ['debrid_settings', 'debrid_real_debrid_api_key'], ['tmdb_settings', 'tmdb_api_key'], ['mdblist_settings', 'mdblist_api_key'],
];
const chars = value => [...value].length;

function requireJson(value, type, maxBytes = 1048576) {
  if (value === null || value === undefined || jsonType(value) !== type || byteLength(value) > maxBytes) {
    throw invalid(`Invalid or oversized ${type} payload`);
  }
  if (type === 'array' && value.length > 1000) throw invalid('At most 1000 items per request');
}

// Validate serialized primitive fields so malformed clients cannot poison another device's decoder.
function validateFields(item, strings, numbers, booleans) {
  requireJson(item, 'object');
  for (const key of strings) if (key in item && !['string', 'null'].includes(jsonType(item[key]))) throw invalid(`Expected string: ${key}`);
  for (const key of numbers) if (key in item && !['number', 'null'].includes(jsonType(item[key]))) throw invalid(`Expected number: ${key}`);
  for (const key of booleans) if (key in item && jsonType(item[key]) !== 'boolean') throw invalid(`Expected boolean: ${key}`);
}

function toInteger(value, type = 'integer') {
  if (!Number.isInteger(value)) throw new PgError('22P02', `invalid input syntax for type ${type}: "${textOf(value)}"`);
  if (type === 'integer' && (value < -2147483648 || value > 2147483647)) throw new PgError('22003', 'value out of range for type integer');
  return value;
}

const isNonNegativeLong = value => Number.isInteger(value) && value >= 0 && value <= MAX_LONG;
const checkViolation = (table, column) => new PgError('23514', `new row for relation "${table}" violates check constraint "${table}_${column}_check"`);

class SyncStore {
  constructor(sql, userId) {
    this.sql = sql;
    this.userId = userId;
  }
  all(query, ...bindings) { return this.sql.exec(query, ...bindings).toArray(); }
  first(query, ...bindings) { return this.all(query, ...bindings)[0] ?? null; }
  run(query, ...bindings) { this.sql.exec(query, ...bindings); }

  requireProfile(profileId) {
    if (profileId === null || profileId < 1 || profileId > 6 || !this.first('SELECT 1 AS ok FROM profiles WHERE profile_index = ?', profileId)) {
      throw invalid('Profile does not exist for this account');
    }
  }

  event(profileId, kind, operation, payloadText) {
    this.run('INSERT INTO events (profile_id, kind, operation, payload, created_at) VALUES (?, ?, ?, ?, ?)', profileId, kind, operation, payloadText, nowIso());
  }

  profileJson(row) {
    return {
      user_id: this.userId, profile_index: row.profile_index, id: row.id, name: row.name,
      avatar_color_hex: row.avatar_color_hex, avatar_id: row.avatar_id, avatar_url: row.avatar_url,
      profile_background_id: row.profile_background_id, profile_background_url: row.profile_background_url,
      uses_primary_addons: row.uses_primary_addons === 1, uses_primary_plugins: row.uses_primary_plugins === 1,
      pin_enabled: false, pin_locked_until: null, created_at: row.created_at, updated_at: row.updated_at,
    };
  }

  pullProfiles() {
    return this.all('SELECT * FROM profiles ORDER BY profile_index').map(row => this.profileJson(row));
  }

  pushProfiles(profiles, clientMax) {
    requireJson(profiles, 'array', 65536);
    if (clientMax === null || clientMax < 1 || clientMax > 6 || profiles.length > clientMax) throw invalid('Profile limit is six');
    for (const item of profiles) {
      validateFields(item, ['name', 'avatar_color_hex', 'avatar_id', 'avatar_url', 'profile_background_id', 'profile_background_url'], ['profile_index'], ['uses_primary_addons', 'uses_primary_plugins']);
      const index = item.profile_index ?? null;
      if (index !== null) toInteger(index);
      const nameLength = item.name == null ? 0 : chars(item.name);
      if (index === null || index < 1 || index > clientMax || nameLength < 1 || nameLength > 100) throw invalid('Invalid profile');
      if (chars(item.avatar_url ?? '') > 2048 || chars(item.profile_background_url ?? '') > 2048) throw invalid('Profile image URL too long');
      const color = item.avatar_color_hex ?? '#1E88E5';
      if (!COLOR.test(color)) throw checkViolation('profiles', 'avatar_color_hex');
      const now = nowIso();
      this.run(`INSERT INTO profiles (profile_index, id, name, avatar_color_hex, avatar_id, avatar_url, profile_background_id, profile_background_url, uses_primary_addons, uses_primary_plugins, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (profile_index) DO UPDATE SET name = excluded.name, avatar_color_hex = excluded.avatar_color_hex, avatar_id = excluded.avatar_id,
          avatar_url = excluded.avatar_url, profile_background_id = excluded.profile_background_id, profile_background_url = excluded.profile_background_url,
          uses_primary_addons = excluded.uses_primary_addons, uses_primary_plugins = excluded.uses_primary_plugins, updated_at = excluded.updated_at`,
        index, crypto.randomUUID(), item.name, color, item.avatar_id ?? null, item.avatar_url ?? null, item.profile_background_id ?? null,
        item.profile_background_url ?? null, item.uses_primary_addons === true ? 1 : 0, item.uses_primary_plugins === true ? 1 : 0, now, now);
    }
    // Omitted profiles are deliberately kept. Clients delete with sync_delete_profile_data.
  }

  deleteProfileData(profileId) {
    this.requireProfile(profileId);
    if (profileId === 1) throw invalid('The primary profile cannot be deleted');
    // Keep tombstones if the index is later reused, so other devices can remove stale records.
    for (const row of this.all(`SELECT kind, payload FROM records WHERE profile_id = ? AND kind IN ('library', 'watch_progress', 'watched_items')`, profileId)) {
      this.event(profileId, row.kind, 'delete', row.payload);
    }
    this.run('DELETE FROM records WHERE profile_id = ?', profileId);
    this.run('DELETE FROM extensions WHERE profile_id = ?', profileId);
    this.run('DELETE FROM profiles WHERE profile_index = ?', profileId);
  }

  profileLocks() {
    return this.all('SELECT profile_index FROM profiles ORDER BY profile_index').map(row => ({ profile_index: row.profile_index, pin_enabled: false, pin_locked_until: null }));
  }

  pushExtensions(kind, profileId, items) {
    this.requireProfile(profileId);
    requireJson(items, 'array');
    // Clients send complete lists, so replacement preserves removal and ordering.
    this.run('DELETE FROM extensions WHERE kind = ? AND profile_id = ?', kind, profileId);
    for (const item of items) {
      validateFields(item, ['url', 'name'], ['sort_order'], ['enabled']);
      if (jsonType(item.url) !== 'string') throw invalid('Missing extension URL');
      if (chars(item.url) < 1 || chars(item.url) > 8192) throw checkViolation(kind, 'url');
      const name = item.name ?? '';
      if (chars(name) > 512) throw checkViolation(kind, 'name');
      const sortOrder = item.sort_order == null ? 0 : toInteger(item.sort_order);
      this.run(`INSERT INTO extensions (kind, profile_id, url, name, enabled, sort_order) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (kind, profile_id, url) DO UPDATE SET name = excluded.name, enabled = excluded.enabled, sort_order = excluded.sort_order`,
        kind, profileId, item.url, name, item.enabled === false ? 0 : 1, sortOrder);
    }
  }

  extensionRows(kind) {
    return this.all('SELECT profile_id, url, name, enabled, sort_order FROM extensions WHERE kind = ? ORDER BY rowid', kind)
      .map(row => ({ user_id: this.userId, profile_id: row.profile_id, url: row.url, name: row.name, enabled: row.enabled === 1, sort_order: row.sort_order }));
  }

  itemKey(kind, item, isDelete) {
    let key;
    if (kind === 'watch_progress' && isDelete) {
      if (jsonType(item) !== 'string') throw invalid('Expected progress key string');
      key = item;
    } else {
      requireJson(item, 'object');
      if (kind === 'watch_progress') {
        key = textOf(item.progress_key);
      } else {
        if (jsonType(item.content_id) !== 'string' || chars(item.content_id) < 1 || chars(item.content_id) > 2048) throw invalid('Missing content_id');
        if (kind === 'library') {
          if (jsonType(item.content_type) !== 'string' || chars(item.content_type) < 1 || chars(item.content_type) > 100) throw invalid('Missing content_type');
          key = JSON.stringify([item.content_id, item.content_type]);
        } else {
          // The client deletes watched items by content_id/season/episode, without content_type.
          key = JSON.stringify([item.content_id, textOf(item.season), textOf(item.episode)]);
        }
      }
    }
    if (key === null || chars(key) < 1 || chars(key) > 8192) throw invalid('Invalid record key');
    return key;
  }

  pushItems(kind, profileId, items, isDelete = false) {
    this.requireProfile(profileId);
    requireJson(items, 'array');
    const timestampField = TIMESTAMP_FIELD[kind];
    for (const item of items) {
      const key = this.itemKey(kind, item, isDelete);
      const old = this.first('SELECT payload FROM records WHERE profile_id = ? AND kind = ? AND record_key = ?', profileId, kind, key);
      let payloadText;
      if (isDelete) {
        if (!old) continue;
        this.run('DELETE FROM records WHERE profile_id = ? AND kind = ? AND record_key = ?', profileId, kind, key);
        payloadText = old.payload;
      } else {
        validateFields(item, ['content_id', 'content_type', 'name', 'title', 'poster', 'poster_shape', 'background', 'logo', 'description', 'release_info', 'addon_base_url', 'video_id', 'progress_key'],
          ['season', 'episode', 'position', 'duration', 'last_watched', 'watched_at', 'added_at', 'imdb_rating'], []);
        if (jsonType(item.content_id) !== 'string' || jsonType(item.content_type) !== 'string' || chars(item.content_id) < 1 || chars(item.content_id) > 2048 ||
          chars(item.content_type) < 1 || chars(item.content_type) > 100) throw invalid('Missing content_id/content_type');
        let payload;
        if (kind === 'library') {
          if ('genres' in item) {
            requireJson(item.genres, 'array', 65536);
            if (item.genres.some(genre => jsonType(genre) !== 'string')) throw invalid('Genres must be strings');
          }
          payload = { name: '', poster_shape: 'POSTER', genres: [], added_at: 0, ...stripNulls(item) };
        } else if (kind === 'watch_progress') {
          if (jsonType(item.video_id) !== 'string') throw invalid('Missing video_id');
          payload = { position: 0, duration: 0, last_watched: 0, ...stripNulls(item) };
        } else {
          payload = { title: '', watched_at: 0, ...stripNulls(item) };
        }
        for (const field of ['season', 'episode']) {
          if (item[field] != null && (!Number.isInteger(item[field]) || item[field] < 0 || item[field] > 2147483647)) throw invalid('Invalid season/episode');
        }
        // Epoch millisecond and position fields must decode as nonnegative Kotlin Longs.
        if (!isNonNegativeLong(payload[timestampField])) throw invalid('Invalid timestamp');
        if (kind === 'watch_progress' && (!isNonNegativeLong(payload.position) || !isNonNegativeLong(payload.duration))) throw invalid('Invalid position/duration');
        const oldPayload = old ? JSON.parse(old.payload) : null;
        if ((oldPayload?.[timestampField] ?? -1) > payload[timestampField]) continue; // Never let an older write win.
        payloadText = canonicalJson(payload);
        if (old?.payload === payloadText) continue;
        this.run(`INSERT INTO records (profile_id, kind, record_key, payload, updated_at) VALUES (?, ?, ?, ?, ?)
          ON CONFLICT (profile_id, kind, record_key) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at`,
          profileId, kind, key, payloadText, nowIso());
      }
      this.event(profileId, kind, isDelete ? 'delete' : 'upsert', payloadText);
    }
  }

  pullItems(kind, profileId, limit, offset, sinceLastWatched = null) {
    this.requireProfile(profileId);
    if (limit === null || limit < 1 || limit > 1000 || offset === null || offset < 0) throw invalid('Invalid pagination');
    return this.records(kind, profileId, sinceLastWatched, limit, offset);
  }

  records(kind, profileId, sinceLastWatched, limit, offset = 0) {
    if (sinceLastWatched === null) {
      const rows = limit === null
        ? this.all('SELECT payload FROM records WHERE profile_id = ? AND kind = ? ORDER BY record_key', profileId, kind)
        : this.all('SELECT payload FROM records WHERE profile_id = ? AND kind = ? ORDER BY record_key LIMIT ? OFFSET ?', profileId, kind, limit, offset);
      return rows.map(row => JSON.parse(row.payload));
    }
    const matching = this.all('SELECT payload FROM records WHERE profile_id = ? AND kind = ? ORDER BY record_key', profileId, kind)
      .map(row => JSON.parse(row.payload))
      .filter(payload => (payload.last_watched ?? 0) > sinceLastWatched);
    return limit === null ? matching.slice(offset) : matching.slice(offset, offset + limit);
  }

  pullWatchProgress(profileId, sinceLastWatched, limit) {
    this.requireProfile(profileId);
    if ((limit !== null && (limit < 1 || limit > 1000)) || (sinceLastWatched !== null && sinceLastWatched < 0)) throw invalid('Invalid progress filter/limit');
    // One JSON array: the client snapshot has no pagination, so it must never be truncated.
    return this.records('watch_progress', profileId, sinceLastWatched, limit);
  }

  pullWatchedItems(profileId, page, pageSize) {
    if (page === null || page < 1 || page > 2147483) throw invalid('Invalid page');
    return this.pullItems('watched_items', profileId, pageSize, (page - 1) * (pageSize ?? 0));
  }

  deltaCursor(kind, profileId) {
    this.requireProfile(profileId);
    return this.first('SELECT COALESCE(MAX(event_id), 0) AS cursor FROM events WHERE profile_id = ? AND kind = ?', profileId, kind).cursor;
  }

  pullDelta(kind, profileId, sinceEventId, limit) {
    this.requireProfile(profileId);
    if (limit === null || limit < 1 || limit > 1000 || sinceEventId === null || sinceEventId < 0) throw invalid('Invalid delta pagination');
    return this.all('SELECT event_id, operation, payload FROM events WHERE profile_id = ? AND kind = ? AND event_id > ? ORDER BY event_id LIMIT ?', profileId, kind, sinceEventId, limit)
      .map(row => ({ ...JSON.parse(row.payload), event_id: row.event_id, operation: row.operation }));
  }

  pushBlob(kind, profileId, key, payload) {
    this.requireProfile(profileId);
    if (kind === 'collections') {
      requireJson(payload, 'array');
    } else {
      requireJson(payload, 'object');
      if (!['mobile', 'desktop', 'home_catalog_shared'].includes(key) || (kind === 'profile_settings_blob' && key === 'home_catalog_shared')) {
        throw invalid('Invalid settings platform');
      }
    }
    if (kind === 'profile_settings_blob') {
      // Provider keys have a dedicated isolation boundary; never duplicate them into ordinary settings.
      payload = structuredClone(payload);
      for (const [section, field] of SETTINGS_SECRETS) {
        const target = payload.features?.[section];
        if (target && typeof target === 'object' && !Array.isArray(target)) delete target[field];
      }
    }
    this.run(`INSERT INTO records (profile_id, kind, record_key, payload, updated_at) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (profile_id, kind, record_key) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at`,
      profileId, kind, key, canonicalJson(payload), nowIso());
  }

  pullBlob(kind, profileId, key, field) {
    this.requireProfile(profileId);
    return this.all('SELECT payload, updated_at FROM records WHERE profile_id = ? AND kind = ? AND record_key = ?', profileId, kind, key)
      .map(row => ({ profile_id: profileId, [field]: JSON.parse(row.payload), updated_at: row.updated_at }));
  }

  pushCredentials(profileId, credentials) {
    this.requireProfile(profileId);
    requireJson(credentials, 'array', 262144);
    for (const item of credentials) {
      requireJson(item, 'object', 65536);
      if (jsonType(item.provider) !== 'string' || !PROVIDER.test(item.provider)) throw invalid('Invalid provider');
      const credential = item.credential_json;
      requireJson(credential, 'object', 65536);
      const required = item.provider === 'animeskip' ? 'client_id' : 'api_key';
      if (!(required in credential) || Object.entries(credential).some(([key, value]) => !['api_key', 'client_id'].includes(key) || jsonType(value) !== 'string')) {
        throw invalid('Invalid credential fields');
      }
      this.run(`INSERT INTO records (profile_id, kind, record_key, payload, updated_at) VALUES (?, 'provider_credentials', ?, ?, ?)
        ON CONFLICT (profile_id, kind, record_key) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at`,
        profileId, item.provider, canonicalJson(credential), nowIso());
    }
  }

  pullCredentials(profileId) {
    this.requireProfile(profileId);
    return this.all(`SELECT record_key, payload, updated_at FROM records WHERE profile_id = ? AND kind = 'provider_credentials' ORDER BY record_key`, profileId)
      .map(row => ({ provider: row.record_key, credential_json: JSON.parse(row.payload), updated_at: row.updated_at }));
  }

  registerDevice(installationId, clientName, clientVersion, platform, deviceName) {
    if (chars(clientName ?? '') > 128 || chars(clientVersion ?? '') > 64 || chars(platform ?? '') > 64 || chars(deviceName ?? '') > 256) throw invalid('Invalid device metadata');
    if (installationId === null) throw new PgError('23502', 'null value in column "installation_id" violates not-null constraint');
    if (chars(installationId) < 1 || chars(installationId) > 128) throw checkViolation('devices', 'installation_id');
    this.run(`INSERT INTO devices (installation_id, client_name, client_version, platform, device_name, updated_at) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (installation_id) DO UPDATE SET client_name = excluded.client_name, client_version = excluded.client_version,
        platform = excluded.platform, device_name = excluded.device_name, updated_at = excluded.updated_at`,
      installationId, clientName, clientVersion, platform, deviceName, nowIso());
  }
}

// Parameter specs: a spec without `default` is required, exactly like PostgreSQL function arguments.
const int = def => (def === undefined ? { type: 'integer' } : { type: 'integer', default: def });
const big = def => (def === undefined ? { type: 'bigint' } : { type: 'bigint', default: def });
const text = def => (def === undefined ? { type: 'text' } : { type: 'text', default: def });
const json = { type: 'jsonb' };
const origin = { p_origin_client_id: text(null) };
const profileOnly = { p_profile_id: int() };

const pushPull = (kind, items) => ({
  [`sync_push_${kind === 'library' ? 'library_items' : kind}`]: { params: { p_profile_id: int(), [items]: json, ...origin }, returns: 'void', run: (s, a) => s.pushItems(kind, a.p_profile_id, a[items]) },
  [`sync_delete_${kind === 'library' ? 'library_items' : kind}`]: { params: { p_profile_id: int(), p_keys: json, ...origin }, returns: 'void', run: (s, a) => s.pushItems(kind, a.p_profile_id, a.p_keys, true) },
  [`sync_get_${kind}_delta_cursor`]: { params: profileOnly, returns: 'bigint', run: (s, a) => s.deltaCursor(kind, a.p_profile_id) },
  [`sync_pull_${kind}_delta`]: { params: { p_profile_id: int(), p_since_event_id: big(), p_limit: int(500) }, returns: 'setof', run: (s, a) => s.pullDelta(kind, a.p_profile_id, a.p_since_event_id, a.p_limit) },
});
const blob = (pushName, pullName, kind, payloadParam, field, platform) => ({
  [pushName]: { params: { p_profile_id: int(), ...(platform ? { p_platform: text() } : {}), [payloadParam]: json, ...origin }, returns: 'void', run: (s, a) => s.pushBlob(kind, a.p_profile_id, platform ? a.p_platform : 'collections', a[payloadParam]) },
  [pullName]: { params: { p_profile_id: int(), ...(platform ? { p_platform: text() } : {}) }, returns: 'setof', run: (s, a) => s.pullBlob(kind, a.p_profile_id, platform ? a.p_platform : 'collections', field) },
});
const unsupportedPin = params => ({ params, returns: 'void', run: (s, a) => { s.requireProfile(a.p_profile_id); throw new PgError('0A000', PIN_UNSUPPORTED); } });

export const FUNCTIONS = {
  sync_pull_profiles: { params: {}, returns: 'setof', run: s => s.pullProfiles() },
  sync_push_profiles: { params: { p_profiles: json, p_client_max_profiles: int(6), ...origin }, returns: 'void', run: (s, a) => s.pushProfiles(a.p_profiles, a.p_client_max_profiles) },
  sync_delete_profile_data: { params: { p_profile_id: int(), ...origin }, returns: 'void', run: (s, a) => s.deleteProfileData(a.p_profile_id) },
  sync_pull_profile_locks: { params: {}, returns: 'setof', run: s => s.profileLocks() },
  verify_profile_pin: { params: { p_profile_id: int(), p_pin: text() }, returns: 'setof', run: (s, a) => { s.requireProfile(a.p_profile_id); return [{ unlocked: false, retry_after_seconds: 0, message: PIN_UNSUPPORTED }]; } },
  set_profile_pin: unsupportedPin({ p_profile_id: int(), p_pin: text(), p_current_pin: text(null) }),
  clear_profile_pin: unsupportedPin({ p_profile_id: int(), p_current_pin: text(null) }),
  clear_profile_pin_with_account_password: unsupportedPin({ p_profile_id: int(), p_account_password: text() }),
  sync_push_addons: { params: { p_profile_id: int(), p_addons: json, ...origin }, returns: 'void', run: (s, a) => s.pushExtensions('addons', a.p_profile_id, a.p_addons) },
  sync_push_plugins: { params: { p_profile_id: int(), p_plugins: json, ...origin }, returns: 'void', run: (s, a) => s.pushExtensions('plugins', a.p_profile_id, a.p_plugins) },
  sync_pull_library: { params: { p_profile_id: int(), p_limit: int(500), p_offset: int(0) }, returns: 'setof', run: (s, a) => s.pullItems('library', a.p_profile_id, a.p_limit, a.p_offset) },
  sync_pull_watch_progress: { params: { p_profile_id: int(), p_since_last_watched: big(null), p_limit: int(null) }, returns: 'jsonb', run: (s, a) => s.pullWatchProgress(a.p_profile_id, a.p_since_last_watched, a.p_limit) },
  sync_pull_watched_items: { params: { p_profile_id: int(), p_page: int(1), p_page_size: int(500) }, returns: 'setof', run: (s, a) => s.pullWatchedItems(a.p_profile_id, a.p_page, a.p_page_size) },
  ...pushPull('library', 'p_items'),
  ...pushPull('watch_progress', 'p_entries'),
  ...pushPull('watched_items', 'p_items'),
  ...blob('sync_push_collections', 'sync_pull_collections', 'collections', 'p_collections_json', 'collections_json', false),
  ...blob('sync_push_home_catalog_settings', 'sync_pull_home_catalog_settings', 'home_catalog_settings', 'p_settings_json', 'settings_json', true),
  ...blob('sync_push_profile_settings_blob', 'sync_pull_profile_settings_blob', 'profile_settings_blob', 'p_settings_json', 'settings_json', true),
  sync_push_provider_credentials: { params: { p_profile_id: int(), p_credentials: json, ...origin }, returns: 'void', run: (s, a) => s.pushCredentials(a.p_profile_id, a.p_credentials) },
  sync_pull_provider_credentials: { params: profileOnly, returns: 'setof', run: (s, a) => s.pullCredentials(a.p_profile_id) },
  register_current_device: {
    params: { p_installation_id: text(), p_client_name: text(), p_client_version: text(), p_platform: text(), p_device_name: text() }, returns: 'void',
    run: (s, a) => s.registerDevice(a.p_installation_id, a.p_client_name, a.p_client_version, a.p_platform, a.p_device_name),
  },
};

function coerce(spec, value) {
  if (value === null || value === undefined) return null;
  if (spec.type === 'jsonb') return value;
  if (spec.type === 'text') return typeof value === 'string' ? value : textOf(value);
  const number = typeof value === 'string' && /^-?\d+$/.test(value.trim()) ? Number(value) : value;
  return toInteger(number, spec.type);
}

/** Matches PostgREST: unknown or missing named arguments mean no such function overload. */
export function resolveArguments(name, body) {
  const fn = Object.hasOwn(FUNCTIONS, name) ? FUNCTIONS[name] : null;
  const keys = Object.keys(body);
  const notFound = () => new PgError('PGRST202', `Could not find the function public.${name}(${[...keys].sort().join(', ')}) in the schema cache`);
  if (!fn || keys.some(key => !Object.hasOwn(fn.params, key))) throw notFound();
  const args = {};
  for (const [key, spec] of Object.entries(fn.params)) {
    if (Object.hasOwn(body, key)) args[key] = coerce(spec, body[key]);
    else if ('default' in spec) args[key] = spec.default;
    else throw notFound();
  }
  return { fn, args };
}

function migrate(sql) {
  for (const statement of [
    `CREATE TABLE IF NOT EXISTS profiles (profile_index INTEGER PRIMARY KEY CHECK (profile_index BETWEEN 1 AND 6), id TEXT NOT NULL, name TEXT NOT NULL,
      avatar_color_hex TEXT NOT NULL, avatar_id TEXT, avatar_url TEXT, profile_background_id TEXT, profile_background_url TEXT,
      uses_primary_addons INTEGER NOT NULL DEFAULT 0, uses_primary_plugins INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)`,
    `CREATE TABLE IF NOT EXISTS records (profile_id INTEGER NOT NULL, kind TEXT NOT NULL, record_key TEXT NOT NULL, payload TEXT NOT NULL,
      updated_at TEXT NOT NULL, PRIMARY KEY (profile_id, kind, record_key))`,
    `CREATE TABLE IF NOT EXISTS events (event_id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id INTEGER NOT NULL, kind TEXT NOT NULL,
      operation TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL)`,
    'CREATE INDEX IF NOT EXISTS events_lookup ON events (profile_id, kind, event_id)',
    `CREATE TABLE IF NOT EXISTS extensions (kind TEXT NOT NULL, profile_id INTEGER NOT NULL, url TEXT NOT NULL, name TEXT NOT NULL,
      enabled INTEGER NOT NULL, sort_order INTEGER NOT NULL, PRIMARY KEY (kind, profile_id, url))`,
    `CREATE TABLE IF NOT EXISTS devices (installation_id TEXT PRIMARY KEY, client_name TEXT, client_version TEXT, platform TEXT,
      device_name TEXT, updated_at TEXT NOT NULL)`,
  ]) sql.exec(statement);
  const now = nowIso();
  // Every account starts with its primary profile, as the Supabase signup trigger does.
  sql.exec(`INSERT OR IGNORE INTO profiles (profile_index, id, name, avatar_color_hex, created_at, updated_at) VALUES (1, ?, 'Profile 1', '#1E88E5', ?, ?)`, crypto.randomUUID(), now, now);
}

export class UserDataDO extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => migrate(ctx.storage.sql));
  }

  /** Runs one already-resolved RPC. Returns `{ status, body }` in PostgREST's response shape. */
  async call(userId, name, args) {
    const fn = FUNCTIONS[name];
    try {
      const store = new SyncStore(this.ctx.storage.sql, userId);
      const result = this.ctx.storage.transactionSync(() => fn.run(store, args));
      if (fn.returns === 'void') return { status: 204, body: null };
      return { status: 200, body: result ?? null };
    } catch (error) {
      if (error instanceof PgError) return { status: error.status, body: pgBody(error.code, error.message) };
      throw error;
    }
  }

  async extensionRows(userId, kind) {
    return new SyncStore(this.ctx.storage.sql, userId).extensionRows(kind);
  }
}
