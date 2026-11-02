package dev.barmy.hatch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode

class HatchTreeActionsTest {
    @Test fun `double click activates clicked row instead of stale selection`() {
        SwingUtilities.invokeAndWait {
            val root = DefaultMutableTreeNode("root")
            root.add(DefaultMutableTreeNode("first")); root.add(DefaultMutableTreeNode("second"))
            val tree = JTree(root).apply { isRootVisible = false; setSize(400, 300); expandRow(0) }
            tree.setSelectionRow(0)
            var activated: String? = null
            HatchTreeActions.install(tree) { activated = tree.lastSelectedPathComponent.toString() }
            val bounds = tree.getRowBounds(1)
            tree.dispatchEvent(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 1, 0,
                bounds.x + 5, bounds.y + bounds.height / 2, 2, false, MouseEvent.BUTTON1))
            assertEquals("second", activated)
            assertEquals("second", tree.lastSelectedPathComponent.toString())
        }
    }

    @Test fun `Enter activates selected row and right double click does not run`() {
        SwingUtilities.invokeAndWait {
            val tree = JTree().apply { setSize(400, 300); setSelectionRow(0) }
            var calls = 0
            HatchTreeActions.install(tree) { calls++ }
            tree.actionMap.get("hatch.run").actionPerformed(null)
            assertEquals(1, calls)
            tree.dispatchEvent(MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 1, 0,
                10, 10, 2, false, MouseEvent.BUTTON3))
            assertEquals(1, calls)
        }
    }
}
