# IDE smoke harness

Register HatchSmokeActivity.java only in a separate test plugin depending on dev.barmy.hatch.tasks. Supply -Dhatch.smoke.root=<fixture directory>. The fixture directory needs fake-hatch (see fake-hatch.sh), command-ran.txt removed before each run, and a project exposing default:smoke. On Windows provide an executable fixture compatible with direct process launching.

The updated harness checks keyboard launch, active duplicate suppression, real process completion, successful status and no additional Terminal tabs. It writes result.txt. It has not yet been executed for 0.4.0.

Additional manual checks: save an unsaved document by running a task; run two different long tasks concurrently; stop and force-stop; rerun a failed task after changing settings; cancel environment-removal confirmation; refresh while collapsed and selected; invalidate TOML and check stale tasks are disabled; close the project with an active process. Inspect both themes, narrow windows and display scaling.

The historical 0.3.2 logs cover the former Terminal implementation only and do not validate 0.4.0.
