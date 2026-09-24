// OpenPak: the applied network profile (emulators/prds/emulator-network-profile-prd.md §4e).
// One conditional GET per launch; the fetched profile's redirect lists replace the host map
// http_c ships with. Never blocks a game on the network: last-known-good beats a fetch, and
// the compiled-in map beats nothing.
#pragma once

#include <functional>
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

// What the frontend's OpenPak client library supplies (docs/signed-ceiling.md). citra_qt links
// the library and sets these at startup; a build without it (the libretro core, Android) keeps
// the frozen compiled-in families as its ceiling.
struct ClientHooks {
    // The 3DS families of the verified ceiling: the profile's names are kept only inside them.
    std::function<std::vector<std::string>()> families;
    // On the fetch thread, before the profile request: refresh the signed ceiling.
    std::function<void()> before_fetch;
    // After a profile is applied: its JSON body, or empty when the compiled-in map applies.
    std::function<void(const std::string& body)> applied;
};
void SetClientHooks(ClientHooks hooks);

// §2: one conditional GET with the stored ETag, two-second timeout, single attempt. A malformed
// profile is a log line and a fallback; a name outside the ceiling is dropped on its own, logged,
// and the rest applies. Returns at once: the request runs on its own thread, and a second call
// while one is in flight does nothing.
void FetchAtLaunch();

// The "Refresh network settings" action: the same flow, blocking (network) -- call it off the
// UI thread. Safe to ask while the emulator runs.
void Refresh();

// The applied profile (or the compiled-in default when nothing has been fetched).
Applied Current();

// The §4e rewrite: a Nintendo name under an applied family becomes the same name on
// openpak.org; a name in `never` comes back untouched; an unapplied or unmatched host comes
// back empty so the caller uses its compiled-in map.
std::string MapHost(const std::string& host);

// For the settings UI.
std::string StatusLine();

} // namespace OpenPakProfile
