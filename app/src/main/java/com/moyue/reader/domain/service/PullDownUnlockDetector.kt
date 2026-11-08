package com.moyue.reader.domain.service

/**
 * 书架下拉解锁手势的状态机。
 *
 * 设计目标是把“是否达到解锁阈值”和“动画跟手程度”从 Compose 中剥离出来，
 * 让手势判定可以在 JVM 上确定性地测试。状态迁移如下：
 *
 * ```text
 * IDLE --顶部向下拖动--> PULLING --累计 >= 阈值--> ARMED --松手--> 触发并复位
 *   ^                       |                        |
 *   +----反向/离开顶部/取消--+------------------------+
 * ```
 *
 * 关键行为：
 * - 只有书架处于顶部且手指向下拖动时才累计位移，避免普通滚动误触发；
 * - 达到阈值后进入 [Phase.ARMED]，但**不立即触发**，必须等用户松手，
 *   防止“刚够阈值就弹窗”打断手势，也避免按住不放时反复触发；
 * - [visualOffsetPx] 使用橡皮筋曲线而不是线性位移，让内容“越拉越沉”；
 * - [progress] 仍按原始位移线性计算，保证进度环在阈值处刚好填满。
 *
 * 该类不持有协程、不访问 Android API；UI 层只需把纵向 overscroll 传入，
 * 并读取 [progress]、[visualOffsetPx] 渲染即可。
 */
class PullDownUnlockDetector(
    private val thresholdPx: Float,
    private val maxVisualOffsetPx: Float = thresholdPx * DEFAULT_MAX_OFFSET_RATIO,
) {

    enum class Phase {
        /** 没有下拉手势。 */
        IDLE,

        /** 正在下拉但还没到解锁阈值。 */
        PULLING,

        /** 已越过阈值，松手即可触发解锁。 */
        ARMED,
    }

    /** 自本次手势开始累计的原始下拉位移（像素），用于阈值判定与进度计算。 */
    var accumulatedPx: Float = 0f
        private set

    /** 当前手势阶段。 */
    var phase: Phase = Phase.IDLE
        private set

    /** 线性进度 0..1；用于进度环，阈值处正好为 1。 */
    val progress: Float
        get() = (accumulatedPx / thresholdPx).coerceIn(0f, 1f)

    val isArmed: Boolean
        get() = phase == Phase.ARMED

    /**
     * 内容的视觉下移距离（像素）。
     *
     * 橡皮筋公式 `max * raw / (raw + max)`：
     * - 起点斜率约为 1，手指刚拖动时内容几乎 1:1 跟手；
     * - 位移越大，分母增长越快，跟手比例逐渐下降；
     * - 渐近线为 [maxVisualOffsetPx]，不会无限下移。
     *
     * 阈值处的位移约为 0.36 × max（默认约 90dp），足以完整露出顶部提示。
     */
    val visualOffsetPx: Float
        get() {
            if (accumulatedPx <= 0f) return 0f
            return maxVisualOffsetPx * accumulatedPx / (accumulatedPx + maxVisualOffsetPx)
        }

    /**
     * 输入一次向下的 overscroll 增量。
     *
     * @param deltaYPx 手指向下为正的位移，调用方需传入绝对值（不是 scroll delta）。
     * @param isAtTop 书架是否处于顶部。
     * @return 是否应消费这次位移。非顶部或向上拖动返回 false，让列表正常滚动。
     */
    fun onDrag(deltaYPx: Float, isAtTop: Boolean): Boolean {
        if (!isAtTop || deltaYPx <= 0f) {
            reset()
            return false
        }
        accumulatedPx += deltaYPx
        phase = if (accumulatedPx >= thresholdPx) Phase.ARMED else Phase.PULLING
        return true
    }

    /**
     * 手势结束（手指抬起或取消）时调用。
     *
     * @return true 表示越过阈值、应当弹出密码框；无论结果如何都会复位，
     *   保证同一次手势最多触发一次，下一次手势从零开始。
     */
    fun onRelease(): Boolean {
        val armed = isArmed
        reset()
        return armed
    }

    /** 立即回到初始状态；用于反向滚动、离开顶部、模式切换与动画取消。 */
    fun reset() {
        accumulatedPx = 0f
        phase = Phase.IDLE
    }

    companion object {
        /** 触发阈值：140dp，按当前屏幕密度换算成像素后构造。 */
        const val DEFAULT_THRESHOLD_DP = 140f

        /**
         * 视觉位移渐近线相对阈值的比例。
         *
         * 取 1.8 使阈值处约有 90dp 位移：既能完整露出锁形提示与文案，
         * 又不会因为继续拖动把书架推得过远。
         */
        const val DEFAULT_MAX_OFFSET_RATIO = 1.8f
    }
}
