package com.moyue.reader.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 应用内调度器的抽象。
 *
 * 测试中注入 `UnconfinedTestDispatcher` 可以让异步逻辑同步执行；
 * 正式运行时使用默认调度器，避免在 ViewModel 中硬编码 Dispatchers.IO。
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

object DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
}

/** 可注入时钟，便于测试进度时间、节流冷却等与真实时间相关的逻辑。 */
fun interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
