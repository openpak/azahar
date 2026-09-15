# Next session — azahar

Updated 2026-09-15.

Upstream Azahar (3DS, citra family) plus one OpenPak addition: point the emulated console's
HTTP at OpenPak. The emulator has no NEX friends or online play at all, so the fork covers
the account and content side only. Released through `openpak-v0.1.2`; the network-profile
work and the expanded release recipes are committed but untagged.

## Where things stand

- `use_openpak_network` setting maps the 3DS HTTP hostnames to their openpak.org twins
  (OPENPAK.md).
- EP-4/EP-5 (303e02e, 2026-09-12): one conditional `GET /api/v1/network/profile?platform=3ds`
  per boot rewrites http_c's host map from the profile's redirect lists; whole-profile
  validation, last-known-good, compiled-in fallback; refresh button and source/version line
  in Network settings. Untagged.
- Release recipes for macOS universal, Windows (mxe) and Android from upstream's recipe
  (c8fadb5). Untagged.
- Not yet run against a game.

## Next steps

- Local build of HEAD, then cut the next `openpak-v*` tag with EP-4/EP-5 and the new jobs.
- First against-a-game run: eShop SOAP / BOSS / Miiverse through the profile-driven map.
- E4 per the integration PRD: sign-in that mints a 3DS identity (nn-account emulator surface
  NA-1), friends and saves dialogs on `openpak-client`.

## Pointers

- [`../prds/`](../prds/README.md) — emulator-wide PRDs (`emulators/prds/` in the workspace):
  emulator-integration-prd.md (E4), emulator-network-profile-prd.md.
- `OPENPAK.md` — this fork's own readme.
