package dev.barmy.hatch

import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

object HatchTreeActions {
    fun install(tree: JTree, activate: () -> Unit) {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2 || !SwingUtilities.isLeftMouseButton(event)) return
                val clicked = tree.getPathForLocation(event.x, event.y) ?: return
                tree.selectionPath = clicked
                activate()
                event.consume()
            }
        })
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "hatch.run")
        tree.actionMap.put("hatch.run", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = activate()
        })
    }
}
