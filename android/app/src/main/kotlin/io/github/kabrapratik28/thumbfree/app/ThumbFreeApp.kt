package io.github.kabrapratik28.thumbfree.app

import android.app.Application

class ThumbFreeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The :engine process only hosts EngineService.
        if (isMainProcess(Application.getProcessName(), packageName)) AppGraph.init(this)
    }

    companion object {
        fun isMainProcess(processName: String, packageName: String): Boolean = processName == packageName
    }
}
