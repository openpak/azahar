# Protocol: azahar

## Host map and network profile

- A "Connect this emulator to OpenPak" switch (Configure, General, OpenPak). When on, the HTTP service maps the Nintendo Network hostnames the 3DS uses (account, NASC, conntest, Miiverse, BOSS, eShop SOAP) to their OpenPak equivalents before each request. Built, not yet exercised by a game.
- A launch-time fetch of the OpenPak network profile that rewrites that host map, with whole-profile validation, a stored last-known-good copy and a compiled-in fallback. A "Refresh network settings" button re-runs it. Built, not yet exercised by a game.
