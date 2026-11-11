# Install Starlit TV on a Samsung developer device

Use a trusted local network. Enable Developer Mode on the TV, enter the
computer's LAN IPv4 address in **Host PC IP**, and restart the TV.

Validate access with `apps2samsung-cli tv <TV_IP>`. Build the unsigned WGT
from `tizen/` on a development PC with Node 24:

```sh
npm ci --ignore-scripts
npm ci --ignore-scripts --prefix engine-runtime
npm run lint
npm test
npm run package:tizen
npm run verify:package
```

Install with the Apps2Samsung CLI:

```sh
apps2samsung-cli install artifacts/Starlit-Tizen-0.1.0.wgt --tv <TV_IP> --tizen-cert public --launch
apps2samsung-cli installed --tv <TV_IP>
```

Tizen 5.0 hardware accepted a Public development certificate; other firmware
may require another profile. The WGT remains unsigned in CI so a developer
can sign it for an individual TV. Do not commit signing material or expose
the TV's developer port to the internet. Install success is not a playback
certification; follow [the physical-device checklist](DEVICE-ACCEPTANCE.md).
