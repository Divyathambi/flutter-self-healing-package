package com.flutterImpactAnalyzer.healing

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/** Local, per-project paths; never shared through the project's VCS settings. */
@Service(Service.Level.PROJECT)
@State(name = "FlutterSelfHealing", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SelfHealingSettings : PersistentStateComponent<SelfHealingSettings.Paths> {
    data class Paths(
        var projectPath: String = "",
        var healerPath: String = "",
        var flutterSdkPath: String = ""
    )

    private var paths = Paths()
    override fun getState(): Paths = paths.copy()
    override fun loadState(state: Paths) { paths = state.copy() }
}

