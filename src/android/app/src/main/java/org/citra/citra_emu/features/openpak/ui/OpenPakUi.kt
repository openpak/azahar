// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.openpak.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.citra.citra_emu.R
import org.citra.citra_emu.features.openpak.model.OpenPak
import org.citra.citra_emu.features.openpak.model.OpenPakFriendsWatch

/** The View-side pieces the settings screens, the home screen and the game menu share. */
object OpenPakUi {
    /** §4.3: Snackbars show for the same 6 s as the desktop toasts. */
    const val SNACKBAR_MS = 6000

    /**
     * A §3.10 event as a Snackbar; suppressed while "Show notifications" is off. [always] is for
     * the result of an action the person just took on a screen (the desktop's status line).
     */
    fun snackbar(
        activity: Activity,
        message: String,
        actionText: Int = 0,
        always: Boolean = false,
        action: (() -> Unit)? = null
    ) {
        if (!always && !OpenPak.state().notifications) return
        val view = activity.findViewById<View>(android.R.id.content)
        if (view == null) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            return
        }
        val bar = Snackbar.make(view, message, SNACKBAR_MS)
        if (actionText != 0 && action != null) bar.setAction(actionText) { action() }
        bar.show()
    }

    /**
     * §3.5 as a Material alert: Cancel is the default and what Back does; Sign out revokes the
     * token (off the main thread) and then says so.
     */
    fun confirmSignOut(activity: FragmentActivity, onDone: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.openpak_signout_title)
            .setMessage(R.string.openpak_signout_body_single)
            .setPositiveButton(R.string.openpak_signout_confirm) { _, _ ->
                activity.lifecycleScope.launch {
                    OpenPak.signOut()
                    OpenPakFriendsWatch.reset()
                    snackbar(activity, activity.getString(R.string.openpak_toast_signed_out))
                    onDone()
                }
            }
            .setNegativeButton(R.string.openpak_common_cancel, null)
            .show()
    }

    /**
     * §3.2 / §4.2 first run, for a non-Switch app: the connect screen, once per install, after
     * Azahar's own first-time setup. Never when the app was started to play a game (that path
     * goes straight to the emulation screen and never reaches the home screen).
     */
    @JvmStatic
    fun maybeShowConnect(activity: Activity) {
        OpenPak.init(activity)
        if (OpenPak.connectAsked(activity)) return
        OpenPak.setConnectAsked(activity)
        if (OpenPak.state().signedIn) return
        OpenPakActivity.launch(activity, OpenPakActivity.Screen.CONNECT)
    }

    /** §3.10 "Stored sign-in expired at startup": once per process, a Snackbar, never a prompt. */
    private var storedSignInChecked = false

    fun checkStoredSignIn(activity: FragmentActivity) {
        if (storedSignInChecked) return
        storedSignInChecked = true
        activity.lifecycleScope.launch {
            if (!OpenPak.storedSignInExpired()) return@launch
            val text = activity.getString(R.string.openpak_toast_sign_in_again, OpenPak.EMULATOR)
            snackbar(activity, text, R.string.openpak_common_sign_in_button) {
                OpenPakActivity.launch(activity, OpenPakActivity.Screen.SIGN_IN)
            }
        }
    }

    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, url, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Material 3 for the Compose screens, taken from the View theme the activity already has
 * (Azahar's static colours or Material You, light or dark, black backgrounds), so OpenPak looks
 * like the rest of the app.
 */
@Composable
fun OpenPakTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(context) {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val base = if (dark) darkColorScheme() else lightColorScheme()
        fun color(attr: Int, fallback: Color): Color =
            Color(MaterialColors.getColor(context, attr, fallback.toArgb()))

        val surface = color(MaterialR.attr.colorSurface, base.surface)
        val surfaceVariant = color(MaterialR.attr.colorSurfaceVariant, base.surfaceVariant)
        base.copy(
            primary = color(MaterialR.attr.colorPrimary, base.primary),
            onPrimary = color(MaterialR.attr.colorOnPrimary, base.onPrimary),
            primaryContainer = color(MaterialR.attr.colorPrimaryContainer, base.primaryContainer),
            onPrimaryContainer = color(
                MaterialR.attr.colorOnPrimaryContainer,
                base.onPrimaryContainer
            ),
            secondary = color(MaterialR.attr.colorSecondary, base.secondary),
            onSecondary = color(MaterialR.attr.colorOnSecondary, base.onSecondary),
            secondaryContainer = color(
                MaterialR.attr.colorSecondaryContainer,
                base.secondaryContainer
            ),
            onSecondaryContainer = color(
                MaterialR.attr.colorOnSecondaryContainer,
                base.onSecondaryContainer
            ),
            background = color(android.R.attr.colorBackground, base.background),
            onBackground = color(MaterialR.attr.colorOnBackground, base.onBackground),
            surface = surface,
            onSurface = color(MaterialR.attr.colorOnSurface, base.onSurface),
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = color(MaterialR.attr.colorOnSurfaceVariant, base.onSurfaceVariant),
            outline = color(MaterialR.attr.colorOutline, base.outline),
            error = color(MaterialR.attr.colorError, base.error),
            onError = color(MaterialR.attr.colorOnError, base.onError),
            surfaceContainerLowest = surface,
            surfaceContainerLow = surface,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceVariant,
            surfaceContainerHighest = surfaceVariant
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
