# Validation ? Hatch Tasks 0.6.0

Validation date: 2026-11-02.

JDK 21 and PyCharm PY-253.28294.336: unit tests, buildPlugin, verifyPluginStructure and Plugin Verifier passed. The suite has 21 tests, including five creation tests for TOML escaping, metadata edits, existing-file protection, IDE metadata preservation, and input validation. Verifier reports compatibility with deprecated/experimental API warnings; details are in build/reports/pluginVerifier.

Real Hatch generation succeeded for src and flat layouts, tests and CI enabled, no licenses, and output paths containing spaces. The smoke projects are under build/hatch-creation-smoke-*. This checks the CLI generation contract; it does not exercise the IDE wizard.

Pending: actual New Project dialog visibility and interaction on both 253 and 262, live interpreter registration and cancellation/retry, theme/scaling inspection, and 262 binary verification. Existing task execution/runtime limitations from the prior validation remain unverified by these unit checks. The separate integration harness has not been executed for this release.

Artifact: build/distributions/hatch-idea-plugin-0.6.0.zip. No installation into the user's IDE or Marketplace publishing was performed.
