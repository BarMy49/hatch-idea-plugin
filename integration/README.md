# IDE smoke harness

Register HatchSmokeActivity.java only in a separate test plugin depending on dev.barmy.hatch.tasks. Supply -Dhatch.smoke.root=<fixture directory>. The fixture directory needs fake-hatch (see fake-hatch.sh), command-ran.txt removed before each run, and a project exposing default:smoke. On Windows provide an executable fixture compatible with direct process launching.

The updated harness checks automatic run/test configurations and repeated-setup deduplication, keyboard launch, active duplicate suppression, real process completion, successful status and no additional Terminal tabs. It writes result.txt. It has not yet been executed for 0.5.0.

Additional manual checks: save an unsaved document by running a task; run two different long tasks concurrently; stop and force-stop; rerun a failed task after changing settings; cancel environment-removal confirmation; refresh while collapsed and selected; invalidate TOML and check stale tasks are disabled; close the project with an active process. Inspect both themes, narrow windows and display scaling.

The historical 0.3.2 logs cover the former Terminal implementation only and do not validate 0.5.0.

## Project creation / SDK regression

`HatchCreationSmokeActivity.java` is a separate test-plugin startup activity, never included in the production ZIP. Register it as `postStartupActivity`, with dependencies on `dev.barmy.hatch.tasks` and `com.intellij.modules.python`. Launch in isolated config/system/plugins/log directories with a disposable Python project containing an inherited-SDK module.

Place `smoke-options.txt` in that project's root: installed Python path, existing Hatch executable path, and `true`/`false` for local Hatch installation, each on its own line. An optional fourth line `sdk-only` tests an already created `.venv` directly. The marker is consumed before generation. Read `result.txt` for the outcome. The harness seeds an incomplete SDK to test recovery and checks roots, additional data, module inheritance, deduplication, and the downloadable Python catalog.

The SDK-only regression passed on PyCharm 253.28294.336 and 262.11879.98. This does not verify actual downloads or complete wizard interaction.

Full generator/discovery runs passed on 262.11879.98 (existing Hatch) and 253.28294.336 (Hatch installed into the new `.venv`), including project dependency setup. See VALIDATION.md for evidence paths.
