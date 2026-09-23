// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

// The OpenPak surface the Android app calls into, for the 3DS family: an account-only sign-in,
// the account's 3DS identity, friends, cloud saves, the public mods catalogue and the status
// page. openpak-client has no Android UI of its own; this is the whole bridge: plain calls in,
// JSON strings out, so the Kotlin screens (features/openpak) need no JNI object marshalling.
// Every call that touches the network blocks, and Kotlin makes it from Dispatchers.IO, never
// the main thread.

#include "jni/openpak_native.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <filesystem>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include <fmt/format.h>
#include <httplib.h>
#include <jni.h>
#include <nlohmann/json.hpp>

#include <openpak/account.h>
#include <openpak/api.h>
#include <openpak/nat_check.h>
#include <openpak/platform.h>
#include <openpak/save_sync.h>

#include "common/android_utils.h"
#include "common/common_types.h"
#include "common/file_util.h"
#include "common/logging/log.h"
#include "common/scm_rev.h"
#include "common/settings.h"
#include "core/file_sys/archive_source_sd_savedata.h"
#include "core/loader/loader.h"
#include "core/openpak_profile.h"
#include "jni/android_common/android_common.h"
#include "jni/id_cache.h"

namespace {

using nlohmann::json;
namespace Api = WebService::OpenPakApi;
namespace Account = Common::OpenPakAccount;
namespace SaveSync = Nextendo::SaveSync;

constexpr char PLATFORM[] = "3ds";

// What the Kotlin side words: OpenPakNotifier's kinds.
enum class Event : jint {
    Pulled = 0,
    Conflict = 1,
    Pushed = 2,
    PushFailed = 3,
};

std::atomic<bool> g_cloud_sync{true};
std::atomic<bool> g_notifications{true};

jclass s_notifier_class = nullptr;
jmethodID s_notifier_notify = nullptr;
jmethodID s_notifier_checking = nullptr;

std::string TitleHex(u64 title_id) {
    return fmt::format("{:016x}", title_id);
}

// Only 3DS applications have a save of their own on the SD card (title id 00040000xxxxxxxx).
bool IsApplication(u64 title_id) {
    return (title_id >> 32) == 0x00040000;
}

// The application's SD save folder as a real path, or empty when there is none to sync. The
// emulated SD card lives in the user directory the app was given; SaveSync reads and writes it
// through std::filesystem, which needs the raw path behind that directory -- the vanilla build
// has all-files access, the Google Play build does not (no cloud sync there).
std::filesystem::path SaveFolder(u64 title_id) {
    if (!IsApplication(title_id) || !AndroidUtils::CanUseRawFS()) {
        return {};
    }
    std::string path = FileSys::ArchiveSource_SDSaveData::GetSaveDataPathFor(
        FileUtil::GetUserPath(FileUtil::UserPath::SDMCDir), title_id);
    // SaveSync keeps its marker beside the folder, so the folder is named without a trailing
    // separator.
    while (!path.empty() && (path.back() == '/' || path.back() == '\\')) {
        path.pop_back();
    }
    if (path.empty()) {
        return {};
    }
    const std::string native = AndroidUtils::TranslateFilePath(path);
    return native.empty() ? std::filesystem::path{} : std::filesystem::path(native);
}

bool SyncWanted() {
    return g_cloud_sync.load() && Account::HasBearer();
}

// The account's 3DS identity (GET /api/v1/me/3ds): what nn-account minted for it on first
// sight. Kept for the Account and Status screens and the sign-in message.
struct Identity {
    std::string friend_code;
    u64 pid = 0;
    std::string username;
};
std::mutex g_identity_mutex;
std::optional<Identity> g_identity;

// Blocking, network.
std::optional<Identity> FetchIdentity() {
    const std::string bearer = Account::GetBearer();
    if (bearer.empty()) {
        return std::nullopt;
    }
    httplib::Client client(Api::BaseUrl());
    Api::ApplySystemCa(client);
    client.set_connection_timeout(5);
    client.set_read_timeout(10);
    const httplib::Headers headers{{"Authorization", "Bearer " + bearer},
                                   {"X-OpenPak-Client", openpak::Platform::ClientHeader()}};
    const auto result = client.Get("/api/v1/me/3ds", headers);
    if (!result || result->status != 200) {
        LOG_WARNING(Frontend, "OpenPak: no 3DS identity ({})",
                    result ? std::to_string(result->status) : std::string("no answer"));
        return std::nullopt;
    }
    try {
        const auto doc = json::parse(result->body);
        Identity identity;
        identity.friend_code = doc.value("friend_code", std::string{});
        identity.pid = doc.value("pid", static_cast<u64>(0));
        identity.username = doc.value("username", std::string{});
        std::lock_guard lock(g_identity_mutex);
        g_identity = identity;
        return identity;
    } catch (const json::exception& e) {
        LOG_WARNING(Frontend, "OpenPak: the 3DS identity did not parse ({})", e.what());
        return std::nullopt;
    }
}

json IdentityJson(const std::optional<Identity>& identity) {
    if (!identity) {
        return json{{"ok", false}};
    }
    return json{{"ok", true},
                {"friend_code", identity->friend_code},
                {"pid", identity->pid == 0 ? std::string{} : std::to_string(identity->pid)},
                {"username", identity->username}};
}

void NotifyApp(Event event, const std::string& title_hex, const std::string& detail) {
    if (!s_notifier_class || !g_notifications.load()) {
        return;
    }
    JNIEnv* env = IDCache::GetEnvForThread();
    env->CallStaticVoidMethod(s_notifier_class, s_notifier_notify, static_cast<jint>(event),
                              ToJString(env, title_hex), ToJString(env, detail));
}

void ShowChecking(bool show) {
    if (!s_notifier_class) {
        return;
    }
    JNIEnv* env = IDCache::GetEnvForThread();
    env->CallStaticVoidMethod(s_notifier_class, s_notifier_checking, show ? JNI_TRUE : JNI_FALSE);
}

// This run's title, captured before boot so the push on stop cannot race the teardown.
struct Run {
    u64 title_id = 0;
    std::filesystem::path save_dir;
    bool booted = false;
    bool conflict = false;             // both sides have a save: automatic sync waits
    std::optional<Event> pull_message; // said once the core runs
};
std::mutex s_run_mutex;
std::optional<Run> s_run;

// The pull in flight, shared with the thread that runs it: the emulation thread stops waiting
// after five seconds or on Skip, and the pull then leaves the local save alone.
struct Pull {
    std::mutex mutex;
    std::condition_variable cv;
    bool done = false;
    bool skipped = false;
    std::atomic<bool> wanted{true};
    SaveSync::PullOutcome outcome = SaveSync::PullOutcome::Nothing;
};
std::mutex s_pull_mutex;
std::shared_ptr<Pull> s_pull;

json FriendJson(const Api::AccountFriend& f) {
    return json{{"account_id", f.account_id},
                {"pid", f.pid == 0 ? std::string{} : std::to_string(f.pid)},
                {"name", f.display_name},
                {"friend_code", f.friend_code},
                {"online", f.online},
                {"title_id", f.title_id},
                {"console", f.console_namespace},
                {"online_since", f.online_since},
                {"friends_since", f.friends_since},
                {"incoming", f.incoming}};
}

std::optional<u64> ParseHex(const std::string& hex) {
    try {
        size_t used = 0;
        const u64 value = std::stoull(hex, &used, 16);
        if (used != hex.size()) {
            return std::nullopt;
        }
        return value;
    } catch (...) {
        return std::nullopt;
    }
}

Api::AccountFriend RequestOf(JNIEnv* env, jstring jaccount, jstring jpid) {
    Api::AccountFriend request;
    request.account_id = GetJString(env, jaccount);
    try {
        const std::string pid = GetJString(env, jpid);
        request.pid = pid.empty() ? 0 : std::stoull(pid);
    } catch (...) {
        request.pid = 0;
    }
    return request;
}

// The sign-in error as a string-table key, so the screen shows the spec's words and never an
// HTTP code: credentials, rate_limited, unreachable, or server with the server's own sentence.
json SignInError(const std::string& error) {
    if (error == "Wrong email or password.") {
        return json{{"ok", false}, {"code", "credentials"}};
    }
    if (error == "Too many attempts. Wait a minute and try again.") {
        return json{{"ok", false}, {"code", "rate_limited"}};
    }
    const bool machine_text = error.empty() || error.starts_with("Could not reach") ||
                              error.starts_with("Sign-in failed (HTTP") ||
                              error.starts_with("Unexpected") || error.starts_with("The website");
    if (machine_text) {
        if (!error.empty()) {
            LOG_WARNING(Frontend, "OpenPak sign-in failed: {}", error);
        }
        return json{{"ok", false}, {"code", "unreachable"}};
    }
    return json{{"ok", false}, {"code", "server"}, {"message", error}};
}

json ProfileSummary() {
    const OpenPakProfile::Applied applied = OpenPakProfile::Current();
    return json{{"fetched", applied.fetched},
                {"version", applied.version},
                {"source", applied.source}};
}

jstring Text(JNIEnv* env, const json& value) {
    return ToJString(env, value.dump());
}

} // namespace

namespace OpenPakNative {

void SetOptions(bool cloud_sync, bool notifications) {
    g_cloud_sync = cloud_sync;
    g_notifications = notifications;
}

void BeforeBoot(const std::string& filepath) {
    {
        std::lock_guard lock(s_run_mutex);
        s_run.reset();
    }
    // Artic Base streams the game from a console, whose save stays there.
    if (!SyncWanted() || filepath.starts_with("articbase://")) {
        return;
    }
    u64 title_id = 0;
    {
        const auto loader = Loader::GetLoader(filepath);
        if (!loader || loader->ReadProgramId(title_id) != Loader::ResultStatus::Success) {
            return;
        }
    }
    Run run;
    run.title_id = title_id;
    run.save_dir = SaveFolder(title_id);
    if (run.save_dir.empty()) {
        return;
    }

    auto pull = std::make_shared<Pull>();
    {
        std::lock_guard lock(s_pull_mutex);
        s_pull = pull;
    }
    ShowChecking(true);
    std::thread([pull, dir = run.save_dir, id = run.title_id] {
        SaveSync::PullOutcome outcome = SaveSync::PullOutcome::Nothing;
        try {
            outcome = SaveSync::PullBeforeLaunch(dir, id, [pull] { return pull->wanted.load(); });
        } catch (const std::exception& e) {
            LOG_WARNING(Frontend, "OpenPak: save pull {:016X} failed: {}", id, e.what());
        }
        std::lock_guard lock(pull->mutex);
        pull->outcome = outcome;
        pull->done = true;
        pull->cv.notify_all();
    }).detach();

    // Never block a launch: five seconds at most, and Skip ends the wait at once.
    {
        std::unique_lock lock(pull->mutex);
        pull->cv.wait_for(lock, std::chrono::seconds(5),
                          [&] { return pull->done || pull->skipped; });
        if (pull->done) {
            if (pull->outcome == SaveSync::PullOutcome::Pulled) {
                LOG_INFO(Frontend, "OpenPak: cloud save applied for {:016X}", run.title_id);
                run.pull_message = Event::Pulled;
            } else if (pull->outcome == SaveSync::PullOutcome::BothExist) {
                // Starts on the local save; automatic sync for this title waits for the choice.
                run.pull_message = Event::Conflict;
                run.conflict = true;
            }
        } else {
            pull->wanted = false;
            LOG_INFO(Frontend, "OpenPak: save pull {:016X}: not waiting any longer, booting local",
                     run.title_id);
        }
    }
    {
        std::lock_guard lock(s_pull_mutex);
        s_pull.reset();
    }
    ShowChecking(false);

    std::lock_guard lock(s_run_mutex);
    s_run = std::move(run);
}

void AfterBoot() {
    std::optional<Event> message;
    std::string title;
    {
        std::lock_guard lock(s_run_mutex);
        if (!s_run) {
            return;
        }
        s_run->booted = true;
        message = s_run->pull_message;
        title = TitleHex(s_run->title_id);
        s_run->pull_message.reset();
    }
    if (message) {
        NotifyApp(*message, title, {});
    }
}

void AfterShutdown() {
    std::optional<Run> run;
    {
        std::lock_guard lock(s_run_mutex);
        std::swap(run, s_run);
    }
    // Not booted: the core never ran (the load failed), so there is nothing new to send.
    if (!run || !run->booted || run->conflict || !SyncWanted()) {
        return;
    }
    std::vector<u8> zip;
    try {
        zip = SaveSync::CaptureOnExit(run->save_dir, run->title_id);
    } catch (const std::exception& e) {
        LOG_WARNING(Frontend, "OpenPak: save capture {:016X} failed: {}", run->title_id, e.what());
        return;
    }
    if (zip.empty()) {
        return;
    }
    std::thread([run = std::move(*run), zip = std::move(zip)]() mutable {
        std::string error;
        try {
            error = SaveSync::PushCaptured(run.save_dir, run.title_id, std::move(zip));
        } catch (const std::exception& e) {
            error = e.what();
        }
        if (error.empty()) {
            LOG_INFO(Frontend, "OpenPak: save pushed for {:016X}", run.title_id);
            NotifyApp(Event::Pushed, TitleHex(run.title_id), {});
        } else {
            LOG_WARNING(Frontend, "OpenPak: save push {:016X} failed: {}", run.title_id, error);
            NotifyApp(Event::PushFailed, TitleHex(run.title_id), error);
        }
    }).detach();
}

} // namespace OpenPakNative

extern "C" {

// Once per process, after the user directory and config.ini are ready.
JNIEXPORT void JNICALL Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_init(
    JNIEnv* env, jclass, jstring jconfig_dir, jstring jcache_dir, jstring jdevice) {
    static std::once_flag once;
    std::call_once(once, [&] {
        jclass notifier =
            env->FindClass("org/citra/citra_emu/features/openpak/model/OpenPakNotifier");
        if (notifier) {
            s_notifier_class = static_cast<jclass>(env->NewGlobalRef(notifier));
            s_notifier_notify = env->GetStaticMethodID(
                s_notifier_class, "notify", "(ILjava/lang/String;Ljava/lang/String;)V");
            s_notifier_checking = env->GetStaticMethodID(s_notifier_class, "checking", "(Z)V");
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            s_notifier_class = nullptr;
        }

        // The token and the library's own files stay in the app's private storage, not in the
        // user directory the player picked (which other apps may read).
        openpak::Platform::SetDirectories(GetJString(env, jconfig_dir),
                                          GetJString(env, jcache_dir));
        openpak::Platform::SetClient("azahar", Common::g_build_version);
        Api::SetSavesPlatform(PLATFORM);
        const std::string device = GetJString(env, jdevice);
        Api::SetSaveDevice(device);
        Api::SetDeviceName(device);

        // One conditional request per app start, off this thread, and only when the player
        // asked for OpenPak: off means upstream's behaviour, with nothing sent anywhere.
        if (Settings::values.use_openpak_network.GetValue()) {
            OpenPakProfile::FetchAtLaunch();
        }
    });
}

// Who is signed in, as the last sign-in left it, and the [OpenPak] switches. Local only.
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_state(JNIEnv* env, jclass) {
    const bool signed_in = Account::HasBearer();
    return Text(env, json{{"signed_in", signed_in},
                          {"name", signed_in ? Account::GetUsername() : std::string{}},
                          {"website", Api::BaseUrl()},
                          {"status_url", Api::StatusUrl()},
                          {"enabled", Settings::values.use_openpak_network.GetValue()},
                          {"cloud_sync", g_cloud_sync.load()},
                          {"notifications", g_notifications.load()}});
}

// Network: the website token, then the account's 3DS identity.
JNIEXPORT jstring JNICALL Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_signIn(
    JNIEnv* env, jclass, jstring jemail, jstring jpassword, jstring jdevice) {
    const std::string email = GetJString(env, jemail);
    const std::string device = GetJString(env, jdevice);
    Api::SetDeviceName(device);
    const Api::LoginResult result = Api::SignInAccountOnly(email, GetJString(env, jpassword));
    if (!result.ok) {
        return Text(env, SignInError(result.error));
    }

    // The profile call reads the stored bearer, so keep it first, then keep it again under the
    // account's own name, which is what the screens show.
    Account::SaveBearerOnly(email, result.bearer);
    std::string name = email;
    const Api::Profile profile = Api::GetProfile();
    if (profile.ok && !profile.name.empty()) {
        name = profile.name;
        Account::SaveBearerOnly(name, result.bearer);
    }
    if (!device.empty()) {
        Api::SetSaveDevice(device);
    }
    const auto identity = FetchIdentity();
    return Text(env, json{{"ok", true},
                          {"name", name},
                          {"linked", identity && !identity->friend_code.empty()}});
}

// Network: presence ends now; the token is forgotten here and revoked on the server.
JNIEXPORT void JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_signOut(JNIEnv*, jclass) {
    const std::string bearer = Account::GetBearer();
    Account::Clear();
    {
        std::lock_guard lock(g_identity_mutex);
        g_identity.reset();
    }
    if (!bearer.empty()) {
        Api::RevokeToken(bearer);
    }
}

// Network: {ok, name, ...}; ok false with signed_in false means the stored sign-in expired.
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_profile(JNIEnv* env, jclass) {
    const Api::Profile p = Api::GetProfile();
    if (!p.ok) {
        return Text(env,
                    json{{"ok", false}, {"error", p.error}, {"signed_in", Account::HasBearer()}});
    }
    // The name the account goes by now, for the next launch's screens.
    if (!p.name.empty() && p.name != Account::GetUsername()) {
        Account::SaveBearerOnly(p.name, Account::GetBearer());
    }
    return Text(env, json{{"ok", true},
                          {"name", p.name},
                          {"account_id", p.account_id},
                          {"friend_code", p.friend_code},
                          {"image", p.image_base64},
                          {"linked", p.linked_platforms}});
}

// Network when asked to refresh, else the identity already fetched this run.
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_identity(JNIEnv* env, jclass,
                                                                         jboolean jrefresh) {
    std::optional<Identity> identity;
    {
        std::lock_guard lock(g_identity_mutex);
        identity = g_identity;
    }
    if (jrefresh || !identity) {
        identity = FetchIdentity();
    }
    return Text(env, IdentityJson(identity));
}

JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_friends(JNIEnv* env, jclass) {
    const Api::AccountFriends list = Api::GetAccountFriends();
    if (!list.ok) {
        return Text(env, json{{"ok", false}, {"error", list.error}});
    }
    json friends = json::array();
    for (const auto& f : list.friends) {
        friends.push_back(FriendJson(f));
    }
    json requests = json::array();
    for (const auto& r : list.requests) {
        requests.push_back(FriendJson(r));
    }
    return Text(env, json{{"ok", true}, {"friends", friends}, {"requests", requests}});
}

// The friend actions: empty on success, else a sentence fit to show.
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_sendFriendRequest(JNIEnv* env,
                                                                                  jclass,
                                                                                  jstring jcode) {
    return ToJString(env, Api::SendFriendRequest(GetJString(env, jcode)));
}

JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_acceptFriendRequest(
    JNIEnv* env, jclass, jstring jaccount, jstring jpid) {
    return ToJString(env, Api::AcceptFriendRequest(RequestOf(env, jaccount, jpid)));
}

// Decline an incoming request, or cancel an outgoing one.
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_declineFriendRequest(
    JNIEnv* env, jclass, jstring jaccount, jstring jpid) {
    return ToJString(env, Api::DeclineFriendRequest(RequestOf(env, jaccount, jpid)));
}

JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_removeFriend(JNIEnv* env, jclass,
                                                                             jstring jaccount) {
    return ToJString(env, Api::RemoveAccountFriend(GetJString(env, jaccount)));
}

JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_blockAccount(JNIEnv* env, jclass,
                                                                             jstring jaccount) {
    return ToJString(env, Api::BlockAccount(GetJString(env, jaccount)));
}

// The account's 3DS saves, with how the local copy of each stands.
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_cloudSaves(JNIEnv* env, jclass) {
    const Api::CloudSaves list = Api::GetCloudSaves();
    if (!list.ok) {
        return Text(env, json{{"ok", false}, {"error", list.error}});
    }
    json saves = json::array();
    for (const auto& save : list.saves) {
        if (save.platform != PLATFORM) {
            continue;
        }
        json versions = json::array();
        for (const auto& v : save.versions) {
            versions.push_back(json{{"id", v.id},
                                    {"number", v.number},
                                    {"conflict", v.conflict},
                                    {"size", v.size},
                                    {"device", v.device},
                                    {"saved_at", v.saved_at}});
        }
        json local = {{"state", "none"}, {"version", ""}, {"last_written", 0}};
        if (const auto id = ParseHex(save.title_id)) {
            if (const auto dir = SaveFolder(*id); !dir.empty()) {
                const int newest = save.versions.empty() ? 0 : save.versions.front().number;
                const SaveSync::LocalCopy copy = SaveSync::Compare(dir, newest);
                const char* state = "none";
                switch (copy.state) {
                case SaveSync::LocalState::NoLocal:
                    state = "none";
                    break;
                case SaveSync::LocalState::InStep:
                    state = "in_step";
                    break;
                case SaveSync::LocalState::ChangedHere:
                    state = "changed_here";
                    break;
                case SaveSync::LocalState::CloudNewer:
                    state = "cloud_newer";
                    break;
                case SaveSync::LocalState::NoHistory:
                    state = "no_history";
                    break;
                }
                local = {{"state", state},
                         {"version", copy.version},
                         {"last_written", copy.last_written}};
            }
        }
        saves.push_back(json{{"title_id", save.title_id},
                             {"name", save.name},
                             {"versions", versions},
                             {"local", local}});
    }
    return Text(env, json{{"ok", true},
                          {"saves", saves},
                          {"used", list.allowance_used},
                          {"allowance", list.allowance}});
}

// Delete from cloud: every stored version of the title goes. Empty on success.
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_deleteSave(JNIEnv* env, jclass,
                                                                           jlongArray jids) {
    const jsize count = env->GetArrayLength(jids);
    std::vector<jlong> ids(static_cast<size_t>(count));
    env->GetLongArrayRegion(jids, 0, count, ids.data());
    for (const jlong id : ids) {
        const std::string error = Api::DeleteSaveVersion(static_cast<s64>(id));
        if (!error.empty()) {
            return ToJString(env, error);
        }
    }
    return ToJString(env, "");
}

// Take the cloud's: the newest cloud copy replaces the local one (kept beside it).
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_downloadSave(JNIEnv* env, jclass,
                                                                             jstring jtitle) {
    const auto id = ParseHex(GetJString(env, jtitle));
    const auto dir = id ? SaveFolder(*id) : std::filesystem::path{};
    if (dir.empty()) {
        return ToJString(env, "This title has no save folder on this device.");
    }
    return ToJString(env, SaveSync::Download(dir, *id));
}

// Keep this machine's: the local copy goes up as the version after newest_cloud.
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_uploadSave(JNIEnv* env, jclass,
                                                                           jstring jtitle,
                                                                           jint newest) {
    const auto id = ParseHex(GetJString(env, jtitle));
    const auto dir = id ? SaveFolder(*id) : std::filesystem::path{};
    if (dir.empty()) {
        return ToJString(env, "This title has no save folder on this device.");
    }
    return ToJString(env, SaveSync::Upload(dir, *id, newest));
}

// Network: the public mods catalogue for a title.
JNIEXPORT jstring JNICALL Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_mods(
    JNIEnv* env, jclass, jstring jtitle) {
    json mods = json::array();
    for (const auto& mod : Api::GetMods(GetJString(env, jtitle))) {
        mods.push_back(json{{"id", mod.id},
                            {"name", mod.name},
                            {"version", mod.version},
                            {"author", mod.author},
                            {"licence", mod.licence},
                            {"summary", mod.summary}});
    }
    json favourites = json::array();
    if (Account::HasBearer()) {
        for (const auto& id : Api::GetFavouriteModIds()) {
            favourites.push_back(id);
        }
    }
    return Text(env, json{{"mods", mods}, {"favourites", favourites}});
}

JNIEXPORT jboolean JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_setModFavourite(
    JNIEnv* env, jclass, jstring jmod, jboolean jfavourite) {
    return Api::SetModFavourite(GetJString(env, jmod), jfavourite == JNI_TRUE) ? JNI_TRUE
                                                                                 : JNI_FALSE;
}

// Network: the status page and the player counts.
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_status(JNIEnv* env, jclass) {
    const Api::ServiceStatus service = Api::GetServiceStatus();
    const Api::NetworkStatus network = Api::GetStatus();
    json services = json::array();
    for (const auto& s : service.services) {
        services.push_back(json{{"group", s.group},
                                {"name", s.name},
                                {"up", s.up},
                                {"uptime", s.uptime},
                                {"latency", s.latency}});
    }
    json titles = json::array();
    for (const auto& [id, players] : network.titles) {
        titles.push_back(json{{"id", id}, {"players", players}});
    }
    json networks = json::array();
    for (const auto& [id, players] : network.networks) {
        networks.push_back(json{{"id", id}, {"players", players}});
    }
    return Text(env, json{{"ok", service.ok},
                          {"url", service.url},
                          {"headline", service.headline},
                          {"sub", service.sub},
                          {"state", service.state},
                          {"services", services},
                          {"players_ok", network.ok},
                          {"players", network.players_online},
                          {"titles", titles},
                          {"networks", networks}});
}

// Test connection: the round trip to the website, and the NAT type where the network profile
// names two NAT test servers (as the desktop's Status page does).
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_testConnection(JNIEnv* env,
                                                                               jclass) {
    json out = json::object();
    if (const auto ping = Api::PingBackend()) {
        out["ping_ms"] = *ping;
    }
    if (const auto targets = openpak::nat::Targets({})) {
        const openpak::nat::Result nat = openpak::nat::Run(targets->first, targets->second);
        out["nat"] = std::string(1, nat.Type());
    }
    return Text(env, out);
}

// Network: "Refresh network settings" (Azahar's own profile, src/core/openpak_profile).
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_refreshNetwork(JNIEnv* env,
                                                                               jclass) {
    OpenPakProfile::Refresh();
    return Text(env, ProfileSummary());
}

// The applied network profile, without asking anybody.
JNIEXPORT jstring JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_networkSummary(JNIEnv* env,
                                                                               jclass) {
    return Text(env, ProfileSummary());
}

// Skip on the "Checking cloud save..." line: boot now on the local save.
JNIEXPORT void JNICALL
Java_org_citra_citra_1emu_features_openpak_model_OpenPakNative_skipCloudPull(JNIEnv*, jclass) {
    std::shared_ptr<Pull> pull;
    {
        std::lock_guard lock(s_pull_mutex);
        pull = s_pull;
    }
    if (!pull) {
        return;
    }
    std::lock_guard lock(pull->mutex);
    pull->skipped = true;
    pull->wanted = false;
    pull->cv.notify_all();
}

} // extern "C"
