// Real GoTrue + PostgREST tests. Uses only the publishable key and two ordinary accounts.
// Run against a disposable LOCAL stack: these accounts remain so no admin secret is needed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

const base = process.env.STARLIT_SUPABASE_URL?.replace(/\/$/, '');
const key = process.env.STARLIT_SUPABASE_PUBLISHABLE_KEY;
if (!base || !key) throw new Error('Set STARLIT_SUPABASE_URL and STARLIT_SUPABASE_PUBLISHABLE_KEY in .env');
if (!['127.0.0.1', 'localhost', '[::1]'].includes(new URL(base).hostname)) {
  throw new Error('Integration tests create accounts; use a disposable localhost stack');
}

async function request(path, { token, body, method = body === undefined ? 'GET' : 'POST', headers = {} } = {}) {
  const response = await fetch(base + path, {
    method,
    headers: { apikey: key, ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json', ...headers },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const raw = await response.text();
  return { status: response.status, ok: response.ok, data: raw ? JSON.parse(raw) : null };
}
async function rpc(token, name, body = {}, expectFailure = false) {
  const response = await request(`/rest/v1/rpc/${name}`, { token, body });
  if (expectFailure) assert.equal(response.ok, false, `${name} must reject this request`);
  else assert.equal(response.ok, true, `${name}: ${response.status} ${JSON.stringify(response.data)}`);
  return response.data;
}
async function signup(label) {
  const credentials = { email: `starlit-test-${label}-${randomUUID()}@example.test`, password: `Test-${randomUUID()}!` };
  const response = await request('/auth/v1/signup', { body: credentials });
  assert.equal(response.ok, true, `signup: ${JSON.stringify(response.data)}`);
  assert.ok(response.data.access_token, 'Local confirmations must be disabled for the disposable test stack');
  const login = await request('/auth/v1/token?grant_type=password', { body: credentials });
  assert.equal(login.ok, true, 'Supabase email/password login works');
  assert.equal(login.data.user.id, response.data.user.id);
  return { token: login.data.access_token, secondClientToken: response.data.access_token, id: login.data.user.id, refresh: login.data.refresh_token };
}
const profile = { profile_index: 2, name: 'Second profile', avatar_color_hex: '#E53935', uses_primary_addons: true, uses_primary_plugins: false };
const lib = id => ({ content_id: id, content_type: 'movie', name: `Movie ${id}`, poster_shape: 'POSTER', genres: ['Drama'], added_at: 100 });

test('Starlit sync round trips and isolates two accounts through real HTTP', { timeout: 120000 }, async t => {
  const a = await signup('a');
  const b = await signup('b');

  await t.test('email/password sessions refresh using Supabase Auth', async () => {
    const refreshed = await request('/auth/v1/token?grant_type=refresh_token', { body: { refresh_token: a.refresh } });
    assert.equal(refreshed.ok, true);
    assert.equal(refreshed.data.user.id, a.id);
    a.token = refreshed.data.access_token;
  });
  await t.test('profiles preserve ownership, limits, and omitted profiles', async () => {
    const initial = await rpc(a.token, 'sync_pull_profiles');
    assert.equal(initial.length, 1);
    assert.equal(initial[0].profile_index, 1);
    assert.equal(initial[0].user_id, a.id);
    await rpc(a.token, 'sync_push_profiles', { p_profiles: [{ ...profile, user_id: b.id, id: b.id }], p_client_max_profiles: 6, p_origin_client_id: 'test-a' });
    let rows = await rpc(a.token, 'sync_pull_profiles');
    assert.equal(rows.length, 2);
    assert.equal(rows.find(x => x.profile_index === 2).user_id, a.id);
    assert.notEqual(rows.find(x => x.profile_index === 2).id, b.id);
    await rpc(a.token, 'sync_push_profiles', { p_profiles: [{ ...profile, name: 'Updated' }], p_client_max_profiles: 6 });
    rows = await rpc(a.token, 'sync_pull_profiles');
    assert.equal(rows.length, 2, 'An omitted primary profile is not erased by a snapshot');
    assert.equal(rows.find(x => x.profile_index === 2).name, 'Updated');
    assert.equal((await rpc(b.token, 'sync_pull_profiles')).length, 1);
    await rpc(b.token, 'sync_pull_library', { p_profile_id: 2 }, true);
    await rpc(a.token, 'sync_push_profiles', { p_profiles: [{ ...profile, profile_index: 7 }], p_client_max_profiles: 99 }, true);
    await rpc(a.token, 'sync_delete_profile_data', { p_profile_id: 1 }, true);
    const locks = await rpc(a.token, 'sync_pull_profile_locks');
    assert.ok(locks.every(x => x.pin_enabled === false));
    assert.equal((await rpc(a.token, 'verify_profile_pin', { p_profile_id: 1, p_pin: '1234' }))[0].unlocked, false);
    await rpc(a.token, 'set_profile_pin', { p_profile_id: 1, p_pin: '1234' }, true);
    await rpc(a.token, 'clear_profile_pin', { p_profile_id: 1 }, true);
    await rpc(a.token, 'clear_profile_pin_with_account_password', { p_profile_id: 1, p_account_password: 'never-checked-here' }, true);
  });
  await t.test('addons and plugins replace ordered lists and enforce direct-table RLS', async () => {
    for (const kind of ['addons', 'plugins']) {
      const items = [{ url: 'https://example.test/two', name: 'Two', sort_order: 2 }, { url: 'https://example.test/one', name: 'One', enabled: false, sort_order: 1 }];
      await rpc(a.token, `sync_push_${kind}`, { p_profile_id: 1, [`p_${kind}`]: items });
      let rows = await request(`/rest/v1/${kind}?profile_id=eq.1&order=sort_order`, { token: a.token });
      assert.equal(rows.data.length, 2);
      assert.equal(rows.data[0].name, 'One');
      assert.equal(rows.data[0].enabled, false);
      assert.deepEqual((await request(`/rest/v1/${kind}?profile_id=eq.1`, { token: b.token })).data, []);
      assert.deepEqual((await request(`/rest/v1/${kind}?user_id=eq.${a.id}`, { token: b.token })).data, []);
      assert.equal((await request(`/rest/v1/${kind}`, { token: b.token, body: { user_id: a.id, profile_id: 1, ...items[0] } })).ok, false);
      assert.equal((await request(`/rest/v1/${kind}?profile_id=eq.1`, { token: a.token, method: 'DELETE' })).ok, false);
      await rpc(a.token, `sync_push_${kind}`, { p_profile_id: 1, [`p_${kind}`]: [items[0]] });
      rows = await request(`/rest/v1/${kind}?profile_id=eq.1`, { token: a.token });
      assert.equal(rows.data.length, 1, 'Removed extensions must not reappear');
      await rpc(a.token, `sync_push_${kind}`, { p_profile_id: 1, [`p_${kind}`]: [{ url: 42 }] }, true);
      assert.equal((await request(`/rest/v1/${kind}?profile_id=eq.1`, { token: a.token })).data.length, 1, 'Failed replacement is atomic');
    }
  });
  await t.test('library snapshots, pagination, stale writes, deltas and explicit deletes', async () => {
    assert.equal(await rpc(a.token, 'sync_get_library_delta_cursor', { p_profile_id: 1 }), 0);
    await rpc(a.token, 'sync_push_library_items', { p_profile_id: 1, p_items: [lib('tt1'), lib('tt2'), { ...lib('tt1'), content_type: 'series' }], p_origin_client_id: 'test-a' });
    const first = await rpc(a.token, 'sync_pull_library', { p_profile_id: 1, p_limit: 2, p_offset: 0 });
    const second = await rpc(a.token, 'sync_pull_library', { p_profile_id: 1, p_limit: 2, p_offset: 2 });
    assert.equal(first.length, 2);
    assert.equal(second.length, 1);
    assert.equal(new Set([...first, ...second].map(x => x.content_id + x.content_type)).size, 3);
    assert.deepEqual(await rpc(b.token, 'sync_pull_library', { p_profile_id: 1 }), []);
    let events = await rpc(a.token, 'sync_pull_library_delta', { p_profile_id: 1, p_since_event_id: 0, p_limit: 2 });
    assert.equal(events.length, 2);
    const rest = await rpc(a.token, 'sync_pull_library_delta', { p_profile_id: 1, p_since_event_id: events.at(-1).event_id });
    assert.equal(rest.length, 1);
    assert.ok(rest[0].event_id > events.at(-1).event_id);
    const cursor = await rpc(a.token, 'sync_get_library_delta_cursor', { p_profile_id: 1 });
    await rpc(a.token, 'sync_push_library_items', { p_profile_id: 1, p_items: [{ ...lib('tt1'), added_at: 10, name: 'Old' }] });
    assert.deepEqual(await rpc(a.token, 'sync_pull_library_delta', { p_profile_id: 1, p_since_event_id: cursor }), []);
    await rpc(a.token, 'sync_delete_library_items', { p_profile_id: 1, p_keys: [{ content_id: 'tt1', content_type: 'movie' }] });
    events = await rpc(a.token, 'sync_pull_library_delta', { p_profile_id: 1, p_since_event_id: cursor });
    assert.equal(events.length, 1);
    assert.equal(events[0].operation, 'delete');
    assert.equal(events[0].content_type, 'movie');
    assert.equal((await rpc(a.token, 'sync_pull_library', { p_profile_id: 1 })).length, 2);
    assert.equal(await rpc(b.token, 'sync_get_library_delta_cursor', { p_profile_id: 1 }), 0);
    assert.deepEqual(await rpc(b.token, 'sync_pull_library_delta', { p_profile_id: 1, p_since_event_id: 0 }), []);
  });
  await t.test('watch progress uses episode keys, monotonic playback timestamps, and tombstones', async () => {
    const entry = { content_id: 'ttseries', content_type: 'series', video_id: 'ttseries:1:1', season: 1, episode: 1, position: 12000, duration: 120000, last_watched: 200, progress_key: 'ttseries:1:1' };
    await rpc(a.token, 'sync_push_watch_progress', { p_profile_id: 1, p_entries: [entry] });
    assert.deepEqual(await rpc(a.token, 'sync_pull_watch_progress', { p_profile_id: 1 }), [entry]);
    assert.deepEqual(await rpc(b.token, 'sync_pull_watch_progress', { p_profile_id: 1 }), []);
    const cursor = await rpc(a.token, 'sync_get_watch_progress_delta_cursor', { p_profile_id: 1 });
    await rpc(a.token, 'sync_push_watch_progress', { p_profile_id: 1, p_entries: [{ ...entry, position: 1, last_watched: 100 }] });
    assert.equal((await rpc(a.token, 'sync_pull_watch_progress', { p_profile_id: 1 }))[0].position, 12000);
    assert.deepEqual(await rpc(a.token, 'sync_pull_watch_progress', { p_profile_id: 1, p_since_last_watched: 200 }), []);
    assert.deepEqual(await rpc(a.token, 'sync_pull_watch_progress_delta', { p_profile_id: 1, p_since_event_id: cursor }), []);
    await rpc(a.token, 'sync_delete_watch_progress', { p_profile_id: 1, p_keys: [entry.progress_key] });
    const events = await rpc(a.token, 'sync_pull_watch_progress_delta', { p_profile_id: 1, p_since_event_id: cursor });
    assert.equal(events[0].operation, 'delete');
    assert.equal(events[0].progress_key, entry.progress_key);
    assert.deepEqual(await rpc(a.token, 'sync_pull_watch_progress', { p_profile_id: 1 }), []);
    assert.deepEqual(await rpc(b.token, 'sync_pull_watch_progress_delta', { p_profile_id: 1, p_since_event_id: 0 }), []);
    assert.equal(await rpc(b.token, 'sync_get_watch_progress_delta_cursor', { p_profile_id: 1 }), 0);
  });
  await t.test('watched items use one-based pages and episode-aware deletes without content_type', async () => {
    const watched = [1, 2].map(episode => ({ content_id: 'ttseries', content_type: 'series', season: 1, episode, title: 'Series', watched_at: 500 }));
    await rpc(a.token, 'sync_push_watched_items', { p_profile_id: 1, p_items: watched });
    assert.equal((await rpc(a.token, 'sync_pull_watched_items', { p_profile_id: 1, p_page: 1, p_page_size: 1 }))[0].episode, 1);
    assert.equal((await rpc(a.token, 'sync_pull_watched_items', { p_profile_id: 1, p_page: 2, p_page_size: 1 }))[0].episode, 2);
    assert.deepEqual(await rpc(a.token, 'sync_pull_watched_items', { p_profile_id: 1, p_page: 3, p_page_size: 1 }), []);
    assert.deepEqual(await rpc(b.token, 'sync_pull_watched_items', { p_profile_id: 1 }), []);
    const cursor = await rpc(a.token, 'sync_get_watched_items_delta_cursor', { p_profile_id: 1 });
    await rpc(a.token, 'sync_delete_watched_items', { p_profile_id: 1, p_keys: [{ content_id: 'ttseries', season: 1, episode: 1 }] });
    const events = await rpc(a.token, 'sync_pull_watched_items_delta', { p_profile_id: 1, p_since_event_id: cursor });
    assert.equal(events[0].operation, 'delete');
    assert.equal(events[0].episode, 1);
    assert.equal((await rpc(a.token, 'sync_pull_watched_items', { p_profile_id: 1 })).length, 1);
    assert.equal(await rpc(b.token, 'sync_get_watched_items_delta_cursor', { p_profile_id: 1 }), 0);
    assert.deepEqual(await rpc(b.token, 'sync_pull_watched_items_delta', { p_profile_id: 1, p_since_event_id: 0 }), []);
  });
  await t.test('progress snapshots never truncate at the PostgREST 1000-row cap', async () => {
    const entries = Array.from({ length: 1001 }, (_, i) => ({ content_id: `bulk-${i}`, content_type: 'movie', video_id: `bulk-${i}`, progress_key: `bulk-${i}`, position: 100, duration: 1000, last_watched: 1000 + i }));
    await rpc(a.token, 'sync_push_watch_progress', { p_profile_id: 1, p_entries: entries.slice(0, 1000) });
    await rpc(a.token, 'sync_push_watch_progress', { p_profile_id: 1, p_entries: entries.slice(1000) });
    const snapshot = await rpc(a.token, 'sync_pull_watch_progress', { p_profile_id: 1 });
    assert.equal(snapshot.length, 1001);
    assert.equal((await rpc(a.token, 'sync_pull_watch_progress', { p_profile_id: 1, p_limit: 2 })).length, 2);
    assert.deepEqual(await rpc(b.token, 'sync_pull_watch_progress', { p_profile_id: 1 }), []);
    await rpc(a.token, 'sync_delete_watch_progress', { p_profile_id: 1, p_keys: entries.slice(0, 1000).map(x => x.progress_key) });
    await rpc(a.token, 'sync_delete_watch_progress', { p_profile_id: 1, p_keys: entries.slice(1000).map(x => x.progress_key) });
    assert.deepEqual(await rpc(a.token, 'sync_pull_watch_progress', { p_profile_id: 1 }), []);
  });
  await t.test('collections, home catalog settings, platform blobs, credentials round trip independently', async () => {
    const collections = [{ id: 'favorites', name: 'Favorites', items: [{ id: 'tt1', type: 'movie' }] }];
    await rpc(a.token, 'sync_push_collections', { p_profile_id: 1, p_collections_json: collections });
    assert.deepEqual((await rpc(a.token, 'sync_pull_collections', { p_profile_id: 1 }))[0].collections_json, collections);
    assert.deepEqual(await rpc(b.token, 'sync_pull_collections', { p_profile_id: 1 }), []);
    const settings = { show_catalog_type: true, hide_unreleased_content: false, items: [{ addon_id: 'a', type: 'movie', catalog_id: 'top', enabled: false, order: 3 }] };
    await rpc(a.token, 'sync_push_home_catalog_settings', { p_profile_id: 1, p_platform: 'home_catalog_shared', p_settings_json: settings });
    assert.deepEqual((await rpc(a.token, 'sync_pull_home_catalog_settings', { p_profile_id: 1, p_platform: 'home_catalog_shared' }))[0].settings_json, settings);
    assert.deepEqual(await rpc(b.token, 'sync_pull_home_catalog_settings', { p_profile_id: 1, p_platform: 'home_catalog_shared' }), []);
    for (const platform of ['mobile', 'desktop']) {
      await rpc(a.token, 'sync_push_profile_settings_blob', { p_profile_id: 1, p_platform: platform, p_settings_json: { version: 4, features: { theme_settings: { theme: platform }, tmdb_settings: { tmdb_api_key: 'must-be-stripped', language: 'en' } } } });
      const blob = (await rpc(a.token, 'sync_pull_profile_settings_blob', { p_profile_id: 1, p_platform: platform }))[0].settings_json;
      assert.equal(blob.features.theme_settings.theme, platform);
      assert.deepEqual(blob.features.tmdb_settings, { language: 'en' });
      assert.deepEqual(await rpc(b.token, 'sync_pull_profile_settings_blob', { p_profile_id: 1, p_platform: platform }), []);
    }
    const credentials = [{ provider: 'tmdb', credential_json: { api_key: 'test-only-private-key' } }, { provider: 'animeskip', credential_json: { client_id: 'test-only-id' } }, { provider: 'debrid:real-debrid', credential_json: { api_key: 'test-only-debrid-key' } }];
    await rpc(a.token, 'sync_push_provider_credentials', { p_profile_id: 1, p_credentials: credentials });
    const rows = await rpc(a.token, 'sync_pull_provider_credentials', { p_profile_id: 1 });
    assert.equal(rows.length, 3);
    assert.equal(rows.find(x => x.provider === 'tmdb').credential_json.api_key, 'test-only-private-key');
    assert.deepEqual(await rpc(b.token, 'sync_pull_provider_credentials', { p_profile_id: 1 }), []);
    await rpc(a.token, 'sync_push_provider_credentials', { p_profile_id: 1, p_credentials: [{ provider: 'tmdb', credential_json: { api_key: '' } }] });
    const cleared = await rpc(a.token, 'sync_pull_provider_credentials', { p_profile_id: 1 });
    assert.equal(cleared.length, 3, 'Partial client snapshots preserve unknown/newer provider types');
    assert.equal(cleared.find(x => x.provider === 'tmdb').credential_json.api_key, '');
    await rpc(a.token, 'register_current_device', { p_installation_id: randomUUID(), p_client_name: 'Starlit', p_client_version: 'test', p_platform: 'mobile', p_device_name: 'HTTP integration' });
  });
  await t.test('malformed mutations roll back and untrusted arguments cannot select another account', async () => {
    const before = await rpc(a.token, 'sync_get_library_delta_cursor', { p_profile_id: 1 });
    await rpc(a.token, 'sync_push_library_items', { p_profile_id: 1, p_items: [lib('rollback'), { content_id: 123 }] }, true);
    assert.equal(await rpc(a.token, 'sync_get_library_delta_cursor', { p_profile_id: 1 }), before);
    assert.equal((await rpc(a.token, 'sync_pull_library', { p_profile_id: 1 })).some(x => x.content_id === 'rollback'), false);
    await rpc(a.token, 'sync_push_library_items', { p_profile_id: 1, p_items: {} }, true);
    await rpc(a.token, 'sync_push_library_items', { p_profile_id: 1, p_items: [{ ...lib('bad'), genres: [true] }] }, true);
    await rpc(a.token, 'sync_push_library_items', { p_profile_id: 1, p_items: [{ ...lib('bad'), added_at: -1 }] }, true);
    await rpc(a.token, 'sync_push_watch_progress', { p_profile_id: 1, p_entries: [{ content_id: 'x', content_type: 'movie', video_id: 'x', progress_key: 'x', position: -1 }] }, true);
    await rpc(a.token, 'sync_push_watched_items', { p_profile_id: 1, p_items: [{ content_id: 'x', content_type: 'series', season: 1.5 }] }, true);
    await rpc(a.token, 'sync_pull_library', { p_profile_id: 1, p_offset: -1 }, true);
    await rpc(a.token, 'sync_pull_library_delta', { p_profile_id: 1, p_since_event_id: -1 }, true);
    await rpc(a.token, 'sync_pull_watched_items', { p_profile_id: 1, p_page: 0 }, true);
    await rpc(a.token, 'sync_push_collections', { p_profile_id: 1, p_collections_json: {} }, true);
    await rpc(a.token, 'sync_push_profile_settings_blob', { p_profile_id: 1, p_platform: 'wrong', p_settings_json: {} }, true);
    await rpc(a.token, 'sync_push_provider_credentials', { p_profile_id: 1, p_credentials: [{ provider: 'tmdb', credential_json: { api_key: 123 } }] }, true);
    await rpc(b.token, 'sync_pull_provider_credentials', { p_profile_id: 1, p_user_id: a.id }, true);
    assert.equal((await request('/rest/v1/records', { token: a.token })).ok, false);
    assert.equal((await request('/rest/v1/profiles', { token: a.token, headers: { 'Accept-Profile': 'starlit_private' } })).ok, false);
  });
  await t.test('a second session on the same account reads and updates the shared server state', async () => {
    for (const [name, extra] of [
      ['sync_pull_library', {}], ['sync_pull_watch_progress', {}], ['sync_pull_watched_items', {}],
      ['sync_pull_collections', {}], ['sync_pull_provider_credentials', {}],
      ['sync_pull_profile_settings_blob', { p_platform: 'mobile' }],
      ['sync_pull_profile_settings_blob', { p_platform: 'desktop' }],
      ['sync_pull_home_catalog_settings', { p_platform: 'home_catalog_shared' }],
    ]) {
      const params = { p_profile_id: 1, ...extra };
      assert.deepEqual(await rpc(a.secondClientToken, name, params), await rpc(a.token, name, params));
    }
    for (const kind of ['addons', 'plugins']) {
      assert.deepEqual((await request(`/rest/v1/${kind}?profile_id=eq.1`, { token: a.secondClientToken })).data,
        (await request(`/rest/v1/${kind}?profile_id=eq.1`, { token: a.token })).data);
    }
    await rpc(a.secondClientToken, 'sync_push_library_items', { p_profile_id: 1, p_items: [lib('from-second-client')] });
    assert.ok((await rpc(a.token, 'sync_pull_library', { p_profile_id: 1 })).some(x => x.content_id === 'from-second-client'));
    assert.deepEqual(await rpc(b.token, 'sync_pull_library', { p_profile_id: 1 }), []);
  });
  await t.test('concurrent writes produce ordered durable delta events', async () => {
    const cursor = await rpc(a.token, 'sync_get_library_delta_cursor', { p_profile_id: 1 });
    await Promise.all(Array.from({ length: 8 }, (_, i) => rpc(a.token, 'sync_push_library_items', { p_profile_id: 1, p_items: [lib(`parallel-${i}`)] })));
    const events = await rpc(a.token, 'sync_pull_library_delta', { p_profile_id: 1, p_since_event_id: cursor });
    assert.equal(events.length, 8);
    assert.equal(new Set(events.map(x => x.content_id)).size, 8);
    assert.ok(events.every((event, i) => i === 0 || event.event_id > events[i - 1].event_id));
    assert.equal(await rpc(a.token, 'sync_get_library_delta_cursor', { p_profile_id: 1 }), events.at(-1).event_id);
  });
  await t.test('profile deletion cascades records without touching the other account', async () => {
    await rpc(a.token, 'sync_push_library_items', { p_profile_id: 2, p_items: [lib('second-profile')] });
    await rpc(a.token, 'sync_push_provider_credentials', { p_profile_id: 2, p_credentials: [{ provider: 'tmdb', credential_json: { api_key: 'profile-two-key' } }] });
    await rpc(a.token, 'sync_push_addons', { p_profile_id: 2, p_addons: [{ url: 'https://example.test/profile-two' }] });
    await rpc(a.token, 'sync_delete_profile_data', { p_profile_id: 2 });
    assert.equal((await rpc(a.token, 'sync_pull_profiles')).length, 1);
    await rpc(a.token, 'sync_push_profiles', { p_profiles: [profile] });
    assert.deepEqual(await rpc(a.token, 'sync_pull_library', { p_profile_id: 2 }), []);
    assert.deepEqual(await rpc(a.token, 'sync_pull_provider_credentials', { p_profile_id: 2 }), []);
    assert.deepEqual((await request('/rest/v1/addons?profile_id=eq.2', { token: a.token })).data, []);
    const events = await rpc(a.token, 'sync_pull_library_delta', { p_profile_id: 2, p_since_event_id: 0 });
    assert.deepEqual(events.map(x => x.operation), ['upsert', 'delete']);
    assert.equal((await rpc(b.token, 'sync_pull_profiles'))[0].user_id, b.id);
  });
  await t.test('all public RPCs reject requests without an authenticated user', async () => {
    const spec = await request('/rest/v1/', { token: a.token, headers: { Accept: 'application/openapi+json' } });
    assert.equal(spec.ok, true);
    const functions = Object.entries(spec.data.paths).filter(([path]) => path.startsWith('/rpc/'));
    assert.ok(functions.length >= 30, 'PostgREST exposes the complete contract');
    for (const [path, operations] of functions) {
      const props = operations.post?.parameters?.find(x => x.in === 'body')?.schema?.properties || {};
      const params = Object.fromEntries(Object.keys(props).map(name => [name, name === 'p_profile_id' ? 1 : null]));
      const response = await request(`/rest/v1${path}`, { body: params });
      assert.equal(response.ok, false, `${path} must deny anonymous requests`);
    }
    const validAnonymousCall = await request('/rest/v1/rpc/sync_pull_profiles', { body: {} });
    assert.equal(validAnonymousCall.status, 401, 'A syntactically valid anonymous call must fail permission checking');
  });
});
