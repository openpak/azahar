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

Server side: the OpenPak network (`account`, `nn-account`, `nn-friends`, `nn-nncs`, `nn-boss`,
`nn-juxtaposition`, `nn-soap` for Wii U and 3DS; `nn-wfc` for Wii and DS) answers both the
Nintendo names and the `openpak.org` names behind one TLS front.
