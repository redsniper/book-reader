package com.moyue.reader.domain.service

/**
 * 书架下拉解锁检测器。
 *
 * 纯状态机，不依赖 Compose：
 * - 仅在书架处于顶部时累计向下的 overscroll；
 * - 累计超过 thresholdPx 时返回一次 true；
 * - 非顶部或反向滚动则复位。
 *
 * UI 层通过 NestedScrollConnection 把 overscroll 的 available.y 传入即可。
 */
class PullDownUnlockDetector(private val thresholdPx: Float) {

    var accumulatedPx: Float = 0f
        private set

    private var armed: Boolean = true

    val progress: Float
        get() = (accumulatedPx / thresholdPx).coerceIn(0f, 1f)

    /**
     * 输入一次 overscroll 增量。
     *
     * @param deltaYPx 向下为正（NestedScrollConnection 的 available.y）。
     * @param isAtTop 列表是否处于顶部。
     * @return 是否触发解锁。
     */
    fun onOverscroll(deltaYPx: Float, isAtTop: Boolean): Boolean {
        if (!isAtTop || deltaYPx <= 0f) {
            reset()
            return false
        }
        if (!armed) return false
        accumulatedPx += deltaYPx
        if (accumulatedPx >= thresholdPx) {
            armed = false
            accumulatedPx = 0f
            return true
        }
        return false
    }

    /** 手势结束/取消时调用，允许下一次手势重新触发。 */
    fun reset() {
        accumulatedPx = 0f
        armed = true
    }

    companion object {
        /** 触发阈值：140dp，按当前屏幕密度换算成像素后构造。 */
        const val DEFAULT_THRESHOLD_DP = 140f
    }
}
