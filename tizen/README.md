# Starlit TV for Samsung Tizen

Independent TV client in this repository's `tizen/` subtree. Adapted from [NuvioTVSmart](https://github.com/NuvioMedia/NuvioTVSmart) at the exact revision in [UPSTREAM.json](UPSTREAM.json), under GPLv3. Copyright and license notices remain intact. Starlit is independent; upstream does not endorse this adaptation.

**NOT DEVICE-VERIFIED.** This is an implementation candidate. No Samsung TV has passed install, playback, local streaming, standby or signed update acceptance. The release workflow fails closed until the exact WGT passes [the acceptance protocol](docs/DEVICE-ACCEPTANCE.md).

Build with Node 24:

```sh
cd tizen
npm ci --ignore-scripts
npm ci --ignore-scripts --prefix engine-runtime
npm run lint
npm test
npm run package:tizen
npm run verify:package
npm run test:browser
```

The unsigned `artifacts/Starlit-Tizen-<version>.wgt` includes the local streaming Web Service and optional PluginService. Required service/runtime checks fail packaging if anything is absent. ZIP timestamps/order are fixed; a second build must match `SHA256SUMS-Tizen`. Account discovery uses Starlit's public HTTPS discovery endpoint; no upstream identity service or credentials are included. A custom HTTPS account server remains available from the account screen.

The TV interface includes Home, Search, Library, title/episode details, source selection, profile switching, collections, Settings and AVPlay controls. Catalog and stream providers come only from manifests the user adds. Sources retain quality/size/provider/seed metadata supplied by add-ons. No offline queue or durable media library is implemented; local torrent pieces use bounded memory and are released after playback ends.

Tizen 4 is direct-only. Tizen 5/5.5 local streaming requires a successful packaged service, trusted MessagePort and HTTP Range probe. Tizen 6+ plugin execution also requires its runtime capability probes. Version numbers are eligibility gates, not proof a particular firmware works. Protected stream and codec/audio/subtitle compatibility must be tested on the target TV.

Account linking uses short-lived, one-use device authorization. Only the human approval code goes in the QR URL. Sessions refresh through Starlit Auth; sign-out attempts device revocation and clears local account data. Per-profile library, add-ons, settings, collections, progress and watched history use the existing scoped RPC contracts. TV preferences have a separate `tv` settings blob. The app's sandboxed credential storage is private to the distinct package ID; it is not claimed to be hardware-encrypted.

MyAnimeList: connect on Starlit desktop, then explicitly select **Sync connection to linked TVs** in desktop Tracking settings. This shares OAuth credentials through the private provider-credential contract (the account server administrator is trusted). In TV Settings → Integrations → MyAnimeList, load the linked account. The TV can manage statuses and update watched episodes only for explicit `mal:` IDs after the watched threshold. Clearing the TV session keeps the desktop connection. Refresh tokens are reconciled through account sync; concurrent external token revocation requires reconnecting.

See [Apps2Samsung installation](docs/INSTALL.md), [port ownership](docs/PORT-MAP.md), [license notices](docs/NOTICES.md), and [device acceptance](docs/DEVICE-ACCEPTANCE.md). Android and desktop download functionality is unchanged.
