# Azahar Emulator

OpenPak's Azahar Emulator.

| | |
| --- | --- |
| Console | 3DS |
| Kind | Emulator |
| Based on | [Azahar](https://github.com/azahar-emu/azahar) (GPL-2.0-or-later) |
| Licence | As upstream, see [license.txt](license.txt) |

## What it does

- Connect this emulator to OpenPak (Configure, General, OpenPak). The HTTP service maps the 3DS Nintendo Network hostnames (account, NASC, conntest, Miiverse, BOSS, eShop SOAP) to their OpenPak equivalents before each request.
- Fetches the OpenPak network profile at launch, with a stored last-known-good copy and a compiled-in fallback.
- Shared OpenPak menu and window: sign-in, account, friends, cloud saves, mods, status and settings.
- Cloud saves for each application's SD save folder, keyed by title id.
- Mods through LayeredFS, one mod per title.

## Build

```
git clone --recursive https://github.com/openpak/azahar
```

Build steps are upstream's, see the [Azahar wiki](https://github.com/azahar-emu/azahar/wiki/Building-From-Source). The `externals/openpak-client` submodule is private. See [deploy](docs/deploy.md) for the OpenPak-specific notes.

## Docs

- [Protocol](docs/protocol.md)
- [Evidence](docs/evidence.md)
- [Deploy](docs/deploy.md)
- [Notes](docs/notes.md)
- [Upstream README](docs/upstream.md)

## Licence

Azahar is licensed under GPL-2.0-or-later, see [license.txt](license.txt). This fork keeps that licence. OpenPak is an independent project and is not affiliated with Nintendo.
