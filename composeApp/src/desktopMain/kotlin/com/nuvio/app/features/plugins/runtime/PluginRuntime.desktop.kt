package com.nuvio.app.features.plugins.runtime

import com.dokar.quickjs.QuickJs
import com.nuvio.app.features.plugins.configureDesktopQuickJsLibrary
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal val pluginDispatcher: CoroutineDispatcher = Dispatchers.Default

internal fun QuickJs.configurePluginRuntime() = Unit

internal suspend fun preparePluginRuntime() = withContext(Dispatchers.IO) { configureDesktopQuickJsLibrary() }
