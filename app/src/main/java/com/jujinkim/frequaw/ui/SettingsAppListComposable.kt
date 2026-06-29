package com.jujinkim.frequaw.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jujinkim.frequaw.R
import com.jujinkim.frequaw.Screen
import com.jujinkim.frequaw.HorizontalDirection
import com.jujinkim.frequaw.SortMode
import com.jujinkim.frequaw.VerticalDirection
import com.jujinkim.frequaw.data.FrequawData
import com.jujinkim.frequaw.data.FrequawDataHelper
import com.jujinkim.frequaw.viewmodel.SettingViewModel

@Composable
fun SettingsAppListComposable(
    data: FrequawData,
    widgetId: Int,
    viewModel: SettingViewModel = viewModel()
) {
    val context = LocalContext.current
    val widgetData = data.getWidgetSetting(widgetId)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SettingListTitleComposable(stringResource(R.string.header_app_list))
        }

        // App sort by
        item {
            SettingListItemDescriptionComposable(
                text = stringResource(R.string.app_list_sort_method),
                description = stringResource(getCurrentSortModeStringId(widgetData.sortAppBy))
            ) {
                viewModel.navigate(Screen.AppListSortMode)
            }
        }

        // App sort direction - horizontal axis
        item {
            SettingListItemDropdownComposable(
                text = stringResource(R.string.app_list_sort_direction_horizontal),
                items = mapOf(
                    HorizontalDirection.LeftToRight.name to stringResource(R.string.app_list_sort_direction_ltr),
                    HorizontalDirection.RightToLeft.name to stringResource(R.string.app_list_sort_direction_rtl)
                ),
                selectedKey = widgetData.horizontalDirection.name,
                onItemChanged = {
                    widgetData.horizontalDirection = HorizontalDirection.valueOf(it)
                    FrequawDataHelper.saveWidgetSetting(widgetData)
                }
            )
        }

        // App sort direction - vertical axis
        item {
            SettingListItemDropdownComposable(
                text = stringResource(R.string.app_list_sort_direction_vertical),
                items = mapOf(
                    VerticalDirection.TopToBottom.name to stringResource(R.string.app_list_sort_direction_ttb),
                    VerticalDirection.BottomToTop.name to stringResource(R.string.app_list_sort_direction_btt)
                ),
                selectedKey = widgetData.verticalDirection.name,
                onItemChanged = {
                    widgetData.verticalDirection = VerticalDirection.valueOf(it)
                    FrequawDataHelper.saveWidgetSetting(widgetData)
                }
            )
        }

        // App filter mode
        item {
            SettingListItemDescriptionComposable(
                text = stringResource(R.string.app_list_app_list),
                description = stringResource(R.string.app_list_app_list_desc)
            ) {
                viewModel.navigate(Screen.AppListFilterApp)
            }
        }

        // Pin app
        item {
            SettingListItemDescriptionComposable(
                text = stringResource(R.string.app_list_pin_app),
                description = stringResource(R.string.app_list_pin_app_desc),
            ) {
                viewModel.navigate(Screen.AppListPinApp)
            }
        }
    }
}

fun getCurrentSortModeStringId(mode: SortMode) = when (mode) {
    SortMode.Recommend -> R.string.select_detect_mode_recommend
    SortMode.Count -> R.string.select_detect_mode_launch_count
    SortMode.Period -> R.string.select_detect_mode_usage_time
    SortMode.Recent -> R.string.select_detect_mode_recent
}