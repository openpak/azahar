// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include "citra_qt/openpak_host.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <filesystem>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <thread>

#include <QApplication>
#include <QCoreApplication>
#include <QDialog>
#include <QDialogButtonBox>
#include <QEventLoop>
#include <QLabel>
#include <QMainWindow>
#include <QMenuBar>
#include <QPainter>
#include <QPainterPath>
#include <QPalette>
#include <QPointer>
#include <QProgressBar>
#include <QTimer>
#include <QVBoxLayout>

#include <fmt/format.h>
#include <httplib.h>
#include <json.hpp>

#include <openpak/account.h>
#include <openpak/api.h>
#include <openpak/platform.h>
#include <openpak/qt/account_dialog.h>
#include <openpak/qt/avatar_cache.h>
#include <openpak/qt/friend_notifier.h>
#include <openpak/qt/host.h>
#include <openpak/qt/host_kit.h>
#include <openpak/qt/prompts.h>
#include <openpak/qt/sign_in_dialog.h>
#include <openpak/qt/toast.h>
#include <openpak/save_sync.h>

#include "citra_qt/uisettings.h"
#include "common/file_util.h"
#include "common/logging/log.h"
#include "common/scm_rev.h"
#include "common/settings.h"
#include "core/file_sys/archive_source_sd_savedata.h"
#include "core/openpak_profile.h"

namespace OpenPakQt {
namespace {

namespace Api = WebService::OpenPakApi;
using Kind = NextendoToast::Kind;

QString Tr(const char* text) {
    return QCoreApplication::translate("OpenPak", text);
}

bool OpenPakOn() {
    return Settings::values.use_openpak_network.GetValue();
}

// Only 3DS applications have a save of their own on the SD card (title id 00040000xxxxxxxx).
bool IsApplication(u64 title_id) {
    return (title_id >> 32) == 0x00040000;
}

std::filesystem::path SaveFolder(u64 title_id) {
    std::string path = FileSys::ArchiveSource_SDSaveData::GetSaveDataPathFor(
        FileUtil::GetUserPath(FileUtil::UserPath::SDMCDir), title_id);
    // SaveSync keeps its marker beside the folder, so the folder is named without a trailing
    // separator.
    while (!path.empty() && (path.back() == '/' || path.back() == '\\')) {
        path.pop_back();
    }
    return std::filesystem::path(path);
}

// The account's 3DS identity (GET /api/v1/me/3ds): what nn-account minted for it on first sight.
struct Identity {
    std::string friend_code;
    u64 pid = 0;
    std::string username;
};
std::mutex g_identity_mutex;
std::optional<Identity> g_identity;

// Blocking, network: call off the UI thread.
void FetchIdentity() {
    const std::string bearer = Common::OpenPakAccount::GetBearer();
    if (bearer.empty()) {
        return;
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
        return;
    }
    try {
        const auto doc = nlohmann::json::parse(result->body);
        Identity identity;
        identity.friend_code = doc.value("friend_code", std::string{});
        identity.pid = doc.value("pid", static_cast<u64>(0));
        identity.username = doc.value("username", std::string{});
        std::lock_guard lock(g_identity_mutex);
        g_identity = identity;
    } catch (const nlohmann::json::exception& e) {
        LOG_WARNING(Frontend, "OpenPak: the 3DS identity did not parse ({})", e.what());
    }
}

std::optional<Identity> CurrentIdentity() {
    std::lock_guard lock(g_identity_mutex);
    return g_identity;
}

// The ".../title/00040000/xxxxxxxx/data" of an application, keyed in the cloud by its title id.
class AzaharHost final : public openpak::qt::Host {
public:
    using Host::Host;

    QMainWindow* main_window = nullptr;
    Hooks hooks;
    u64 running_title = 0;

    openpak::qt::Family GetFamily() const override {
        return openpak::qt::Family::N3DS;
    }
    QString ConsoleIdentity() const override {
        const auto identity = CurrentIdentity();
        if (!identity || (identity->friend_code.empty() && identity->pid == 0)) {
            return {};
        }
        QStringList rows;
        if (!identity->friend_code.empty()) {
            rows << Tr("Friend code %1").arg(QString::fromStdString(identity->friend_code));
        }
        if (identity->pid != 0) {
            rows << Tr("PID %1").arg(identity->pid);
        }
        return rows.join(QLatin1Char('\n'));
    }
    QString ConsoleLink() const override {
        const auto identity = CurrentIdentity();
        return identity && !identity->username.empty()
                   ? Tr("Linked as %1").arg(QString::fromStdString(identity->username))
                   : QString{};
    }

    bool IsLinked() const override {
        return SignedIn();
    }
    void SignIn() override {
        ShowSignIn(main_window);
    }
    void SignOut() override {
        OpenPakQt::SignOut();
    }
    void RefreshFriendCache() override {}
    void NotifyFriendRequestSent(const QString&) override {}
    QString JoinFriendSession(u64) override {
        return {};
    }
    void EnsureChatConnected() override {}
    NextendoChatClient* GetChatClient() override {
        return nullptr;
    }

    QString ResolveGameName(const std::string& app_id_hex,
                            const std::string& hint_name) const override {
        u64 id = 0;
        try {
            id = std::stoull(app_id_hex, nullptr, 16);
        } catch (...) {
            return QString::fromStdString(hint_name);
        }
        if (hooks.titles) {
            for (const auto& [title, name] : hooks.titles()) {
                if (title == id && !name.isEmpty()) {
                    return name;
                }
            }
        }
        return QString::fromStdString(hint_name);
    }
    QString ResolveGameIcon(const std::string&) const override {
        return {};
    }
    std::string GetLocalAppId() const override {
        if (running_title == 0 || !hooks.game_running || !hooks.game_running()) {
            return {};
        }
        return fmt::format("{:016x}", running_title);
    }
    void QuickStart(u64) override {}
    void ManualSaveDownload(u64) override {}
    std::filesystem::path SaveDirectory(u64 title_id) override {
        return IsApplication(title_id) ? SaveFolder(title_id) : std::filesystem::path{};
    }
    std::vector<Title> InstalledTitles() const override {
        std::vector<Title> out;
        if (hooks.titles) {
            for (const auto& [id, name] : hooks.titles()) {
                if (IsApplication(id)) {
                    out.push_back({id, name});
                }
            }
        }
        return out;
    }
    // LayeredFS: one mod per title, applied from load/mods/<title id>/.
    std::filesystem::path ModDirectory(u64 title_id) override {
        return std::filesystem::path(FileUtil::GetUserPath(FileUtil::UserPath::LoadDir)) / "mods" /
               fmt::format("{:016X}", title_id);
    }
    bool OneModPerTitle() const override {
        return true;
    }

    QString AccentColor() const override {
        return qApp->palette().color(QPalette::Highlight).name();
    }
    bool IsDarkTheme() const override {
        return qApp->palette().color(QPalette::Window).lightness() < 128;
    }
    bool NotificationsEnabled() const override {
        return UISettings::values.openpak_notifications.GetValue();
    }
    void SetNotificationsEnabled(bool enabled) override {
        UISettings::values.openpak_notifications = enabled;
    }
    // The setting lists Bottom right, Bottom left, Top right, Top left; the toast counts
    // TopRight, TopLeft, BottomRight, BottomLeft.
    int NotificationCorner() const override {
        static constexpr int kToToast[] = {2, 3, 0, 1};
        return kToToast[std::clamp(UISettings::values.openpak_notification_corner.GetValue(), 0,
                                   3)];
    }
    void SetNotificationCorner(int corner) override {
        static constexpr int kFromToast[] = {2, 3, 0, 1};
        UISettings::values.openpak_notification_corner = kFromToast[std::clamp(corner, 0, 3)];
    }
    bool RedirectEnabled() const override {
        return OpenPakOn();
    }
    bool CloudSyncEnabled() const override {
        return UISettings::values.openpak_cloud_sync.GetValue();
    }
    void SetCloudSyncEnabled(bool enabled) override {
        UISettings::values.openpak_cloud_sync = enabled;
    }
    std::string ServerIp() const override {
        return {};
    }
    std::string NatIp() const override {
        return {};
    }
    void SetGuestInputSuspended(bool) override {}
    openpak::qt::Navigation* CreateNavigation(QObject*) override {
        return nullptr;
    }

    void AccountChanged(bool linked) {
        if (linked) {
            emit AccountLinked();
        } else {
            emit AccountUnlinked();
        }
    }
};

AzaharHost* g_host = nullptr;
NextendoToast* g_toast = nullptr;
openpak::qt::FriendNotifier* g_friends = nullptr;
std::optional<QPixmap> g_avatar;
std::set<u64> g_paused; // titles whose automatic sync waits for a conflict to be resolved
// Pushes in flight, so the exit can wait for them.
std::mutex g_pushes_mutex;
std::condition_variable g_pushes_cv;
int g_pushes = 0;

// Toasts belong to the UI thread; this may be called from any.
void Toast(const QString& text, Kind kind) {
    QMetaObject::invokeMethod(
        qApp,
        [text, kind] {
            if (g_toast) {
                g_toast->Show(text, {}, {}, kind);
            }
        },
        Qt::QueuedConnection);
}

QString NameOf(u64 title_id) {
    const QString name =
        g_host ? g_host->ResolveGameName(fmt::format("{:016x}", title_id), {}) : QString{};
    return name.isEmpty() ? QString::fromStdString(fmt::format("{:016X}", title_id)) : name;
}

void LoadAvatar() {
    g_avatar.reset();
    if (!SignedIn()) {
        return;
    }
    std::thread([] {
        auto profile = std::make_shared<Api::Profile>(Api::GetProfile());
        QMetaObject::invokeMethod(
            qApp,
            [profile] {
                if (!profile->ok || profile->image_base64.empty()) {
                    return;
                }
                const QPixmap source =
                    Nextendo::AvatarCache::Get("self", profile->image_base64, 64);
                if (source.isNull()) {
                    return;
                }
                QPixmap round(20, 20);
                round.fill(Qt::transparent);
                QPainter painter(&round);
                painter.setRenderHint(QPainter::Antialiasing);
                QPainterPath clip;
                clip.addEllipse(0, 0, 20, 20);
                painter.setClipPath(clip);
                painter.drawPixmap(0, 0, 20, 20, source);
                g_avatar = round;
            },
            Qt::QueuedConnection);
    }).detach();
}

// The stored sign-in, checked once at launch: an expired one is dropped with a toast, never a
// prompt; a live one fetches the 3DS identity for the Account page.
void CheckStoredSignIn() {
    if (!SignedIn()) {
        return;
    }
    std::thread([] {
        auto profile = std::make_shared<Api::Profile>(Api::GetProfile());
        if (profile->ok) {
            FetchIdentity();
        }
        QMetaObject::invokeMethod(
            qApp,
            [profile] {
                if (!profile->ok && !SignedIn()) {
                    Toast(Tr("The OpenPak sign-in for %1 has expired. Sign in again from the "
                             "OpenPak menu.")
                              .arg(QStringLiteral("Azahar")),
                          Kind::Account);
                    g_host->AccountChanged(false);
                    return;
                }
                if (profile->ok && !profile->name.empty() &&
                    profile->name != Common::OpenPakAccount::GetUsername()) {
                    Common::OpenPakAccount::SaveBearerOnly(profile->name,
                                                           Common::OpenPakAccount::GetBearer());
                }
            },
            Qt::QueuedConnection);
    }).detach();
    LoadAvatar();
}

QString SourceWord(const std::string& source) {
    if (source == "fetched") {
        return Tr("fetched");
    }
    if (source == "cached") {
        return Tr("cached");
    }
    return Tr("built in");
}

} // namespace

bool SignedIn() {
    return Common::OpenPakAccount::HasBearer();
}

QString AccountName() {
    return SignedIn() ? QString::fromStdString(Common::OpenPakAccount::GetUsername()) : QString{};
}

void Init(QMainWindow* window, Hooks hooks) {
    const std::filesystem::path config_dir = FileUtil::GetUserPath(FileUtil::UserPath::ConfigDir);
    const std::filesystem::path cache_dir = FileUtil::GetUserPath(FileUtil::UserPath::CacheDir);
    openpak::Platform::SetDirectories(config_dir, cache_dir / "openpak");
    openpak::Platform::SetClient("azahar", Common::g_build_version);
    // The website is read once, before the first request (the library keeps it for the run).
    if (const std::string website = UISettings::values.openpak_website.GetValue();
        !website.empty()) {
        qputenv("OPENPAK_API", QByteArray::fromStdString(website));
    }
    Api::SetSavesPlatform("3ds");
    if (const std::string device = UISettings::values.openpak_device_name.GetValue();
        !device.empty()) {
        Api::SetSaveDevice(device);
        Api::SetDeviceName(device);
    }

    g_host = new AzaharHost(qApp);
    g_host->main_window = window;
    g_host->hooks = std::move(hooks);
    openpak::qt::Host::SetCurrent(g_host);
    g_toast = new NextendoToast(window);
    QObject::connect(g_toast, &NextendoToast::clicked, g_host, [](NextendoToast::Kind kind) {
        QWidget* parent = g_host->main_window;
        switch (kind) {
        case Kind::Online:
        case Kind::Offline:
        case Kind::Request:
            if (SignedIn()) {
                OpenWindow(parent, OpenPakAccountDialog::kFriendsPage);
            }
            break;
        case Kind::Saves:
            if (SignedIn()) {
                OpenWindow(parent, OpenPakAccountDialog::kCloudSavesPage);
            }
            break;
        case Kind::Account:
            if (SignedIn()) {
                OpenWindow(parent, OpenPakAccountDialog::kAccountPage);
            } else if (!g_host->hooks.game_running || !g_host->hooks.game_running()) {
                ShowSignIn(parent);
            }
            break;
        default:
            break;
        }
    });
    g_friends = new openpak::qt::FriendNotifier(g_toast, g_host);
    CheckStoredSignIn();

    // One conditional request at launch, off the UI thread, so the first game already runs on
    // the profile's host map; a game started sooner uses the last-known-good or compiled-in one.
    if (OpenPakOn()) {
        OpenPakProfile::FetchAtLaunch();
    }
}

void AddMenu(QMenuBar* menu_bar) {
    openpak::qt::MenuHooks hooks;
    hooks.game_running = [] { return g_host->hooks.game_running && g_host->hooks.game_running(); };
    hooks.openpak_on = &OpenPakOn;
    hooks.signed_in_as = &AccountName;
    hooks.avatar = [] { return g_avatar.value_or(QPixmap{}); };
    hooks.sign_in = [] { ShowSignIn(g_host->main_window); };
    hooks.sign_out = [] { SignOut(); };
    hooks.open_window = [](int page) { OpenWindow(g_host->main_window, page); };
    hooks.open_settings = [] {
        if (g_host->hooks.open_settings) {
            g_host->hooks.open_settings();
        }
    };
    openpak::qt::AddOpenPakMenu(menu_bar, std::move(hooks));
}

bool ShowSignIn(QWidget* parent) {
    if (g_host->hooks.game_running && g_host->hooks.game_running()) {
        return false;
    }
    OpenPakSignInDialog dialog(parent);
    const std::string device = UISettings::values.openpak_device_name.GetValue();
    dialog.SetDeviceName(device.empty() ? openpak::qt::DefaultDeviceName(QStringLiteral("Azahar"))
                                        : QString::fromStdString(device));
    // Off the UI thread: the website token, then the account's 3DS identity.
    dialog.SetSubmitter([](QString email, QString password, QString device_name) {
        Api::SetDeviceName(device_name.toStdString());
        const QString error = openpak::qt::SignInAccountOnly(email, password, device_name);
        if (error.isEmpty()) {
            FetchIdentity();
        }
        return error;
    });
    if (dialog.exec() != QDialog::Accepted || !SignedIn()) {
        return false;
    }
    UISettings::values.openpak_device_name = dialog.DeviceName().toStdString();
    const auto identity = CurrentIdentity();
    Toast(identity && !identity->friend_code.empty()
              ? Tr("Signed in as %1, and this console is now linked to your account.")
                    .arg(AccountName())
              : Tr("Signed in as %1.").arg(AccountName()),
          Kind::Account);
    LoadAvatar();
    if (g_friends) {
        g_friends->Reset();
    }
    g_host->AccountChanged(true);
    return true;
}

void SignOut() {
    openpak::qt::SignOutAndRevoke();
    g_avatar.reset();
    {
        std::lock_guard lock(g_identity_mutex);
        g_identity.reset();
    }
    Toast(Tr("Signed out of OpenPak."), Kind::Account);
    if (g_friends) {
        g_friends->Reset();
    }
    g_host->AccountChanged(false);
}

void OpenWindow(QWidget* parent, int page) {
    OpenPakAccountDialog dialog(g_host, parent ? parent : g_host->main_window, page);
    dialog.exec();
}

void MaybeAskToConnect() {
    if (!g_host || UISettings::values.openpak_connect_asked.GetValue()) {
        return;
    }
    UISettings::values.openpak_connect_asked = true;
    if (SignedIn()) {
        return;
    }
    if (!openpak::qt::AskToConnect(g_host->main_window, openpak::qt::Family::N3DS)) {
        return;
    }
    if (ShowSignIn(g_host->main_window)) {
        // Signing in from the prompt turns on the connection and cloud sync.
        Settings::values.use_openpak_network = true;
        UISettings::values.openpak_cloud_sync = true;
        OpenPakProfile::FetchAtLaunch();
    }
}

QString NetworkStatusLine() {
    const OpenPakProfile::Applied applied = OpenPakProfile::Current();
    const QString what =
        applied.fetched ? Tr("version %1, %2").arg(applied.version).arg(SourceWord(applied.source))
                        : SourceWord("built-in");
    return Tr("Network settings: %1.").arg(what);
}

void RefreshNetwork(std::function<void()> done) {
    std::thread([done = std::move(done)] {
        OpenPakProfile::Refresh();
        QMetaObject::invokeMethod(qApp, [done] { done(); }, Qt::QueuedConnection);
    }).detach();
}

void BeforeBoot(u64 title_id, const QString& title_name) {
    if (g_host) {
        g_host->running_title = title_id;
    }
    if (!IsApplication(title_id) || !SignedIn() ||
        !UISettings::values.openpak_cloud_sync.GetValue()) {
        return;
    }
    const std::filesystem::path folder = SaveFolder(title_id);

    // The pull runs on a worker; this (UI) thread keeps drawing in a local event loop and gives
    // up after five seconds or on Skip. still_wanted is asked once more before anything is
    // written, so a pull that lands after the ceiling touches nothing.
    struct Wait {
        std::atomic<bool> wanted{true};
        std::atomic<bool> done{false};
        Nextendo::SaveSync::PullOutcome result = Nextendo::SaveSync::PullOutcome::Nothing;
    };
    auto wait = std::make_shared<Wait>();
    QEventLoop loop;
    QPointer<QEventLoop> loop_ptr(&loop);
    std::thread([wait, folder, title_id, loop_ptr] {
        const auto result = Nextendo::SaveSync::PullBeforeLaunch(
            folder, title_id, [wait] { return wait->wanted.load(); });
        wait->result = result;
        wait->done = true;
        QMetaObject::invokeMethod(
            qApp,
            [loop_ptr] {
                if (loop_ptr) {
                    loop_ptr->quit();
                }
            },
            Qt::QueuedConnection);
    }).detach();

    QDialog box(g_host->main_window);
    box.setWindowTitle(Tr("OpenPak"));
    auto* layout = new QVBoxLayout(&box);
    layout->addWidget(new QLabel(Tr("Checking cloud save...")));
    auto* bar = new QProgressBar;
    bar->setRange(0, 0);
    bar->setTextVisible(false);
    layout->addWidget(bar);
    auto* buttons = new QDialogButtonBox;
    buttons->addButton(Tr("Skip"), QDialogButtonBox::RejectRole);
    QObject::connect(buttons, &QDialogButtonBox::rejected, &loop, &QEventLoop::quit);
    layout->addWidget(buttons);
    QTimer show_later;
    show_later.setSingleShot(true);
    QObject::connect(&show_later, &QTimer::timeout, &box, [&box] { box.show(); });
    show_later.start(300);
    QTimer ceiling;
    ceiling.setSingleShot(true);
    QObject::connect(&ceiling, &QTimer::timeout, &loop, &QEventLoop::quit);
    ceiling.start(5000);
    box.setWindowModality(Qt::ApplicationModal);
    if (!wait->done) {
        loop.exec();
    }
    // Skip, the ceiling or the answer: from here on a late pull writes nothing.
    wait->wanted = wait->done.load();
    show_later.stop();
    box.hide();

    const auto result = wait->done ? wait->result : Nextendo::SaveSync::PullOutcome::Nothing;
    if (result == Nextendo::SaveSync::PullOutcome::Pulled) {
        g_paused.erase(title_id);
        LOG_INFO(Frontend, "OpenPak: cloud save applied for {:016X}", title_id);
        Toast(Tr("Cloud save for %1 downloaded; the previous local copy was kept beside it.")
                  .arg(title_name.isEmpty() ? NameOf(title_id) : title_name),
              Kind::Saves);
    } else if (result == Nextendo::SaveSync::PullOutcome::BothExist) {
        // Starts on the local save; automatic sync for this title waits for the choice.
        g_paused.insert(title_id);
        Toast(Tr("%1 has a save here and a different one in the cloud. Choose one on the Cloud "
                 "saves page.")
                  .arg(title_name.isEmpty() ? NameOf(title_id) : title_name),
              Kind::Saves);
    } else {
        g_paused.erase(title_id);
    }
}

void AfterRun(u64 title_id) {
    if (g_host) {
        g_host->running_title = 0;
    }
    if (!IsApplication(title_id) || !SignedIn() ||
        !UISettings::values.openpak_cloud_sync.GetValue() || g_paused.count(title_id) != 0) {
        return;
    }
    // Local I/O here, while the save is final; the network on a worker.
    const std::filesystem::path folder = SaveFolder(title_id);
    std::vector<u8> zip = Nextendo::SaveSync::CaptureOnExit(folder, title_id);
    if (zip.empty()) {
        return;
    }
    const QString name = NameOf(title_id);
    {
        std::lock_guard lock(g_pushes_mutex);
        ++g_pushes;
    }
    std::thread([folder, title_id, name, zip = std::move(zip)]() mutable {
        const std::string error =
            Nextendo::SaveSync::PushCaptured(folder, title_id, std::move(zip));
        if (error.empty()) {
            LOG_INFO(Frontend, "OpenPak: save pushed for {:016X}", title_id);
            Toast(Tr("Save for %1 uploaded to OpenPak.").arg(name), Kind::Saves);
        } else {
            LOG_WARNING(Frontend, "OpenPak: save push for {:016X} failed: {}", title_id, error);
            Toast(Tr("The save for %1 did not upload: %2").arg(name, QString::fromStdString(error)),
                  Kind::Saves);
        }
        std::lock_guard lock(g_pushes_mutex);
        --g_pushes;
        g_pushes_cv.notify_all();
    }).detach();
}

void FinishPending() {
    // A save still going up when the window closes gets half a minute to arrive.
    std::unique_lock lock(g_pushes_mutex);
    g_pushes_cv.wait_for(lock, std::chrono::seconds(30), [] { return g_pushes == 0; });
}

} // namespace OpenPakQt
