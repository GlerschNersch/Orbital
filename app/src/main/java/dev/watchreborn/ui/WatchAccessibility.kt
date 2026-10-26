package dev.watchreborn.ui

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * TalkBack support for [WatchView], which draws its whole UI on one canvas.
 *
 * Exposes the currently interactive elements as virtual accessibility views
 * through a framework [AccessibilityNodeProvider] (no extra dependencies):
 *
 *  - app grid / search / folder / overview bubbles, built from the same
 *    per-frame hit circles the touch handler consumes, so a node exists for
 *    exactly what a sighted user can tap, with the same bounds.
 *
 * Activating a node calls back into the view's real tap handler at the node's
 * centre, so accessibility users get identical behaviour (launch, open
 * folder, widget pod, card action) rather than a parallel implementation.
 *
 * The Context Card rows and Cluster sheet rows are covered too (1.8.0),
 * from the same rect lists their draws build. The topology picker, widget
 * pod buttons and Control Center tiles followed in 1.9.1, from their own
 * rect lists — every interactive surface now exposes nodes.
 */
class WatchAccessibilityProvider(
    private val host: View,
    private val nodesProvider: () -> List<Node>,
    private val activate: (Node) -> Unit,
    private val longActivate: (Node) -> Unit = {},
) : AccessibilityNodeProvider() {

    /** One virtual accessibility view: stable [key], spoken [label], bounds. */
    data class Node(
        val key: String,
        val label: String,
        val bounds: Rect,
        val centerX: Float,
        val centerY: Float,
    )

    private val manager =
        host.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    private var focusedKey: String? = null
    private var hoveredKey: String? = null
    private var lastSignature: Int = Int.MIN_VALUE

    val touchExplorationEnabled: Boolean
        get() = manager.isEnabled && manager.isTouchExplorationEnabled

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == HOST_VIEW_ID) {
            val info = AccessibilityNodeInfo()
            host.onInitializeAccessibilityNodeInfo(info)
            // The host node must enumerate its virtual children, or the
            // accessibility service never learns they exist.
            val count = nodesProvider().size
            for (i in 0 until count) info.addChild(host, i)
            return info
        }
        val node = nodesProvider().getOrNull(virtualViewId) ?: return null
        return nodeInfo(virtualViewId, node)
    }

    private fun nodeInfo(virtualViewId: Int, node: Node): AccessibilityNodeInfo {
        val info = AccessibilityNodeInfo()
        info.setSource(host, virtualViewId)
        info.setParent(host)
        info.packageName = host.context.packageName
        info.className = "android.widget.Button"
        info.contentDescription = node.label
        info.setBoundsInParent(node.bounds)
        info.isVisibleToUser = true
        info.isEnabled = true
        info.isClickable = true
        info.isFocusable = true
        info.isAccessibilityFocused = focusedKey == node.key
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
        info.isLongClickable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        return info
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == HOST_VIEW_ID) return false
        val node = nodesProvider().getOrNull(virtualViewId) ?: return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> {
                activate(node)
                true
            }
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                longActivate(node)
                true
            }
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focusedKey != node.key) {
                    focusedKey = node.key
                    sendEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, virtualViewId, node)
                }
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focusedKey == node.key) {
                    focusedKey = null
                    sendEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, virtualViewId, node)
                }
                true
            }
            else -> false
        }
    }

    override fun findAccessibilityNodeInfosByText(
        text: String,
        virtualViewId: Int,
    ): MutableList<AccessibilityNodeInfo>? {
        val needle = text.lowercase()
        val nodes = nodesProvider()
        val out = ArrayList<AccessibilityNodeInfo>()
        nodes.forEachIndexed { i, node ->
            if (virtualViewId == HOST_VIEW_ID || virtualViewId == i) {
                if (node.label.lowercase().contains(needle)) out += nodeInfo(i, node)
            }
        }
        return out
    }

    override fun findFocus(focusType: Int): AccessibilityNodeInfo? {
        if (focusType != AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) return null
        val key = focusedKey ?: return null
        val nodes = nodesProvider()
        val idx = nodes.indexOfFirst { it.key == key }
        return if (idx >= 0) nodeInfo(idx, nodes[idx]) else null
    }

    /**
     * Called after every draw: when the set of interactive elements changed,
     * tell the system to re-query the hierarchy and drop focus from nodes that
     * no longer exist. Cheap no-op while accessibility is off.
     */
    fun contentChanged() {
        if (!manager.isEnabled) return
        val nodes = nodesProvider()
        var sig = nodes.size
        for (n in nodes) sig = 31 * sig + n.key.hashCode()
        if (sig == lastSignature) return
        lastSignature = sig
        if (focusedKey != null && nodes.none { it.key == focusedKey }) focusedKey = null
        if (hoveredKey != null && nodes.none { it.key == hoveredKey }) hoveredKey = null
        host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /** Explore-by-touch: move accessibility focus to whatever is under the finger. */
    fun onHover(x: Float, y: Float, exit: Boolean) {
        if (!touchExplorationEnabled) return
        val nodes = nodesProvider()
        val idx = if (exit) -1 else nodes.indexOfFirst {
            x >= it.bounds.left && x < it.bounds.right && y >= it.bounds.top && y < it.bounds.bottom
        }
        val key = nodes.getOrNull(idx)?.key
        if (key == hoveredKey) return
        hoveredKey = key
        if (idx >= 0) {
            val node = nodes[idx]
            sendEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, idx, node)
            if (focusedKey != node.key) {
                focusedKey = node.key
                sendEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, idx, node)
            }
        }
    }

    private fun sendEvent(type: Int, virtualViewId: Int, node: Node) {
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(type)
        event.packageName = host.context.packageName
        event.className = "android.widget.Button"
        event.text.add(node.label)
        event.isEnabled = true
        event.setSource(host, virtualViewId)
        event.setContentDescription(node.label)
        manager.sendAccessibilityEvent(event)
    }
}
