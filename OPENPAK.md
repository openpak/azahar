# Azahar for OpenPak

Fork of upstream Azahar with two additions: a way to point the emulated console at OpenPak, and
the OpenPak menu, window and settings every OpenPak emulator shares
(`emulators/prds/openpak-ux-spec.md`, 3DS family). Everything else is upstream, merged as it
moves. Builds: `vX.Y.Z` tags (`v*.*.*`) publish a GitHub Release
(`.github/workflows/openpak_release.yml`; the release is created first and each build attaches
its own file). Not yet run against a game.

## The connection switch

**Configure → General → OpenPak → Connect this emulator to OpenPak** (not while a game runs). It
is the `use_openpak_network` setting: in `qt-config.ini` it sits in the `[Data Storage]` group,
next to `use_custom_storage`. When on, the HTTP service maps the Nintendo Network
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
compiled-in map applies until a profile lands. The fetch runs on its own thread (at launch and
when a game boots), so neither the UI nor a game ever waits for it. Configure → OpenPak →
Advanced has **Refresh network settings** (off the UI thread) and the line "Network settings:
version N, fetched."

## The OpenPak menu, window and settings (UX spec)

- **OpenPak menu**, immediately left of Help (`openpak::qt::AddOpenPakMenu`, from
  `externals/openpak-client`): *Sign in to OpenPak...* / *Signed in as {name}*, Friends,
  Invitations, Cloud saves, Mods, News, Status, *OpenPak settings...* (Configure at the OpenPak
  tab), *OpenPak website*, *Sign out...*. Sign in and sign out wait for the game to stop; with the
  connection off the header opens the settings.
- **The OpenPak window** is the library's (`OpenPakAccountDialog`, `Family::N3DS`): Account (the
  3DS identity rows "Friend code {0}" and the principal id, from `GET /api/v1/me/3ds`), Friends
  (list, requests, add by friend code), Invitations and News show their not-here panels, Cloud
  saves, Mods (LayeredFS: one mod per title in `load/mods/<title id>/`, marked `.openpak-mod`; a
  mod put there by hand is never overwritten), Status.
- **Sign-in** is the library dialog with a Device name (`[UI/OpenPak] openpak_device_name`), off
  the UI thread, errors inline; it mints the website token and then asks for the account's 3DS
  identity. **Sign out** is confirmed and revokes the token. A first interactive launch without
  a game asks *Connect to OpenPak?* once (`openpak_connect_asked`); signing in from it turns on
  the connection and cloud sync.
- **Configure → General → OpenPak** (after Network): the switch, the account row, *Open
  OpenPak...*, *Sync cloud saves automatically...* (`openpak_cloud_sync`, default on), *Show
  notifications* and *Notification corner*, and a collapsed Advanced with the Website
  (`openpak_website`, read at launch) and *Refresh network settings*. These keys live in
  `qt-config.ini` under `[UI]`, group `OpenPak`.
- **Toasts** (library toast, 6 s): signed in / out, an expired stored sign-in, cloud save pulled,
  pushed, push failed and conflict, friends coming online or asking.

## Cloud saves

A 3DS application's save is its SD save folder (`sdmc/Nintendo 3DS/<id>/<id>/title/00040000/
<low>/data`), synced as SaveSync syncs a folder: under platform `3ds`, keyed by the 16-digit
title id, with a version marker beside the folder. Before a game boots, the newest cloud copy
comes down when the local one is not already in step (the old one is kept as
`data.openpak-backup`) -- at most five seconds, with "Checking cloud save..." and Skip; when the
game stops the folder goes up off the UI thread, and closing the emulator waits for it. A local
and a cloud save that never met are a conflict: the game starts on the local save, a toast says
so, automatic sync for it pauses, and the Cloud saves page's *Resolve...* chooses.

## Not yet

- The 3DS identity is shown, not yet written into the emulated console (NASC credentials and
  the friend seed); the system menu still uses the console's own.
- The Wii U/3DS Miiverse and SpotPass panels carry no link: OpenPak serves Miiverse to consoles
  only, and there is no web Miiverse to open.

Server side: the OpenPak network (`account`, `nn-account`, `nn-friends`, `nn-nncs`, `nn-boss`,
`nn-juxtaposition`, `nn-soap` for Wii U and 3DS; `nn-wfc` for Wii and DS) answers both the
Nintendo names and the `openpak.org` names behind one TLS front.
