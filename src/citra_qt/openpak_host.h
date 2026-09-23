// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

// OpenPak for Azahar's desktop frontend (emulators/prds/openpak-ux-spec.md, 3DS family): the
// OpenPak menu, the shared window from openpak-client's Qt library, sign-in, the connect prompt,
// the sign-out confirmation, toasts, and cloud saves around a run. Configure -> OpenPak is
// ConfigureOpenPak (configuration/configure_openpak.h), which calls back in here.

#pragma once

#include <functional>
#include <utility>
#include <vector>

#include <QString>

#include "common/common_types.h"

class QMainWindow;
class QMenuBar;
class QWidget;

namespace OpenPakQt {

struct Hooks {
    std::function<bool()> game_running;
    std::function<void()> open_settings;                          // Configure, at the OpenPak tab
    std::function<std::vector<std::pair<u64, QString>>()> titles; // the game list
};

// Once, from the main window's constructor (after the config is read): the client library's
// files, the website, the saves platform; the toasts; the stored sign-in checked off the UI
// thread; the network profile fetched off the UI thread.
void Init(QMainWindow* window, Hooks hooks);

// The OpenPak menu, immediately left of Help.
void AddMenu(QMenuBar* menu_bar);

// On a plain interactive launch (no game given): the connect prompt, once per install.
void MaybeAskToConnect();

bool SignedIn();
QString AccountName();
// The sign-in dialog; true when it signed in.
bool ShowSignIn(QWidget* parent);
// After the confirmation, which the caller shows.
void SignOut();
void OpenWindow(QWidget* parent, int page);

// "Network settings: version 3, fetched." for the settings' Advanced section.
QString NetworkStatusLine();
// Re-fetches the network profile off the UI thread; done runs on the UI thread.
void RefreshNetwork(std::function<void()> done);

// Cloud saves around a run (UX spec §5.1). Before: the newest cloud copy comes down when the
// local save is not already in step -- at most five seconds, with "Checking cloud save..." and
// Skip, never blocking longer. After: the save goes up off the UI thread.
void BeforeBoot(u64 title_id, const QString& title_name);
void AfterRun(u64 title_id);
// At exit: waits for pushes still in flight.
void FinishPending();

} // namespace OpenPakQt
