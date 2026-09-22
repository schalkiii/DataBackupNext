package com.xayah.feature.main.list

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.xayah.core.data.repository.ListData
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.data.repository.ScopeState
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.util.of
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.decodeURL
import com.xayah.core.util.ifEmptyEncodeURLWithSpace
import com.xayah.core.util.launchOnDefault
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.navigateSingle
import com.xayah.core.work.WorkManagerInitializer
import com.xayah.feature.main.list.ListUiState.Loading
import com.xayah.feature.main.list.ListUiState.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val listDataRepo: ListDataRepo,
) : ViewModel() {
    private val target: Target = Target.valueOf(savedStateHandle.get<String>(MainRoutes.ARG_TARGET)!!.decodeURL().trim())
    private val opType: OpType = OpType.of(savedStateHandle.get<String>(MainRoutes.ARG_OP_TYPE)?.decodeURL()?.trim())
    private val cloudName: String = savedStateHandle.get<String>(MainRoutes.ARG_ACCOUNT_NAME)?.decodeURL()?.trim() ?: ""
    private val backupDir: String = savedStateHandle.get<String>(MainRoutes.ARG_ACCOUNT_REMOTE)?.decodeURL()?.trim()?.ifEmpty { context.localBackupSaveDir() } ?: context.localBackupSaveDir()

    init {
        // Reset list data
        listDataRepo.initialize(target, opType, cloudName, backupDir)
    }

    val uiState: StateFlow<ListUiState> = listDataRepo.scope.flatMapLatest { scope ->
        if (scope == null) {
            flowOf(Loading)
        } else {
            when (scope.target) {
                Target.Apps -> listDataRepo.getListData().map {
                    val listData = it.castTo<ListData.Apps>()
                    Success.Apps(
                        opType = scope.opType,
                        selected = listData.selected,
                        isUpdating = listData.isUpdating,
                        cloudName = scope.cloudName,
                        backupDir = scope.backupDir,
                    )
                }

                Target.Files -> listDataRepo.getListData().map {
                    val listData = it.castTo<ListData.Files>()
                    Success.Files(
                        opType = scope.opType,
                        selected = listData.selected,
                        isUpdating = listData.isUpdating,
                        cloudName = scope.cloudName,
                        backupDir = scope.backupDir,
                    )
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        initialValue = Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    // 当前作用域（含操作模式），由统一页切换驱动
    private val currentScope: ScopeState? get() = listDataRepo.scope.value

    fun onResume() {
        viewModelScope.launchOnDefault {
            val scope = currentScope ?: return@launchOnDefault
            when (scope.target) {
                Target.Apps -> {
                    when (scope.opType) {
                        OpType.BACKUP -> {
                            val state = uiState.value.castTo<Success.Apps>()
                            if (state.isUpdating.not()) {
                                WorkManagerInitializer.fastInitializeAndUpdateApps(context)
                            }
                        }

                        OpType.RESTORE -> {}
                    }
                }

                Target.Files -> {
                    when (scope.opType) {
                        OpType.BACKUP -> {
                            val state = uiState.value.castTo<Success.Files>()
                            if (state.isUpdating.not()) {
                                WorkManagerInitializer.fastInitializeAndUpdateFiles(context)
                            }
                        }

                        OpType.RESTORE -> {}
                    }
                }
            }
        }
    }

    fun toNextPage(navController: NavHostController) {
        val scope = currentScope ?: return
        when (scope.target) {
            Target.Apps -> {
                when (scope.opType) {
                    OpType.BACKUP -> {
                        navController.navigateSingle(MainRoutes.PackagesBackupProcessingGraph.route)
                    }

                    OpType.RESTORE -> {
                        navController.navigateSingle(
                            MainRoutes.PackagesRestoreProcessingGraph.getRoute(
                                cloudName = scope.cloudName.ifEmptyEncodeURLWithSpace(),
                                backupDir = scope.backupDir.ifEmptyEncodeURLWithSpace()
                            )
                        )
                    }
                }
            }

            Target.Files -> {
                when (scope.opType) {
                    OpType.BACKUP -> {
                        navController.navigateSingle(MainRoutes.MediumBackupProcessingGraph.route)
                    }

                    OpType.RESTORE -> {
                        navController.navigateSingle(
                            MainRoutes.MediumRestoreProcessingGraph.getRoute(
                                cloudName = scope.cloudName.ifEmptyEncodeURLWithSpace(),
                                backupDir = scope.backupDir.ifEmptyEncodeURLWithSpace()
                            )
                        )
                    }
                }
            }
        }
    }
}

sealed interface ListUiState {
    data object Loading : ListUiState
    sealed class Success(
        open val opType: OpType,
        open val selected: Long,
        open val isUpdating: Boolean,
        open val cloudName: String,
        open val backupDir: String,
    ) : ListUiState {
        data class Apps(
            override val opType: OpType,
            override val selected: Long,
            override val isUpdating: Boolean,
            override val cloudName: String,
            override val backupDir: String,
        ) : Success(opType, selected, isUpdating, cloudName, backupDir)

        data class Files(
            override val opType: OpType,
            override val selected: Long,
            override val isUpdating: Boolean,
            override val cloudName: String,
            override val backupDir: String,
        ) : Success(opType, selected, isUpdating, cloudName, backupDir)
    }
}
