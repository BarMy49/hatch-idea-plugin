package dev.barmy.hatch.smoke;

import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.wm.ToolWindowManager;
import dev.barmy.hatch.*;
import javax.swing.*;
import javax.swing.tree.*;
import java.nio.file.*;

/** Register only in a separate test plugin. Supply -Dhatch.smoke.root=<fixture directory>. */
public final class HatchSmokeActivity implements StartupActivity.DumbAware {
    public void runActivity(Project project) {
        Path root = Path.of(System.getProperty("hatch.smoke.root", "/tmp/hatch-ui-test"));
        ApplicationManager.getApplication().invokeLater(() -> {
            project.getService(HatchSettings.class).setExecutable(root.resolve("fake-hatch").toString());
            var manager = ToolWindowManager.getInstance(project);
            var terminal = manager.getToolWindow("Terminal");
            int terminalCount = terminal == null ? 0 : terminal.getContentManager().getContentCount();
            manager.getToolWindow("Hatch").show(() -> {
                try {
                    var panel = manager.getToolWindow("Hatch").getContentManager().getContent(0).getComponent();
                    var field = panel.getClass().getDeclaredField("tree"); field.setAccessible(true);
                    JTree tree = (JTree)field.get(panel);
                    var service = project.getService(HatchExecution.class);
                    int[] ticks = {0}; boolean[] launched = {false};
                    Timer timer = new Timer(200, null);
                    timer.addActionListener(e -> {
                        try {
                            if (++ticks[0] > 300) throw new AssertionError("Timed out");
                            if (!launched[0]) {
                                var nodes = ((DefaultMutableTreeNode)tree.getModel().getRoot()).depthFirstEnumeration();
                                while (nodes.hasMoreElements()) {
                                    var node = nodes.nextElement();
                                    if (node.toString().equals("default:smoke")) {
                                        tree.setSelectionPath(new TreePath(node.getPath()));
                                        tree.getActionMap().get("hatch.run").actionPerformed(null);
                                        tree.getActionMap().get("hatch.run").actionPerformed(null);
                                        if (service.getRuns().size() != 1) throw new AssertionError("Duplicate active launch");
                                        launched[0] = true; break;
                                    }
                                }
                            } else if (!service.getRuns().get(0).getActive()) {
                                var run = service.getRuns().get(0);
                                if (run.getStatus() != HatchRunStatus.Succeeded || run.getExitCode() != 0) throw new AssertionError(run.toString());
                                if (!Files.exists(root.resolve("command-ran.txt"))) throw new AssertionError("No real process marker");
                                var now = manager.getToolWindow("Terminal");
                                if ((now == null ? 0 : now.getContentManager().getContentCount()) != terminalCount) throw new AssertionError("Terminal tab created");
                                Files.writeString(root.resolve("result.txt"), "PASS: keyboard launch, active duplicate, real process, success status, no terminal tabs");
                                timer.stop();
                            }
                        } catch (Throwable failure) {
                            timer.stop(); failure.printStackTrace();
                            try { Files.writeString(root.resolve("result.txt"), "FAIL: " + failure); } catch (Exception ignored) {}
                        }
                    });
                    timer.start();
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
        });
    }
}
