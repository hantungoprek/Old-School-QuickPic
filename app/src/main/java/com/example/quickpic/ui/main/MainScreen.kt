package com.example.quickpic.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentValues
import android.content.ContentUris
import android.provider.MediaStore
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private enum class HomeTab { Folders, Photos, Videos }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(com.example.quickpic.data.DefaultDataRepository(context.applicationContext)) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hasPermission = context.hasMediaPermission()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refresh() }

    if (!hasPermission) {
        PermissionRequired(modifier) { permissionLauncher.launch(mediaPermissions()) }
        return
    }

    when (val current = state) {
        MainScreenUiState.Loading -> Loading(modifier)
        is MainScreenUiState.Error -> ErrorMessage(current.throwable.message ?: "Unable to load media.", modifier)
        is MainScreenUiState.Success -> LibraryContent(current.data, viewModel, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(library: com.example.quickpic.data.MediaLibrary, viewModel: MainScreenViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(HomeTab.Folders.ordinal) }
    var openFolderPath by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerItems by remember { mutableStateOf<List<com.example.quickpic.data.MediaItem>>(emptyList()) }
    var viewerIndex by remember { mutableIntStateOf(0) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var dateSortOpen by remember { mutableStateOf(false) }
    var rotationOpen by remember { mutableStateOf(false) }
    var detailsItem by remember { mutableStateOf<com.example.quickpic.data.MediaItem?>(null) }
    var renameFolder by remember { mutableStateOf<com.example.quickpic.data.MediaFolder?>(null) }
    var renameItem by remember { mutableStateOf<com.example.quickpic.data.MediaItem?>(null) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedMediaIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val photoRotations = remember { mutableStateMapOf<String, Int>() }
    val sortMode by viewModel.selectedSortMode.collectAsStateWithLifecycle()
    val sortDirection by viewModel.selectedSortDirection.collectAsStateWithLifecycle()

    if (settingsOpen) {
        SettingsScreen(onBack = { settingsOpen = false })
        return
    }

    val selectedFolder = openFolderPath?.let { path -> library.folders.firstOrNull { it.path == path } }
    val title = selectedFolder?.displayName ?: "Old School QuickPic"
    val drawerState = rememberDrawerState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    if (drawerOpen && drawerState.currentValue == DrawerValue.Closed) LaunchedEffect(Unit) { drawerState.open() }
    if (!drawerOpen && drawerState.currentValue == DrawerValue.Open) LaunchedEffect(Unit) { drawerState.close() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                Text("Old School QuickPic", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                HorizontalDivider()
                DrawerItem("Folder", Icons.Default.Folder, selectedFolder == null && selectedTab == HomeTab.Folders.ordinal) { selectedTab = HomeTab.Folders.ordinal; openFolderPath = null; drawerOpen = false }
                DrawerItem("Momen", Icons.Default.CalendarMonth, false) { selectedTab = HomeTab.Photos.ordinal; openFolderPath = null; drawerOpen = false }
                DrawerItem("Tambah", Icons.Default.Add, false) { drawerOpen = false }
                DrawerItem("Pengaturan", Icons.Default.Settings, false) { drawerOpen = false; settingsOpen = true }
                DrawerItem("Tentang", Icons.Default.Info, false) { drawerOpen = false; aboutOpen = true }
            }
        },
    ) {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (selectionMode) {
                            IconButton(onClick = {
                                selectionMode = false
                                selectedMediaIds = emptySet()
                            }) { Icon(Icons.Default.Close, "Batal memilih") }
                        } else if (selectedFolder != null) {
                            IconButton(onClick = { openFolderPath = null }) { Icon(Icons.Default.ArrowBack, "Kembali") }
                        } else IconButton(onClick = { drawerOpen = true }) { Icon(Icons.Default.Menu, "Menu") }
                    },
                    title = {
                        if (selectionMode) Text("${selectedMediaIds.size} dipilih")
                        else Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    actions = {
                        if (selectionMode) {
                            IconButton(onClick = {
                                val folderMedia = selectedFolder?.let { folder -> library.media.filter { it.relativePath == folder.path } } ?: emptyList()
                                selectedMediaIds = if (selectedMediaIds.size == folderMedia.size) emptySet() else folderMedia.map { it.id }.toSet()
                            }) { Icon(Icons.Default.SelectAll, "Pilih semua") }
                        } else if (selectedFolder != null) {
                            IconButton(onClick = {
                                selectionMode = true
                                selectedMediaIds = emptySet()
                            }) { Icon(Icons.Default.Checklist, "Tandai") }
                        }
                        if (!selectionMode) Box {
                            IconButton(onClick = { overflowOpen = true }) { Icon(Icons.Default.MoreVert, "Menu lainnya") }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                OverflowItem("Tampilan", Icons.Default.GridView) { overflowOpen = false }
                                OverflowItem("Urutkan", Icons.Default.Sort) { overflowOpen = false; sortOpen = true }
                                OverflowItem("Tambah", Icons.Default.Add) { overflowOpen = false }
                                if (selectedFolder == null) OverflowItem("Muat tersembunyi", Icons.Default.VisibilityOff) { overflowOpen = false }
                                if (selectedFolder != null) {
                                    OverflowItem("Sembunyikan", Icons.Default.VisibilityOff) { overflowOpen = false }
                                    OverflowItem("Sembunyikan Folder", Icons.Default.FolderOff) { overflowOpen = false }
                                    OverflowItem("Ganti nama", Icons.Default.Edit) { overflowOpen = false; selectedFolder?.let { renameFolder = it } }
                                    OverflowItem("Perbaiki waktu", Icons.Default.Schedule) { overflowOpen = false }
                                    OverflowItem("Tautkan ke beranda", Icons.Default.Home) { overflowOpen = false }
                                }
                                OverflowItem("Pengaturan", Icons.Default.Settings) { overflowOpen = false; settingsOpen = true }
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            if (selectedFolder != null) {
                val folderMedia = library.media.filter { it.relativePath == selectedFolder.path }
                MediaGrid(
                    media = folderMedia,
                    modifier = Modifier.padding(innerPadding),
                    rotationDegrees = { uri -> photoRotations[uri.toString()] ?: 0 },
                    selectionMode = selectionMode,
                    selectedMediaIds = selectedMediaIds,
                    onMediaClick = { index ->
                        val item = folderMedia[index]
                        if (selectionMode) {
                            selectedMediaIds = if (item.id in selectedMediaIds) {
                                selectedMediaIds - item.id
                            } else {
                                selectedMediaIds + item.id
                            }
                        } else {
                            viewerItems = folderMedia
                            viewerIndex = index
                        }
                    },
                )
            } else {
                Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    TabRow(selectedTabIndex = selectedTab) {
                        HomeTab.entries.forEachIndexed { index, tab ->
                            Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(tab.label()) }, icon = { Icon(tab.icon(), null) })
                        }
                    }
                    when (HomeTab.entries[selectedTab]) {
                        HomeTab.Folders -> FolderGrid(library.folders, rotationDegrees = { uri -> photoRotations[uri.toString()] ?: 0 }) { openFolderPath = it.path }
                        HomeTab.Photos -> { val list = library.media.filterNot { it.isVideo }; MediaGrid(list, Modifier.fillMaxSize(), rotationDegrees = { uri -> photoRotations[uri.toString()] ?: 0 }, onMediaClick = { viewerItems = list; viewerIndex = it }) }
                        HomeTab.Videos -> { val list = library.media.filter { it.isVideo }; MediaGrid(list, Modifier.fillMaxSize(), rotationDegrees = { uri -> photoRotations[uri.toString()] ?: 0 }, onMediaClick = { viewerItems = list; viewerIndex = it }) }
                    }
                }
            }
        }
    }

    if (sortOpen) {
        SortDialog(sortMode) { mode ->
            sortOpen = false
            if (mode == SortMode.DATE) dateSortOpen = true else viewModel.setSortMode(mode)
        }
    }
    if (dateSortOpen) {
        DateSortDialog(sortDirection) { direction ->
            viewModel.setSortMode(SortMode.DATE)
            viewModel.setSortDirection(direction)
            dateSortOpen = false
        }
    }
    if (aboutOpen) AlertDialog(onDismissRequest = { aboutOpen = false }, title = { Text("Tentang") }, text = { Text("Old School QuickPic\nGaleri foto/video offline bergaya QuickPic klasik.") }, confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text("Tutup") } })
    detailsItem?.let { item -> MediaDetailsDialog(item) { detailsItem = null } }
    renameFolder?.let { folder ->
        RenameDialog(
            title = "Ganti nama folder",
            initialName = folder.displayName,
            errorMessage = renameError,
            onDismiss = { renameFolder = null; renameError = null },
            onRename = { newName ->
                val result = context.renameMediaFolder(folder, newName)
                if (result == null) {
                    renameFolder = null
                    renameError = null
                    viewModel.refresh()
                    val parent = folder.path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
                    openFolderPath = if (parent.isBlank()) "${newName.trim()}/" else "$parent/${newName.trim()}/"
                } else renameError = result
            },
        )
    }
    renameItem?.let { item ->
        RenameDialog(
            title = "Ganti nama",
            initialName = item.displayName,
            errorMessage = renameError,
            onDismiss = { renameItem = null; renameError = null },
            onRename = { newName ->
                val result = context.renameMediaItem(item, newName)
                if (result == null) {
                    renameItem = null
                    renameError = null
                    viewerItems = emptyList()
                    viewModel.refresh()
                } else renameError = result
            },
        )
    }
    if (viewerItems.isNotEmpty()) MediaViewer(
        viewerItems,
        viewerIndex,
        onDismiss = { viewerItems = emptyList() },
        onRotateRequest = { rotationOpen = true },
        onDetailsRequest = { if (viewerItems.isNotEmpty()) detailsItem = viewerItems[viewerIndex] },
        onRenameRequest = { if (viewerItems.isNotEmpty()) renameItem = viewerItems[viewerIndex] },
        rotationDegrees = { uri -> photoRotations[uri.toString()] ?: 0 },
    )
    if (rotationOpen) {
        RotationDialog { degrees ->
            if (degrees != 0 && viewerItems.isNotEmpty()) {
                val uri = viewerItems[viewerIndex].uri
                val currentDegrees = photoRotations[uri.toString()] ?: 0
                photoRotations[uri.toString()] = (currentDegrees + degrees).mod(360)
            }
            rotationOpen = false
        }
    }
}

@Composable private fun DrawerItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) = NavigationDrawerItem(label = { Text(label) }, selected = selected, onClick = onClick, icon = { Icon(icon, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
@Composable private fun OverflowItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) = DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)

@Composable private fun SortDialog(current: SortMode, onSelect: (SortMode) -> Unit) {
    AlertDialog(
        onDismissRequest = { onSelect(current) },
        title = { Text("Urutkan") },
        text = {
            Column {
                SortOption("Nama", SortMode.NAME, current, onSelect)
                SortOption("Tanggal", SortMode.DATE, current, onSelect, showSubmenu = true)
                SortOption("Alur", SortMode.FLOW, current, onSelect)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun DateSortDialog(current: SortDirection, onSelect: (SortDirection) -> Unit) {
    AlertDialog(
        onDismissRequest = { onSelect(current) },
        title = { Text("Urutkan berdasarkan tanggal") },
        text = {
            Column {
                DirectionOption("Terbaru → Terlama", SortDirection.DESCENDING, current, onSelect)
                DirectionOption("Terlama → Terbaru", SortDirection.ASCENDING, current, onSelect)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun SortOption(label: String, mode: SortMode, current: SortMode, onSelect: (SortMode) -> Unit, showSubmenu: Boolean = false) = Row(Modifier.fillMaxWidth().clickable { onSelect(mode) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    RadioButton(mode == current, { onSelect(mode) })
    Text(label, Modifier.weight(1f))
    if (showSubmenu) Icon(Icons.Default.ChevronRight, "Pilihan urutan tanggal")
}

@Composable
private fun DirectionOption(label: String, direction: SortDirection, current: SortDirection, onSelect: (SortDirection) -> Unit) = Row(Modifier.fillMaxWidth().clickable { onSelect(direction) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    RadioButton(direction == current, { onSelect(direction) })
    Text(label)
}

@Composable
private fun RotationDialog(onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = { onSelect(0) },
        title = { Text("Putar") },
        text = {
            Column {
                RotationOption("Putar ke kiri", -90, onSelect)
                RotationOption("Putar ke kanan", 90, onSelect)
                RotationOption("Putar 180 derajat", 180, onSelect)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun RotationOption(label: String, degrees: Int, onSelect: (Int) -> Unit) = Row(Modifier.fillMaxWidth().clickable { onSelect(degrees) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    val icon = when (degrees) {
        -90 -> Icons.Default.RotateLeft
        90 -> Icons.Default.RotateRight
        else -> Icons.Default.RotateRight
    }
    Icon(icon, null)
    Spacer(Modifier.width(12.dp))
    Text(label)
}

@Composable private fun HomeTab.icon() = when (this) { HomeTab.Folders -> Icons.Default.Folder; HomeTab.Photos -> Icons.Default.Image; HomeTab.Videos -> Icons.Default.Movie }
private fun HomeTab.label() = when (this) { HomeTab.Folders -> "Folder"; HomeTab.Photos -> "Foto"; HomeTab.Videos -> "Video" }

@Composable
private fun FolderGrid(
    folders: List<com.example.quickpic.data.MediaFolder>,
    rotationDegrees: (Uri) -> Int = { 0 },
    onFolderClick: (com.example.quickpic.data.MediaFolder) -> Unit,
) {
    if (folders.isEmpty()) { EmptyState("Tidak ada folder foto/video."); return }
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(folders, key = { it.path }) { folder -> FolderCard(folder, rotationDegrees(folder.thumbnail), onFolderClick) } }
}
@Composable
private fun FolderCard(
    folder: com.example.quickpic.data.MediaFolder,
    rotationDegrees: Int = 0,
    onFolderClick: (com.example.quickpic.data.MediaFolder) -> Unit,
) = Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onFolderClick(folder) }.semantics { contentDescription = "Folder ${folder.displayName}" }) {
    MediaThumbnailImage(folder.thumbnail, null, Modifier.fillMaxWidth().height(120.dp), rotationDegrees)
    Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null, Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)); Column(Modifier.weight(1f)) { Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall); Text("${folder.totalCount} item", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@Composable internal fun MediaGrid(
    media: List<com.example.quickpic.data.MediaItem>,
    modifier: Modifier = Modifier,
    rotationDegrees: (Uri) -> Int = { 0 },
    selectionMode: Boolean = false,
    selectedMediaIds: Set<Long> = emptySet(),
    onMediaClick: (Int) -> Unit,
) {
    if (media.isEmpty()) { EmptyState("Tidak ada media di sini.", modifier); return }
    LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(media, key = { it.id }) { item ->
            MediaThumbnail(
                item = item,
                rotationDegrees = rotationDegrees(item.uri),
                selectionMode = selectionMode,
                selected = item.id in selectedMediaIds,
            ) { onMediaClick(media.indexOf(item)) }
        }
    }
}
@Composable private fun MediaThumbnail(
    item: com.example.quickpic.data.MediaItem,
    rotationDegrees: Int = 0,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) = Box(
    Modifier
        .fillMaxWidth()
        .height(140.dp)
        .semantics { contentDescription = item.displayName }
        .clickable(onClick = onClick)
) {
    MediaThumbnailImage(item.uri, item.displayName, Modifier.fillMaxSize(), rotationDegrees)
    if (item.isVideo) {
        Surface(Modifier.align(Alignment.TopStart), color = MaterialTheme.colorScheme.scrim.copy(alpha = .7f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Movie, null, Modifier.size(16.dp), tint = Color.White)
                Text(" VIDEO", Modifier.padding(end = 6.dp, top = 3.dp, bottom = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!selectionMode) Icon(Icons.Default.PlayArrow, "Putar video", Modifier.align(Alignment.Center).size(48.dp), tint = Color.White)
    }
    Text(item.displayName, Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.scrim.copy(alpha = .7f)).padding(6.dp, 3.dp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    if (selectionMode) {
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(28.dp),
            shape = RoundedCornerShape(50),
            color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .55f),
            tonalElevation = 2.dp,
        ) {
            Icon(
                imageVector = if (selected) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (selected) "Ditandai" else "Belum ditandai",
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}
@Composable
private fun MediaThumbnailImage(
    uri: Uri,
    contentDescription: String?,
    modifier: Modifier,
    rotationDegrees: Int = 0,
) {
    val context = LocalContext.current
    val loader = remember(context) {
        ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()
    }
    AsyncImage(
        model = uri,
        contentDescription = contentDescription,
        imageLoader = loader,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .graphicsLayer { rotationZ = rotationDegrees.toFloat() },
    )
}

@Composable private fun MediaViewer(
    items: List<com.example.quickpic.data.MediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onRotateRequest: () -> Unit,
    onDetailsRequest: () -> Unit,
    onRenameRequest: () -> Unit,
    rotationDegrees: (Uri) -> Int,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.lastIndex), pageCount = { items.size })
    var controlsVisible by remember { mutableStateOf(true) }
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    var landscape by rememberSaveable { mutableStateOf(false) }
    var photoZoomInSignal by remember { mutableIntStateOf(0) }
    var photoZoomOutSignal by remember { mutableIntStateOf(0) }
    var viewerOverflowOpen by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)

    Dialog(onDismissRequest = { onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DisposableEffect(Unit) {
            val window = (context.findActivity())?.window
            val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()); activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = pagerState.currentPage == 0 || items[pagerState.currentPage].isVideo.not()) { page ->
                val item = items[page]
                if (item.isVideo) {
                    Box(Modifier.fillMaxSize()) {
                        VideoPlayer(item.uri, Modifier.fillMaxSize())
                        if (controlsVisible) {
                            IconButton(
                                onClick = { if (page > 0) scope.launch { pagerState.animateScrollToPage(page - 1) } },
                                modifier = Modifier.align(Alignment.CenterStart),
                                enabled = page > 0,
                            ) { Icon(Icons.Default.SkipPrevious, "Sebelumnya", tint = Color.White, modifier = Modifier.size(42.dp)) }
                            IconButton(
                                onClick = { if (page < items.lastIndex) scope.launch { pagerState.animateScrollToPage(page + 1) } },
                                modifier = Modifier.align(Alignment.CenterEnd),
                                enabled = page < items.lastIndex,
                            ) { Icon(Icons.Default.SkipNext, "Berikutnya", tint = Color.White, modifier = Modifier.size(42.dp)) }
                        }
                        if (controlsVisible) Text("${page + 1} / ${items.size}", Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp), color = Color.White)
                    }
                } else {
                    ZoomablePhoto(
                        uri = item.uri,
                        zoomInSignal = photoZoomInSignal,
                        zoomOutSignal = photoZoomOutSignal,
                        rotationDegrees = rotationDegrees(item.uri),
                    ) { controlsVisible = !controlsVisible }
                }
            }
            if (controlsVisible) {
                Surface(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Color.Black.copy(alpha = .55f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp)) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Kembali", tint = Color.White) }
                        Text(items[pagerState.currentPage].displayName, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (items[pagerState.currentPage].isVideo) IconButton(onClick = { landscape = !landscape; activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }) { Icon(Icons.Default.ScreenRotation, "Rotasi", tint = Color.White) }
                        else {
                            IconButton(onClick = { context.shareMedia(items[pagerState.currentPage].uri) }) { Icon(Icons.Default.Share, "Bagikan", tint = Color.White) }
                        }
                        Box {
                            IconButton(onClick = { viewerOverflowOpen = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                            DropdownMenu(expanded = viewerOverflowOpen, onDismissRequest = { viewerOverflowOpen = false }) {
                                DropdownMenuItem(text = { Text("Salin") }, onClick = { viewerOverflowOpen = false })
                                DropdownMenuItem(text = { Text("Rincian") }, onClick = { viewerOverflowOpen = false; onDetailsRequest() })
                                DropdownMenuItem(
                                    text = { Text("Putar") },
                                    trailingIcon = { Icon(Icons.Default.ChevronRight, "Submenu") },
                                    onClick = { viewerOverflowOpen = false; onRotateRequest() },
                                )
                                DropdownMenuItem(text = { Text("Ubah") }, onClick = { viewerOverflowOpen = false })
                                DropdownMenuItem(text = { Text("Gunakan sebagai") }, onClick = { viewerOverflowOpen = false })
                                DropdownMenuItem(text = { Text("Pindah ke") }, onClick = { viewerOverflowOpen = false })
                                DropdownMenuItem(text = { Text("Salin ke") }, onClick = { viewerOverflowOpen = false })
                                DropdownMenuItem(text = { Text("Ganti nama") }, onClick = { viewerOverflowOpen = false; onRenameRequest() })
                                DropdownMenuItem(text = { Text("Lihat di peta") }, onClick = { viewerOverflowOpen = false })
                                DropdownMenuItem(text = { Text("Pengaturan") }, onClick = { viewerOverflowOpen = false })
                            }
                        }
                    }
                }
                if (!items[pagerState.currentPage].isVideo) {
                    Row(Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 8.dp)) { ZoomButton(Icons.Default.Remove, "Zoom out") { photoZoomOutSignal++ }; Spacer(Modifier.width(4.dp)); ZoomButton(Icons.Default.Add, "Zoom in") { photoZoomInSignal++ } }
                    Text("${pagerState.currentPage + 1} / ${items.size}", Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ZoomButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) = Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = .6f)) { IconButton(onClick = onClick) { Icon(icon, description, tint = Color.White) } }

@Composable
private fun ZoomablePhoto(
    uri: Uri,
    zoomInSignal: Int,
    zoomOutSignal: Int,
    rotationDegrees: Int,
    onTap: () -> Unit,
) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var lastZoomInSignal by remember(uri) { mutableIntStateOf(zoomInSignal) }
    var lastZoomOutSignal by remember(uri) { mutableIntStateOf(zoomOutSignal) }
    LaunchedEffect(zoomInSignal) {
        if (zoomInSignal > lastZoomInSignal) { scale = min(5f, scale * 1.25f); lastZoomInSignal = zoomInSignal }
    }
    LaunchedEffect(zoomOutSignal) {
        if (zoomOutSignal > lastZoomOutSignal) { scale = max(1f, scale / 1.25f); if (scale <= 1f) offset = Offset.Zero; lastZoomOutSignal = zoomOutSignal }
    }
    Box(Modifier.fillMaxSize().pointerInput(uri) { detectTransformGestures { _, pan, zoom, _ -> scale = min(5f, max(1f, scale * zoom)); offset += pan } }) {
        AsyncImage(uri, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
                rotationZ = rotationDegrees.toFloat()
            }.clickable { onTap() })
    }
}

@Composable
private fun MediaDetailsDialog(
    item: com.example.quickpic.data.MediaItem,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rincian") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Nama", item.displayName)
                DetailRow("Jenis", item.mimeType.ifBlank { if (item.isVideo) "Video" else "Foto" })
                DetailRow("Lokasi", item.relativePath.ifBlank { "Internal storage" })
                DetailRow("Ukuran", formatFileSize(item.sizeBytes))
                if (item.isVideo) DetailRow("Durasi", formatDuration(item.durationMillis))
                DetailRow("Tanggal", formatDateTime(item.dateAddedSeconds))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "Tidak diketahui"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) { value /= 1024.0; index++ }
    return if (index == 0) "${bytes} ${units[index]}" else String.format(java.util.Locale.US, "%.2f %s", value, units[index])
}

private fun formatDateTime(seconds: Long): String {
    if (seconds <= 0L) return "Tidak diketahui"
    return java.text.SimpleDateFormat("dd MMM yyyy, HH:mm:ss", java.util.Locale("id", "ID"))
        .format(java.util.Date(seconds * 1000L))
}

@Composable
private fun VideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(ExoMediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    var position by remember(uri) { mutableLongStateOf(0L) }
    var duration by remember(uri) { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(modifier) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; controllerShowTimeoutMs = 2500 } }, modifier = Modifier.fillMaxSize())
        Text(
            text = "${formatDuration(position)} / ${formatDuration(duration)}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 52.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60 % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun Context.renameMediaItem(item: com.example.quickpic.data.MediaItem, requestedName: String): String? {
    val trimmed = requestedName.trim()
    if (trimmed.isBlank()) return "Nama tidak boleh kosong."
    if (trimmed.contains("/") || trimmed.contains("\\")) return "Nama tidak boleh mengandung karakter /."
    if (trimmed == item.displayName) return null
    return runCatching {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, trimmed) }
        val updated = contentResolver.update(item.uri, values, null, null)
        if (updated != 1) "File tidak dapat diganti nama." else null
    }.getOrElse { "Gagal mengganti nama: ${it.message ?: "akses ditolak"}" }
}

private fun Context.renameMediaFolder(folder: com.example.quickpic.data.MediaFolder, requestedName: String): String? {
    val trimmed = requestedName.trim()
    if (trimmed.isBlank()) return "Nama folder tidak boleh kosong."
    if (trimmed.contains("/") || trimmed.contains("\\")) return "Nama folder tidak boleh mengandung karakter /."
    val parent = folder.path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
    val newPath = if (parent.isBlank()) "$trimmed/" else "$parent/$trimmed/"
    if (newPath.equals(folder.path, ignoreCase = true)) return null
    return runCatching {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val items = contentResolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf(folder.path),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            buildList { while (cursor.moveToNext()) add(cursor.getLong(idColumn)) }
        } ?: emptyList()
        if (items.isEmpty()) return@runCatching "Folder kosong atau sudah berubah."
        val values = ContentValues().apply { put(MediaStore.Files.FileColumns.RELATIVE_PATH, newPath) }
        var changed = 0
        items.forEach { id ->
            val uri = ContentUris.withAppendedId(collection, id)
            changed += contentResolver.update(uri, values, null, null)
        }
        if (changed != items.size) "Sebagian isi folder tidak dapat dipindahkan. Folder belum diganti nama sepenuhnya." else null
    }.getOrElse { "Gagal mengganti nama folder: ${it.message ?: "akses ditolak"}" }
}

@Composable
private fun RenameDialog(
    title: String,
    initialName: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nama baru") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.trim().isNotEmpty()) { Text("Simpan") } },
    )
}

private fun Context.shareMedia(uri: Uri) {
    runCatching {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "*/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Bagikan media"))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable private fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Pengaturan") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Kembali") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsItem("Umum", Icons.Default.Tune)
            SettingsItem("Jelajah", Icons.Default.FolderOpen)
            SettingsItem("Lihat", Icons.Default.Visibility)
            SettingsItem("Keamanan", Icons.Default.Lock)
            SettingsItem("Singgahan (Cache)", Icons.Default.Cached)
        }
    }
}
@Composable private fun SettingsItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector) = ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable { })

@Composable private fun EmptyState(message: String, modifier: Modifier = Modifier) = Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(message) }
@Composable private fun PermissionRequired(modifier: Modifier, onRequestPermission: () -> Unit) = Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("Izinkan akses foto dan video untuk menampilkan galeri."); Button(onClick = onRequestPermission, Modifier.padding(top = 16.dp)) { Text("Izinkan akses") } }
@Composable private fun Loading(modifier: Modifier) = Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
@Composable private fun ErrorMessage(message: String, modifier: Modifier) = Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(message) }

private fun Context.hasMediaPermission() = mediaPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
private fun mediaPermissions() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
private fun Context.findActivity(): Activity? { var c: Context = this; while (c is android.content.ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }
