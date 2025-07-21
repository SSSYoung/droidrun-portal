package com.droidrun.portal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.droidrun.portal.model.ElementNode
import com.droidrun.portal.model.PhoneState
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Droidrun 在手机端的无障碍服务核心实现。
 * 该服务负责：
 * 1. 周期性抓取当前页面可交互元素并缓存
 * 2. 向 OverlayManager 绘制高亮框及索引号
 * 3. 通过 ContentProvider 接口供外部（PC 侧 Agent）读取 UI 树与手机状态
 */
class DroidrunAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DroidrunA11yService"
        private var instance: DroidrunAccessibilityService? = null
        private const val MIN_ELEMENT_SIZE = 5 // 最小元素尺寸（像素），过滤过小控件

        // 周期刷新常量
        private const val REFRESH_INTERVAL_MS = 250L // 每 250 ms 更新一次
        private const val MIN_FRAME_TIME_MS = 16L    // 两帧之间的最小间隔（约 60 FPS）

        fun getInstance(): DroidrunAccessibilityService? = instance
    }

    private lateinit var overlayManager: OverlayManager        // 悬浮框绘制管理
    private val screenBounds = Rect()                           // 屏幕矩形区域
    private lateinit var configManager: ConfigManager           // 配置持久化管理
    private val mainHandler = Handler(Looper.getMainLooper())   // 主线程 Handler

    // 周期刷新相关状态
    private var isInitialized = false
    private val isProcessing = AtomicBoolean(false)             // 防抖锁，确保同一时刻只刷新一次
    private var lastUpdateTime = 0L
    private var currentPackageName: String = ""                // 当前前台包名
    private val visibleElements = mutableListOf<ElementNode>()  // 缓存 ElementNode，便于 recycle

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(this)
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val windowMetrics = windowManager.currentWindowMetrics
        val bounds = windowMetrics.bounds
        // 记录屏幕尺寸，后续用来判断元素是否在屏内
        screenBounds.set(0, 0, bounds.width(), bounds.height())

        // 初始化 ConfigManager（内部使用 SharedPreferences）
        configManager = ConfigManager.getInstance(this)
        isInitialized = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager.showOverlay() // 服务连通即显示悬浮层
        instance = this

        // 配置无障碍监听参数
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK // 监听所有事件
            packageNames = null                            // 监控所有包
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE

            // API 34 以上，开启双指穿透，可避免手势冲突
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH
            }
        }

        // 应用已保存的可视化配置
        applyConfiguration()

        // 启动周期性更新任务
        startPeriodicUpdates()

        Log.d(TAG, "Accessibility service connected and configured")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: ""

        // 检测前台包名变化，若变化则重置 Overlay
        if (eventPackage.isNotEmpty() && eventPackage != currentPackageName && currentPackageName.isNotEmpty()) {
            resetOverlayState()
        }

        if (eventPackage.isNotEmpty()) {
            currentPackageName = eventPackage
        }

        // 事件类型若涉及 UI 内容变动，则交由周期任务统一刷新
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 具体刷新由 updateRunnable 负责
            }
        }
    }

    // 周期刷新任务 Runnable
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isInitialized && configManager.overlayVisible) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastUpdate = currentTime - lastUpdateTime

                if (timeSinceLastUpdate >= MIN_FRAME_TIME_MS) {
                    refreshVisibleElements() // 拉取元素并更新 Overlay
                    lastUpdateTime = currentTime
                }
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private fun startPeriodicUpdates() {
        lastUpdateTime = System.currentTimeMillis()
        mainHandler.postDelayed(updateRunnable, REFRESH_INTERVAL_MS)
        Log.d(TAG, "Started periodic updates")
    }

    private fun stopPeriodicUpdates() {
        mainHandler.removeCallbacks(updateRunnable)
        Log.d(TAG, "Stopped periodic updates")
    }

    /**
     * 根据当前无障碍树刷新可见元素列表，并更新 Overlay。
     */
    private fun refreshVisibleElements() {
        if (!isProcessing.compareAndSet(false, true)) {
            return // 若正在处理则直接返回，防止并发
        }

        try {
            if (currentPackageName.isEmpty()) {
                overlayManager.clearElements()
                overlayManager.refreshOverlay()
                return
            }

            // 清除上一帧元素引用，避免内存泄漏
            clearElementList()

            // 获取新的元素树
            val elements = getVisibleElementsInternal()

            // 若 Overlay 可见且元素列表不为空则刷新显示
            if (configManager.overlayVisible && elements.isNotEmpty()) {
                overlayManager.clearElements()

                elements.forEach { rootElement ->
                    addElementAndChildrenToOverlay(rootElement, 0)
                }

                overlayManager.refreshOverlay()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing visible elements: ${e.message}", e)
        } finally {
            isProcessing.set(false)
        }
    }

    /**
     * 前台包名切换时重置 Overlay 状态
     */
    private fun resetOverlayState() {
        try {
            overlayManager.clearElements()
            overlayManager.refreshOverlay()
            clearElementList()
            Log.d(TAG, "Reset overlay state for package change")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting overlay state: ${e.message}", e)
        }
    }

    /**
     * 回收全部 ElementNode 里的 AccessibilityNodeInfo，避免泄漏
     */
    private fun clearElementList() {
        for (element in visibleElements) {
            try {
                element.nodeInfo.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling node: ${e.message}")
            }
        }
        visibleElements.clear()
    }

    /**
     * 应用用户配置（是否显示 Overlay、Y 偏移等）
     */
    private fun applyConfiguration() {
        mainHandler.post {
            try {
                val config = configManager.getCurrentConfiguration()
                if (config.overlayVisible) {
                    overlayManager.showOverlay()
                } else {
                    overlayManager.hideOverlay()
                }
                overlayManager.setPositionOffsetY(config.overlayOffset)
                Log.d(TAG, "Applied configuration: overlayVisible=${config.overlayVisible}, overlayOffset=${config.overlayOffset}")
            } catch (e: Exception) {
                Log.e(TAG, "Error applying configuration: ${e.message}", e)
            }
        }
    }

    // 以下方法由 MainActivity 通过 instance 调用，实现 UI 操控 ------------------------------

    /** 设置悬浮框可见性 */
    fun setOverlayVisible(visible: Boolean): Boolean {
        return try {
            configManager.overlayVisible = visible

            mainHandler.post {
                if (visible) {
                    overlayManager.showOverlay()
                    // 显示时立即刷新一帧，避免空框
                    refreshVisibleElements()
                } else {
                    overlayManager.hideOverlay()
                }
            }

            Log.d(TAG, "Overlay visibility set to: $visible")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting overlay visibility: ${e.message}", e)
            false
        }
    }

    /** 查询悬浮框是否可见 */
    fun isOverlayVisible(): Boolean = configManager.overlayVisible

    /** 设置悬浮框 Y 方向偏移 */
    fun setOverlayOffset(offset: Int): Boolean {
        return try {
            configManager.overlayOffset = offset

            mainHandler.post {
                overlayManager.setPositionOffsetY(offset)
            }

            Log.d(TAG, "Overlay offset set to: $offset")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting overlay offset: ${e.message}", e)
            false
        }
    }

    /** 获取当前悬浮框 Y 偏移 */
    fun getOverlayOffset(): Int = configManager.overlayOffset

    /** 提供给 ContentProvider 直接读取的公开方法 */
    fun getVisibleElements(): MutableList<ElementNode> {
        return getVisibleElementsInternal()
    }

    /**
     * 构造当前可见元素树，返回根节点列表
     */
    private fun getVisibleElementsInternal(): MutableList<ElementNode> {
        val elements = mutableListOf<ElementNode>()
        val indexCounter = IndexCounter(1) // 从 1 开始编号

        val rootNode = rootInActiveWindow ?: return elements
        val rootElement = findAllVisibleElements(rootNode, 0, null, indexCounter)
        rootElement?.let {
            collectRootElements(it, elements)
        }

        // 记录下来用于稍后回收
        synchronized(visibleElements) {
            clearElementList()
            visibleElements.addAll(elements)
        }

        return elements
    }

    // 把根元素放进列表（目前只有单窗口场景，可扩展多窗口）
    private fun collectRootElements(element: ElementNode, rootElements: MutableList<ElementNode>) {
        rootElements.add(element)
    }

    /**
     * 递归遍历无障碍树，筛选可见元素并构建 ElementNode 关系
     */
    private fun findAllVisibleElements(
        node: AccessibilityNodeInfo,
        windowLayer: Int,
        parent: ElementNode?,
        indexCounter: IndexCounter
    ): ElementNode? {
        try {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val isInScreen = Rect.intersects(rect, screenBounds)
            val hasSize = rect.width() > MIN_ELEMENT_SIZE && rect.height() > MIN_ELEMENT_SIZE

            var currentElement: ElementNode? = null

            if (isInScreen && hasSize) {
                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                val className = node.className?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                // 给元素生成展示文本：优先文本→描述→id→类名
                val displayText = when {
                    text.isNotEmpty() -> text
                    contentDesc.isNotEmpty() -> contentDesc
                    viewId.isNotEmpty() -> viewId.substringAfterLast('/')
                    else -> className.substringAfterLast('.')
                }

                // 元素类型简单分类，可按需扩展
                val elementType = if (node.isClickable) {
                    "Clickable"
                } else if (node.isCheckable) {
                    "Checkable"
                } else if (node.isEditable) {
                    "Input"
                } else if (text.isNotEmpty()) {
                    "Text"
                } else if (node.isScrollable) {
                    "Container"
                } else {
                    "View"
                }

                val id = ElementNode.createId(rect, className.substringAfterLast('.'), displayText)

                currentElement = ElementNode(
                    AccessibilityNodeInfo(node),
                    Rect(rect),
                    displayText,
                    className.substringAfterLast('.'),
                    windowLayer,
                    System.currentTimeMillis(),
                    id
                )

                // 生成并赋值唯一索引，用于 Overlay 显示序号
                currentElement.overlayIndex = indexCounter.getNext()

                // 建立父子层级关系
                parent?.addChild(currentElement)
            }

            // 递归处理子节点
            for (i in 0 until node.childCount) {
                val childNode = node.getChild(i) ?: continue
                findAllVisibleElements(childNode, windowLayer, currentElement, indexCounter)
                // 子节点已在上面 parent?.addChild() 中关联
            }

            return currentElement

        } catch (e: Exception) {
            Log.e(TAG, "Error in findAllVisibleElements: ${e.message}", e)
            return null
        }
    }

    /**
     * 组装并返回当前手机状态
     */
    fun getPhoneState(): PhoneState {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val keyboardVisible = detectKeyboardVisibility()
        val currentPackage = rootInActiveWindow?.packageName?.toString()
        val appName = getAppName(currentPackage)

        return PhoneState(focusedNode, keyboardVisible, currentPackage, appName)
    }

    /** 判断软键盘是否显示 */
    private fun detectKeyboardVisibility(): Boolean {
        try {
            val windows = windows
            if (windows != null) {
                val hasInputMethodWindow = windows.any { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                windows.forEach { it.recycle() }
                return hasInputMethodWindow
            } else { return false }
        } catch (e: Exception) { return false}
    }

    /** 通过包名获取应用名称 */
    private fun getAppName(packageName: String?): String? {
        return try {
            if (packageName == null) return null

            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name for package $packageName: ${e.message}")
            null
        }
    }

    /** 辅助类：自增索引计数器 */
    private class IndexCounter(private var current: Int = 1) {
        fun getNext(): Int = current++
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
        stopPeriodicUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicUpdates()
        clearElementList()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * 将 ElementNode 及其子节点递归添加到 OverlayManager
     */
    private fun addElementAndChildrenToOverlay(element: ElementNode, depth: Int) {
        overlayManager.addElement(
            text = element.text,
            rect = element.rect,
            type = element.className,
            index = element.overlayIndex
        )

        for (child in element.children) {
            addElementAndChildrenToOverlay(child, depth + 1)
        }
    }
}