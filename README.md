# Hatch Tasks 0.5.0

A native Hatch task browser and execution console for PyCharm builds 253?262.

Open **View > Tool Windows > Hatch**. Configure the Hatch executable under **Settings > Tools > Hatch**, then refresh. Link additional projects with the toolbar. Double-click a script/build task or press Enter to run it; group nodes expand. Right-click to save a standard Run configuration or unlink a project.

The bottom **Hatch Tasks** window keeps live output and session history. Different tasks can run concurrently; launching an active task again focuses its output. Stop cancels a task; click again to force termination. Rerun uses the current executable setting. Clear Finished removes completed runs; the latest 50 completed runs are retained during the session. Output supports console input and ANSI colors, without opening Terminal tabs.

The browser preserves selection and expansion on refresh. Failed discovery retains cached tasks marked stale and disables execution until refresh succeeds. Removal operations require confirmation, including reruns. Project trust is required for discovery and execution.

Saved run configurations use the standard Run window and retain working-directory, argument, executable override, and environment-variable settings. Existing settings and project links remain compatible.

Run and test configurations are created automatically at startup for discovered `run`/`start` and `test`/`tests` scripts. Names use the package metadata, such as **run hatch-task-demo**, with suffixes for non-default environments. They appear in the normal run selector with a monochrome Hatch icon. Disable automatic creation under **Settings > Tools > Hatch** if desired.

Refresh reuses existing configurations and preserves edits. Deleted generated configurations stay deleted; use **Save Configuration** in the task context menu to restore one. Configurations are stored locally. Metadata lookup failures leave the task browser usable and show details for recovery.

## Build

Requires JDK 21. Run `gradlew.bat test buildPlugin verifyPluginStructure` (or `./gradlew` on Unix). Install the ZIP from `build/distributions` through Settings > Plugins > Install Plugin from Disk.

Run `gradlew.bat verifyPlugin` for binary compatibility checks. Use `-PverificationIdePath=<IDE directory>` to include another installed IDE. Use `runIde` for manual testing with `examples/demo`.

Hatch must be installed separately. This plugin does not manage Python interpreters or attach the Python debugger. The console is not a terminal emulator; shell-specific interactive programs may need a separate terminal.
