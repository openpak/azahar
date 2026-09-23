// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.openpak.model

/**
 * The bridge to openpak-client (jni/openpak_native.cpp). Everything that answers with data
 * answers with a JSON string. Calls marked "network" block: make them from Dispatchers.IO (see
 * [OpenPak]), never the main thread.
 */
object OpenPakNative {
    /** Directories, client name, saves platform; fetches the network profile off-thread. */
    @JvmStatic
    external fun init(configDir: String, cacheDir: String, device: String)

    /** Local: {signed_in, name, website, status_url, enabled, cloud_sync, notifications}. */
    @JvmStatic
    external fun state(): String

    /** Network: {ok, name, linked} or {ok:false, code, message}. */
    @JvmStatic
    external fun signIn(email: String, password: String, device: String): String

    /** Network: forgets the token here and revokes it on the server. */
    @JvmStatic
    external fun signOut()

    /** Network. */
    @JvmStatic
    external fun profile(): String

    /** Network when [refresh] or nothing is known yet: {ok, friend_code, pid, username}. */
    @JvmStatic
    external fun identity(refresh: Boolean): String

    /** Network: {ok, friends, requests}. */
    @JvmStatic
    external fun friends(): String

    /** Network. Empty on success, else why. */
    @JvmStatic
    external fun sendFriendRequest(friendCode: String): String

    /** Network. Empty on success, else why. */
    @JvmStatic
    external fun acceptFriendRequest(accountId: String, pid: String): String

    /** Network: declines an incoming request or cancels an outgoing one. */
    @JvmStatic
    external fun declineFriendRequest(accountId: String, pid: String): String

    /** Network. Empty on success, else why. */
    @JvmStatic
    external fun removeFriend(accountId: String): String

    /** Network. Empty on success, else why. */
    @JvmStatic
    external fun blockAccount(accountId: String): String

    /** Network, plus local I/O for each title's local copy. */
    @JvmStatic
    external fun cloudSaves(): String

    /** Network: deletes every listed version. Empty on success, else why. */
    @JvmStatic
    external fun deleteSave(versionIds: LongArray): String

    /** Network: take the cloud's copy. Empty on success, else why. */
    @JvmStatic
    external fun downloadSave(titleId: String): String

    /** Network: keep this machine's copy. Empty on success, else why. */
    @JvmStatic
    external fun uploadSave(titleId: String, newestCloud: Int): String

    /** Network: {mods, favourites} for a title. */
    @JvmStatic
    external fun mods(titleId: String): String

    /** Network. */
    @JvmStatic
    external fun setModFavourite(modId: String, favourite: Boolean): Boolean

    /** Network: the status page and the player counts. */
    @JvmStatic
    external fun status(): String

    /** Network: {ping_ms?, nat?}. */
    @JvmStatic
    external fun testConnection(): String

    /** Network: "Refresh network settings"; answers {fetched, version, source}. */
    @JvmStatic
    external fun refreshNetwork(): String

    /** Local: the applied network profile, {fetched, version, source}. */
    @JvmStatic
    external fun networkSummary(): String

    /** Skip on the "Checking cloud save..." line. */
    @JvmStatic
    external fun skipCloudPull()
}
