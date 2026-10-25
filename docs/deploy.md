# Deploy: azahar

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
