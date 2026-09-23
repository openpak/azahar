// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

// OpenPak cloud saves around a 3DS application's run, called from the emulation thread in
// native.cpp. Every step is a no-op unless an account is signed in and "Sync cloud saves
// automatically" is on.

#pragma once

#include <string>

namespace OpenPakNative {

// From config.cpp on every (re)load: the [OpenPak] switches that live outside Settings::values.
void SetOptions(bool cloud_sync, bool notifications);

// Before the core loads the title: pulls the newest cloud copy of its save. Blocks for at most
// five seconds (or until the player taps Skip) and never stops the boot. Call it without the
// surface lock held: the UI thread must stay free while it waits.
void BeforeBoot(const std::string& filepath);

// Once the core runs: says what the pull did.
void AfterBoot();

// After the core has shut down: captures the save (local I/O) and uploads it off this thread.
void AfterShutdown();

} // namespace OpenPakNative
