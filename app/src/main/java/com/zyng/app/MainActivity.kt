package com.zyng.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.sin

private val Ink = Color(0xFF090909)
private val Panel = Color(0xFF151515)
private val PanelSoft = Color(0xCC191919)
private val Orange = Color(0xFFFF7138)
private val Paper = Color(0xFFF4F1EE)
private val Quiet = Color(0xFF9B9692)
private val Line = Color(0xFF2C2927)

private enum class Page(val title: String, val marker: String) {
    HOME("Overview", "01"),
    APPS("App manager", "02"),
    MODS("Mods & files", "03"),
    TOOLS("Device tools", "04"),
    SETTINGS("Settings", "05")
}

private data class AppEntry(val name: String, val packageName: String)
private data class ImportResult(val added: Int, val backedUp: Int, val skipped: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 9, 9)
        window.navigationBarColor = android.graphics.Color.rgb(9, 9, 9)
        setContent { ZyngApp() }
    }
}

@Composable
private fun ZyngApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.HOME) }
    var glass by remember { mutableStateOf(true) }
    var motion by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var appList by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(false) }
    var refreshApps by remember { mutableStateOf(0) }
    var chosenFolder by remember { mutableStateOf<Uri?>(null) }
    var pendingArchive by remember { mutableStateOf<Uri?>(null) }
    var pendingInstall by remember { mutableStateOf<Uri?>(null) }
    var uninstallTarget by remember { mutableStateOf<AppEntry?>(null) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(page, refreshApps) {
        if (page == Page.APPS) {
            loadingApps = true
            appList = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
            loadingApps = false
        }
    }

    val openInstaller: (Uri) -> Unit = { uri ->
        try {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
            notice = "Android opened its installer. Review the package details there."
        } catch (_: Exception) {
            notice = "Android could not open this APK. Check that the file is valid and try again."
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val uri = pendingInstall
        pendingInstall = null
        if (uri != null && (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls())) {
            openInstaller(uri)
        } else if (uri != null) {
            notice = "Allow Zyng to install unknown apps in Android settings, then choose the APK again."
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                pendingInstall = uri
                try {
                    settingsLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    )
                } catch (_: Exception) {
                    pendingInstall = null
                    notice = "Open Android settings to allow APK installs, then try again."
                }
            } else {
                openInstaller(uri)
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                chosenFolder = uri
                notice = "Folder selected. Zyng can work only within the access Android granted."
            } catch (_: Exception) {
                notice = "Android did not grant lasting access to that folder. Choose it again if needed."
            }
        }
    }

    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
            if (name.endsWith(".zip", ignoreCase = true)) {
                pendingArchive = uri
            } else {
                notice = "Choose a ZIP archive to install a mod."
            }
        }
    }

    val transition = rememberInfiniteTransition(label = "domain-warp")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(10000), RepeatMode.Reverse),
        label = "warp-phase"
    )

    MaterialTheme(colorScheme = darkColorScheme(primary = Orange, background = Ink, surface = Panel)) {
        Box(modifier = Modifier.fillMaxSize().background(Ink)) {
            if (motion) WarpBackground(phase)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 760.dp
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(page, onSelect = { page = it }, glass = glass)
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            TopBar(page, notice, onClearNotice = { notice = null })
                            PageContent(
                                page = page,
                                glass = glass,
                                apps = appList,
                                loadingApps = loadingApps,
                                folder = chosenFolder,
                                importing = importing,
                                notice = notice,
                                onOpenPage = { page = it },
                                onImportApk = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) },
                                onRefreshApps = { refreshApps++ },
                                onOpenApp = { packageName ->
                                    val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                                    if (launch != null) context.startActivity(launch)
                                    else notice = "Android could not open this app."
                                },
                                onUninstall = { uninstallTarget = it },
                                onPickFolder = { folderPicker.launch(null) },
                                onPickArchive = { archivePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                                onOpenSettings = {
                                    try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                    catch (_: Exception) { notice = "Android settings could not be opened." }
                                },
                                onToggleGlass = { glass = it },
                                onToggleMotion = { motion = it }
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(page, notice, onClearNotice = { notice = null })
                        PageContent(
                            page = page,
                            glass = glass,
                            apps = appList,
                            loadingApps = loadingApps,
                            folder = chosenFolder,
                            importing = importing,
                            notice = notice,
                            onOpenPage = { page = it },
                            onImportApk = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) },
                            onRefreshApps = { refreshApps++ },
                            onOpenApp = { packageName ->
                                val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                                if (launch != null) context.startActivity(launch)
                                else notice = "Android could not open this app."
                            },
                            onUninstall = { uninstallTarget = it },
                            onPickFolder = { folderPicker.launch(null) },
                            onPickArchive = { archivePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                            onOpenSettings = {
                                try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                catch (_: Exception) { notice = "Android settings could not be opened." }
                            },
                            onToggleGlass = { glass = it },
                            onToggleMotion = { motion = it }
                        )
                        BottomNav(page, onSelect = { page = it })
                    }
                }
            }
        }
    }

    pendingArchive?.let { archiveUri ->
        AlertDialog(
            onDismissRequest = { if (!importing) pendingArchive = null },
            containerColor = Panel,
            title = { Text("Install this mod archive?", color = Paper, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "Files will be added to the folder you selected. Existing files are copied into a Zyng Backups folder before replacement. Only use archives you trust.",
                    color = Quiet
                )
            },
            confirmButton = {
                Button(
                    enabled = !importing && chosenFolder != null,
                    onClick = {
                        val destination = chosenFolder
                        if (destination != null) {
                            importing = true
                            pendingArchive = null
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        installArchive(context, archiveUri, destination)
                                    }
                                    notice = "Mod import finished: ${result.added} files added, ${result.backedUp} backed up, ${result.skipped} skipped."
                                } catch (_: Exception) {
                                    notice = "The archive could not be installed. Check folder access and try again."
                                } finally {
                                    importing = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
                ) { Text(if (importing) "Working…" else "Install mod", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingArchive = null }, enabled = !importing) {
                    Text("Cancel", color = Paper)
                }
            }
        )
    }

    uninstallTarget?.let { app ->
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            containerColor = Panel,
            title = { Text("Uninstall ${app.name}?", color = Paper, fontWeight = FontWeight.SemiBold) },
            text = { Text("Android will show its own confirmation before removing this app.", color = Quiet) },
            confirmButton = {
                Button(
                    onClick = {
                        uninstallTarget = null
                        try {
                            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                        } catch (_: Exception) {
                            notice = "Android could not open the uninstall screen."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink)
                ) { Text("Continue", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                OutlinedButton(onClick = { uninstallTarget = null }) { Text("Cancel", color = Paper) }
            }
        )
    }
}

@Composable
private fun Sidebar(page: Page, onSelect: (Page) -> Unit, glass: Boolean) {
    Column(
        Modifier.width(224.dp).fillMaxHeight()
            .background(Color(0xF20D0D0D))
            .border(BorderStroke(1.dp, Line.copy(alpha = 0.8f)))
            .padding(horizontal = 16.dp, vertical = 22.dp)
    ) {
        BrandMark()
        Spacer(Modifier.height(34.dp))
        Text("WORKSPACE", color = Quiet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(12.dp))
        Page.entries.forEach { item ->
            NavItem(item, page == item, compact = false, onClick = { onSelect(item) })
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.weight(1f))
        Surface(
            color = if (glass) PanelSoft else Panel,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Line)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("QUEST LINK", color = Quiet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(Orange))
                    Spacer(Modifier.width(8.dp))
                    Text("No headset connected", color = Paper, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("ZYNG  /  1.0.0", color = Quiet.copy(alpha = 0.7f), fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Orange),
            contentAlignment = Alignment.Center
        ) {
            Text("Z", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
        Column {
            Text("ZYNG", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 2.2.sp)
            Text("QUEST TOOLKIT", color = Quiet, fontSize = 9.sp, letterSpacing = 1.3.sp)
        }
    }
}

@Composable
private fun TopBar(page: Page, notice: String?, onClearNotice: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(page.marker, color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(page.title, color = Paper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(4.dp)).background(Quiet))
                Text("OFFLINE", color = Quiet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            }
        }
        if (notice != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFF21170F))
                    .clickable(onClick = onClearNotice).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(notice, color = Paper, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("×", color = Orange, fontSize = 18.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = 0.8f)))
    }
}

@Composable
private fun BottomNav(page: Page, onSelect: (Page) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .background(Color(0xF20D0D0D))
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Page.entries.forEach { item -> NavItem(item, page == item, compact = true, onClick = { onSelect(item) }) }
    }
}

@Composable
private fun NavItem(item: Page, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier.clip(shape)
            .background(if (selected) Color(0xFF28201B) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(item.marker, color = if (selected) Orange else Quiet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            item.title,
            color = if (selected) Paper else Quiet,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PageContent(
    page: Page,
    glass: Boolean,
    apps: List<AppEntry>,
    loadingApps: Boolean,
    folder: Uri?,
    importing: Boolean,
    notice: String?,
    onOpenPage: (Page) -> Unit,
    onImportApk: () -> Unit,
    onRefreshApps: () -> Unit,
    onOpenApp: (String) -> Unit,
    onUninstall: (AppEntry) -> Unit,
    onPickFolder: () -> Unit,
    onPickArchive: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleGlass: (Boolean) -> Unit,
    onToggleMotion: (Boolean) -> Unit
) {
    val panelColor = if (glass) PanelSoft else Panel
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            when (page) {
                Page.HOME -> HomeScreen(panelColor, onOpenPage)
                Page.APPS -> AppsScreen(panelColor, apps, loadingApps, onImportApk, onRefreshApps, onOpenApp, onUninstall)
                Page.MODS -> ModsScreen(panelColor, folder, importing, onPickFolder, onPickArchive)
                Page.TOOLS -> ToolsScreen(panelColor, onOpenSettings)
                Page.SETTINGS -> SettingsScreen(panelColor, glass, onToggleGlass, onToggleMotion)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun HomeScreen(panel: Color, onOpenPage: (Page) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text("YOUR QUEST, YOUR RULES", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(8.dp))
                Text("Make your headset\nyour own.", color = Paper, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("A considered home for apps, game files and device tools.", color = Quiet, fontSize = 13.sp)
            }
            Box(Modifier.padding(start = 12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF21170F)).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text("EARLY BUILD", color = Orange, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = panel,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Line.copy(alpha = 0.9f))
        ) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF28201B)), contentAlignment = Alignment.Center) {
                    Text("Q", color = Orange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("No headset connected", color = Paper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Connect a Quest to manage apps and files.", color = Quiet, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { onOpenPage(Page.APPS) }, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Line)) {
                    Text("Open app manager", color = Paper, fontSize = 12.sp)
                }
            }
        }
        Text("YOUR TOOLKIT", color = Quiet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureTile("02", "App manager", "Browse, install or remove apps", Modifier.weight(1f), panel) { onOpenPage(Page.APPS) }
            FeatureTile("03", "Mods & files", "Install archives to a chosen folder", Modifier.weight(1f), panel) { onOpenPage(Page.MODS) }
            FeatureTile("04", "Device tools", "System shortcuts and access notes", Modifier.weight(1f), panel) { onOpenPage(Page.TOOLS) }
        }
        Surface(color = Color(0xFF16120F), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF35251B))) {
            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
                Text("!", color = Orange, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Android stays in control", color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text("Installs and removals always go through Android's own confirmation screens. Protected game folders may not be available.", color = Quiet, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(number: String, title: String, subtitle: String, modifier: Modifier, panel: Color, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = panel,
        shape = RoundedCornerShape(19.dp),
        border = BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(number, color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Text(title, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = Quiet, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun AppsScreen(
    panel: Color,
    apps: List<AppEntry>,
    loading: Boolean,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onUninstall: (AppEntry) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("App manager", "Install APKs or manage apps Android makes visible.") {
            Button(onClick = onImport, colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink), shape = RoundedCornerShape(12.dp)) {
                Text("Choose APK", fontWeight = FontWeight.SemiBold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("APP VISIBILITY", "Launcher apps", Modifier.weight(1f), panel)
            MetricTile("INSTALL FLOW", "Android-confirmed", Modifier.weight(1f), panel)
            MetricTile("DEVICE LINK", "Not connected", Modifier.weight(1f), panel)
        }
        Surface(color = panel, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Visible apps", color = Paper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Android controls what Zyng can list.", color = Quiet, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Line)) {
                        Text("Refresh", color = Paper, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    loading -> Text("Loading visible apps…", color = Quiet, fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp))
                    apps.isEmpty() -> Text("No launchable apps were returned by Android.", color = Quiet, fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp))
                    else -> apps.forEachIndexed { index, app ->
                        if (index > 0) Spacer(Modifier.height(1.dp).fillMaxWidth().background(Line))
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(35.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF28201B)), contentAlignment = Alignment.Center) {
                                Text(app.name.take(1).uppercase(Locale.ROOT), color = Orange, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.name, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, color = Quiet, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("Open", color = Orange, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpen(app.packageName) }.padding(9.dp))
                            Text("Remove", color = Quiet, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onUninstall(app) }.padding(9.dp))
                        }
                    }
                }
            }
        }
        Text("APK install opens Android's installer. Zyng does not bypass package checks or install silently.", color = Quiet, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun ModsScreen(panel: Color, folder: Uri?, importing: Boolean, onPickFolder: () -> Unit, onPickArchive: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("Mods & files", "Install a ZIP archive into a folder you explicitly select.") {
            Button(onClick = onPickFolder, colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink), shape = RoundedCornerShape(12.dp)) {
                Text("Choose folder", fontWeight = FontWeight.SemiBold)
            }
        }
        Surface(color = panel, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("DESTINATION", color = Quiet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(10.dp))
                Text(if (folder == null) "No folder selected" else DocumentFile.fromTreeUri(LocalContext.current, folder)?.name ?: "Selected folder", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Android's folder picker decides what Zyng can read and write.", color = Quiet, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onPickArchive,
                    enabled = folder != null && !importing,
                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink, disabledContainerColor = Color(0xFF393330), disabledContentColor = Quiet),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (importing) "Installing…" else "Choose mod ZIP", fontWeight = FontWeight.SemiBold) }
            }
        }
        Surface(color = Color(0xFF16120F), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF35251B))) {
            Column(Modifier.fillMaxWidth().padding(17.dp)) {
                Text("Protected folders", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Recent Android versions restrict access to some game data and OBB folders. If Android blocks a folder, Zyng cannot override that restriction. Use mods only where the game and its publisher allow them.", color = Quiet, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        Surface(color = panel, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(17.dp)) {
                Text("Safety built in", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Text("Existing files are copied to a Zyng Backups folder before replacement. Archive paths that try to leave the selected folder are skipped.", color = Quiet, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun ToolsScreen(panel: Color, onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("Device tools", "Shortcuts and clear status for your headset.") { }
        Surface(color = panel, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("HEADSET STATUS", color = Quiet, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(13.dp))
                Text("No Quest connected", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Device-level controls are unavailable until a supported, permission-based connection is added.", color = Quiet, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Surface(color = panel, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Android settings", color = Paper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Open the system settings directly. Zyng does not change hidden headset settings.", color = Quiet, fontSize = 12.sp)
                OutlinedButton(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Line)) {
                    Text("Open Android settings", color = Paper)
                }
            }
        }
        MetricTile("MODERATION", "No hidden commands", Modifier.fillMaxWidth(), panel)
    }
}

@Composable
private fun SettingsScreen(panel: Color, glass: Boolean, onToggleGlass: (Boolean) -> Unit, onToggleMotion: (Boolean) -> Unit) {
    var motionSetting by remember { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("Settings", "Tune Zyng's interface.") { }
        Surface(color = panel, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                SettingRow("Liquid glass panels", "Use softly translucent surfaces.", glass, onToggleGlass)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                SettingRow("Domain-warp background", "Keep the ambient motion behind the workspace.", motionSetting, {
                    motionSetting = it
                    onToggleMotion(it)
                })
            }
        }
        Surface(color = Color(0xFF16120F), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF35251B))) {
            Column(Modifier.fillMaxWidth().padding(17.dp)) {
                Text("Permissions", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Zyng asks Android for access only when you choose an APK or folder. You can revoke folder access in Android settings.", color = Quiet, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = Quiet, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Paper, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Quiet, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier, panel: Color) {
    Surface(modifier, color = panel, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = Quiet, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            Text(value, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WarpBackground(phase: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height
        val stroke = 1.dp.toPx()
        for (band in 0..13) {
            val baseline = h * band / 13f
            val path = Path()
            for (step in 0..84) {
                val x = w * step / 84f
                val normalized = x / w
                val waveA = sin((normalized * 5.2f + phase * 0.55f + band * 0.19f).toDouble()).toFloat() * (h * 0.025f)
                val waveB = sin((normalized * 12.5f - phase * 0.8f + band * 0.31f).toDouble()).toFloat() * (h * 0.009f)
                val pinch = sin((normalized * 2.1f + phase * 0.2f).toDouble()).toFloat() * (h * 0.035f)
                val y = baseline + waveA + waveB + pinch
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val alpha = if (band % 4 == 0) 0.13f else 0.045f
            drawPath(path, Orange.copy(alpha = alpha), style = Stroke(width = stroke))
        }
    }
}

private fun loadLaunchableApps(context: android.content.Context): List<AppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, 0)
        .mapNotNull { resolve ->
            val activity = resolve.activityInfo ?: return@mapNotNull null
            if (activity.packageName == context.packageName) return@mapNotNull null
            AppEntry(resolve.loadLabel(context.packageManager)?.toString() ?: activity.packageName, activity.packageName)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
}

private fun installArchive(context: android.content.Context, archiveUri: Uri, treeUri: Uri): ImportResult {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Folder unavailable")
    val backupRoot = root.findFile("Zyng Backups")?.takeIf { it.isDirectory }
        ?: root.createDirectory("Zyng Backups") ?: error("Backup folder unavailable")
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
    val backupFolder = backupRoot.createDirectory(stamp) ?: backupRoot
    var added = 0
    var backedUp = 0
    var skipped = 0
    var totalBytes = 0L
    var entries = 0
    val input = context.contentResolver.openInputStream(archiveUri) ?: error("Archive unavailable")
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entries++
            if (entries > 3000) error("Archive contains too many entries")
            val raw = entry.name.replace('\\', '/')
            val parts = raw.split('/').filter { it.isNotEmpty() }
            if (raw.startsWith('/') || parts.isEmpty() || parts.any { it == "." || it == ".." }) {
                skipped++
                zip.closeEntry()
                continue
            }
            val targetParent = ensureDirectories(root, parts.dropLast(if (entry.isDirectory) 0 else 1))
            val backupParent = ensureDirectories(backupFolder, parts.dropLast(if (entry.isDirectory) 0 else 1))
            if (entry.isDirectory) {
                targetParent.findFile(parts.last())?.takeIf { it.isDirectory } ?: targetParent.createDirectory(parts.last())
                zip.closeEntry()
                continue
            }
            val name = parts.last()
            val existing = targetParent.findFile(name)
            if (existing != null && !existing.isFile) {
                skipped++
                zip.closeEntry()
                continue
            }
            if (existing != null) {
                val backup = backupParent.createFile(
                    URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream",
                    name
                ) ?: error("Could not create backup")
                val oldInput = context.contentResolver.openInputStream(existing.uri) ?: error("Could not read existing file")
                val backupOutput = context.contentResolver.openOutputStream(backup.uri, "w") ?: error("Could not write backup")
                oldInput.use { source -> backupOutput.use { target -> source.copyTo(target) } }
                if (!existing.delete()) error("Could not replace existing file")
                backedUp++
            }
            val outputFile = targetParent.createFile(
                URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream",
                name
            ) ?: error("Could not create mod file")
            val output = context.contentResolver.openOutputStream(outputFile.uri, "w") ?: error("Could not write mod file")
            output.use { sink ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > 250L * 1024L * 1024L) error("Archive is too large")
                    sink.write(buffer, 0, count)
                }
            }
            added++
            zip.closeEntry()
        }
    }
    return ImportResult(added, backedUp, skipped)
}

private fun ensureDirectories(root: DocumentFile, names: List<String>): DocumentFile {
    var current = root
    for (name in names) {
        val found = current.findFile(name)
        current = if (found?.isDirectory == true) found
        else if (found == null) current.createDirectory(name) ?: error("Could not create folder")
        else error("A file blocks the archive folder path")
    }
    return current
}
