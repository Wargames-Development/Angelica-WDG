# Angelica 2.2.14 secondary-camera backport

Working branch: `codex/angelica-2.2.14-pip`.

Base: upstream tag `2.2.14` (`75639d3b73cd9eaa629f4b95d3a65467f363f459`).
Camera API ported from `d03c473350873e803439230c0c0ca804a23c1923` (Second PiP Camera API).

This branch retains the 2.2.14 renderer and dependency versions. It does not merge master or the intervening upstream rendering updates. The API remains `com.gtnewhorizons.angelica.api.SecondaryCamera`, as expected by the FMUR camera bridge.

Backport adaptations:

- Use 2.2.14's monolithic GLStateManager and existing terrain-renderer cleanup methods.
- Restore the lightmap matrix through the older matrix API, including its generation tracking.
- Keep separate output framebuffers and previous-matrix histories per view.
- Borrow main-view shadow targets without clearing or destroying them from the secondary view.
- Retain cleanup for partially constructed secondary pipelines.
- Add the GTNH LWJGL snapshot mirror without changing dependency versions.

Build with `./gradlew build`. Successful packaging increments `modBuildNumber` and produces `build/libs/angelica-2.2.14_X<N>.jar`. To reproduce a specific number without incrementing it, use `./gradlew build -PmodBuildNumber=<N>`.

The original branch name `2.2.14` overlaps the release tag. The distinct working branch avoids that ambiguity; publishing uses an explicit `refs/heads/...` destination.

Build and automated tests do not establish in-game rendering correctness. Verify a fresh launch, vehicle LODs, a shadered PiP scope, and shader-on/shader-off transitions in the target pack on both macOS and Windows.

Validation for X1 (2026-11-11):

- `./gradlew build -PmodBuildNumber=1 --offline --console=plain` succeeded.
- 570 root unit tests passed, including both PreviousMatrixSharingTest cases.
- Across configured test tasks: 1,708 passed, 255 skipped, no failures or errors. Many compatibility-profile tests are skipped on macOS core-profile OpenGL.
- Production jar ZIP integrity, metadata version, public SecondaryCamera API, and the original InputAvailability constructor verified.
- In-game validation remains outstanding.

## X2: shader activation with numbered builds

X1 is superseded. Its `2.2.14_X1` version string reached the original StandardMacros parser, which attempted `Integer.parseInt("14_X1")`. The 05:16:41 and 05:16:58 client log entries show shaderpack loading aborting and falling back to normal rendering before pipeline construction.

X2 backports only the suffix-safe version parser from master and adds regression coverage. The shader's numeric ANGELICA_VERSION remains `20214000` for `2.2.14_X1`, `2.2.14_X2`, and later numbered builds. The camera backport is unchanged.

The packaged-version regression reproduced the NumberFormatException before the fix. After the fix, `./gradlew build --offline --console=plain` passed, including all 575 root tests and five shader-version tests. X2 metadata and ZIP integrity were verified. In-game activation and the complete shader transition/PiP sequence still require validation in the target pack.

## X3: restore stock primary-camera history

Reported symptom on X2: only with shaders, distant players trail their motion and vehicle LOD surfaces overlap, sometimes while stationary. This is not yet proven to be duplicate entity submissions.

The camera port changed primary previous-matrix state from shared history on SystemTimeUniforms.COUNTER to per-pipeline history on FrameUpdateNotifier's local counter. X3 restores the stock shared primary history and main frame counter. Only secondary pipelines use isolated matrix history and local frame counters. PiP output framebuffers and the X2 version-parser fix are retained.

Two tests establishing the stock primary-history contract failed on X2 before this change. After the change, both pass, along with the main/secondary isolation test. Full build passed with 577 root tests, zero failures or skips. The X3 production jar metadata and ZIP integrity were verified.

This is a focused candidate for the reported shader ghosting. The tests establish history behavior, not reproduction or resolution of the in-game visual artifact. Test distant players and vehicles before opening a scope and again after a shadered PiP pass. No DH, FMUR, or MCHELI changes were made for X3.
