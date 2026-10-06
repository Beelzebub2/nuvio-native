Nuvio Native 1.0.4 adds automatic P2P recovery after sleep and network changes.

- Active torrent sessions detect wake-up gaps and changes to Wi-Fi, Ethernet, or VPN interfaces. Recovery waits for the new interfaces to settle, then rebuilds the owned engine and re-adds the same source.
- Playback reconnects to the new local stream URL at the saved position and retains its paused or playing state.
- Repeated local engine failures trigger bounded recovery. A torrent with no peers does not trigger a restart loop.
- Windows torrent listeners choose a port available for both TCP and UDP, avoiding the engine's random-port collision failure. Startup waits for the settings API; settings changes have a separate bounded timeout.
- Local engine requests now support cancellation and use shorter timeouts. Source changes and delayed cleanup cannot stop a newer stream.
- The bundled engine is unchanged; recovery restarts only Nuvio's own process and retains user files and preferences.

Windows releases and authenticated private updates continue to use the tag workflow and SHA-256 verification.

This installer has no Windows Authenticode certificate. Source and upstream GPLv3 notices remain in this private repository.
