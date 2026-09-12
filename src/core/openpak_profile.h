// OpenPak: the applied network profile (prds/emulator-network-profile-prd.md §4e).
// One conditional GET per launch; the fetched profile's redirect lists replace the host map
// http_c ships with. Never blocks a game on the network: last-known-good beats a fetch, and
// the compiled-in map beats nothing.
#pragma once

#include <string>
#include <vector>

namespace OpenPakProfile {

struct Applied {
    bool fetched = false; // false: nothing applied, http_c's compiled-in map is the truth
    int version = 0;
    std::string source;                    // "fetched" | "cached" | "built-in"
    std::vector<std::string> suffixes;     // families whose names are answered on openpak.org
    std::vector<std::string> exact;        // single names outside a family
    std::vector<std::string> never;        // names that must reach the real internet untouched
};

// §2: one conditional GET with the stored ETag, two-second timeout, single attempt. Validated
// against the compiled-in families before anything is applied; a rejected profile is a log
// line and a fallback.
void FetchAtLaunch();

// The "Refresh network settings" action: same flow, safe to ask while the emulator runs.
void Refresh();

// The applied profile (or the compiled-in default when nothing has been fetched).
const Applied& Current();

// The §4e rewrite: a Nintendo name under an applied family becomes the same name on
// openpak.org; a name in `never` comes back untouched; an unapplied or unmatched host comes
// back empty so the caller uses its compiled-in map.
std::string MapHost(const std::string& host);

// For the settings UI.
std::string StatusLine();

} // namespace OpenPakProfile
