// Public client IDs identify an OAuth app, but must be registered with each provider.
// Reject missing or obviously unsafe identifiers before publishing installers.
export const trackingProviderIds = Object.freeze([
  'TRAKT_CLIENT_ID',
  'SIMKL_CLIENT_ID',
  'MDBLIST_CLIENT_ID',
]);

const clientIdPattern = /^[A-Za-z0-9._~-]+$/;
const placeholderPattern = /^(?:example|placeholder|test|dummy|your[-_]?client[-_]?id)$/i;

export function validateTrackingProviderIds(env) {
  const missing = trackingProviderIds.filter((name) => !String(env[name] ?? '').trim());
  const invalid = trackingProviderIds.filter((name) => {
    const raw = String(env[name] ?? '');
    if (!raw.trim()) return false;
    return raw !== raw.trim() || !clientIdPattern.test(raw) || placeholderPattern.test(raw);
  });
  return { missing, invalid };
}

if (process.argv.includes('--require-release')) {
  const { missing, invalid } = validateTrackingProviderIds(process.env);
  if (missing.length || invalid.length) {
    if (missing.length) console.error(`Missing tracking OAuth client ID variables: ${missing.join(', ')}`);
    if (invalid.length) console.error(`Invalid tracking OAuth client ID variables: ${invalid.join(', ')}`);
    console.error('Register provider applications and configure repository Actions variables before publishing a release. See docs/TRACKING-PROVIDERS.md.');
    process.exitCode = 1;
  } else {
    console.log('Tracking OAuth release client ID preflight passed (registration and real sign-in are not verified).');
  }
}
