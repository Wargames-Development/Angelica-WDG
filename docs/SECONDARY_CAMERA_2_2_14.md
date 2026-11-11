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
