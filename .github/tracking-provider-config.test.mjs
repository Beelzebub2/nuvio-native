import assert from 'node:assert/strict';
import test from 'node:test';
import { trackingProviderIds, validateTrackingProviderIds } from './tracking-provider-config.mjs';

const registered = {
  TRAKT_CLIENT_ID: 'registeredTrakt_123',
  SIMKL_CLIENT_ID: 'registeredSimkl-123',
  MDBLIST_CLIENT_ID: 'registeredMdbList.123',
};

test('release requires a registered-looking public ID for each provider', () => {
  assert.deepEqual(validateTrackingProviderIds(registered), { missing: [], invalid: [] });
  assert.deepEqual(trackingProviderIds, ['TRAKT_CLIENT_ID', 'SIMKL_CLIENT_ID', 'MDBLIST_CLIENT_ID']);
});

test('reports only the missing variable names, without logging values', () => {
  assert.deepEqual(validateTrackingProviderIds({ TRAKT_CLIENT_ID: 'id1' }), {
    missing: ['SIMKL_CLIENT_ID', 'MDBLIST_CLIENT_ID'],
    invalid: [],
  });
});

test('refuses obvious placeholder and unsafe client identifiers', () => {
  const config = { ...registered, SIMKL_CLIENT_ID: 'your-client-id', MDBLIST_CLIENT_ID: 'bad"\ncode' };
  assert.deepEqual(validateTrackingProviderIds(config), {
    missing: [],
    invalid: ['SIMKL_CLIENT_ID', 'MDBLIST_CLIENT_ID'],
  });
});
