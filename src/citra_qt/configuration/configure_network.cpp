// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <QIcon>
#include <QMessageBox>
#include <QtConcurrent/QtConcurrentRun>
#include "citra_qt/configuration/configure_network.h"
#include "citra_qt/uisettings.h"
#include "core/openpak_profile.h"
#include "ui_configure_network.h"

ConfigureWeb::ConfigureWeb(QWidget* parent)
    : QWidget(parent), ui(std::make_unique<Ui::ConfigureWeb>()) {
    ui->setupUi(this);

#ifndef ENABLE_DISCORD_RPC
    ui->discord_group->setEnabled(false);
#endif
#ifndef ENABLE_WEB_SERVICE
    ui->web_api_url_lineedit->setEnabled(false);
    ui->token_lineedit->setEnabled(false);
#endif
    // OpenPak: the refresh action re-runs the launch fetch; bounded by its two-second
    // timeout, and the applied profile is swapped whole.
    connect(ui->openpak_profile_refresh, &QPushButton::clicked, this, [this] {
        ui->openpak_profile_refresh->setEnabled(false);
        ui->openpak_profile_status->setText(tr("Refreshing..."));
        OpenPakProfile::Refresh();
        UpdateOpenPakProfileStatus();
        ui->openpak_profile_refresh->setEnabled(true);
    });
    SetConfiguration();
}

ConfigureWeb::~ConfigureWeb() = default;

void ConfigureWeb::SetConfiguration() {

    ui->web_api_url_lineedit->setText(
        QString::fromStdString(Settings::values.web_api_url.GetValue()));
    ui->token_lineedit->setText(QString::fromStdString(Settings::values.network_token.GetValue()));

#ifdef ENABLE_DISCORD_RPC
    ui->toggle_discordrpc->setChecked(UISettings::values.enable_discord_presence.GetValue());
#endif
    UpdateOpenPakProfileStatus();
}

// OpenPak: one line saying which profile is in effect — the first question answered when a
// player reports "it can't connect".
void ConfigureWeb::UpdateOpenPakProfileStatus() {
    ui->openpak_profile_status->setText(
        QString::fromStdString(OpenPakProfile::StatusLine()));
}

void ConfigureWeb::ApplyConfiguration() {
#ifdef ENABLE_DISCORD_RPC
    UISettings::values.enable_discord_presence = ui->toggle_discordrpc->isChecked();
#endif

    Settings::values.web_api_url = ui->web_api_url_lineedit->text().toStdString();
    Settings::values.network_token = ui->token_lineedit->text().toStdString();
}

void ConfigureWeb::RetranslateUI() {
    ui->retranslateUi(this);
}
