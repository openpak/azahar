// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.openpak.model

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.citra.citra_emu.features.openpak.ui.OpenPakActivity
import org.citra.citra_emu.features.openpak.ui.OpenPakUi

/**
 * The friends poll while the app is in front (openpak-ux-spec §3.10, §4.3): every 30 s, a friend
 * coming online or starting a game is a Snackbar, and a friend request is a system notification
 * on the OpenPak channel that opens Friends. The first poll after start or sign-in is silent.
 * There is no background service: nothing is polled while the app is not in front.
 */
object OpenPakFriendsWatch {
    private const val CHANNEL_ID = "openpak"
    private const val INTERVAL_MS = 30_000L

    private val scope = MainScope()
    private var job: Job? = null
    private var first = true
    private var online = mapOf<String, String>()
    private var asking = setOf<String>()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                if (OpenPakNotifier.foreground != null) poll()
                delay(INTERVAL_MS)
            }
        }
    }

    /** After a sign-in or sign-out: the next poll is silent again. */
    fun reset() {
        first = true
        online = emptyMap()
        asking = emptySet()
    }

    private suspend fun poll() {
        val context = CitraApplication.appContext
        val state = OpenPak.state()
        if (!state.signedIn || !state.notifications) return
        val answer = OpenPak.friends(context) as? OpenPak.Answer.Ok ?: return
        val now = mutableMapOf<String, String>()
        for (friend in answer.value.friends) {
            if (!friend.online || friend.accountId.isEmpty()) continue
            now[friend.accountId] = friend.titleId
            if (first) continue
            val game = OpenPak.gameName(friend.titleId)
            val before = online[friend.accountId]
            val text = when {
                before == null && game == null ->
                    context.getString(R.string.openpak_toast_friend_online, friend.name)

                before == null || (before != friend.titleId && game != null) ->
                    context.getString(R.string.openpak_toast_friend_playing, friend.name, game)

                else -> null
            }
            val activity = OpenPakNotifier.foreground
            if (text != null && activity != null) {
                OpenPakUi.snackbar(activity, text, R.string.openpak_page_friends) {
                    OpenPakActivity.launch(activity, OpenPakActivity.Screen.FRIENDS)
                }
            }
        }
        val nowAsking = mutableSetOf<String>()
        for (request in answer.value.requests) {
            if (!request.incoming) continue
            nowAsking.add(request.key)
            if (!first && request.key !in asking) notifyRequest(context, request)
        }
        online = now
        asking = nowAsking
        first = false
    }

    private fun notifyRequest(context: Context, request: OpenPak.Friend) {
        // Android 13 and later ask for the permission (Azahar's setup does); older ones do not.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.openpak_menu_title),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val open = Intent(context, OpenPakActivity::class.java)
            .putExtra(OpenPakActivity.EXTRA_SCREEN, OpenPakActivity.Screen.FRIENDS.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            request.key.hashCode(),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_openpak)
            .setContentTitle(context.getString(R.string.openpak_toast_cat_friend_request))
            .setContentText(context.getString(R.string.openpak_toast_friend_request, request.name))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(request.key.hashCode(), notification)
    }
}
