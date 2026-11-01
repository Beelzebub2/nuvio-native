package com.nuvio.app.features.updater

import java.net.URI

/** Public releases never require retrieving or forwarding the user's GitHub CLI token. */
internal fun isPublicReleaseAssetUri(uri: URI): Boolean =
    uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) && uri.query == null && uri.fragment == null &&
        uri.path.matches(Regex("/Beelzebub2/nuvio-native/releases/download/v[0-9]+\\.[0-9]+\\.[0-9]+/[a-zA-Z0-9._-]+"))

internal fun isAllowedReleaseRedirectUri(uri: URI): Boolean =
    uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
        (uri.port == -1 || uri.port == 443) &&
        (isPublicReleaseAssetUri(uri) || uri.host in setOf(
            "release-assets.githubusercontent.com", "objects.githubusercontent.com",
        ))
