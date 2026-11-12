# Contributing

Small, focused changes are easiest to review. Open a bug report or feature request
before a change that alters behavior, dependencies, permissions or public interfaces.
Read the [security policy](SECURITY.md) before reporting a vulnerability.

## Building offline

Use Python 3.12, JDK 21 and Android SDK platform 37. The build uses the pinned
versions in the project manifests. Offline work requires those dependencies and
the Gradle distribution to be present in the local caches already.

From the repository root:

```sh
tools/mac-venv.sh
tools/build.sh :app:assembleDebug
```

`tools/mac-venv.sh` creates `mac/.venv` and installs the declared Python packages
with `uv --offline`. `tools/build.sh` selects the JDK and SDK, prepares a local
signing key and invokes Gradle with `--offline --no-daemon`. Neither command
fetches missing dependencies. Build paths can be set with `JAVA_HOME`,
`ANDROID_HOME`, `GRADLE_USER_HOME` and `ATOMIC_SIGNING_DIR`, as documented in the
build script. Keep signing material, virtual environments and build outputs out
of commits. Use a separate signing directory for development.

## Running the tests

Run these three commands from the repository root. The Python wrapper changes
into `mac/`, so the first command runs the Mac-side tests from that directory.

```sh
tools/py.sh -m pytest -q tests
```

```sh
tools/build.sh :app:testDebugUnitTest
```

```sh
tools/build.sh :app:testDebugUnitTest --tests 'tech.atomiclabs.link.BoundaryTest' --tests 'tech.atomiclabs.link.LabelPolicyTest' --tests 'tech.atomiclabs.link.BuildPolicyTest'
```

Use focused test files or Gradle `--tests` selectors while iterating, then run the
full tests before opening a pull request. Tests use fakes, temporary files and
port 0. They must not contact a phone, a vault or a running installation. Keep
credentials and phone content out of fixtures and diagnostics; generate synthetic
secret values in the test. Cover refused and failed operations as well as success.

CI uses fresh Ubuntu runners, Python 3.12, Temurin JDK 21 and Android platform 37.
It installs the pinned Python dependencies and prepares a temporary signing key
before running Android unit tests. Device behavior needs separate manual checks;
unit tests do not establish that a command works on a physical phone.

## Branches and commits

Branch from `main` using `type/short-description`, such as `fix/connection-error`.
Use `type: description` commit messages with `feat`, `fix`, `refactor`, `docs`,
`test` or `chore`. Keep each commit focused and explain the resulting behavior.

## Review expectations

Describe the problem, the change and the commands you ran, including failures or
checks you could not run. Add a regression test for a bug fix and show it failing
without the fix. Keep platform access behind the existing interfaces, avoid new
dependencies, and update public documentation when behavior changes. Preserve
upstream notices and attribution. Follow the [Code of Conduct](CODE_OF_CONDUCT.md)
in issues, reviews and other project spaces.
