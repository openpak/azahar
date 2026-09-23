// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <algorithm>

#include <QCheckBox>
#include <QComboBox>
#include <QCoreApplication>
#include <QFormLayout>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QLayout>
#include <QLineEdit>
#include <QPointer>
#include <QPushButton>
#include <QToolButton>
#include <QVBoxLayout>

#include <openpak/qt/account_dialog.h>
#include <openpak/qt/prompts.h>

#include "citra_qt/configuration/configure_openpak.h"
#include "citra_qt/openpak_host.h"
#include "citra_qt/uisettings.h"
#include "common/settings.h"
#include "core/openpak_profile.h"

namespace {
QString Tr(const char* text) {
    return QCoreApplication::translate("OpenPak", text);
}
} // namespace

ConfigureOpenPak::ConfigureOpenPak(bool is_powered_on_, QWidget* parent)
    : QWidget(parent), is_powered_on{is_powered_on_} {
    Build();
    SetConfiguration();
}

ConfigureOpenPak::~ConfigureOpenPak() = default;

void ConfigureOpenPak::Build() {
    auto* layout = new QVBoxLayout(this);

    // Account
    auto* account = new QGroupBox(Tr("Account"));
    auto* account_layout = new QVBoxLayout(account);
    enable = new QCheckBox(Tr("Connect this emulator to OpenPak"));
    enable->setToolTip(Tr("When this is off the emulator behaves exactly as upstream does: "
                          "offline, and nothing is sent anywhere."));
    account_layout->addWidget(enable);
    auto* account_row = new QHBoxLayout;
    account_text = new QLabel;
    account_text->setWordWrap(true);
    account_button = new QPushButton;
    account_row->addWidget(account_text, 1);
    account_row->addWidget(account_button);
    account_layout->addLayout(account_row);
    auto* open = new QPushButton(Tr("Open OpenPak..."));
    auto* open_row = new QHBoxLayout;
    open_row->addWidget(open);
    open_row->addStretch(1);
    account_layout->addLayout(open_row);
    cloud_sync = new QCheckBox(Tr("Sync cloud saves automatically when a game starts and stops"));
    account_layout->addWidget(cloud_sync);
    layout->addWidget(account);

    // Notifications
    auto* notifications_box = new QGroupBox(Tr("Notifications"));
    auto* notifications_layout = new QFormLayout(notifications_box);
    notifications = new QCheckBox(Tr("Show notifications"));
    notifications_layout->addRow(notifications);
    corner = new QComboBox;
    corner->addItems({Tr("Bottom right"), Tr("Bottom left"), Tr("Top right"), Tr("Top left")});
    notifications_layout->addRow(Tr("Notification corner"), corner);
    layout->addWidget(notifications_box);

    // Advanced, collapsed
    auto* advanced_toggle = new QToolButton;
    advanced_toggle->setText(Tr("Advanced"));
    advanced_toggle->setCheckable(true);
    advanced_toggle->setToolButtonStyle(Qt::ToolButtonTextBesideIcon);
    advanced_toggle->setArrowType(Qt::RightArrow);
    advanced_toggle->setAutoRaise(true);
    layout->addWidget(advanced_toggle);
    auto* advanced = new QWidget;
    auto* advanced_layout = new QFormLayout(advanced);
    website = new QLineEdit;
    website->setPlaceholderText(QStringLiteral("https://openpak.org"));
    website->setToolTip(Tr("Where the account lives and where you sign in. Leave this at "
                           "openpak.org unless you run your own deployment."));
    advanced_layout->addRow(Tr("Website"), website);
    refresh = new QPushButton(Tr("Refresh network settings"));
    network_status = new QLabel;
    network_status->setWordWrap(true);
    auto* refresh_row = new QHBoxLayout;
    refresh_row->addWidget(refresh);
    refresh_row->addWidget(network_status, 1);
    advanced_layout->addRow(refresh_row);
    advanced->hide();
    layout->addWidget(advanced);
    layout->addStretch(1);
    connect(advanced_toggle, &QToolButton::toggled, advanced, [advanced, advanced_toggle](bool on) {
        advanced->setVisible(on);
        advanced_toggle->setArrowType(on ? Qt::DownArrow : Qt::RightArrow);
    });

    // No network on the UI thread: the refresh runs beside it and reports back.
    connect(refresh, &QPushButton::clicked, this, [this] {
        refresh->setEnabled(false);
        network_status->setText(Tr("Checking..."));
        QPointer<ConfigureOpenPak> self{this};
        OpenPakQt::RefreshNetwork([self] {
            if (!self) {
                return;
            }
            self->network_status->setText(OpenPakQt::NetworkStatusLine());
            self->refresh->setEnabled(true);
        });
    });

    connect(account_button, &QPushButton::clicked, this, [this] {
        if (OpenPakQt::SignedIn()) {
            if (openpak::qt::ConfirmSignOut(this)) {
                OpenPakQt::SignOut();
            }
        } else {
            OpenPakQt::ShowSignIn(this);
        }
        UpdateAccount();
    });
    connect(open, &QPushButton::clicked, this,
            [this] { OpenPakQt::OpenWindow(this, OpenPakAccountDialog::kAccountPage); });
}

void ConfigureOpenPak::UpdateAccount() {
    if (OpenPakQt::SignedIn()) {
        account_text->setText(Tr("Signed in as %1").arg(OpenPakQt::AccountName()));
        account_button->setText(Tr("Sign out..."));
    } else {
        account_text->setText(Tr("Not signed in"));
        account_button->setText(Tr("Sign in..."));
    }
    // Sign in, sign out and the switch wait for the game to stop (UX spec §5.5).
    account_button->setEnabled(!is_powered_on);
    account_button->setToolTip(is_powered_on ? Tr("Stop the running game first.") : QString{});
    enable->setEnabled(!is_powered_on);
    enable->setToolTip(is_powered_on ? Tr("Stop the running game first.")
                                     : Tr("When this is off the emulator behaves exactly as "
                                          "upstream does: offline, and nothing is sent anywhere."));
}

void ConfigureOpenPak::SetConfiguration() {
    enable->setChecked(Settings::values.use_openpak_network.GetValue());
    cloud_sync->setChecked(UISettings::values.openpak_cloud_sync.GetValue());
    notifications->setChecked(UISettings::values.openpak_notifications.GetValue());
    corner->setCurrentIndex(
        std::clamp(UISettings::values.openpak_notification_corner.GetValue(), 0, 3));
    website->setText(QString::fromStdString(UISettings::values.openpak_website.GetValue()));
    network_status->setText(OpenPakQt::NetworkStatusLine());
    UpdateAccount();
}

void ConfigureOpenPak::ApplyConfiguration() {
    const bool turned_on = enable->isChecked() && !Settings::values.use_openpak_network.GetValue();
    Settings::values.use_openpak_network = enable->isChecked();
    if (turned_on) {
        // The launch fetch was skipped while the connection was off.
        OpenPakProfile::FetchAtLaunch();
    }
    UISettings::values.openpak_cloud_sync = cloud_sync->isChecked();
    UISettings::values.openpak_notifications = notifications->isChecked();
    UISettings::values.openpak_notification_corner = corner->currentIndex();
    // Read at the next launch: the library keeps the website it started with for the run.
    UISettings::values.openpak_website = website->text().trimmed().toStdString();
}

void ConfigureOpenPak::RetranslateUI() {
    // The OpenPak strings come from the shared table (context "OpenPak"); rebuilding the tab is
    // what re-reads them.
    SetConfiguration();
}
