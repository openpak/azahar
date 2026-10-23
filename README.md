# Azahar for OpenPak

Azahar for OpenPak is OpenPak's Nintendo 3DS emulator fork, built on [Azahar](https://github.com/azahar-emu/azahar). It is an independent project. Upstream Azahar remains our base, and we merge upstream changes as they land. This is not an official Azahar release.

The fork adds a way to point the emulated console's HTTP traffic at OpenPak, plus the OpenPak sign-in, menu, settings and cloud saves that every OpenPak emulator shares. Everything else is upstream Azahar.

**Status:** Transitional (kept for now, successor planned, no builds advertised).

## Not affiliated

This is an independent project and is not endorsed by the Azahar project. It is not affiliated with Nintendo, and all trademarks belong to their owners.

## What works today

Sourced from the fork's own notes. None of this has been run against a game yet.

- A "Connect this emulator to OpenPak" switch (Configure, General, OpenPak). When on, the HTTP service maps the Nintendo Network hostnames the 3DS uses (account, NASC, conntest, Miiverse, BOSS, eShop SOAP) to their OpenPak equivalents before each request. Built, not yet exercised by a game.
- A launch-time fetch of the OpenPak network profile that rewrites that host map, with whole-profile validation, a stored last-known-good copy and a compiled-in fallback. A "Refresh network settings" button re-runs it. Built, not yet exercised by a game.
- The shared OpenPak menu and window: website sign-in and sign-out, account page, friends list, cloud saves, mods, status and settings. Built.
- Cloud saves for the SD save folder of each application, keyed by title id, with a backup of the replaced local copy and a conflict flow. Built, not yet exercised by a game.
- Mods through LayeredFS, one mod per title, from the OpenPak window.

Not verified: any eShop, BOSS or Miiverse request through the redirect, any save sync against a real title, and any hardware behaviour.

## Connecting to OpenPak

Create an account at https://openpak.org. In the emulator, use the OpenPak menu (left of Help) to sign in, then turn on the connection switch under Configure, General, OpenPak. The connection cannot be changed while a game is running. A first interactive launch asks once whether to connect.

You must supply your own 3DS games and any console data you need, dumped from hardware you own. OpenPak provides none and never will.

Azahar does not emulate NEX, so friends and online play are not available. This fork covers the account and content side only.

## Downloads

No public build yet. Release tags exist and the repository has a release workflow, but no release has been published and none is advertised while this fork is transitional. Check https://github.com/openpak/azahar/releases for any change.

## Building from source

Build instructions are upstream's: see the Azahar wiki page "Building From Source" at https://github.com/azahar-emu/azahar/wiki/Building-From-Source.

OpenPak-specific notes:

- Clone with submodules (`git clone --recursive`). The fork adds a submodule, `externals/openpak-client`, which is currently a private repository. Without access to it the OpenPak code does not build, so outside contributors cannot yet build this fork as it stands.
- Release builds run from `v*.*.*` tags through `.github/workflows/openpak_release.yml`.

## Known limitations

- Not yet run against a game.
- No NEX friends or online play, because Azahar has none.
- The account's 3DS identity is shown in the OpenPak window but is not yet written into the emulated console. The system menu still uses the console's own.
- Miiverse and SpotPass panels have no web link, because OpenPak serves Miiverse to consoles only.
- Invitations and News show placeholder panels.

## Contributing and support

Issues: https://github.com/openpak/azahar/issues. For anything that is not specific to OpenPak, upstream Azahar is the better place.

Security problems: report them privately to the OpenPak maintainers through the contact on https://openpak.org. Please do not open a public issue.

The most useful help right now is a first test of the redirect and cloud saves against real titles, with the log attached. Upstream's contribution notes are preserved in `README.upstream.md`.

## License and acknowledgements

Azahar is licensed under GPL-2.0-or-later; the full text is in `license.txt`. This fork keeps that licence. Azahar continues the work of Citra, and we credit the Azahar and Citra contributors whose code this is built on.

The original upstream README is preserved as [README.upstream.md](README.upstream.md).
