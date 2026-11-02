# Validation ? Hatch Tasks 0.5.0

Validation date: 2026-11-02.

## Automated checks

JDK 21, PyCharm SDK PY-253.28294.336. Gradle test, buildPlugin, verifyPluginStructure and verifyPlugin passed. The current unit suite contains 16 tests covering executable input, task/model parsing, tree activation, configuration naming/precedence, identities, and settings reload. Obsolete shell-rendering tests were removed with the Terminal launcher.

Plugin Verifier reports compatibility with PY-253.28294.336. Deprecated and experimental API warnings remain; detailed reports are under build/reports/pluginVerifier. A final repeat build is recorded in build/auto-config-validation.log.

## Implemented behavior awaiting GUI verification

The updated separate integration harness checks keyboard launch, active duplicate suppression, real process completion, successful status and no new Terminal tabs. It has not been executed for 0.5.0. Historical 0.3.2 integration results apply only to the former implementation.

The new execution lifecycle, parallel tasks, cancellation/force termination, rerun, history eviction and project-close cleanup still need runtime integration coverage. Light/dark themes, display scaling and narrow-window layout also need visual inspection. Windows process-tree cancellation and binary compatibility against PyCharm 262 have not been verified for this version. Do not treat the older validation record as evidence for these changes.

## Artifact

Installable ZIP: build/distributions/hatch-idea-plugin-0.5.0.zip. Installation into an IDE and Marketplace publication were not performed.

Automatic setup UI and run-manager reconciliation require execution of the updated smoke harness. Unit tests cover the planner and persistence data; they do not establish runtime RunManager behavior. The real Hatch CLI returned hatch-task-demo for the example metadata-name lookup. Light/dark icon assets are included but visual inspection remains pending.
