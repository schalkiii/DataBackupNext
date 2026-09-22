package com.xayah.feature.main.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.OpType
import com.xayah.core.model.UserInfo
import com.xayah.core.ui.component.Divider
import com.xayah.core.ui.component.LinearProgressIndicator
import com.xayah.core.ui.component.SearchBar
import com.xayah.core.ui.component.SecondaryTopBar
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingVertical
import com.xayah.core.ui.token.SizeTokens

@Composable
internal fun ListTopBar(viewModel: ListTopBarViewModel = hiltViewModel()) {
    val uiState: ListTopBarUiState by viewModel.uiState.collectAsStateWithLifecycle()
    ListTopBar(uiState, viewModel)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ListTopBar(
    uiState: ListTopBarUiState,
    viewModel: ListTopBarViewModel
) {
    val title: String
    var subtitle: String? = null
    when (uiState) {
        is ListTopBarUiState.Loading -> {
            title = stringResource(id = R.string.loading)
        }

        is ListTopBarUiState.Success -> {
            title = when (uiState) {
                is ListTopBarUiState.Success.Apps -> {
                    when (uiState.opType) {
                        OpType.BACKUP -> stringResource(id = R.string.select_apps)

                        OpType.RESTORE -> stringResource(id = R.string.backed_up_apps)
                    }
                }

                is ListTopBarUiState.Success.Files -> {
                    when (uiState.opType) {
                        OpType.BACKUP -> stringResource(id = R.string.select_files)

                        OpType.RESTORE -> stringResource(id = R.string.backed_up_files)
                    }
                }
            }

            if (uiState.selected != 0L && uiState.total != 0L) {
                subtitle = "(${uiState.selected}/${uiState.total})"
            }

            // 备份提醒角标：仅备份页展示，提示"N 个应用有新版本待备份"
            val outdatedText = if (uiState is ListTopBarUiState.Success.Apps && uiState.opType == OpType.BACKUP && uiState.outdatedCount > 0L) {
                stringResource(id = R.string.format_x_apps_have_updates, uiState.outdatedCount)
            } else {
                null
            }
            if (outdatedText != null) {
                subtitle = if (subtitle == null) outdatedText else "${subtitle} · $outdatedText"
            }
        }
    }

    Column {
        SecondaryTopBar(
            scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()),
            title = title,
            subtitle = subtitle,
            actions = {
                ListActions()
            },
        )

        AnimatedVisibility(visible = (uiState is ListTopBarUiState.Loading) || (uiState is ListTopBarUiState.Success && uiState.isUpdating)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (uiState is ListTopBarUiState.Success) {
            SearchBar(
                modifier = Modifier
                    .paddingHorizontal(SizeTokens.Level16)
                    .paddingVertical(SizeTokens.Level8),
                enabled = true,
                placeholder = when (uiState) {
                    is ListTopBarUiState.Success.Apps -> stringResource(id = R.string.search_bar_hint_packages)
                    is ListTopBarUiState.Success.Files -> stringResource(id = R.string.search_bar_hint_medium)
                },
                onTextChange = viewModel::search
            )
        }

        AnimatedVisibility(visible = uiState is ListTopBarUiState.Success.Apps) {
            val state = uiState.castTo<ListTopBarUiState.Success.Apps>()
            // 统一页模式切换：备份 ↔ 恢复
            ModeSwitch(opType = state.opType, onSwitch = viewModel::switchMode)
            UserTabs(selected = state.userIndex, userList = state.userList, usersMap = state.userMap, onTabClick = viewModel::setUser)
        }

        AnimatedVisibility(visible = uiState !is ListTopBarUiState.Success.Apps) {
            Divider(modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(opType: OpType, onSwitch: () -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .paddingHorizontal(SizeTokens.Level16)
            .paddingVertical(SizeTokens.Level8)
    ) {
        SegmentedButton(
            selected = opType == OpType.BACKUP,
            onClick = {
                if (opType != OpType.BACKUP) onSwitch()
            },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
        ) {
            Text(text = stringResource(id = R.string.backup))
        }
        SegmentedButton(
            selected = opType == OpType.RESTORE,
            onClick = {
                if (opType != OpType.RESTORE) onSwitch()
            },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
        ) {
            Text(text = stringResource(id = R.string.restore))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserTabs(selected: Int, userList: List<UserInfo>, usersMap: Map<Int, Long>, onTabClick: (index: Int) -> Unit) {
    if (userList.isNotEmpty()) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selected,
            edgePadding = SizeTokens.Level0,
            indicator = @Composable {
                TabRowDefaults.PrimaryIndicator(
                    Modifier.tabIndicatorOffset(selected, matchContentSize = true),
                    shape = CircleShape
                )
            },
            divider = {
                Divider(modifier = Modifier.fillMaxWidth())
            }
        ) {
            userList.forEachIndexed { index, user ->
                Tab(
                    selected = selected == index,
                    onClick = {
                        onTabClick(index)
                    },
                    text = {
                        BadgedBox(
                            modifier = Modifier.fillMaxSize(),
                            badge = {
                                if (usersMap.containsKey(user.id)) {
                                    Badge { Text(text = usersMap[user.id].toString()) }
                                }
                            }
                        ) {
                            Text(text = "${user.name} (${user.id})", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                )
            }
        }
    }
}
