package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ZoyaAccessibilityService : AccessibilityService() {

    data class NodeSummary(
        val text: String,
        val description: String,
        val viewId: String,
        val className: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val bounds: Rect
    )

    data class ScreenInspectionResult(
        val foregroundPackage: String,
        val windowTitle: String,
        val allTexts: List<String>,
        val interactiveElements: List<NodeSummary>,
        val editableFields: List<NodeSummary>
    )

    companion object {
        var shouldAutoClick = false
            set(value) {
                field = value
                if (value) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        field = false
                    }, 10000)
                }
            }
        var targetAppName = "whatsapp"
        var instance: ZoyaAccessibilityService? = null

        fun isServiceRunning(): Boolean = instance != null

        fun dispatchGestureClick(x: Float, y: Float): Boolean {
            val inst = instance ?: return false
            val path = Path()
            path.moveTo(x, y)
            path.lineTo(x, y)

            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            val gesture = builder.build()

            return inst.dispatchGesture(gesture, null, null)
        }

        fun dispatchGestureLongPress(x: Float, y: Float, durationMs: Long = 1000): Boolean {
            val inst = instance ?: return false
            val path = Path()
            path.moveTo(x, y)
            path.lineTo(x, y)

            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            val gesture = builder.build()

            return inst.dispatchGesture(gesture, null, null)
        }

        fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
            val inst = instance ?: return false
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)

            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            val gesture = builder.build()

            return inst.dispatchGesture(gesture, null, null)
        }

        fun clickTextOnScreen(text: String): Boolean {
            val inst = instance ?: return false
            return inst.findAndClick(text)
        }

        fun inspectScreen(): ScreenInspectionResult? {
            val inst = instance ?: return null
            return inst.inspectCurrentScreen()
        }

        fun typeTextOnScreen(text: String, targetField: String? = null, clearFirst: Boolean = false): Boolean {
            val inst = instance ?: return false
            return inst.performType(text, targetField, clearFirst)
        }

        fun scrollScreen(direction: String): Boolean {
            val inst = instance ?: return false
            return inst.performScroll(direction)
        }

        fun pressGlobalBack(): Boolean {
            val inst = instance ?: return false
            return inst.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        fun pressGlobalHome(): Boolean {
            val inst = instance ?: return false
            return inst.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        fun pressGlobalRecents(): Boolean {
            val inst = instance ?: return false
            return inst.performGlobalAction(GLOBAL_ACTION_RECENTS)
        }

        fun getForegroundPackageName(): String {
            val inst = instance ?: return ""
            return inst.rootInActiveWindow?.packageName?.toString() ?: ""
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("ZoyaAccessibility", "Accessibility Service Connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !shouldAutoClick) return

        val packageName = event.packageName?.toString() ?: ""
        if (packageName.contains("whatsapp")) {
            val rootNode = rootInActiveWindow ?: return
            val clicked = searchAndClickSendButton(rootNode)
            if (clicked) {
                Log.d("ZoyaAccessibility", "Successfully clicked send button!")
                shouldAutoClick = false
            }
        }
    }

    fun inspectCurrentScreen(): ScreenInspectionResult? {
        val root = rootInActiveWindow ?: return null
        val texts = mutableListOf<String>()
        val interactive = mutableListOf<NodeSummary>()
        val editable = mutableListOf<NodeSummary>()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (text.isNotEmpty()) {
                texts.add(text)
            } else if (desc.isNotEmpty()) {
                texts.add(desc)
            }

            if (isClickable || isEditable || text.isNotEmpty() || desc.isNotEmpty()) {
                val summary = NodeSummary(
                    text = text,
                    description = desc,
                    viewId = viewId,
                    className = className,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    bounds = bounds
                )
                if (isEditable) {
                    editable.add(summary)
                }
                if (isClickable) {
                    interactive.add(summary)
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)

        val pkg = root.packageName?.toString() ?: ""
        val title = root.paneTitle?.toString() ?: ""

        return ScreenInspectionResult(
            foregroundPackage = pkg,
            windowTitle = title,
            allTexts = texts.distinct(),
            interactiveElements = interactive,
            editableFields = editable
        )
    }

    fun findAndClick(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val cleanQuery = query.trim().lowercase()

        // 1. First search by exact/partial text
        val nodesByText = root.findAccessibilityNodeInfosByText(query)
        if (nodesByText.isNotEmpty()) {
            for (node in nodesByText) {
                if (performNodeClick(node)) return true
            }
        }

        // 2. Search whole tree for matches in text, description, or viewId
        var targetNode: AccessibilityNodeInfo? = null

        fun searchTree(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (text.contains(cleanQuery) || desc.contains(cleanQuery) || viewId.contains(cleanQuery)) {
                targetNode = node
                return true
            }

            for (i in 0 until node.childCount) {
                if (searchTree(node.getChild(i))) return true
            }
            return false
        }

        searchTree(root)
        if (targetNode != null) {
            return performNodeClick(targetNode!!)
        }

        return false
    }

    fun performType(text: String, targetField: String?, clearFirst: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false

        var targetNode: AccessibilityNodeInfo? = null

        // If targetField is specified, search for it
        if (!targetField.isNullOrBlank()) {
            val cleanTarget = targetField.trim().lowercase()
            fun searchTarget(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                val nodeText = node.text?.toString()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val hint = node.hintText?.toString()?.lowercase() ?: ""
                val viewId = node.viewIdResourceName?.lowercase() ?: ""

                if (node.isEditable && (nodeText.contains(cleanTarget) || desc.contains(cleanTarget) || hint.contains(cleanTarget) || viewId.contains(cleanTarget))) {
                    targetNode = node
                    return true
                }
                for (i in 0 until node.childCount) {
                    if (searchTarget(node.getChild(i))) return true
                }
                return false
            }
            searchTarget(root)
        }

        // Fallback to find any focused or editable node
        if (targetNode == null) {
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode == null) {
            fun findFirstEditable(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isEditable) {
                    targetNode = node
                    return true
                }
                for (i in 0 until node.childCount) {
                    if (findFirstEditable(node.getChild(i))) return true
                }
                return false
            }
            findFirstEditable(root)
        }

        val nodeToType = targetNode ?: return false

        // Focus node
        nodeToType.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val arguments = Bundle()
        val finalText = if (clearFirst) {
            text
        } else {
            val existing = nodeToType.text?.toString() ?: ""
            if (existing.isNotEmpty()) "$existing $text" else text
        }
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        return nodeToType.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun performScroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val isForward = direction.lowercase() in listOf("forward", "down", "right")

        fun tryScroll(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isScrollable) {
                val action = if (isForward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                if (node.performAction(action)) return true
            }
            for (i in 0 until node.childCount) {
                if (tryScroll(node.getChild(i))) return true
            }
            return false
        }

        if (tryScroll(root)) return true

        // Fallback: Gesture swipe on screen center
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        return if (isForward) {
            // Scroll down: swipe finger from bottom to top
            dispatchSwipe(width / 2f, height * 0.75f, width / 2f, height * 0.25f, 400)
        } else {
            // Scroll up: swipe finger from top to bottom
            dispatchSwipe(width / 2f, height * 0.25f, width / 2f, height * 0.75f, 400)
        }
    }

    private fun searchAndClickSendButton(node: AccessibilityNodeInfo): Boolean {
        val idsToTry = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send"
        )
        for (id in idsToTry) {
            val sendButtons = node.findAccessibilityNodeInfosByViewId(id)
            if (sendButtons.isNotEmpty()) {
                for (button in sendButtons) {
                    if (performNodeClick(button)) {
                        Log.d("ZoyaAccessibility", "Clicked send button by ID: $id")
                        return true
                    }
                }
            }
        }
        return recursiveSearchAndClick(node)
    }

    private fun recursiveSearchAndClick(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc == "send" || desc == "bheje" || desc == "bhejen" || desc == "envio") {
            if (performNodeClick(node)) {
                Log.d("ZoyaAccessibility", "Clicked send button by content description!")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (recursiveSearchAndClick(child)) {
                    return true
                }
            }
        }
        return false
    }

    private fun performNodeClick(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            if (dispatchGestureClick(x, y)) {
                return true
            }
        }

        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    override fun onInterrupt() {
        Log.d("ZoyaAccessibility", "Accessibility Service Interrupted")
    }
}
