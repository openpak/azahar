# Azahar for OpenPak

Fork of upstream Azahar with one addition: a way to point the emulated console at OpenPak.
Everything else is upstream, merged as it moves. Builds: `openpak-v*` tags publish a GitHub
Release (`.github/workflows/openpak_release.yml`). Not yet run against a game.

A `use_openpak_network` setting (qt-config.ini, `[System]` block written next to
`use_custom_storage`; no dialog yet). When on, the HTTP service maps the Nintendo Network
hostnames the 3DS uses (account, NASC, conntest, Miiverse, BOSS, eShop SOAP) to their
`openpak.org` twins before every request, so the system apps reach OpenPak's 3DS adapter and
content services. Azahar does not emulate NEX friends or online play, so this covers the
account and content side only.

Since 2026-09-12 the host map is not only compiled in: when the OpenPak network is on,
`System::Init` fetches the OpenPak **network profile** (`GET
openpak.org/api/v1/network/profile?platform=3ds`, one conditional GET, two-second timeout —
emulators/prds/emulator-network-profile-prd.md) and rewrites http_c's map from its redirect
lists: the longest applied family rewrites the Nintendo suffix to `.openpak.org`, the way the
compiled-in map pairs names; never-names reach the real internet untouched. A fetched profile
is rejected whole unless every redirect entry sits inside the compiled-in families, the server
address is a usable literal and the platform is 3ds; last-known-good beats a fetch, and the
compiled-in map applies until a profile lands. Network settings has a **Refresh network
settings** button and a line showing the source and version in effect.

Server side: the OpenPak network (`account`, `nn-account`, `nn-friends`, `nn-nncs`, `nn-boss`,
`nn-juxtaposition`, `nn-soap` for Wii U and 3DS; `nn-wfc` for Wii and DS) answers both the
Nintendo names and the `openpak.org` names behind one TLS front.
