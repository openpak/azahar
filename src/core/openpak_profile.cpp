// OpenPak: see openpak_profile.h.
#include "openpak_profile.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <sstream>
#include <thread>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#endif
#include <fmt/format.h>
#include <httplib.h>
#include <json.hpp>
#include <openssl/sha.h>

#include "common/file_util.h"
#include "common/logging/log.h"

namespace OpenPakProfile {
namespace {

constexpr const char* kPlatform = "3ds";
// Two seconds is generous for a request a game must never wait on twice.
constexpr auto kTimeout = std::chrono::seconds{2};

std::mutex g_mutex;
Applied g_applied;

std::filesystem::path ProfilePath() {
    return std::filesystem::path(FileUtil::GetUserPath(FileUtil::UserPath::ConfigDir)) /
           "openpak_network_profile.json";
}

std::filesystem::path EtagPath() {
    return std::filesystem::path(FileUtil::GetUserPath(FileUtil::UserPath::ConfigDir)) /
           "openpak_network_profile.etag";
}

std::string ReadFile(const std::filesystem::path& path) {
    std::error_code ec;
    if (!std::filesystem::exists(path, ec))
        return {};
    std::ifstream file(path, std::ios::binary);
    std::stringstream buffer;
    buffer << file.rdbuf();
    return buffer.str();
}

bool WriteFile(const std::filesystem::path& path, const std::string& contents) {
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    if (!file)
        return false;
    file.write(contents.data(), static_cast<std::streamsize>(contents.size()));
    return static_cast<bool>(file);
}

std::string ToLower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
    return s;
}

// The compiled-in ceiling (emulators/prds/emulator-network-profile-prd.md §3): the families this
// emulator will ever rewrite. The profile chooses within them; anything outside rejects the
// whole profile.
const std::vector<std::string>& AllowedFamilies() {
    static const std::vector<std::string> families{
        ".nintendo.net",        ".nintendo.com", ".nintendo.co.jp", ".nintendowifi.net",
        ".nintendo-europe.com", ".gamespy.com",  ".openpak.org",
    };
    return families;
}

bool NameAllowed(const std::string& host) {
    const std::string name = ToLower(host);
    if (name.empty() || name.front() == '.' || name.find('/') != std::string::npos ||
        name.find('@') != std::string::npos)
        return false;
    for (const std::string& family : AllowedFamilies()) {
        const size_t n = family.size();
        if (name.size() > n && name.compare(name.size() - n, n, family) == 0)
            return true;
    }
    return false;
}

bool AddressUsable(const std::string& literal) {
    in_addr v4{};
    in6_addr v6{};
    if (inet_pton(AF_INET, literal.c_str(), &v4) == 1) {
        const uint32_t ip = ntohl(v4.s_addr);
        return (ip >> 24) != 127 && (ip >> 24) != 0 && (ip >> 16) != 0xA9FE &&
               ((ip >> 24) & 0xF0) != 0xE0 && ip != 0xFFFFFFFF;
    }
    if (inet_pton(AF_INET6, literal.c_str(), &v6) == 1) {
        return !IN6_IS_ADDR_LOOPBACK(&v6) && !IN6_IS_ADDR_LINKLOCAL(&v6) &&
               !IN6_IS_ADDR_UNSPECIFIED(&v6) && !IN6_IS_ADDR_MULTICAST(&v6);
    }
    return false;
}

// "https://host/..." — only what validation needs.
bool SplitURL(const std::string& url, std::string& scheme, std::string& host) {
    const auto scheme_end = url.find("://");
    if (scheme_end == std::string::npos || scheme_end == 0)
        return false;
    scheme = ToLower(url.substr(0, scheme_end));
    std::string rest = url.substr(scheme_end + 3);
    const auto path = rest.find_first_of("/?#");
    if (path != std::string::npos)
        rest = rest.substr(0, path);
    if (rest.empty() || rest.find('@') != std::string::npos)
        return false;
    const auto colon = rest.rfind(':');
    host = colon == std::string::npos ? rest : rest.substr(0, colon);
    return !host.empty();
}

std::string Validate(const nlohmann::json& doc) {
    if (!doc.is_object())
        return "profile is not an object";
    if (!doc.contains("version") || !doc["version"].is_number_integer() ||
        doc["version"].get<long long>() < 0)
        return "version is missing or negative";
    if (!doc.contains("platform") || !doc["platform"].is_string() ||
        doc["platform"].get<std::string>() != kPlatform)
        return fmt::format("platform is not \"{}\"", kPlatform);

    if (doc.contains("server") && doc["server"].is_object()) {
        auto const& server = doc["server"];
        if (!server.contains("address") || !server["address"].is_string() ||
            !AddressUsable(server["address"].get<std::string>()))
            return "server.address is not a usable literal address";
    }

    for (const char* field : {"suffixes", "exact", "never"}) {
        if (!doc.contains("redirect") || !doc["redirect"].is_object() ||
            !doc["redirect"].contains(field))
            continue;
        auto const& list = doc["redirect"][field];
        if (!list.is_array())
            return fmt::format("redirect.{} is not an array", field);
        for (auto const& entry : list) {
            if (!entry.is_string() || !NameAllowed(entry.get<std::string>()))
                return fmt::format("redirect.{} names \"{}\", outside the families this "
                                   "emulator rewrites",
                                   field,
                                   entry.is_string() ? entry.get<std::string>() : "(non-string)");
        }
    }

    if (doc.contains("services") && doc["services"].is_array()) {
        for (auto const& entry : doc["services"]) {
            if (!entry.is_object() || !entry.contains("id") || !entry["id"].is_string() ||
                !entry.contains("url") || !entry["url"].is_string())
                return "services contains a malformed entry";
            std::string scheme, host;
            if (!SplitURL(entry["url"].get<std::string>(), scheme, host))
                return fmt::format("service {} has an unparsable url",
                                   entry["id"].get<std::string>());
            if (!NameAllowed(host))
                return fmt::format("service {} points outside the families this emulator rewrites",
                                   entry["id"].get<std::string>());
        }
    }
    return {};
}

Applied Parse(const nlohmann::json& doc) {
    Applied applied;
    applied.fetched = true;
    applied.version = doc.value("version", 0);
    applied.source = "fetched";
    if (doc.contains("redirect") && doc["redirect"].is_object()) {
        auto const& redirect = doc["redirect"];
        if (redirect.contains("suffixes") && redirect["suffixes"].is_array())
            for (auto const& s : redirect["suffixes"])
                applied.suffixes.push_back(ToLower(s.get<std::string>()));
        if (redirect.contains("exact") && redirect["exact"].is_array())
            for (auto const& s : redirect["exact"])
                applied.exact.push_back(ToLower(s.get<std::string>()));
        if (redirect.contains("never") && redirect["never"].is_array())
            for (auto const& s : redirect["never"])
                applied.never.push_back(ToLower(s.get<std::string>()));
    }
    return applied;
}

bool FetchOnce(const std::string& etag_sent, std::string& body, std::string& etag_out,
               long& status) {
    // OPENPAK_API is honoured like the account API's base url: https, or loopback for a local
    // stack. TLS verification is never disabled — a profile that did not arrive over public
    // TLS from openpak.org is not a profile.
    std::string base = "https://openpak.org";
    if (const char* env = getenv("OPENPAK_API")) {
        std::string candidate = ToLower(env);
        if (candidate.rfind("https://", 0) == 0 || candidate.rfind("http://127.0.0.1", 0) == 0 ||
            candidate.rfind("http://localhost", 0) == 0 || candidate.rfind("http://[::1]", 0) == 0)
            base = env;
    }

    httplib::Client client(base);
    client.set_connection_timeout(kTimeout);
    client.set_read_timeout(kTimeout);
    client.set_follow_location(false);

    httplib::Headers headers;
    if (!etag_sent.empty())
        headers.emplace("If-None-Match", etag_sent);

    auto result =
        client.Get(fmt::format("/api/v1/network/profile?platform={}", kPlatform), headers);
    if (!result)
        return false;
    status = result->status;
    body = result->body;
    etag_out = result->get_header_value("ETag");
    return true;
}

void ApplyStoredOrBuiltIn(const std::string& reason) {
    const std::string stored = ReadFile(ProfilePath());
    if (!stored.empty()) {
        try {
            auto doc = nlohmann::json::parse(stored);
            const std::string problem = Validate(doc);
            if (problem.empty()) {
                Applied applied = Parse(doc);
                applied.source = "cached";
                std::lock_guard lock(g_mutex);
                g_applied = std::move(applied);
                LOG_INFO(Service_HTTP, "network profile: cached v{} in use ({})", g_applied.version,
                         reason);
                return;
            }
            LOG_WARNING(Service_HTTP,
                        "network profile: stored profile rejected ({}); the "
                        "compiled-in map applies",
                        problem);
        } catch (const nlohmann::json::exception& e) {
            LOG_WARNING(Service_HTTP, "network profile: stored profile does not parse ({})",
                        e.what());
        }
    }
    std::lock_guard lock(g_mutex);
    g_applied = Applied{};
    g_applied.source = "built-in";
    LOG_INFO(Service_HTTP, "network profile: the compiled-in host map applies ({})", reason);
}

} // namespace

void FetchAtLaunch() {
    // Off the caller's thread (the UI thread, when a game boots): the compiled-in or
    // last-known-good map applies until the answer lands, and one fetch at a time is enough.
    static std::atomic<bool> in_flight{false};
    if (in_flight.exchange(true)) {
        return;
    }
    std::thread([] {
        Refresh();
        in_flight = false;
    }).detach();
}

void Refresh() {
    const std::string etag_sent = ReadFile(EtagPath());
    std::string body, etag;
    long status = 0;
    if (!FetchOnce(etag_sent, body, etag, status)) {
        ApplyStoredOrBuiltIn("the profile endpoint could not be reached");
        return;
    }

    if (status == 304) {
        ApplyStoredOrBuiltIn("unchanged at the server");
        return;
    }

    if (status != 200) {
        ApplyStoredOrBuiltIn(fmt::format("HTTP {}", status));
        return;
    }

    nlohmann::json doc;
    try {
        doc = nlohmann::json::parse(body);
    } catch (const nlohmann::json::exception& e) {
        LOG_WARNING(Service_HTTP, "network profile: rejected the fetched profile ({})", e.what());
        ApplyStoredOrBuiltIn("the fetched profile did not parse");
        return;
    }

    const std::string problem = Validate(doc);
    if (!problem.empty()) {
        // Rejected whole, never partially applied.
        LOG_WARNING(Service_HTTP, "network profile: rejected the fetched profile ({})", problem);
        ApplyStoredOrBuiltIn("the fetched profile failed validation");
        return;
    }

    WriteFile(ProfilePath(), body);
    WriteFile(EtagPath(), etag);

    Applied applied = Parse(doc);
    {
        std::lock_guard lock(g_mutex);
        g_applied = applied;
    }
    LOG_INFO(Service_HTTP, "network profile: fetched v{} for {}", applied.version, kPlatform);
}

Applied Current() {
    std::lock_guard lock(g_mutex);
    return g_applied;
}

std::string MapHost(const std::string& host) {
    Applied applied;
    {
        std::lock_guard lock(g_mutex);
        applied = g_applied;
    }
    if (!applied.fetched)
        return {}; // nothing applied: the caller's compiled-in map is the truth

    const std::string name = ToLower(host);

    // `never`: these names must reach the real internet. A connection test pointed at
    // OpenPak measures OpenPak.
    for (const std::string& never : applied.never) {
        const size_t n = never.size();
        if (name == never || (name.size() > n && name.compare(name.size() - n, n, never) == 0 &&
                              name[name.size() - n - 1] == '.'))
            return {};
    }

    // The rewrite: the longest applied family wins; the Nintendo suffix becomes
    // ".openpak.org", which is exactly how the compiled-in map pairs names.
    const std::string openpak = ".openpak.org";
    const std::string* best = nullptr;
    size_t best_len = 0;
    auto consider = [&](const std::string& family) {
        const size_t n = family.size();
        if (name.size() > n && name.compare(name.size() - n, n, family) == 0 && n > best_len) {
            best = &family;
            best_len = n;
        }
    };
    for (const std::string& family : applied.suffixes)
        consider(family);
    for (const std::string& exact : applied.exact) {
        // an exact name rewrites by whatever family it sits under
        for (const std::string& family : AllowedFamilies()) {
            const size_t n = family.size();
            if (name.size() > n && name.compare(name.size() - n, n, family) == 0) {
                consider(family);
                break;
            }
        }
    }
    if (!best || *best == openpak)
        return {};

    std::string out = name;
    out.replace(out.size() - best->size(), best->size(), openpak);
    return out == name ? std::string{} : out;
}

std::string StatusLine() {
    std::lock_guard lock(g_mutex);
    if (g_applied.fetched)
        return fmt::format("Network profile: {} (v{})", g_applied.source, g_applied.version);
    return "Network profile: built-in host map";
}

} // namespace OpenPakProfile
