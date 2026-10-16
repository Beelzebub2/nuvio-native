Nuvio Native 1.0.9 improves MyAnimeList sign-in, episode synopsis popups, and the built-in Airing Now schedule.

- MyAnimeList now supports both Other/public applications and Web applications with a client secret. Web secrets are stored with the existing Windows DPAPI-protected, profile-specific tokens and survive token refresh. The connection setup explains the exact loopback redirect URL and why MyAnimeList displays the registered website on its approval page.
- A declined or invalid MyAnimeList authorization now reports an error promptly instead of waiting for the full callback timeout. Occupied callback ports have a specific error, and a temporary account-name lookup failure no longer discards an otherwise successful sign-in.
- Episode synopsis hover cards now use the Nuvio theme, readable typography, a bounded description, and cleaned-up provider markup. They no longer duplicate the episode title or interfere with middle-click Watch Later interactions.
- Built-in Airing Now settings now include a saved **Hide NSFW / hentai anime** checkbox, enabled by default for both new and existing settings. Broadcast, subtitle, and dub feeds, the expanded catalog, and dated schedules follow the preference. Turning the filter off explicitly allows adult results where the source provides them.
- The adult filter uses AniList's `isAdult` classification, explicit Hentai genres, and the release provider's default adult exclusions. Mixed episode classifications cannot expose a flagged title; switching the preference invalidates previously cached schedules and native details.
- The bundled gateway source now requests AniList adult flags and has a separate adult-enabled release-schedule route with independent caching. Deploy the gateway separately to use that route; the desktop app has a direct-provider fallback in the meantime.

The Windows release is built and tested automatically when this version's Git tag is pushed. Windows installers retain SHA-256 checksums and private release distribution; they are not Authenticode-signed.

MyAnimeList account authorization still requires a registered client and the user's approval. The API application's callback URL must be `http://127.0.0.1:54328/myanimelist/callback`.
