// Copyright 2026 OpenPak
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.openpak.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.citra.citra_emu.R
import org.citra.citra_emu.features.openpak.model.OpenPak
import org.citra.citra_emu.features.openpak.model.OpenPakFriendsWatch
import org.citra.citra_emu.features.openpak.ui.OpenPakActivity.Screen

private val PresenceOnline = Color(0xFF3FB950)
private val PresenceOffline = Color(0xFF7A7A7A)
private val Amber = Color(0xFFD29922)
private val Red = Color(0xFFD73A49)

/** The console this app is, for "{0} identity" (§3.6). */
private const val CONSOLE = "3DS"

/** One of the seven sections, in window order (§3.6), with the §6 icon set. */
private data class Section(val screen: Screen, val title: Int, val icon: ImageVector)

private val SECTIONS = listOf(
    Section(Screen.ACCOUNT, R.string.openpak_page_account, Icons.Filled.Person),
    Section(Screen.FRIENDS, R.string.openpak_page_friends, Icons.Filled.People),
    Section(Screen.INVITATIONS, R.string.openpak_page_invitations, Icons.Filled.Mail),
    Section(Screen.SAVES, R.string.openpak_page_saves, Icons.Filled.CloudUpload),
    Section(Screen.MODS, R.string.openpak_page_mods, Icons.Filled.Extension),
    Section(Screen.NEWS, R.string.openpak_page_news, Icons.Filled.Newspaper),
    Section(Screen.STATUS, R.string.openpak_page_status, Icons.Filled.SignalCellularAlt)
)

/** The screen stack, the signed-in state every screen reads, and the navigation between them. */
@Composable
fun OpenPakApp(activity: AppCompatActivity, start: Screen, onFinish: () -> Unit) {
    var stackText by rememberSaveable { mutableStateOf(start.name) }
    val stack = stackText.split(',').map { Screen.valueOf(it) }
    var fromConnect by rememberSaveable { mutableStateOf(start == Screen.CONNECT) }
    var state by remember { mutableStateOf(OpenPak.state()) }
    val scope = rememberCoroutineScope()

    val push: (Screen) -> Unit = { stackText = (stack + it).joinToString(",") { s -> s.name } }
    val pop: () -> Unit = {
        if (stack.size <= 1) {
            onFinish()
        } else {
            stackText = stack.dropLast(1).joinToString(",") { it.name }
        }
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    val signIn: () -> Unit = { push(Screen.SIGN_IN) }
    val signOut: () -> Unit = {
        OpenPakUi.confirmSignOut(activity) { state = OpenPak.state() }
    }

    when (stack.last()) {
        Screen.HOME -> HomeScreen(state, onBack = pop, onOpen = push, onSignIn = signIn)
        Screen.ACCOUNT -> AccountScreen(state, pop, signIn, signOut)
        Screen.FRIENDS -> FriendsScreen(activity, state, pop, signIn)
        Screen.INVITATIONS -> NotHereScreen(
            R.string.openpak_page_invitations,
            Icons.Filled.Mail,
            R.string.openpak_na_invites_3ds,
            pop
        )

        Screen.SAVES -> SavesScreen(activity, state, pop, signIn)
        Screen.MODS -> ModsScreen(activity, state, pop)
        // No links: OpenPak serves Miiverse and SpotPass to consoles only, with no web page.
        Screen.NEWS -> NotHereScreen(
            R.string.openpak_page_news,
            Icons.Filled.Newspaper,
            R.string.openpak_na_news_3ds,
            pop
        )

        Screen.STATUS -> StatusScreen(state, pop)
        Screen.SIGN_IN -> SignInScreen(
            website = state.website,
            onBack = pop,
            onSignedIn = { name, linked ->
                scope.launch {
                    if (fromConnect) {
                        OpenPak.turnOnConnection()
                        fromConnect = false
                    }
                    state = OpenPak.state()
                    OpenPakFriendsWatch.reset()
                    val text = if (linked) {
                        activity.getString(R.string.openpak_toast_signed_in_linked, name)
                    } else {
                        activity.getString(R.string.openpak_toast_signed_in, name)
                    }
                    OpenPakUi.snackbar(activity, text)
                    // From the first-run screen, signing in is the end of it.
                    if (stack.first() == Screen.CONNECT) onFinish() else pop()
                }
            }
        )

        Screen.CONNECT -> ConnectScreen(
            website = state.website,
            onSignIn = {
                fromConnect = true
                push(Screen.SIGN_IN)
            },
            onNotNow = onFinish
        )
    }
}

// ---- building blocks ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenPakScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.openpak_common_close)
                        )
                    }
                },
                actions = { actions() }
            )
        },
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshableList(
    padding: PaddingValues,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            content = content
        )
    }
}

@Composable
private fun Avatar(name: String, image: String, size: Dp, online: Boolean? = null) {
    val bitmap = remember(image) {
        if (image.isEmpty()) {
            null
        } else {
            try {
                val bytes = Base64.decode(image, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
    Box(modifier = Modifier.size(size)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        } else {
            // Initials on a tinted circle until there is a picture.
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(1).uppercase(Locale.getDefault()),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = if (size >= 48.dp) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.bodyLarge
                    }
                )
            }
        }
        if (online != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size / 3)
                    .clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .background(if (online) PresenceOnline else PresenceOffline)
            )
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun Pill(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Dot(color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value.ifEmpty { stringResource(R.string.openpak_common_none) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InlineError(message: String?) {
    if (message.isNullOrEmpty()) return
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp)
    )
}

@Composable
private fun HelperText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SmallLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** §3.6 "Signed out": the page asks for an account. */
@Composable
private fun NeedSignIn(padding: PaddingValues, onSignIn: () -> Unit) {
    val running = OpenPak.gameRunning()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.openpak_common_need_sign_in),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.size(16.dp))
        Button(onClick = onSignIn, enabled = !running) {
            Text(stringResource(R.string.openpak_common_sign_in_button))
        }
        if (running) HelperText(stringResource(R.string.openpak_common_stop_game_first))
    }
}

/** A page that cannot apply to the 3DS family: the icon, the reason, and nothing else. */
@Composable
private fun NotHereScreen(titleRes: Int, icon: ImageVector, textRes: Int, onBack: () -> Unit) {
    OpenPakScaffold(stringResource(titleRes), onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(16.dp))
            Text(
                stringResource(textRes),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/** Friend code as a monospace pill with Copy; "Copied." is the result line. */
@Composable
private fun FriendCode(code: String) {
    val context = LocalContext.current
    val copied = stringResource(R.string.openpak_common_copied)
    val failed = stringResource(R.string.openpak_common_copy_failed, code)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = code.ifEmpty { stringResource(R.string.openpak_common_none) },
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
        if (code.isNotEmpty()) {
            IconButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                val activity = context as? Activity
                if (clipboard != null) {
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            context.getString(R.string.openpak_account_friend_code),
                            code
                        )
                    )
                    activity?.let { OpenPakUi.snackbar(it, copied, always = true) }
                } else {
                    activity?.let { OpenPakUi.snackbar(it, failed, always = true) }
                }
            }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.openpak_common_copy)
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.openpak_common_cancel))
            }
        }
    )
}

// ---- Home ----

// Badge was experimental in older Material 3 releases.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: OpenPak.State,
    onBack: () -> Unit,
    onOpen: (Screen) -> Unit,
    onSignIn: () -> Unit
) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf<OpenPak.Profile?>(null) }
    var requests by remember { mutableStateOf(0) }
    LaunchedEffect(state.signedIn) {
        profile = null
        requests = 0
        if (state.signedIn) {
            (OpenPak.profile(context) as? OpenPak.Answer.Ok)?.let { profile = it.value }
            (OpenPak.friends(context) as? OpenPak.Answer.Ok)?.let { answer ->
                requests = answer.value.requests.count { it.incoming }
            }
        }
    }

    OpenPakScaffold(stringResource(R.string.openpak_menu_title), onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (state.signedIn) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Avatar(profile?.name ?: state.name, profile?.image ?: "", 56.dp)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    profile?.name ?: state.name,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                FriendCode(profile?.friendCode ?: "")
                            }
                        }
                    } else {
                        val running = OpenPak.gameRunning()
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.openpak_connect_body_3ds),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.size(12.dp))
                            Button(onClick = onSignIn, enabled = !running) {
                                Text(stringResource(R.string.openpak_menu_sign_in))
                            }
                            if (running) {
                                HelperText(stringResource(R.string.openpak_common_stop_game_first))
                            }
                        }
                    }
                }
            }
            items(SECTIONS) { section ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onOpen(section.screen) }
                        .padding(horizontal = 16.dp)
                ) {
                    Icon(
                        section.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        stringResource(section.title),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    // Incoming friend requests (the 3DS family has no invitations to count).
                    if (section.screen == Screen.FRIENDS && requests > 0) {
                        Badge { Text(if (requests > 99) "99+" else requests.toString()) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---- Account ----

@Composable
private fun AccountScreen(
    state: OpenPak.State,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<OpenPak.Profile?>(null) }
    var identity by remember { mutableStateOf<OpenPak.Identity?>(null) }
    var identityAsked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun fetch(refreshIdentity: Boolean) {
        when (val answer = OpenPak.profile(context)) {
            is OpenPak.Answer.Ok -> {
                profile = answer.value
                error = null
            }

            is OpenPak.Answer.Failed -> error = answer.message
        }
        identity = OpenPak.identity(refreshIdentity)
        identityAsked = true
    }

    val load: () -> Unit = {
        scope.launch {
            refreshing = true
            fetch(true)
            refreshing = false
        }
    }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            refreshing = true
            fetch(false)
            refreshing = false
        }
    }

    OpenPakScaffold(stringResource(R.string.openpak_page_account), onBack) { padding ->
        if (!state.signedIn) {
            NeedSignIn(padding, onSignIn)
            return@OpenPakScaffold
        }
        val running = OpenPak.gameRunning()
        RefreshableList(padding, refreshing, load) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(profile?.name ?: state.name, profile?.image ?: "", 56.dp)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    profile?.name ?: state.name,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                FriendCode(profile?.friendCode ?: "")
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        OutlinedButton(onClick = onSignOut, enabled = !running) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.openpak_menu_sign_out))
                        }
                        if (running) {
                            HelperText(stringResource(R.string.openpak_common_stop_game_first))
                        }
                    }
                }
            }
            item { InlineError(error) }
            item {
                val id = identity
                DetailRow(
                    stringResource(R.string.openpak_account_friend_code),
                    profile?.friendCode ?: ""
                )
                // The 3DS identity rows: the console friend code and the principal id.
                val rows = listOfNotNull(
                    id?.friendCode?.takeIf { it.isNotEmpty() }?.let {
                        stringResource(R.string.openpak_account_friend_code_row, it)
                    },
                    id?.pid?.takeIf { it.isNotEmpty() }?.let {
                        stringResource(R.string.openpak_account_pid, it)
                    }
                )
                DetailRow(
                    stringResource(R.string.openpak_account_identity, CONSOLE),
                    rows.joinToString("\n")
                )
                DetailRow(
                    stringResource(R.string.openpak_account_consoles),
                    profile?.linked?.joinToString(", ") { OpenPak.consoleName(it) } ?: ""
                )
                if (id != null && id.username.isNotEmpty()) {
                    DetailRow(
                        stringResource(R.string.openpak_account_link),
                        stringResource(R.string.openpak_account_linked_as, id.username)
                    )
                } else if (identityAsked && !refreshing) {
                    DetailRow(
                        stringResource(R.string.openpak_account_link),
                        stringResource(R.string.openpak_account_link_failed)
                    )
                    TextButton(
                        onClick = load,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) { Text(stringResource(R.string.openpak_account_try_again)) }
                }
            }
        }
    }
}

// ---- Friends (the 3DS family: list, requests, add by friend code) ----

/** One friend or request action that needs a yes first (§7: Remove and Block are confirmed). */
private data class FriendConfirm(val friend: OpenPak.Friend, val block: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendsScreen(
    activity: AppCompatActivity,
    state: OpenPak.State,
    onBack: () -> Unit,
    onSignIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf<OpenPak.Friends?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<FriendConfirm?>(null) }
    var code by rememberSaveable { mutableStateOf("") }

    suspend fun fetch() {
        when (val answer = OpenPak.friends(context)) {
            is OpenPak.Answer.Ok -> {
                friends = answer.value
                error = null
            }

            // A failed refresh keeps the last list and says why.
            is OpenPak.Answer.Failed -> error = answer.message
        }
    }

    val load: () -> Unit = {
        scope.launch {
            refreshing = true
            fetch()
            refreshing = false
        }
    }
    // Loads when shown, then every 30 s while it stays open.
    LaunchedEffect(state.signedIn) {
        if (!state.signedIn) return@LaunchedEffect
        while (true) {
            fetch()
            delay(30_000)
        }
    }

    // Runs one action; a failure (or [done], when there are words for it) is the screen's
    // status line, a Snackbar; then the list reloads.
    fun act(done: String?, action: suspend () -> String?) {
        scope.launch {
            busy = true
            val failure = action()
            busy = false
            (failure ?: done)?.let { OpenPakUi.snackbar(activity, it, always = true) }
            if (failure == null) fetch()
        }
    }

    val addFriend: () -> Unit = {
        val typed = code.trim()
        if (typed.isEmpty()) {
            OpenPakUi.snackbar(
                activity,
                context.getString(R.string.openpak_friends_no_code),
                always = true
            )
        } else {
            act(context.getString(R.string.openpak_friends_added, typed)) {
                OpenPak.sendFriendRequest(context, typed).also { if (it == null) code = "" }
            }
        }
    }

    OpenPakScaffold(stringResource(R.string.openpak_page_friends), onBack) { padding ->
        if (!state.signedIn) {
            NeedSignIn(padding, onSignIn)
            return@OpenPakScaffold
        }
        RefreshableList(padding, refreshing || busy, load) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                ) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        placeholder = {
                            Text(stringResource(R.string.openpak_friends_add_placeholder))
                        },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addFriend() }),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = addFriend, enabled = !busy) {
                        Text(stringResource(R.string.openpak_friends_add))
                    }
                }
            }
            item { InlineError(error) }

            val current = friends
            val requests = current?.requests ?: emptyList()
            if (requests.isNotEmpty()) {
                item { SectionHeading(stringResource(R.string.openpak_friends_requests)) }
                items(requests, key = { "request-" + it.key }) { request ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menuFor = "request-" + request.key }
                            )
                            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Avatar(request.name, "", 34.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(request.name, style = MaterialTheme.typography.bodyLarge)
                            SmallLine(
                                stringResource(
                                    if (request.incoming) {
                                        R.string.openpak_friends_incoming
                                    } else {
                                        R.string.openpak_friends_outgoing
                                    }
                                )
                            )
                        }
                        Box {
                            IconButton(onClick = { menuFor = "request-" + request.key }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = request.name)
                            }
                            DropdownMenu(
                                expanded = menuFor == "request-" + request.key,
                                onDismissRequest = { menuFor = null }
                            ) {
                                if (request.incoming) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.openpak_friends_accept))
                                        },
                                        enabled = !busy,
                                        onClick = {
                                            menuFor = null
                                            act(null) {
                                                OpenPak.acceptRequest(context, request)
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.openpak_friends_decline))
                                        },
                                        enabled = !busy,
                                        onClick = {
                                            menuFor = null
                                            act(null) {
                                                OpenPak.declineRequest(context, request)
                                            }
                                        }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.openpak_friends_cancel_request
                                                )
                                            )
                                        },
                                        enabled = !busy,
                                        onClick = {
                                            menuFor = null
                                            act(null) {
                                                OpenPak.declineRequest(context, request)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                item { SectionHeading(stringResource(R.string.openpak_friends_list)) }
            }

            val list = current?.friends
            if (list != null && list.isEmpty()) {
                item { CenteredMessage(stringResource(R.string.openpak_friends_empty)) }
            }
            items(list ?: emptyList(), key = { it.key }) { friend ->
                val key = friend.key
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { expanded = if (expanded == key) null else key },
                            onLongClick = { menuFor = key }
                        )
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(friend.name, "", 34.dp, online = friend.online)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(friend.name, style = MaterialTheme.typography.bodyLarge)
                                SmallLine(friendStatus(friend))
                            }
                        }
                        if (expanded == key) FriendDetails(friend)
                    }
                    Box {
                        IconButton(onClick = { menuFor = key }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = friend.name)
                        }
                        DropdownMenu(
                            expanded = menuFor == key,
                            onDismissRequest = { menuFor = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.openpak_friends_remove)) },
                                enabled = !busy && friend.accountId.isNotEmpty(),
                                onClick = {
                                    menuFor = null
                                    confirm = FriendConfirm(friend, block = false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.openpak_friends_block)) },
                                enabled = !busy && friend.accountId.isNotEmpty(),
                                onClick = {
                                    menuFor = null
                                    confirm = FriendConfirm(friend, block = true)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    confirm?.let { (friend, block) ->
        if (block) {
            ConfirmDialog(
                title = stringResource(R.string.openpak_friends_block_title, friend.name),
                text = stringResource(R.string.openpak_friends_block_confirm),
                confirm = stringResource(R.string.openpak_friends_block),
                onConfirm = {
                    confirm = null
                    act(null) {
                        OpenPak.blockFriend(context, friend)
                    }
                },
                onDismiss = { confirm = null }
            )
        } else {
            ConfirmDialog(
                title = stringResource(R.string.openpak_friends_remove_title),
                text = stringResource(R.string.openpak_friends_remove_confirm, friend.name),
                confirm = stringResource(R.string.openpak_friends_remove),
                onConfirm = {
                    confirm = null
                    act(null) {
                        OpenPak.removeFriend(context, friend)
                    }
                },
                onDismiss = { confirm = null }
            )
        }
    }
}

/** The expanded row: "On {console}", "Online since", "Friend code:", "Friends since". */
@Composable
private fun FriendDetails(friend: OpenPak.Friend) {
    Column(modifier = Modifier.padding(start = 50.dp, top = 4.dp)) {
        if (friend.online && friend.console.isNotEmpty()) {
            FriendLine(
                stringResource(
                    R.string.openpak_friends_on_console,
                    OpenPak.consoleName(friend.console)
                )
            )
        }
        if (friend.online && friend.onlineSince > 0) {
            FriendLine(
                stringResource(
                    R.string.openpak_friends_online_since,
                    OpenPak.formatTime(friend.onlineSince * 1000)
                )
            )
        }
        if (friend.friendCode.isNotEmpty()) {
            FriendLine(stringResource(R.string.openpak_friends_code_line, friend.friendCode))
        }
        if (friend.friendsSince > 0) {
            FriendLine(
                stringResource(
                    R.string.openpak_friends_since,
                    OpenPak.formatTime(friend.friendsSince * 1000)
                )
            )
        }
    }
}

@Composable
private fun FriendLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Online, Offline, Playing {game}, Playing, or On {console}. */
@Composable
private fun friendStatus(friend: OpenPak.Friend): String = when {
    !friend.online -> stringResource(R.string.openpak_friends_offline)
    friend.titleId.isNotEmpty() -> OpenPak.gameName(friend.titleId)
        ?.let { stringResource(R.string.openpak_friends_playing, it) }
        ?: stringResource(R.string.openpak_friends_playing_unknown)

    friend.console.isNotEmpty() -> stringResource(
        R.string.openpak_friends_on_console,
        OpenPak.consoleName(friend.console)
    )

    else -> stringResource(R.string.openpak_friends_online)
}

// ---- Cloud saves ----

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SavesScreen(
    activity: AppCompatActivity,
    state: OpenPak.State,
    onBack: () -> Unit,
    onSignIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saves by remember { mutableStateOf<OpenPak.CloudSaves?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmDownload by remember { mutableStateOf<OpenPak.CloudSave?>(null) }
    var confirmDelete by remember { mutableStateOf<OpenPak.CloudSave?>(null) }
    var resolve by remember { mutableStateOf<OpenPak.CloudSave?>(null) }

    val load: () -> Unit = {
        scope.launch {
            refreshing = true
            when (val answer = OpenPak.cloudSaves(context)) {
                is OpenPak.Answer.Ok -> {
                    saves = answer.value
                    error = null
                }

                is OpenPak.Answer.Failed -> error = answer.message
            }
            refreshing = false
        }
    }
    LaunchedEffect(state.signedIn) { if (state.signedIn) load() }

    // Runs one action, says how it went (the desktop's status line), then reloads.
    fun act(save: OpenPak.CloudSave, done: Int, action: suspend (OpenPak.CloudSave) -> String) {
        scope.launch {
            busy = true
            val failure = action(save)
            busy = false
            val message = if (failure.isEmpty()) {
                context.getString(done, save.name)
            } else {
                context.getString(R.string.openpak_error_server, failure)
            }
            OpenPakUi.snackbar(activity, message, always = true)
            load()
        }
    }

    OpenPakScaffold(stringResource(R.string.openpak_page_saves), onBack) { padding ->
        if (!state.signedIn) {
            NeedSignIn(padding, onSignIn)
            return@OpenPakScaffold
        }
        val running = OpenPak.gameRunning()
        val runningTitle = OpenPak.runningTitle()
        RefreshableList(padding, refreshing || busy, load) {
            val current = saves
            if (current != null && current.allowance > 0) {
                item {
                    Text(
                        stringResource(
                            R.string.openpak_saves_usage,
                            OpenPak.formatSize(context, current.used),
                            OpenPak.formatSize(context, current.allowance)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            item { InlineError(error) }
            if (current != null && current.saves.isEmpty()) {
                item { CenteredMessage(stringResource(R.string.openpak_saves_empty)) }
            }
            items(current?.saves ?: emptyList(), key = { it.titleId }) { save ->
                val thisGameRuns =
                    running && (runningTitle.isEmpty() || runningTitle == save.titleId)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = { menuFor = save.titleId })
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                save.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (save.conflict) {
                                Spacer(Modifier.width(8.dp))
                                Pill(stringResource(R.string.openpak_saves_conflict), Red)
                            }
                        }
                        val newest = save.newest
                        if (newest != null) {
                            SmallLine(
                                stringResource(
                                    R.string.openpak_saves_size,
                                    OpenPak.formatSize(context, newest.size),
                                    save.versions.size.toString()
                                )
                            )
                            SmallLine(versionLine(newest))
                        }
                        SmallLine(localLine(save))
                        if (thisGameRuns) {
                            HelperText(stringResource(R.string.openpak_common_stop_game_first))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuFor = save.titleId }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = save.name)
                        }
                        DropdownMenu(
                            expanded = menuFor == save.titleId,
                            onDismissRequest = { menuFor = null }
                        ) {
                            if (save.conflict) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.openpak_saves_resolve)) },
                                    onClick = {
                                        menuFor = null
                                        resolve = save
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.openpak_saves_download))
                                    },
                                    enabled = !thisGameRuns && !busy,
                                    onClick = {
                                        menuFor = null
                                        confirmDownload = save
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.openpak_saves_upload)) },
                                    enabled = !busy && save.localState != "none",
                                    onClick = {
                                        menuFor = null
                                        act(save, R.string.openpak_saves_uploaded) {
                                            OpenPak.uploadSave(it)
                                        }
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.openpak_saves_delete)) },
                                enabled = !busy,
                                onClick = {
                                    menuFor = null
                                    confirmDelete = save
                                }
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    confirmDownload?.let { save ->
        ConfirmDialog(
            title = stringResource(R.string.openpak_saves_download),
            text = stringResource(R.string.openpak_saves_download_confirm, save.name),
            confirm = stringResource(R.string.openpak_saves_download),
            onConfirm = {
                confirmDownload = null
                act(save, R.string.openpak_saves_downloaded) { OpenPak.downloadSave(it) }
            },
            onDismiss = { confirmDownload = null }
        )
    }
    confirmDelete?.let { save ->
        ConfirmDialog(
            title = stringResource(R.string.openpak_saves_delete),
            text = stringResource(R.string.openpak_saves_delete_confirm, save.name),
            confirm = stringResource(R.string.openpak_saves_delete),
            onConfirm = {
                confirmDelete = null
                act(save, R.string.openpak_saves_deleted) { OpenPak.deleteSave(it) }
            },
            onDismiss = { confirmDelete = null }
        )
    }
    resolve?.let { save ->
        // §3.9 as a bottom sheet (§4.2). Decide later is the default and what dismissing does.
        val runningTitle = OpenPak.runningTitle()
        val thisGameRuns = OpenPak.gameRunning() &&
            (runningTitle.isEmpty() || runningTitle == save.titleId)
        ModalBottomSheet(onDismissRequest = { resolve = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(
                    stringResource(R.string.openpak_conflict_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.openpak_conflict_body, save.name))
                Spacer(Modifier.size(16.dp))
                Text(
                    stringResource(R.string.openpak_conflict_local),
                    style = MaterialTheme.typography.titleSmall
                )
                SmallLine(
                    if (save.lastWritten > 0) {
                        OpenPak.formatTime(save.lastWritten * 1000)
                    } else {
                        stringResource(R.string.openpak_common_none)
                    }
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.openpak_conflict_cloud),
                    style = MaterialTheme.typography.titleSmall
                )
                save.newest?.let { SmallLine(versionLine(it)) }
                Spacer(Modifier.size(16.dp))
                OutlinedButton(
                    onClick = {
                        resolve = null
                        act(save, R.string.openpak_saves_uploaded) { OpenPak.uploadSave(it) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.openpak_conflict_keep_local)) }
                OutlinedButton(
                    onClick = {
                        resolve = null
                        act(save, R.string.openpak_saves_downloaded) { OpenPak.downloadSave(it) }
                    },
                    enabled = !thisGameRuns,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.openpak_conflict_take_cloud)) }
                if (thisGameRuns) {
                    HelperText(stringResource(R.string.openpak_common_stop_game_first))
                }
                Button(onClick = { resolve = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.openpak_conflict_later))
                }
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

/** "Latest: version {n} from {device}, {time}". */
@Composable
private fun versionLine(version: OpenPak.SaveVersion): String = stringResource(
    R.string.openpak_saves_version,
    version.number.toString(),
    version.device.ifEmpty { stringResource(R.string.openpak_common_none) },
    OpenPak.formatRfc3339(version.savedAt)
)

@Composable
private fun localLine(save: OpenPak.CloudSave): String {
    val written = if (save.lastWritten > 0) {
        OpenPak.formatTime(save.lastWritten * 1000)
    } else {
        stringResource(R.string.openpak_common_none)
    }
    return when (save.localState) {
        "in_step", "changed_here", "cloud_newer" -> stringResource(
            R.string.openpak_saves_local_synced,
            written,
            save.localVersion.ifEmpty { stringResource(R.string.openpak_common_none) }
        )

        "no_history" -> stringResource(R.string.openpak_saves_local_never, written)
        else -> if (OpenPak.isInstalled(save.titleId)) {
            stringResource(R.string.openpak_saves_local_none)
        } else {
            stringResource(R.string.openpak_saves_not_installed)
        }
    }
}

// ---- Mods (the public catalogue for a title; Favourite when signed in) ----

@Composable
private fun ModsScreen(activity: AppCompatActivity, state: OpenPak.State, onBack: () -> Unit) {
    val games = remember { OpenPak.localGames() }
    var selected by rememberSaveable {
        val running = OpenPak.runningTitle()
        mutableStateOf(
            games.firstOrNull { it.titleId == running }?.titleId
                ?: games.firstOrNull()?.titleId ?: ""
        )
    }
    var mods by remember { mutableStateOf<OpenPak.Mods?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val load: () -> Unit = {
        scope.launch {
            if (selected.isEmpty()) {
                mods = OpenPak.Mods(emptyList(), emptySet())
            } else {
                refreshing = true
                mods = OpenPak.mods(selected)
                refreshing = false
            }
        }
    }
    LaunchedEffect(selected) { load() }

    OpenPakScaffold(
        title = stringResource(R.string.openpak_page_mods),
        onBack = onBack,
        actions = {
            // §4.4: the title picker is a dropdown in the app bar.
            if (games.isNotEmpty()) {
                Box {
                    TextButton(onClick = { pickerOpen = true }) {
                        Text(
                            games.firstOrNull { it.titleId == selected }?.name ?: "",
                            maxLines = 1,
                            modifier = Modifier.widthIn(max = 160.dp)
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        games.forEach { game ->
                            DropdownMenuItem(
                                text = { Text(game.name) },
                                onClick = {
                                    pickerOpen = false
                                    selected = game.titleId
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        RefreshableList(padding, refreshing, load) {
            val current = mods
            if (current != null && current.mods.isEmpty()) {
                item { CenteredMessage(stringResource(R.string.openpak_mods_empty)) }
            }
            items(current?.mods ?: emptyList(), key = { it.id }) { mod ->
                val favourite = current?.favourites?.contains(mod.id) == true
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                mod.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(mod.version, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (mod.summary.isNotEmpty()) {
                            Text(mod.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                        SmallLine(stringResource(R.string.openpak_mods_by, mod.author, mod.licence))
                    }
                    if (state.signedIn) {
                        IconButton(onClick = {
                            scope.launch {
                                if (OpenPak.setModFavourite(mod, !favourite)) {
                                    val now = current?.favourites.orEmpty().toMutableSet()
                                    if (favourite) now.remove(mod.id) else now.add(mod.id)
                                    mods = current?.copy(favourites = now)
                                } else {
                                    OpenPakUi.snackbar(
                                        activity,
                                        activity.getString(
                                            R.string.openpak_error_unreachable,
                                            OpenPak.host(state.website)
                                        ),
                                        always = true
                                    )
                                }
                            }
                        }) {
                            Icon(
                                if (favourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = stringResource(R.string.openpak_mods_favourite)
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

// ---- Status ----

@Composable
private fun StatusScreen(state: OpenPak.State, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<OpenPak.Status?>(null) }
    var identity by remember { mutableStateOf<OpenPak.Identity?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf<OpenPak.Connection?>(null) }
    var testing by remember { mutableStateOf(false) }
    var lastTest by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    val load: () -> Unit = {
        scope.launch {
            refreshing = true
            status = OpenPak.status()
            refreshing = false
        }
    }
    // Loads when shown, then every 30 s while it stays open.
    LaunchedEffect(Unit) {
        if (state.signedIn) identity = OpenPak.identity(false)
        while (true) {
            status = OpenPak.status()
            delay(30_000)
        }
    }
    LaunchedEffect(lastTest) {
        while (System.currentTimeMillis() - lastTest < 10_000) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }

    OpenPakScaffold(stringResource(R.string.openpak_page_status), onBack) { padding ->
        RefreshableList(padding, refreshing, load) {
            val s = status
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (s == null) {
                            Text(stringResource(R.string.openpak_status_checking))
                        } else if (!s.ok) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(PresenceOffline)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(
                                        R.string.openpak_status_no_health,
                                        s.url.ifEmpty { state.statusUrl }
                                    )
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(
                                    when (s.state) {
                                        "ok" -> PresenceOnline
                                        "some" -> Amber
                                        else -> Red
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(s.headline, style = MaterialTheme.typography.titleMedium)
                            }
                            if (s.sub.isNotEmpty()) {
                                Text(s.sub, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (s != null) {
                            SmallLine(
                                stringResource(
                                    R.string.openpak_status_refreshed,
                                    OpenPak.formatTime(s.refreshed)
                                )
                            )
                            if (s.playersOk) {
                                SmallLine(
                                    stringResource(
                                        R.string.openpak_status_players,
                                        s.players.toString()
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (s != null && s.services.isNotEmpty()) {
                item { SectionHeading(stringResource(R.string.openpak_status_services)) }
                items(s.services) { service ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Dot(if (service.up) PresenceOnline else Red)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(service.name, style = MaterialTheme.typography.bodyLarge)
                            SmallLine(
                                stringResource(
                                    R.string.openpak_status_uptime,
                                    String.format(Locale.getDefault(), "%.1f", service.uptime),
                                    service.latency
                                )
                            )
                        }
                        Text(
                            stringResource(
                                if (service.up) {
                                    R.string.openpak_status_up
                                } else {
                                    R.string.openpak_status_down
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                SectionHeading(stringResource(R.string.openpak_status_session))
                DetailRow(
                    stringResource(R.string.openpak_status_account),
                    if (state.signedIn) {
                        stringResource(R.string.openpak_status_signed_in, state.name)
                    } else {
                        stringResource(R.string.openpak_status_signed_out)
                    }
                )
                val id = identity
                DetailRow(
                    stringResource(R.string.openpak_status_link),
                    if (state.signedIn && id != null && id.username.isNotEmpty()) {
                        stringResource(
                            R.string.openpak_status_linked,
                            id.username,
                            id.friendCode.ifEmpty { stringResource(R.string.openpak_common_none) }
                        )
                    } else {
                        stringResource(R.string.openpak_status_no_console_account)
                    }
                )
                val c = connection
                val host = OpenPak.host(state.website)
                DetailRow(
                    stringResource(R.string.openpak_status_nat),
                    when {
                        testing -> stringResource(R.string.openpak_status_checking)
                        c == null -> stringResource(R.string.openpak_status_not_tested)
                        c.nat == "A" || c.nat == "B" ->
                            stringResource(R.string.openpak_status_nat_open)

                        c.nat == "C" || c.nat == "D" ->
                            stringResource(R.string.openpak_status_nat_strict)

                        else -> stringResource(R.string.openpak_common_none)
                    }
                )
                DetailRow(
                    stringResource(R.string.openpak_status_ping),
                    when {
                        testing -> stringResource(R.string.openpak_status_checking)
                        c == null -> stringResource(R.string.openpak_status_not_tested)
                        c.pingMs != null -> stringResource(
                            R.string.openpak_status_edge_up,
                            host,
                            c.pingMs.toString()
                        )

                        else -> stringResource(R.string.openpak_status_edge_down, host)
                    }
                )
                OutlinedButton(
                    onClick = {
                        lastTest = System.currentTimeMillis()
                        scope.launch {
                            testing = true
                            connection = OpenPak.testConnection()
                            testing = false
                        }
                    },
                    // Once every 10 s.
                    enabled = !testing && now - lastTest >= 10_000,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.openpak_status_test)) }
            }

            if (s != null && (s.titles.isNotEmpty() || s.networks.isNotEmpty())) {
                item { SectionHeading(stringResource(R.string.openpak_status_players_heading)) }
                items(s.networks) { count ->
                    CountRow(OpenPak.consoleName(count.id), count.players)
                }
                items(s.titles) { count ->
                    CountRow(OpenPak.gameName(count.id) ?: count.id, count.players)
                }
            }
        }
    }
}

@Composable
private fun CountRow(name: String, players: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(players.toString(), style = MaterialTheme.typography.bodyLarge)
    }
}

// ---- Sign in (§3.3 as a full screen) ----

@Composable
private fun SignInScreen(
    website: String,
    onBack: () -> Unit,
    onSignedIn: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var device by rememberSaveable { mutableStateOf(OpenPak.defaultDeviceName(context)) }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var verifyIntro by rememberSaveable { mutableStateOf(false) }
    val running = OpenPak.gameRunning()
    val emailFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { emailFocus.requestFocus() }

    OpenPakScaffold(stringResource(R.string.openpak_signin_title), onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (verifyIntro) {
                Text(
                    stringResource(R.string.openpak_setup_verify),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            InlineError(error)
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.openpak_signin_email)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(emailFocus)
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.openpak_signin_password)) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (reveal) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(R.string.openpak_signin_password)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = device,
                onValueChange = { device = it },
                label = { Text(stringResource(R.string.openpak_signin_device)) },
                supportingText = { Text(stringResource(R.string.openpak_signin_device_hint)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = {
                verifyIntro = true
                OpenPakUi.openUrl(context, "$website/register")
            }) { Text(stringResource(R.string.openpak_setup_create)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            when (val result = OpenPak.signIn(context, email, password, device)) {
                                is OpenPak.SignInResult.Ok -> {
                                    password = ""
                                    onSignedIn(result.name, result.linked)
                                }

                                // The screen stays, with the email kept.
                                is OpenPak.SignInResult.Failed -> error = result.message
                            }
                            busy = false
                        }
                    },
                    enabled = email.isNotBlank() && password.isNotEmpty() && !busy && !running
                ) { Text(stringResource(R.string.openpak_signin_submit)) }
                if (busy) {
                    Spacer(Modifier.width(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            if (running) HelperText(stringResource(R.string.openpak_common_stop_game_first))
        }
    }
}

// ---- First run (§3.2 connect prompt, as a full screen) ----

@Composable
private fun ConnectScreen(website: String, onSignIn: () -> Unit, onNotNow: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onNotNow() }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painterResource(R.drawable.ic_openpak),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(16.dp))
            Text(
                stringResource(R.string.openpak_connect_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(16.dp))
            Text(
                stringResource(R.string.openpak_connect_body_3ds),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = { OpenPakUi.openUrl(context, "$website/register") }) {
                Text(stringResource(R.string.openpak_setup_create))
            }
            Spacer(Modifier.size(24.dp))
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.openpak_connect_sign_in))
            }
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.openpak_connect_not_now))
            }
        }
    }
}
