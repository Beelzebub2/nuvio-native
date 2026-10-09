Nuvio Native 1.0.6 keeps the P2P engine ready and adds personal title tracking and episode-level Watch Later.

- When P2P is enabled, the owned engine starts in the background after the active profile loads. It stays ready between videos; leaving the player releases the torrent, while app exit stops the process. Disabling P2P closes it, and profile/settings changes refresh its configuration.
- TCP and UDP listeners are checked together on up to 128 random server ports outside Windows' default ephemeral range. Native startup retries with fresh ports. Existing JSON settings are updated atomically before launch, preserving unrelated preferences; unchanged settings no longer trigger a client rebuild.
- Sleep/network recovery and bounded retries remain active, retaining playback position and pause state. An idle readiness monitor also rebuilds the engine after sleep or a stable network change before the next playback, while ordinary idle time retains the same process. Torrent availability and metadata retrieval still depend on peers and the network.
- My List in the top navigation stores Watching, Completed, On Hold, Dropped, Plan to Watch, and personal ratings from 1–10 for each profile. Use the controls on a title's details page or edit entries directly in My List.
- Middle-click an episode to save that exact episode. Catalog posters save the current unfinished or next playable episode when metadata is available. Continue Watching cards retain their specific episode. Several episodes of one anime can be saved independently.
- Watch Later shows episode titles, unfinished progress, and a Resume action. Existing title-only saves are retained and resolved to episodes when their metadata becomes available; unresolved entries open details to choose an episode.

Windows tag releases and authenticated private updates retain SHA-256 verification.

This installer has no Windows Authenticode certificate. Source and upstream GPLv3 notices remain in this private repository.

Implementation references: [TorrServer client/settings lifecycle](https://github.com/YouROK/TorrServer/blob/master/server/torr/apihelper.go), [Microsoft socket binding guidance](https://learn.microsoft.com/windows/win32/winsock/using-so-reuseaddr-and-so-exclusiveaddruse).
