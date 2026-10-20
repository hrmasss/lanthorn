package app.player.feature.library.smb

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.designsystem.component.SettingsHeader
import app.player.core.designsystem.icon.LanthornIcons
import app.player.feature.library.BackButton
import app.player.feature.library.LibraryIcons
import app.player.feature.library.R
import app.player.feature.library.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A server found on the local network by mDNS (`_smb._tcp`). */
data class FoundServer(val name: String, val host: String, val port: Int)

/** US-62.1: saved servers, servers found nearby, and "add server". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmbServersScreen(onOpen: (SmbServer) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SmbServers(context) }
    val saved by store.servers.collectAsStateWithLifecycle(emptyList())
    val found = rememberDiscovery(context)
    var adding by remember { mutableStateOf<FoundServer?>(null) }
    var addOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.smb_title)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("smb-servers")) {
            item { SettingsHeader(stringResource(R.string.smb_saved)) }
            if (saved.isEmpty()) item { Hint(R.string.smb_saved_none) }
            items(saved, key = { it.id }) { server ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(server.label) },
                    supportingContent = { Text("smb://${server.host}${if (server.port != 445) ":${server.port}" else ""}/${server.share}") },
                    leadingContent = { Icon(LibraryIcons.Folder, contentDescription = null) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(LanthornIcons.More, contentDescription = stringResource(R.string.row_menu, server.label))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.smb_remove)) },
                                    onClick = {
                                        menu = false
                                        SmbClient.forget(server.id)
                                        scope.launch { store.remove(server.id) }
                                    },
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { onOpen(server) }.testTag("smb-server"),
                )
            }
            item { SettingsHeader(stringResource(R.string.smb_found)) }
            if (found.isEmpty()) item { Hint(R.string.smb_found_none) }
            items(found, key = { it.host + ":" + it.port }) { f ->
                ListItem(
                    headlineContent = { Text(f.name) },
                    supportingContent = { Text(f.host) },
                    modifier = Modifier.clickable { adding = f; addOpen = true }.testTag("smb-found"),
                )
            }
            item {
                Button(onClick = { adding = null; addOpen = true }, modifier = Modifier.padding(16.dp).testTag("smb-add")) {
                    Text(stringResource(R.string.smb_add))
                }
            }
        }
    }
    if (addOpen) {
        AddServerDialog(adding, onDismiss = { addOpen = false }, onSaved = { addOpen = false; onOpen(it) })
    }
}

/** Address, share, user, password. Saves only once a connection succeeds, so typos show at once. */
@Composable
private fun AddServerDialog(prefill: FoundServer?, onDismiss: () -> Unit, onSaved: (SmbServer) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(prefill?.name.orEmpty()) }
    var address by rememberSaveable {
        mutableStateOf(prefill?.let { if (it.port == 445) it.host else "${it.host}:${it.port}" }.orEmpty())
    }
    var share by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val host = address.substringBefore(':').trim()
    val port = address.substringAfter(':', "445").trim().toIntOrNull() ?: 445
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smb_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(address, { address = it }, R.string.smb_address, "smb-address", KeyboardType.Uri)
                Field(share, { share = it }, R.string.smb_share, "smb-share")
                Field(user, { user = it }, R.string.smb_user, "smb-user")
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.smb_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("smb-password"),
                )
                Field(name, { name = it }, R.string.smb_name, "smb-name")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("smb-error")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && host.isNotEmpty() && share.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val store = SmbServers(context)
                        val server = store.add(name, host, port, share, user, "", password)
                        val result = withContext(Dispatchers.IO) { runCatching { SmbClient.list(server, "") } }
                        busy = false
                        if (result.isSuccess) {
                            onSaved(server)
                        } else {
                            SmbClient.forget(server.id)
                            store.remove(server.id)
                            error = context.getString(R.string.smb_connect_failed, result.exceptionOrNull()?.reason().orEmpty())
                        }
                    }
                },
                modifier = Modifier.testTag("smb-save"),
            ) { Text(stringResource(if (busy) R.string.smb_connecting else R.string.smb_connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int, tag: String, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** One folder on a share: subfolders, then videos. Tapping a video plays it from the share. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SmbBrowseScreen(serverId: String, path: String, onFolder: (String) -> Unit, onPlay: (Uri) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableStateOf(0) }
    val server by produceState<SmbServer?>(null, serverId) { value = SmbServers(context).get(serverId) }
    val listing by produceState<Result<List<SmbEntry>>?>(null, server, path, attempt) {
        val s = server ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { SmbClient.list(s, path) } }
    }
    val dialect = remember(listing) { server?.let { SmbClient.dialect(it) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(path.substringAfterLast('\\').ifEmpty { server?.label.orEmpty() }, maxLines = 1)
                        dialect?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("smb-dialect")) }
                    }
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val l = listing
            when {
                l == null -> LoadingIndicator(Modifier.align(Alignment.Center))
                l.isFailure -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.smb_connect_failed, l.exceptionOrNull()?.reason().orEmpty()), modifier = Modifier.testTag("smb-error"))
                    TextButton(onClick = { server?.let { SmbClient.forget(it.id) }; attempt++ }) { Text(stringResource(R.string.stream_retry)) }
                }
                else -> LazyColumn(Modifier.fillMaxSize().testTag("smb-list")) {
                    val entries = l.getOrThrow()
                    if (entries.isEmpty()) item { Hint(R.string.smb_empty) }
                    items(entries, key = { it.path }) { e ->
                        ListItem(
                            headlineContent = { Text(e.name) },
                            supportingContent = { if (!e.isFolder) Text(formatSize(e.size)) },
                            leadingContent = { Icon(if (e.isFolder) LibraryIcons.Folder else LibraryIcons.AllVideos, contentDescription = null) },
                            modifier = Modifier.clickable {
                                val s = server ?: return@clickable
                                if (e.isFolder) onFolder(e.path) else onPlay(SmbProvider.uri(context, s, e.path))
                            }.testTag(if (e.isFolder) "smb-folder" else "smb-video"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The viewer-facing part of a connection error, without stack or class names. */
private fun Throwable.reason(): String {
    var t: Throwable? = this
    while (t?.cause != null && t.cause !== t) t = t.cause
    return (t?.message ?: this.message ?: javaClass.simpleName).take(160)
}

/** Servers advertised by mDNS, live while the screen shows. Phones see Samba, macOS and NAS boxes. */
@Composable
private fun rememberDiscovery(context: Context): List<FoundServer> {
    val found = remember { mutableStateListOf<FoundServer>() }
    DisposableEffect(Unit) {
        val nsd = context.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val host = r.host?.hostAddress ?: return
                        val f = FoundServer(r.serviceName, host, r.port)
                        if (found.none { it.host == f.host && it.port == f.port }) found += f
                    }
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit
        }
        runCatching { nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
        onDispose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
    return found
}
