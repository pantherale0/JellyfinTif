package com.github.pantherale0.jellyfintif.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

fun CoroutineScope.launchIO(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(context = context, block = block)
