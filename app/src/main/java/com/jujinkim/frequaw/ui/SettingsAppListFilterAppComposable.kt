package com.jujinkim.frequaw.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.jujinkim.frequaw.FilterMode
import com.jujinkim.frequaw.FrequawApp
import com.jujinkim.frequaw.R
import com.jujinkim.frequaw.applist.AppListManager
import com.jujinkim.frequaw.data.FrequawData
import com.jujinkim.frequaw.data.FrequawDataHelper
import com.jujinkim.frequaw.model.AppFilter
import com.jujinkim.frequaw.model.init
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
@Composable
fun SettingsAppListFilterAppComposable(data: FrequawData, widgetId: Int) = Column {
    val widgetData = data.getWidgetSetting(widgetId)
    var searchStr by rememberSaveable { mutableStateOf("") }
    var isFilterModeMenuOpen by remember { mutableStateOf(false) }
    var isBlockMode by rememberSaveable { mutableStateOf(widgetData.filterMode == FilterMode.BlockList) }

    val items = remember { mutableStateListOf<AppFilter>() }
    var isLoadingProgressShown by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .clickable { isFilterModeMenuOpen = true }
                .wrapContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.filter_mode),
                fontSize = 12.sp
            )
            Text(
                text = if (isBlockMode) stringResource(R.string.blocklist) else stringResource(R.string.allowlist),
                fontSize = 16.sp
            )
            DropdownMenu(
                expanded = isFilterModeMenuOpen,
                onDismissRequest = { isFilterModeMenuOpen = false }
            ) {
                FilterMode.values().forEach { mode ->
                    DropdownMenuItem(onClick = {
                        isBlockMode = mode == FilterMode.BlockList
                        isFilterModeMenuOpen = false
                        widgetData.filterMode = mode
                        FrequawDataHelper.saveWidgetSetting(widgetData)
                    }) {
                        Text(
                            text = if (mode == FilterMode.BlockList) stringResource(R.string.blocklist)
                            else stringResource(R.string.allowlist),
                        )
                    }
                }
            }
        }


        Box(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight()) {
            TextField(
                modifier = Modifier.fillMaxWidth(),
                value = searchStr,
                onValueChange = { searchStr = it },
                label = { Text(stringResource(R.string.search_apps)) }
            )
            if (isLoadingProgressShown)
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp))
        }
    }
    Text(
        text = if (isBlockMode) stringResource(R.string.blocklist_desc)
        else stringResource(R.string.allowlist_desc),
        fontSize = 14.sp,
        modifier = Modifier.padding(8.dp)
    )

    // Rebuild the list when the mode changes or the (debounced) search string changes.
    // collectLatest aborts an in-flight build as soon as a newer search arrives, and the
    // list is only mutated on the main thread after the background build completes, so a
    // cancelled build can never write stale (duplicate) results.
    LaunchedEffect(isBlockMode) {
        snapshotFlow { searchStr }
            .debounce { query -> if (query.isBlank()) 0L else 300L }
            .collectLatest { query ->
                isLoadingProgressShown = true

                val newItems = withContext(Dispatchers.Default) {
                    // Convert the list to AppFilter list with isFilter=true and sort them by app name
                    val appLists = (if (isBlockMode) widgetData.blockApps else widgetData.allowApps)
                        .map { filterPackageName ->
                            ensureActive()
                            AppFilter(filterPackageName, true).init()
                        }
                        .sortedBy { appFilter -> appFilter.appName }
                        .toMutableList()

                    // Add all installed apps with isFilter=false into the list
                    appLists.addAll(
                        AppListManager.getInstalledApps()
                            .filter { pkg -> appLists.find { it.packageName == pkg } == null }
                            .map { filterPackageName ->
                                ensureActive()
                                AppFilter(filterPackageName, false).init()
                            }
                            .sortedBy { appFilter -> appFilter.appName }
                    )

                    // Remove some apps(Frequaw app, Search keyword) from the list and return it
                    appLists
                        .asSequence()
                        .filterNot { appFilter -> appFilter.packageName == FrequawApp.appContext.packageName }
                        .filter { appFilter ->
                            if (query.isBlank()) return@filter true
                            return@filter appFilter.appName.contains(query, true) ||
                                    appFilter.packageName.contains(query, true)
                        }
                        .toList()
                }

                items.clear()
                items.addAll(newItems)
                isLoadingProgressShown = false
            }
    }

    // items list
    LazyColumn {
        items(items) { appFilter ->
            ItemComposable(
                appFilter.icon?.toBitmap() ?: Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565),
                appFilter.appName,
                appFilter.packageName,
                appFilter.isFilter
            ) {
                if (isBlockMode) {
                    if (it) widgetData.blockApps.add(appFilter.packageName)
                    else widgetData.blockApps.remove(appFilter.packageName)
                } else {
                    if (it) widgetData.allowApps.add(appFilter.packageName)
                    else widgetData.allowApps.remove(appFilter.packageName)
                }
                appFilter.isFilter = it
                FrequawDataHelper.saveWidgetSetting(widgetData)
            }
        }
    }
}

@Composable
private fun ItemComposable(
    icon: Bitmap,
    name: String,
    pkg: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) = Row(
    modifier = Modifier.padding(4.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Image(
        bitmap = icon.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(48.dp)
    )
    Spacer(modifier = Modifier.width(4.dp))
    Column(Modifier.weight(1f)) {
        Text(
            text = name,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pkg,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    var checkedState by remember(enabled) { mutableStateOf(enabled) }
    Switch(
        checked = checkedState,
        onCheckedChange = {
            checkedState = it
            onCheckedChange(it)
        }
    )
}