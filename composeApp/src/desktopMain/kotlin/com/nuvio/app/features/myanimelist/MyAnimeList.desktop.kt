package com.nuvio.app.features.myanimelist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioCompactActionButton
import com.nuvio.app.core.ui.NuvioCompactActionTone
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable private fun rememberMalConnection(): MalConnectionState {
    val profile by ProfileRepository.state.collectAsState()
    LaunchedEffect(profile.activeProfile) { MyAnimeListRepository.ensureLoaded() }
    return MyAnimeListRepository.state.collectAsState().value
}
@Composable internal actual fun MyAnimeListSettingsCard() {
    val connection = rememberMalConnection()
    var setup by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MyAnimeList", style = MaterialTheme.typography.titleMedium)
            Text(if (!connection.connected) "Connect your anime list to set statuses and ratings."
                else if (connection.username.isBlank() || connection.username == "Connected") "Connected to MyAnimeList."
                else "Connected as ${connection.username}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !connection.busy, onClick = { setup = true }) { Text(if (connection.connected) "Reconnect" else "Set up / Connect") }
                if (connection.connected) TextButton(onClick = MyAnimeListRepository::disconnect) { Text("Disconnect") }
                if (connection.connecting) TextButton(onClick = MyAnimeListRepository::cancelConnect) { Text("Cancel") }
            }
            if (connection.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            connection.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (setup) MalSetupDialog(connection, onDismiss = { setup = false })
}

@Composable internal actual fun MyAnimeListControls(meta: MetaDetails) {
    val connection = rememberMalConnection()
    key(meta.type, meta.id, connection.profileId, connection.revision) { MalControls(meta, connection) }
}

@Composable private fun MalControls(meta: MetaDetails, connection: MalConnectionState) {
    val scope = rememberCoroutineScope()
    var malId by remember(meta.id, connection.profileId) { mutableStateOf<Int?>(null) }
    var status by remember(meta.id, connection.profileId) { mutableStateOf<MalListStatus?>(null) }
    var busy by remember(meta.id, connection.profileId) { mutableStateOf(false) }
    var error by remember(meta.id, connection.profileId) { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var ratingMenu by remember { mutableStateOf(false) }
    LaunchedEffect(meta.id, connection.profileId, connection.connected, connection.busy) {
        if (connection.busy) return@LaunchedEffect
        busy = true; error = null
        try {
            malId = withTimeoutOrNull(8_000L) { MyAnimeListRepository.resolveId(meta) }
            if (connection.connected) malId?.let { status = MyAnimeListRepository.listStatus(it) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
        finally { busy = false }
    }
    val anime = malId != null || meta.id.substringBefore(':') in listOf("nativeairing", "mal", "anilist", "kitsu") ||
        meta.genres.any { it.equals("anime", true) || it.equals("animation", true) }
    if (!anime) return
    fun update(newStatus: MalStatus? = null, score: Int? = null) {
        val id = malId ?: return
        if (busy || connection.busy) return
        scope.launch {
            busy = true; error = null
            try { status = MyAnimeListRepository.update(id, newStatus, score); NuvioToastController.show("MyAnimeList updated") }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!connection.connected) NuvioCompactActionButton(
                label = "Connect MyAnimeList", icon = Icons.Default.Link,
                enabled = !connection.busy, onClick = { setup = true },
            )
            else {
                Box {
                    NuvioCompactActionButton(
                        label = "MAL · ${status?.status?.label ?: "Set status"}", icon = Icons.Default.PlaylistAddCheck,
                        accessibilityLabel = "MyAnimeList status: ${status?.status?.label ?: "Set status"}",
                        trailingIcon = Icons.Default.ExpandMore, enabled = !busy && !connection.busy && malId != null,
                        onClick = { statusMenu = true },
                    )
                    DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        MalStatus.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) },
                            onClick = { statusMenu = false; update(newStatus = option) }) }
                    }
                }
                Box {
                    NuvioCompactActionButton(
                        label = status?.score?.takeIf { it > 0 }?.let { "Rating · $it/10" } ?: "Rate on MAL",
                        accessibilityLabel = status?.score?.takeIf { it > 0 }?.let { "MyAnimeList rating: $it out of 10" } ?: "Rate on MyAnimeList",
                        icon = Icons.Default.Star, trailingIcon = Icons.Default.ExpandMore,
                        enabled = !busy && !connection.busy && malId != null, onClick = { ratingMenu = true },
                    )
                    DropdownMenu(expanded = ratingMenu, onDismissRequest = { ratingMenu = false }) {
                        (10 downTo 0).forEach { score -> DropdownMenuItem(text = { Text(if (score == 0) "Clear rating" else "$score / 10") },
                            onClick = { ratingMenu = false; update(score = score) }) }
                    }
                }
                NuvioCompactActionButton(
                    label = if (malId == null) "Link MAL entry" else "Change MAL entry",
                    icon = Icons.Default.Edit, tone = NuvioCompactActionTone.Quiet,
                    enabled = !busy && !connection.busy, onClick = { search = true },
                )
            }
        }
        if (busy) LinearProgressIndicator(Modifier.width(220.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            NuvioCompactActionButton(label = "Retry", icon = Icons.Default.Refresh,
                enabled = !busy && !connection.busy,
                onClick = { scope.launch { busy = true; try { malId?.let { status = MyAnimeListRepository.listStatus(it); error = null } }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false } } }) }
    }
    if (setup) MalSetupDialog(connection, onDismiss = { setup = false })
    if (search) MalSearchDialog(meta, onDismiss = { search = false }, onSelect = { selected ->
        MyAnimeListRepository.link(meta, selected.id); malId = selected.id; search = false
        scope.launch { try { status = MyAnimeListRepository.listStatus(selected.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    })
}

@Composable private fun MalSetupDialog(connection: MalConnectionState, onDismiss: () -> Unit) {
    var clientId by remember { mutableStateOf(connection.clientId) }
    var clientSecret by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connect MyAnimeList") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("In MyAnimeList API setup, choose App Type Other (recommended, no secret). Existing Web apps require their client secret. Your MAL password stays on MyAnimeList.")
            TextButton(onClick = { uri.openUri("https://myanimelist.net/apiconfig") }) { Text("Open MyAnimeList API setup") }
            Text("Set the App Redirect URL to exactly:")
            SelectionContainer { Text(MAL_REDIRECT, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(value = clientId, onValueChange = { clientId = it.trim() }, label = { Text("Public client ID") }, singleLine = true)
            OutlinedTextField(value = clientSecret, onValueChange = { clientSecret = it.trim() },
                label = { Text("Client secret (Web apps only)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Text("The site shown on MAL's approval screen (such as example.com) is your registered app's homepage. You can correct it in MAL API setup; it does not determine the sign-in callback.")
            Text("Connect opens your browser. Approve access there, then return here. Tokens are protected by your Windows account.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = clientId.isNotBlank() && !connection.busy, onClick = {
        runCatching { MyAnimeListRepository.connect(clientId, clientSecret); onDismiss() }.onFailure { error = it.message }
    }) { Text("Connect") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun MalSearchDialog(meta: MetaDetails, onDismiss: () -> Unit, onSelect: (MalAnime) -> Unit) {
    var query by remember { mutableStateOf(meta.name) }
    var results by remember { mutableStateOf(emptyList<MalAnime>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() { if (busy) return; scope.launch { busy = true; error = null
        try { results = MyAnimeListRepository.search(query) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message } finally { busy = false } } }
    LaunchedEffect(meta.id) { search() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose this title on MyAnimeList") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true)
            TextButton(enabled = !busy && query.length >= 2, onClick = ::search) { Text("Search") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            results.forEach { anime -> TextButton(onClick = { onSelect(anime) }) { Text(anime.title, maxLines = 2) } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
