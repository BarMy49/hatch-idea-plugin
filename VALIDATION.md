## 0.6.3 — current fixes

Discovery now schedules document saves and result application with explicit non-modal modality. Project setup failures retain both stdout and stderr. Version metadata has been bumped; a 0.6.3 package has not yet been built or validated. Earlier results below refer to 0.6.2.

## Real project creation and discovery ? 2026-11-03

PyCharm 262.11879.98 passed the full generator integration test in an isolated profile, using Python 3.14 and existing Hatch. Verified generated project files, dependency setup, the project `.venv` interpreter, SDK roots/additional data, module SDK inheritance, broken SDK recovery, repeated SDK configuration without duplicates, and live Hatch discovery (package metadata and multiple environments). A real `hatch run default:python` reported the project `.venv/Scripts/python.exe`.

PyCharm 253.28294.336 also passed full creation and discovery with **Install Hatch into .venv** enabled. Local Hatch successfully imported the generated package using the project interpreter.

Evidence: `build/full-creation-262/project/result.txt` and `build/full-creation-253-local/project/result.txt`. The harness invokes the real generator and discovery services inside PyCharm; it does not click through the New Project UI or install a downloaded Python.

# Python creation update ? validation 2026-11-03

- 32 unit tests passed; plugin ZIP build and plugin structure validation passed on the 2025.3 baseline.
- Isolated PyCharm 253.28294.336 and 262.11879.98 SDK regressions passed: SDK roots/additional data, recovery of the incomplete Hatch SDK, module inheritance, and retry without duplicate registration. The IDE download catalog returned Python versions successfully.
- This direct SDK regression used an already created `.venv`. It does not establish successful end-to-end dependency installation or Python download/install. The earlier full creation attempt generated files and `.venv` but did not finish before the test IDE was closed.
- Native desktop automation was unavailable, so visual wizard verification remains pending. Cancellation/network failure and actual Python installation still require live checks.
- Changes are packaged in `build/distributions/hatch-idea-plugin-0.6.2.zip`; the user's installed plugin has not been replaced.

## Earlier validation

# Validation ? Hatch Tasks 0.6.2

Validation date: 2026-11-02.

JDK 21 and PyCharm PY-253.28294.336: unit tests, buildPlugin, verifyPluginStructure and Plugin Verifier passed. The suite has 29 tests, including five creation tests for TOML escaping, metadata edits, existing-file protection, IDE metadata preservation, and input validation. Verifier reports compatibility with deprecated/experimental API warnings; details are in build/reports/pluginVerifier.

Real Hatch generation succeeded for src and flat layouts, tests and CI enabled, no licenses, and output paths containing spaces. The smoke projects are under build/hatch-creation-smoke-*. This checks the CLI generation contract; it does not exercise the IDE wizard.

Pending: actual New Project dialog visibility and interaction on both 253 and 262, live interpreter registration and cancellation/retry, theme/scaling inspection, and 262 binary verification. Existing task execution/runtime limitations from the prior validation remain unverified by these unit checks. The separate integration harness has not been executed for this release.

Artifact: build/distributions/hatch-idea-plugin-0.6.2.zip. No installation into the user's IDE or Marketplace publishing was performed.

Project-local base Python option: four additional unit tests cover existing-interpreter mode, literal arguments, interrupted-creation retry, and protection of unowned environments. A real Python venv smoke test created .venv and verified its interpreter reports an isolated sys.prefix. Live IDE selection and end-to-end Hatch creation from that base remain unverified.

Local Hatch bootstrap adds four unit tests covering local pip invocation, executable verification, install failure without fallback, validation without system Hatch, and preserving the owned bootstrap environment during project-file transfer. Live wizard checks remain pending.

A real local-install smoke test created a fresh .venv, installed Hatch 1.18.2 using that interpreter's pip, verified the local Hatch executable, and generated a project with it. The fixture is under build/local-hatch-smoke-*.
