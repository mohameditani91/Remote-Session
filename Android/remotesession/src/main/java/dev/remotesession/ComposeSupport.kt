package dev.remotesession

import android.annotation.SuppressLint
import android.graphics.RectF
import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString

/** Covers this composable with a placeholder in frames sent to the agent. Password fields are masked without it. */
fun Modifier.remoteSessionMasked(): Modifier = semantics { this[RemoteSessionMasked] = Unit }

private val RemoteSessionMasked = SemanticsPropertyKey<Unit>("RemoteSessionMasked")

/**
 * Reads Compose screens through their semantics tree, the one accessibility and UI tests use. Compose is a
 * compile-only dependency, so nothing here runs unless the app has it.
 */
@SuppressLint("VisibleForTests")
internal object ComposeSupport {

    private val available = runCatching { Class.forName("androidx.compose.ui.platform.ViewRootForTest") }.isSuccess

    fun isComposeView(view: View) = available && view is ViewRootForTest

    /** Password fields and `remoteSessionMasked()` composables, in window pixels. */
    fun maskRects(view: View): List<RectF> {
        val rects = mutableListOf<RectF>()
        fun visit(node: SemanticsNode) {
            if (!node.isShown) return
            if (node.isMasked) {
                node.windowRect()?.let(rects::add)
                return
            }
            node.children.forEach(::visit)
        }
        root(view, merged = false)?.let(::visit)
        return rects
    }

    /** Names the control at this window point for the audit log, from its role and title. Never reads typed text. */
    fun describe(view: View, x: Float, y: Float): String? {
        val node = deepest(view, merged = true, x, y) { it.config.contains(SemanticsActions.OnClick) || it.isEditable } ?: return null
        val config = node.config
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        if (node.isEditable) return named(description ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(" "))?.let { "\"$it\" field" } ?: "text field"
        val kind = when (config.getOrNull(SemanticsProperties.Role)) {
            Role.Button -> "button"
            Role.Switch -> "switch"
            Role.Checkbox -> "checkbox"
            Role.RadioButton -> "radio button"
            Role.Tab -> "tab"
            Role.DropdownList -> "menu"
            else -> return named(description)?.let { "\"$it\"" }
        }
        // Titles of controls are fine to log; text of rows and labels isn't.
        return named(description ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(" "))?.let { "\"$it\" $kind" } ?: kind
    }

    fun isDisabled(view: View, x: Float, y: Float): Boolean =
        deepest(view, merged = true, x, y) { it.config.contains(SemanticsActions.OnClick) }?.config?.contains(SemanticsProperties.Disabled) == true

    fun hasFocusedField(view: View) = focused(view) != null

    /** True when the focused field is a password, masked, or under a masked area (window pixels). */
    fun focusedIsSensitive(view: View, masks: List<RectF>): Boolean {
        val (node, maskedAncestor) = focused(view) ?: return false
        if (maskedAncestor || node.isMasked) return true
        val rect = node.windowRect() ?: return false
        return masks.any { RectF.intersects(it, rect) }
    }

    fun describeFocused(view: View): String? {
        val node = focused(view)?.first ?: return null
        if (node.isMasked) return "hidden field"
        val config = deepest(view, merged = true, node.boundsInWindow.center.x, node.boundsInWindow.center.y) { it.isEditable }?.config ?: node.config
        return named(config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(" "))?.let { "\"$it\" field" } ?: "text field"
    }

    /** Inserts at the cursor of the focused field. Null when no field is focused or it can't take text. */
    fun insert(view: View, text: String): Boolean? {
        val node = focused(view)?.first ?: return null
        val action = node.config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action ?: return null
        return action(AnnotatedString(text))
    }

    fun imeAction(view: View): Boolean? {
        val node = focused(view)?.first ?: return null
        return node.config.getOrNull(SemanticsActions.OnImeAction)?.action?.invoke()
    }

    /** Scrolls the innermost scrollable container at this window point that can move that way. Null when there's none. */
    fun scroll(view: View, x: Float, y: Float, dx: Float, dy: Float): InputResult? {
        val vertical = kotlin.math.abs(dy) >= kotlin.math.abs(dx)
        val delta = if (vertical) dy else dx
        val chain = chain(view, x, y).filter { it.config.contains(SemanticsActions.ScrollBy) }
        val axis = if (vertical) SemanticsProperties.VerticalScrollAxisRange else SemanticsProperties.HorizontalScrollAxisRange
        val scrollers = chain.filter { it.config.contains(axis) }
        if (scrollers.isEmpty()) return null
        val target = scrollers.firstOrNull { node ->
            val range = node.config[axis]
            val value = range.value()
            if (delta > 0) value < range.maxValue() else value > 0
        } ?: return InputResult.nothing("atEdge")
        val scrollBy = target.config[SemanticsActions.ScrollBy].action ?: return InputResult.nothing("atEdge")
        return if (scrollBy(if (vertical) 0f else dx, if (vertical) dy else 0f)) InputResult.performed else InputResult.nothing("atEdge")
    }

    /** Lazy lists keep items they scrolled away composed but unplaced, with their old bounds. */
    private val SemanticsNode.isShown get() = layoutInfo.isPlaced

    private val SemanticsNode.isMasked get() = config.contains(SemanticsProperties.Password) || config.contains(RemoteSessionMasked)
    private val SemanticsNode.isEditable get() = config.contains(SemanticsProperties.EditableText) || config.contains(SemanticsActions.InsertTextAtCursor)

    private fun root(view: View, merged: Boolean): SemanticsNode? {
        val owner = (view as? ViewRootForTest)?.semanticsOwner ?: return null
        return runCatching { if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode }.getOrNull()
    }

    private fun SemanticsNode.windowRect(): RectF? {
        val bounds = boundsInWindow
        if (bounds.width < 1 || bounds.height < 1) return null
        return RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun SemanticsNode.contains(x: Float, y: Float): Boolean {
        val bounds = boundsInWindow
        return x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom
    }

    /** Nodes containing the point, innermost first. Later siblings are drawn on top, so they win. */
    private fun chain(view: View, x: Float, y: Float, merged: Boolean = false): List<SemanticsNode> {
        val chain = mutableListOf<SemanticsNode>()
        var node = root(view, merged) ?: return chain
        while (true) {
            chain += node
            node = node.children.lastOrNull { it.isShown && it.contains(x, y) } ?: break
        }
        return chain.reversed()
    }

    private fun deepest(view: View, merged: Boolean, x: Float, y: Float, match: (SemanticsNode) -> Boolean) =
        chain(view, x, y, merged).firstOrNull(match)

    /** The focused node, and whether something around it is masked. */
    private fun focused(view: View): Pair<SemanticsNode, Boolean>? {
        fun visit(node: SemanticsNode, masked: Boolean): Pair<SemanticsNode, Boolean>? {
            if (!node.isShown) return null
            val inside = masked || node.isMasked
            if (node.config.getOrNull(SemanticsProperties.Focused) == true && node.isEditable) return node to inside
            return node.children.firstNotNullOfOrNull { visit(it, inside) }
        }
        return root(view, merged = false)?.let { visit(it, false) }
    }

    private fun named(text: String?): String? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (trimmed.length > 40) trimmed.take(40) + "…" else trimmed
    }
}
