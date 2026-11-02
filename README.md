# Hatch Tasks for PyCharm

A native task browser, execution console, and project creation wizard for the Python [Hatch](https://hatch.pypa.io/) build system.

**Version:** 0.6.0 | **Target IDE range:** PyCharm builds 253-262 (2025.3-2026.2)

## Contents

- [Features](#features)
- [Installation and setup](#installation-and-setup)
- [How to use the plugin](#how-to-use-the-plugin)
- [Create a Hatch project](#create-a-hatch-project)
- [Troubleshooting](#troubleshooting)
- [Limitations and validation](#limitations-and-validation)
- [Build and development](#build-and-development)

## Features

| Feature | What it provides |
| --- | --- |
| Task browser | Environments, scripts, resolved matrix environments, package builds, and utilities in the Hatch tool window. |
| Execution console | Live output, task status, duration, stop/force-stop, rerun, and session history in Hatch Tasks. |
| Parallel execution | Different tasks run concurrently; launching an active task again focuses its existing output. |
| Run configurations | Automatic run/test configurations named after package metadata, plus manual configuration saving. |
| Linked projects | Run tasks from nested or external Hatch projects in their own working directories. |
| Project creation | Configure Hatch template options and optionally create an environment and select its Python interpreter. |
| IDE integration | Native action toolbars, keyboard activation, search, and monochrome Hatch icons for light and dark themes. |

## Installation and setup

Hatch must be installed separately and available to the IDE. To check your installation, run `hatch --version` in a terminal.

1. Obtain the plugin ZIP, or [build it from source](#build-and-development). Build output is under `build/distributions/`.
2. In PyCharm, open **Settings > Plugins**, choose the gear menu, then **Install Plugin from Disk** and select the ZIP. Restart if prompted.
3. Open a Hatch project containing `pyproject.toml` or `hatch.toml`, and trust it to enable discovery and execution.
4. Open **Settings > Tools > Hatch**. Leave the executable as `hatch` if the IDE can find it, or choose its absolute path. This field accepts an executable path, not a command such as `python -m hatch`.
5. Apply the settings, then open **View > Tool Windows > Hatch**. Use **Refresh** if tasks are not yet visible.

The executable and automatic run-configuration setting are stored per IDE project.

## How to use the plugin

### Browse and run tasks

Expand a project in the **Hatch** tool window. Its tasks are grouped as follows:

| Group | Available actions |
| --- | --- |
| Environments | Run scripts and create or remove an environment. Matrix environments appear individually. |
| Build | Build all distributions, a wheel, or a source distribution. |
| Utilities | Show environments or display the Hatch version. |

Select a task and use **Run**, double-click it, or press **Enter**. Activating a group expands or collapses it. Start typing in the tree to search. Environment removal requires confirmation.

For a first walkthrough, open the included [`examples/demo`](examples/demo), configure Hatch, and refresh. Run **default:start** to print a greeting, **default:test** to run its tests, or **Wheel (.whl)** to build a package. Hatch may install the environment's dependencies on first execution.

### Watch output and manage running tasks

Tree-launched tasks open the bottom **Hatch Tasks** tool window. Select a run in its list to view output, status, duration, and completion details.

- **Stop** requests cancellation. Click it again to force termination if the task does not stop.
- **Rerun** starts a completed task using the current Hatch executable setting. Destructive operations ask for confirmation again.
- **Clear Finished** removes completed runs from the list.
- Standard console actions provide output controls, including clearing and scrolling.

Different tasks can run in parallel. Starting the same active task again focuses its output. The latest 50 completed runs are retained for the current IDE session; history is not persisted across restarts. Output supports console input and ANSI colors. Task execution does not open Terminal tabs.

### Use run configurations

After successful project discovery and metadata lookup, the plugin automatically creates configurations for recognized `run`/`start` and `test`/`tests` scripts. The `run` and `test` names take precedence over their alternatives.

Names use the declared package name. For the demo project, these include **run hatch-task-demo** and **test hatch-task-demo**. Non-default environments add a suffix, such as **test hatch-task-demo [tests.py3.11]**.

Choose a configuration from PyCharm's normal run selector and press **Run**. These configurations execute in the standard **Run** window. Edit them under **Run > Edit Configurations** to customize arguments, working directory, environment variables, or the executable override.

To save another task, right-click it in the Hatch tree and choose **Save Configuration**. Environment-removal tasks cannot be saved from the tree.

Refresh reuses matching configurations and preserves user edits. Deleted generated configurations stay deleted; use **Save Configuration** to restore one. Configurations are stored locally. To disable automatic creation, clear **Automatically create run and test configurations** under **Settings > Tools > Hatch**.

### Link and refresh projects

Use **Link Project** in the Hatch toolbar to add a folder containing `pyproject.toml` or `hatch.toml`. Linked projects can be nested inside the IDE project or located elsewhere. Tasks execute in the selected Hatch project's directory.

To remove a link, select its project in the tree and choose **Unlink Project** from the context menu. Unlinking preserves its files. The IDE project root is included automatically and cannot be unlinked.

Saved changes to `pyproject.toml` or `hatch.toml` trigger a debounced refresh. Manual **Refresh** is also available. The browser preserves selection and expansion state. If discovery fails, cached tasks remain visible as **stale** and cannot run until discovery succeeds.

## Create a Hatch project

1. Open **New Project** from the Welcome screen or **File > New Project**, and choose **Hatch**. It may appear in the additional generators group.
2. Choose the destination and enter the package name. The wizard previews the normalized distribution and Python import names.
3. Configure the template options:

| Options | Purpose |
| --- | --- |
| Description, author, email | Project metadata and attribution. |
| SPDX licenses and license headers | Comma-separated license identifiers; leave the list empty for no license files. |
| Command line application | Generate Hatch's CLI application template. |
| Include tests | Generate the test scaffold. |
| GitHub Actions CI | Generate a CI workflow. |
| Use src layout | Place the package under `src/`; disable for a flat layout. |

4. Set the Hatch executable. First-use author, licensing, and template defaults are loaded from `hatch config show`; subsequent creations reuse wizard preferences.
5. Leave **Create Hatch environment and configure interpreter** enabled to install the default environment and configure its Python SDK in PyCharm. Choose the base Python executable. Disable this option to generate files only.
6. Create the project and follow the background progress. On completion, the plugin opens `pyproject.toml` and refreshes Hatch discovery.

Generation uses a temporary configuration and staging directory without changing global Hatch settings. Existing project content is rejected; IDE-created `.idea` metadata is preserved. License downloads and dependency installation can require network access. If environment setup fails, the generated project remains available and the error dialog offers a retry.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Hatch cannot be found | Set the absolute executable path under **Settings > Tools > Hatch**; the IDE's PATH may differ from your terminal's. |
| No tasks are shown | Confirm the project contains a Hatch configuration, trust the project, and refresh. Link its folder if it is not the IDE project root. |
| Discovery fails or tasks are stale | Choose **Show Details** in the Hatch window, fix the reported executable/configuration problem, and refresh. |
| Automatic run/test configurations are missing | Check the automatic-creation setting, recognized script names, and package metadata. Metadata errors appear in **Show Details**. |
| A task fails | Select its run in **Hatch Tasks**, or inspect the **Run** window for saved configurations. Review output and exit status. |
| Project environment setup fails | Check the selected Python executable, network access, and Hatch error. Retry when prompted, or use the generated files without environment setup. |

## Limitations and validation

- The creation wizard supports Hatch's default template options. Custom template plugins and conversion of existing projects are not included.
- Interpreter setup is provided during project creation. Task discovery for existing projects does not automatically replace their Python interpreter.
- The plugin does not attach the Python debugger or implement the full IntelliJ External System project model.
- The task console is not a terminal emulator; programs requiring terminal-specific interaction may need a separate terminal.
- The declared IDE range is 253-262. Automated binary verification currently covers PyCharm 2025.3. Live wizard/interpreter checks, visual inspection, and PyCharm 2026.2 verification remain pending.

See [VALIDATION.md](VALIDATION.md) for completed checks and remaining verification, and [integration/README.md](integration/README.md) for the IDE smoke-test harness.

## Build and development

Requires **JDK 21**. Use the Gradle wrapper:

```powershell
# Windows
.\gradlew.bat test buildPlugin verifyPluginStructure
.\gradlew.bat verifyPlugin
.\gradlew.bat runIde
```

```bash
# Linux / macOS
./gradlew test buildPlugin verifyPluginStructure
./gradlew verifyPlugin
./gradlew runIde
```

Installable ZIPs are written to `build/distributions/`. Use [`examples/demo`](examples/demo) for manual testing in the sandbox IDE.

Pass `-PlocalIdePath=<IDE directory>` to build against a local installation, or `-PverificationIdePath=<IDE directory>` to include another installation in binary verification.
