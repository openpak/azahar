// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.openpak.model

import android.app.Application
import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.preference.PreferenceManager
import java.io.File
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.GameHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenPak as the Azahar app sees it: the 3DS family (emulators/prds/openpak-ux-spec.md §3.6).
 * An account signs in without a console identity of its own; the account's 3DS identity (friend
 * code and principal id) comes from the server. Friends, cloud saves, the mods catalogue and the
 * status page work from the account.
 *
 * Every suspend function here runs its native call on Dispatchers.IO: the network is never
 * touched from the main thread.
 */
object OpenPak {
    const val EMULATOR = "Azahar"

    data class State(
        val signedIn: Boolean,
        val name: String,
        val website: String,
        val statusUrl: String,
        val enabled: Boolean,
        val cloudSync: Boolean,
        val notifications: Boolean
    )

    data class Profile(
        val name: String,
        val accountId: String,
        val friendCode: String,
        val image: String,
        val linked: List<String>
    )

    /** The account's 3DS identity: what the console side knows it by. */
    data class Identity(val friendCode: String, val pid: String, val username: String)

    data class Friend(
        val accountId: String,
        val pid: String,
        val name: String,
        val friendCode: String,
        val online: Boolean,
        val titleId: String,
        val console: String,
        val onlineSince: Long,
        val friendsSince: Long,
        val incoming: Boolean
    ) {
        val key: String get() = accountId.ifEmpty { pid.ifEmpty { name } }
    }

    data class Friends(val friends: List<Friend>, val requests: List<Friend>)

    data class SaveVersion(
        val id: Long,
        val number: Int,
        val conflict: Boolean,
        val size: Long,
        val device: String,
        val savedAt: String
    )

    data class CloudSave(
        val titleId: String,
        val name: String,
        val versions: List<SaveVersion>,
        val localState: String,
        val localVersion: String,
        val lastWritten: Long
    ) {
        val newest: SaveVersion? get() = versions.firstOrNull()
        val conflict: Boolean get() = localState == "no_history" || versions.any { it.conflict }
    }

    data class CloudSaves(val saves: List<CloudSave>, val used: Long, val allowance: Long)

    data class Mod(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val licence: String,
        val summary: String
    )

    data class Mods(val mods: List<Mod>, val favourites: Set<String>)

    data class Service(val name: String, val up: Boolean, val uptime: Double, val latency: String)

    data class Count(val id: String, val players: Int)

    data class Status(
        val ok: Boolean,
        val url: String,
        val headline: String,
        val sub: String,
        val state: String,
        val services: List<Service>,
        val playersOk: Boolean,
        val players: Int,
        val titles: List<Count>,
        val networks: List<Count>,
        val refreshed: Long
    )

    data class Connection(val pingMs: Int?, val nat: String?)

    /** A result the screens show: the data, or a sentence from the string table. */
    sealed class Answer<out T> {
        data class Ok<T>(val value: T) : Answer<T>()
        data class Failed(val message: String) : Answer<Nothing>()
    }

    private const val PREFS = "openpak"
    private const val KEY_CONNECT_ASKED = "connect_asked"

    private var initialized = false

    private val appContext: Context get() = CitraApplication.appContext

    /**
     * Once per process, after Azahar's user directory and config are ready. Everything below
     * calls it first, so the first screen that asks is never too early.
     */
    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        if (initialized || !DirectoryInitialization.areCitraDirectoriesReady()) return
        initialized = true
        val app = context.applicationContext
        // The token stays in the app's private storage, not in the user directory.
        OpenPakNative.init(
            File(app.filesDir, "openpak").path,
            File(app.cacheDir, "openpak").path,
            defaultDeviceName(app)
        )
        (app as? Application)?.let { OpenPakNotifier.register(it) }
    }

    private fun ensureInit() = init(appContext)

    fun defaultDeviceName(context: Context): String =
        context.getString(R.string.openpak_signin_device_default_single)
            .replace("{emulator}", EMULATOR)
            .replace("{machine}", Build.MODEL)

    fun state(): State {
        ensureInit()
        val json = JSONObject(OpenPakNative.state())
        return State(
            signedIn = json.optBoolean("signed_in"),
            name = json.optString("name"),
            website = json.optString("website", "https://openpak.org"),
            statusUrl = json.optString("status_url"),
            enabled = json.optBoolean("enabled"),
            cloudSync = json.optBoolean("cloud_sync", true),
            notifications = json.optBoolean("notifications", true)
        )
    }

    /** "Signed in as {name}" or "Not signed in", for the settings rows. */
    fun accountLine(context: Context): String {
        val state = state()
        return if (state.signedIn) {
            context.getString(R.string.openpak_menu_signed_in_as, state.name)
        } else {
            context.getString(R.string.openpak_status_signed_out)
        }
    }

    /** Whether a game is running: sign-in, sign-out and the enable switch wait until it stops. */
    fun gameRunning(): Boolean = NativeLibrary.isRunning()

    /** The running title as 16 hex digits, or empty. */
    fun runningTitle(): String = if (gameRunning()) {
        String.format(Locale.ROOT, "%016x", NativeLibrary.getRunningTitleId())
    } else {
        ""
    }

    // ---- first run ----

    fun connectAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONNECT_ASKED, false)

    fun setConnectAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONNECT_ASKED, true).apply()
    }

    /**
     * After a sign-in from the connect screen (§3.2): the 3DS family's connection
     * (use_openpak_network) and cloud sync go on, and the core reads them at once.
     */
    suspend fun turnOnConnection() = withContext(Dispatchers.IO) {
        BooleanSetting.USE_OPENPAK_NETWORK.boolean = true
        SettingsFile.saveFile(SettingsFile.FILE_NAME_CONFIG, BooleanSetting.USE_OPENPAK_NETWORK)
        BooleanSetting.OPENPAK_CLOUD_SYNC.boolean = true
        SettingsFile.saveFile(SettingsFile.FILE_NAME_CONFIG, BooleanSetting.OPENPAK_CLOUD_SYNC)
        NativeLibrary.reloadSettings()
        // The profile fetch at start was skipped while the connection was off.
        OpenPakNative.refreshNetwork()
    }

    // ---- account ----

    sealed class SignInResult {
        data class Ok(val name: String, val linked: Boolean) : SignInResult()
        data class Failed(val message: String) : SignInResult()
    }

    suspend fun signIn(
        context: Context,
        email: String,
        password: String,
        device: String
    ): SignInResult = withContext(Dispatchers.IO) {
        ensureInit()
        val json = JSONObject(OpenPakNative.signIn(email.trim(), password, device.trim()))
        if (json.optBoolean("ok")) {
            SignInResult.Ok(json.optString("name"), json.optBoolean("linked"))
        } else {
            val website = state().website
            SignInResult.Failed(
                when (json.optString("code")) {
                    "credentials" -> context.getString(R.string.openpak_error_credentials)
                    "rate_limited" -> context.getString(R.string.openpak_error_rate_limited)
                    "server" -> context.getString(
                        R.string.openpak_error_server,
                        json.optString("message")
                    )

                    else -> context.getString(R.string.openpak_error_unreachable, host(website))
                }
            )
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        ensureInit()
        OpenPakNative.signOut()
    }

    /**
     * The stored sign-in, checked once per process: true when it was there and the server no
     * longer takes it (the library has dropped it by then). Never a prompt (§5.1).
     */
    suspend fun storedSignInExpired(): Boolean = withContext(Dispatchers.IO) {
        if (!state().signedIn) return@withContext false
        val json = JSONObject(OpenPakNative.profile())
        if (json.optBoolean("ok")) {
            OpenPakNative.identity(true)
            false
        } else {
            !json.optBoolean("signed_in", true)
        }
    }

    suspend fun profile(context: Context): Answer<Profile> = withContext(Dispatchers.IO) {
        ensureInit()
        val json = JSONObject(OpenPakNative.profile())
        if (!json.optBoolean("ok")) return@withContext failed(context, json.optString("error"))
        Answer.Ok(
            Profile(
                name = json.optString("name"),
                accountId = json.optString("account_id"),
                friendCode = json.optString("friend_code"),
                image = json.optString("image"),
                linked = json.optJSONArray("linked").strings()
            )
        )
    }

    /** The 3DS identity; null when the server has none for this account (yet). */
    suspend fun identity(refresh: Boolean): Identity? = withContext(Dispatchers.IO) {
        ensureInit()
        val json = JSONObject(OpenPakNative.identity(refresh))
        if (!json.optBoolean("ok")) {
            null
        } else {
            Identity(
                friendCode = json.optString("friend_code"),
                pid = json.optString("pid"),
                username = json.optString("username")
            )
        }
    }

    suspend fun friends(context: Context): Answer<Friends> = withContext(Dispatchers.IO) {
        ensureInit()
        val json = JSONObject(OpenPakNative.friends())
        if (!json.optBoolean("ok")) return@withContext failed(context, json.optString("error"))
        val friends = json.optJSONArray("friends").friends()
        // Online friends first, then alphabetical.
        val sorted = friends.sortedWith(
            compareBy<Friend> { !it.online }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
        Answer.Ok(Friends(sorted, json.optJSONArray("requests").friends()))
    }

    /** The friend actions: null on success, else the sentence to show. */
    suspend fun sendFriendRequest(context: Context, code: String): String? =
        action(context) { OpenPakNative.sendFriendRequest(code.trim()) }

    suspend fun acceptRequest(context: Context, friend: Friend): String? =
        action(context) { OpenPakNative.acceptFriendRequest(friend.accountId, friend.pid) }

    suspend fun declineRequest(context: Context, friend: Friend): String? =
        action(context) { OpenPakNative.declineFriendRequest(friend.accountId, friend.pid) }

    suspend fun removeFriend(context: Context, friend: Friend): String? =
        action(context) { OpenPakNative.removeFriend(friend.accountId) }

    suspend fun blockFriend(context: Context, friend: Friend): String? =
        action(context) { OpenPakNative.blockAccount(friend.accountId) }

    private suspend fun action(context: Context, call: () -> String): String? =
        withContext(Dispatchers.IO) {
            ensureInit()
            val error = call()
            if (error.isEmpty()) null else failed(context, error).message
        }

    suspend fun cloudSaves(context: Context): Answer<CloudSaves> = withContext(Dispatchers.IO) {
        ensureInit()
        val json = JSONObject(OpenPakNative.cloudSaves())
        if (!json.optBoolean("ok")) return@withContext failed(context, json.optString("error"))
        val array = json.optJSONArray("saves") ?: JSONArray()
        val names = localGameNames()
        val saves = (0 until array.length()).map { i ->
            val s = array.getJSONObject(i)
            val versionArray = s.optJSONArray("versions") ?: JSONArray()
            val versions = (0 until versionArray.length()).map { j ->
                val v = versionArray.getJSONObject(j)
                SaveVersion(
                    id = v.optLong("id"),
                    number = v.optInt("number"),
                    conflict = v.optBoolean("conflict"),
                    size = v.optLong("size"),
                    device = v.optString("device"),
                    savedAt = v.optString("saved_at")
                )
            }
            val titleId = s.optString("title_id").lowercase(Locale.ROOT)
            val local = s.optJSONObject("local") ?: JSONObject()
            val serverName = s.optString("name")
            CloudSave(
                titleId = titleId,
                // The site falls back to the title id when it does not know the game.
                name = names[titleId] ?: serverName.ifEmpty { titleId.uppercase(Locale.ROOT) },
                versions = versions,
                localState = local.optString("state", "none"),
                localVersion = local.optString("version"),
                lastWritten = local.optLong("last_written")
            )
        }
        Answer.Ok(CloudSaves(saves, json.optLong("used"), json.optLong("allowance")))
    }

    suspend fun deleteSave(save: CloudSave): String = withContext(Dispatchers.IO) {
        OpenPakNative.deleteSave(save.versions.map { it.id }.toLongArray())
    }

    suspend fun downloadSave(save: CloudSave): String =
        withContext(Dispatchers.IO) { OpenPakNative.downloadSave(save.titleId) }

    suspend fun uploadSave(save: CloudSave): String = withContext(Dispatchers.IO) {
        OpenPakNative.uploadSave(save.titleId, save.newest?.number ?: 0)
    }

    fun isInstalled(titleId: String): Boolean = localGameNames().containsKey(titleId)

    suspend fun mods(titleId: String): Mods = withContext(Dispatchers.IO) {
        ensureInit()
        val json = JSONObject(OpenPakNative.mods(titleId))
        val array = json.optJSONArray("mods") ?: JSONArray()
        val mods = (0 until array.length()).map { i ->
            val m = array.getJSONObject(i)
            Mod(
                id = m.optString("id"),
                name = m.optString("name"),
                version = m.optString("version"),
                author = m.optString("author"),
                licence = m.optString("licence"),
                summary = m.optString("summary")
            )
        }
        Mods(mods, json.optJSONArray("favourites").strings().toSet())
    }

    suspend fun setModFavourite(mod: Mod, favourite: Boolean): Boolean =
        withContext(Dispatchers.IO) { OpenPakNative.setModFavourite(mod.id, favourite) }

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        ensureInit()
        val json = JSONObject(OpenPakNative.status())
        val services = json.optJSONArray("services") ?: JSONArray()
        Status(
            ok = json.optBoolean("ok"),
            url = json.optString("url"),
            headline = json.optString("headline"),
            sub = json.optString("sub"),
            state = json.optString("state"),
            services = (0 until services.length()).map { i ->
                val s = services.getJSONObject(i)
                Service(
                    s.optString("name"),
                    s.optBoolean("up"),
                    s.optDouble("uptime"),
                    s.optString("latency")
                )
            },
            playersOk = json.optBoolean("players_ok"),
            players = json.optInt("players"),
            titles = json.optJSONArray("titles").counts(),
            networks = json.optJSONArray("networks").counts(),
            refreshed = System.currentTimeMillis()
        )
    }

    suspend fun testConnection(): Connection = withContext(Dispatchers.IO) {
        ensureInit()
        val json = JSONObject(OpenPakNative.testConnection())
        Connection(
            pingMs = if (json.has("ping_ms")) json.optInt("ping_ms") else null,
            nat = json.optString("nat").ifEmpty { null }
        )
    }

    /** "Refresh network settings", then the {0} of "Network settings: {0}." */
    suspend fun refreshNetwork(context: Context): String = withContext(Dispatchers.IO) {
        ensureInit()
        networkWords(context, OpenPakNative.refreshNetwork())
    }

    /** The {0} of "Network settings: {0}." for the applied profile, without asking anybody. */
    fun networkSummary(context: Context): String {
        ensureInit()
        return networkWords(context, OpenPakNative.networkSummary())
    }

    private fun networkWords(context: Context, summary: String): String {
        val json = JSONObject(summary)
        if (!json.optBoolean("fetched")) return context.getString(R.string.openpak_network_built_in)
        val source = when (json.optString("source")) {
            "fetched" -> context.getString(R.string.openpak_network_fetched)
            "cached" -> context.getString(R.string.openpak_network_cached)
            else -> context.getString(R.string.openpak_network_built_in)
        }
        return context.getString(
            R.string.openpak_network_version,
            json.optInt("version").toString(),
            source
        )
    }

    // ---- the library's 3DS applications, by title id ----

    data class LocalGame(val titleId: String, val name: String)

    /** The applications in the game list, for names and the Mods title picker. */
    fun localGames(): List<LocalGame> {
        val games: List<Game> = GameHelper.cachedGameList.toList().ifEmpty { storedGames() }
        return games.asSequence()
            .filter { !it.isSystemTitle && (it.titleId ushr 32) == 0x00040000L }
            .map { LocalGame(String.format(Locale.ROOT, "%016x", it.titleId), it.title) }
            .distinctBy { it.titleId }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    private fun storedGames(): List<Game> {
        val stored = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getStringSet(GameHelper.KEY_GAMES, emptySet()) ?: emptySet()
        return stored.mapNotNull {
            try {
                Json.decodeFromString<Game>(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun localGameNames(): Map<String, String> =
        localGames().associate { it.titleId to it.name }

    fun gameName(titleId: String): String? =
        if (titleId.isEmpty()) null else localGameNames()[titleId.lowercase(Locale.ROOT)]

    /** A title as the messages name it: the game list's name, else its id. */
    fun titleName(titleId: String): String = gameName(titleId) ?: titleId.uppercase(Locale.ROOT)

    // ---- formatting (§5.4: absolute, the locale's short date and time) ----

    fun formatTime(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(epochMillis))

    fun formatRfc3339(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            formatTime(OffsetDateTime.parse(value).toInstant().toEpochMilli())
        } catch (e: Exception) {
            value
        }
    }

    fun formatSize(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes)

    fun consoleName(namespace: String): String = when (namespace.lowercase(Locale.ROOT)) {
        "switch" -> "Switch"
        "wiiu" -> "Wii U"
        "3ds" -> "3DS"
        "wii" -> "Wii"
        "ds" -> "DS"
        else -> namespace
    }

    fun host(url: String): String = url.substringAfter("://").substringBefore('/')

    /**
     * A library error as the screen shows it: the server's own sentence when it sent one,
     * "Could not reach {0}." otherwise. HTTP codes and exception text stay in the log.
     */
    private fun failed(context: Context, error: String): Answer.Failed {
        val technical = error.isEmpty() || error.contains("HTTP") ||
            error.startsWith("Could not reach") || error.startsWith("Unexpected") ||
            error.contains("exception", ignoreCase = true)
        return Answer.Failed(
            if (technical) {
                context.getString(R.string.openpak_error_unreachable, host(state().website))
            } else {
                context.getString(R.string.openpak_error_server, error)
            }
        )
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private fun JSONArray?.friends(): List<Friend> = if (this == null) {
        emptyList()
    } else {
        (0 until length()).map { i ->
            val f = getJSONObject(i)
            Friend(
                accountId = f.optString("account_id"),
                pid = f.optString("pid"),
                name = f.optString("name"),
                friendCode = f.optString("friend_code"),
                online = f.optBoolean("online"),
                titleId = f.optString("title_id").lowercase(Locale.ROOT),
                console = f.optString("console"),
                onlineSince = f.optLong("online_since"),
                friendsSince = f.optLong("friends_since"),
                incoming = f.optBoolean("incoming")
            )
        }
    }

    private fun JSONArray?.counts(): List<Count> = if (this == null) {
        emptyList()
    } else {
        (0 until length()).map {
            val c = getJSONObject(it)
            Count(c.optString("id"), c.optInt("players"))
        }.sortedByDescending { it.players }
    }
}
