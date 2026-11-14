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
 * - 阈值由 UI 按“整窗高度 1/2”换算后传入（见 [thresholdPxForWindowHeight]），
 *   不再使用固定 dp：隐藏入口的“大幅下拉”体感应在不同尺寸设备上保持一致，
 *   同时把随手发现与误触概率降到最低；
 * - 未达阈值时只有 [visualOffsetPx] 驱动的橡皮筋跟手位移，**没有任何提示或震动**；
 *   达到阈值后进入 [Phase.ARMED]，但**不立即触发**，必须等用户松手，
 *   防止“刚够阈值就弹窗”打断手势，也避免按住不放时反复触发；
 * - [visualOffsetPx] 使用橡皮筋曲线而不是线性位移，让内容“越拉越沉”，
 *   并以固定上限保证半屏的长距离拖动不会把书架推离屏幕；
 * - [progress] 仍按原始位移线性计算，表达“距离阈值还差多少”，便于测试与诊断；
 *   UI 不再用它渲染进度环：阈值前必须无提示，越过阈值后圆环固定为满。
 *
 * 该类不持有协程、不访问 Android API；UI 层只需把纵向 overscroll 传入，
 * 并读取 [visualOffsetPx] 渲染跟手位移、读取 [isArmed] 决定是否显示提示。
 */
class PullDownUnlockDetector(
    private val thresholdPx: Float,
    private val maxVisualOffsetPx: Float,
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

    /** 线性进度 0..1；用于表达距离阈值的比例，阈值处正好为 1。 */
    val progress: Float
        get() = if (thresholdPx <= 0f) 1f else (accumulatedPx / thresholdPx).coerceIn(0f, 1f)

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
     * 阈值处约为渐近线的 79%：以 1/2 屏为阈值时约 95dp，足以完整露出顶部提示层
     * （提示层约 74dp，系统字体放到 2.0 倍时约 90dp）。
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
        /**
         * 解锁阈值相对整窗高度的比例：1/2 屏。
         *
         * 之所以按屏幕比例而不是固定 dp：隐藏书架是“界面隐藏”，入口越隐蔽越好；
         * 固定 140dp 在 6.7 英寸屏上只占约 1/7，随手一拉就会亮出提示。
         * 半屏仍属于“大幅下拉”，阈值前完全静默，普通滚动与随手下滑不会暴露入口；
         * 同时比 2/3 屏更容易单手拉满，按用户要求于 2026-11-14 下调。
         */
        const val THRESHOLD_SCREEN_RATIO = 1f / 2f

        /**
         * 跟手位移的渐近线上限（dp）。
         *
         * 固定 120dp 而不是按阈值放大：阈值按整窗高度计算，若仍用“1.8 × 阈值”，
         * 书架会被推走大半屏。120dp 的上限保证长距离拖动时内容最多下移一条
         * 提示带的高度，阈值处（约 95dp）刚好露出提示（含 2.0 倍字体），
         * 又不会让书架离开屏幕。
         */
        const val MAX_VISUAL_OFFSET_DP = 120f

        /**
         * 由实测窗口像素高度换算下拉阈值。
         *
         * 高度必须包含状态栏与导航栏（书架根布局是 edge-to-edge 的整窗），
         * 这样“半屏”才与用户的行进距离一致。
         */
        fun thresholdPxForWindowHeight(windowHeightPx: Int): Float {
            if (windowHeightPx <= 0) return 0f
            return windowHeightPx * THRESHOLD_SCREEN_RATIO
        }
    }
}
