// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <QWidget>

class QCheckBox;
class QComboBox;
class QLabel;
class QLineEdit;
class QPushButton;

// Configure -> OpenPak (UX spec §3.13): the connection switch, the account row, Open OpenPak...,
// cloud sync, notifications, and a collapsed Advanced with the website and Refresh network
// settings. The switches apply with the dialog; signing in and out happen at once.
class ConfigureOpenPak : public QWidget {
    Q_OBJECT

public:
    explicit ConfigureOpenPak(bool is_powered_on, QWidget* parent = nullptr);
    ~ConfigureOpenPak() override;

    void SetConfiguration();
    void ApplyConfiguration();
    void RetranslateUI();

private:
    void Build();
    void UpdateAccount();

    bool is_powered_on;
    QCheckBox* enable = nullptr;
    QLabel* account_text = nullptr;
    QPushButton* account_button = nullptr;
    QCheckBox* cloud_sync = nullptr;
    QCheckBox* notifications = nullptr;
    QComboBox* corner = nullptr;
    QLineEdit* website = nullptr;
    QPushButton* refresh = nullptr;
    QLabel* network_status = nullptr;
};
