/**
 * Trakt device-code authentication is a public-client flow. The TV application
 * cannot protect a bundled OAuth client secret; never send or package one.
 */
export const hasTraktClientId = (clientId) => Boolean(String(clientId || "").trim());

export function traktDeviceTokenRequest(deviceCode, clientId) {
  return { code: deviceCode, client_id: clientId };
}

export function traktRefreshTokenRequest(refreshToken, clientId) {
  return { refresh_token: refreshToken, client_id: clientId, grant_type: "refresh_token" };
}

export function traktRevokeTokenRequest(token, clientId) {
  return { token, client_id: clientId };
}
