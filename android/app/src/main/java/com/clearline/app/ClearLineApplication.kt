package com.clearline.app

import android.app.Application

class ClearLineApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
    override fun onCreate() { super.onCreate(); graph.initialize() }
}
