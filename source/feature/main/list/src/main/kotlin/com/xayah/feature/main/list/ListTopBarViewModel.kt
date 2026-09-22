package com.xayah.feature.main.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.ListData
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.UserInfo
import com.xayah.core.model.util.of
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.decodeURL
import com.xayah.core.util.launchOnDefault
import com.xayah.feature.main.list.ListTopBarUiState.Loading
import com.xayah.feature.main.list.ListTopBarUiState.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ListTopBarViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listDataRepo: ListDataRepo,
) : ViewModel() {
    private val target: Target = Target.valueOf(savedStateHandle.get<String>(MainRoutes.ARG_TARGET)!!.decodeURL().trim())
    private val opType: OpType = OpType.of(savedStateHandle.get<String>(MainRoutes.ARG_OP_TYPE)?.decodeURL()?.trim())
    private val cloudName: String = savedStateHandle.get<String>(MainRoutes.ARG_ACCOUNT_NAME)?.decodeURL()?.trim() ?: ""
    private val backupDir: String = savedStateHandle.get<String>(MainRoutes.ARG_ACCOUNT_REMOTE)?.decodeURL()?.trim() ?: ""

    val uiState: StateFlow<ListTopBarUiState> = listDataRepo.scope.flatMapLatest { scope ->
        if (scope == null) {
            flowOf(Loading)
        } else {
            when (scope.target) {
                Target.Apps -> listDataRepo.getListData().map {
                    val listData = it.castTo<ListData.Apps>()
                    Success.Apps(
                        opType = scope.opType,
                        selected = listData.selected,
                        total = listData.total,
                        isUpdating = listData.isUpdating,
                        userIndex = listData.userIndex,
                        userList = listData.userList,
                        userMap = listData.userMap,
                        outdatedCount = listData.outdatedCount,
                        cloudName = scope.cloudName,
                        backupDir = scope.backupDir,
                    )
                }

                Target.Files -> listDataRepo.getListData().map {
                    val listData = it.castTo<ListData.Files>()
                    Success.Files(
                        opType = scope.opType,
                        selected = listData.selected,
                        total = listData.total,
                        isUpdating = listData.isUpdating,
                    )
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        initialValue = Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    /**
     * 统一页模式切换：备份 ↔ 恢复（仅 Apps 目标有效）。
     * 通过重发布 ListDataRepo 作用域，令所有列表 ViewModel 自动重建。
     */
    fun switchMode() {
        viewModelScope.launchOnDefault {
            val scope = listDataRepo.scope.value ?: return@launchOnDefault
            if (scope.target != Target.Apps) return@launchOnDefault
            val newOpType = if (scope.opType == OpType.BACKUP) OpType.RESTORE else OpType.BACKUP
            listDataRepo.switchMode(scope.target, newOpType, scope.cloudName, scope.backupDir)
        }
    }

    fun search(text: String) {
        viewModelScope.launchOnDefault {
            listDataRepo.setSearchQuery(text)
        }
    }

    fun setUser(index: Int) {
        viewModelScope.launchOnDefault {
            listDataRepo.setUserIndex(index)
        }
    }
}

sealed interface ListTopBarUiState {
    data object Loading : ListTopBarUiState
    sealed class Success(
        open val opType: OpType,
        open val selected: Long,
        open val total: Long,
        open val isUpdating: Boolean,
    ) : ListTopBarUiState {
        data class Apps(
            override val opType: OpType,
            override val selected: Long,
            override val total: Long,
            override val isUpdating: Boolean,
            val userIndex: Int,
            val userList: List<UserInfo>,
            val userMap: Map<Int, Long>,
            val outdatedCount: Long = 0L, // 备份提醒角标：待更新应用数
            val cloudName: String = "",
            val backupDir: String = "",
        ) : Success(opType, selected, total, isUpdating)

        data class Files(
            override val opType: OpType,
            override val selected: Long,
            override val total: Long,
            override val isUpdating: Boolean,
        ) : Success(opType, selected, total, isUpdating)
    }
}
