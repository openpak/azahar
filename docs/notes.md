# Notes: azahar

## Overview

Azahar for OpenPak is OpenPak's Nintendo 3DS emulator fork, built on [Azahar](https://github.com/azahar-emu/azahar). It is an independent project. Upstream Azahar remains our base, and we merge upstream changes as they land. This is not an official Azahar release.

The fork adds a way to point the emulated console's HTTP traffic at OpenPak, plus the OpenPak sign-in, menu, settings and cloud saves that every OpenPak emulator shares. Everything else is upstream Azahar.

**Status:** Transitional (kept for now, successor planned, no builds advertised).

## Not affiliated

This is an independent project and is not endorsed by the Azahar project. It is not affiliated with Nintendo, and all trademarks belong to their owners.

## Known limitations

- Not yet run against a game.
- No NEX friends or online play, because Azahar has none.
- The account's 3DS identity is shown in the OpenPak window but is not yet written into the emulated console. The system menu still uses the console's own.
- Miiverse and SpotPass panels have no web link, because OpenPak serves Miiverse to consoles only.
- Invitations and News show placeholder panels.

## Contributing and support

Issues: https://github.com/openpak/azahar/issues. For anything that is not specific to OpenPak, upstream Azahar is the better place.

Security problems: report them privately to the OpenPak maintainers through the contact on https://openpak.org. Please do not open a public issue.

The most useful help right now is a first test of the redirect and cloud saves against real titles, with the log attached. Upstream's contribution notes are preserved in `upstream.md`.

## License and acknowledgements

Azahar is licensed under GPL-2.0-or-later; the full text is in `license.txt`. This fork keeps that licence. Azahar continues the work of Citra, and we credit the Azahar and Citra contributors whose code this is built on.

The original upstream README is preserved as [upstream.md](upstream.md).
