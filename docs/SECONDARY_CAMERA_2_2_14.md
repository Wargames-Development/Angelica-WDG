# Flans PiP scope-only shader integration (X4)

This replaces the X1–X3 general secondary-camera attempt on the `2.2.14` base. It is pending in-game acceptance, not a claim that the complete modpack has been validated.

Angelica branch: `codex/angelica-2.2.14-pip`. FMUR branch: `barrack-AngelicaAPI`.

## Boundary

The optional API is now `com.gtnewhorizons.angelica.api.FlansScope`, version 1. The old `SecondaryCamera` API is removed. The new FMUR provider discovers this explicitly; missing APIs keep the original lightweight camera. Old FMUR cannot discover the replacement as the old API, so it also retains its existing fallback.

Only ordinary Flans model/colour PiP lenses in Automatic/Quality mode select this provider. Thermal, FLIR, vehicle displays and Performance mode retain their prior rendering path. No DH, MCHELI, network, server, explosion or WGCore source changes are part of this work.

Stock 2.2.14 source is restored exactly for the primary AngelicaChunkRenderer, IrisCeleritasShaderProvider, FrameUpdateNotifier, MatrixUniforms, ViewportUniforms and RenderTargets. FMUR LightweightCamera is restored exactly to pre-API commit e80debe. Public PipelineManager getters remain primary-only; its sole new lifecycle action disposes private scope resources on a shader reload.

## Rendering and ownership

- FMUR uses its pre-existing ShaderCamera provider interface. Provider selection happens before the old wrapper that suspends shaders. Rejected samples enter the original isolated lightweight fallback.
- Uploaded terrain meshes are read through private FMUR draw lists, index batches and VAOs. The primary world renderer is not asked to draw, cull, rebuild or update its visibility lists for a scope.
- Scope colour/depth targets, terrain programs, phase stacks, frame/jitter clock, matrix history and DH projection scratch are private. The API rejects outputs aliasing the main framebuffer or its colour/depth textures.
- Internal Iris geometry callbacks opt into the scope context while it is active. Public pipeline lookup, DH event integration and primary ownership remain primary-only. Main matrix-history and time providers retain stock semantics.
- Primary TESR instancing/deferred caches and player reflection capture do not accept scope submissions. Scope shadow maps are borrowed read-only; a scope cannot clear, resize or destroy them.
- Native scene geometry is shadered first. The established distant actor/terrain layer is submitted once, in a private forward stage after pack post-processing, retaining its existing depth encoding and presentation. This does not add shaderpack material lighting to the existing distant layer.
- The render guard restores framebuffer, program, VAO/VBO, sampler, texture matrix, projection/model-view, lightmap, phase, entity/item and attribution state on success and exceptions. Snapshot matrices use reusable direct memory because JOML's Unsafe path cannot safely use heap FloatBuffers.

## Limits

This is a same-position, narrower-view Flans scope, not a remote/vehicle/world camera API. It currently uses the OpenGL backend with sampler objects. Packs declaring custom image/storage buffers, or trying to write borrowed shadow images, retain the lightweight camera rather than sharing writable primary resources. First successful scope use compiles private shader programs.

## Validation

- Angelica: `./gradlew build -PmodBuildNumber=4 --offline --console=plain`.
- FMUR: Java 8 `bash ./gradlew build -Pbranch=pip-scope-test -PmodBuildNumber=205 -I tools/player_lod_checks.gradle auxiliaryViewChecks playerLodChecks externalPlayerPresentationChecks --offline --console=plain`.
- Real core-profile OpenGL tests check unchanged public primary pipeline lookup during a scope, restoration after exceptions, rejection of aliased outputs, unchanged primary framebuffer pixels, and phase cleanup. Separate tests cover independent scope/main matrix history and jitter clocks.
- The packaged artifacts and API signatures are inspected separately. No jars are installed automatically.

## Required in-game acceptance

1. Fresh launch with the paired test jars, shader enabled, no scope opened: compare stationary/moving distant players and vehicles against stock 2.2.14, including duplicates, trails and z-fighting.
2. Open and close ordinary Flans PiP scopes; native scope geometry should show the active pack and the existing distant layer should remain visible once. Recheck the main view after every close.
3. Toggle shaders off/on, change packs, resize, reconnect and change dimensions. Recheck NTM/native models and primary LODs.
4. Confirm thermal, vehicle displays, Performance mode and stock-Angelica fallback behave as before.
5. Repeat on macOS and Windows. The automated suite does not reproduce or prove resolution of the user's complete in-game shader/LOD regression.


## X5: repeated scope depth lifetime correction

The GLACxd double-launch session reported black/frozen full-screen flickering on ADS, possibly an M416 with CL6X. These names are unconfirmed; the correction applies to every supported Flans scope. The archived `lwjgl3ify-java21-child.log.1` identifies GLACxd, records the first successful 1024x1024 scope render at 19:31:04 and a normal shutdown starting at 19:32:03. It contains no intervening scope exception. The surviving iGlac process is a different client; its thread dump cannot establish GLACxd's failure state. Double launches rotate/share log filenames; a read-only copy of the matching log is retained at `/tmp/flans-pip-GLACxd-2026-11-11-1931.log`.

Confirmed defect: the private scope bypasses vanilla's per-frame framebuffer depth clear. Its output depth texture was cleared on creation but accumulated native and forward-layer depth on later samples. `FlansScopePass` now binds and clears only the validated private output depth before every scene, overriding inherited scissor/depth-write restrictions inside `ScopeRenderState`. This prevents stale depth rejecting new geometry. It does not establish that stale depth alone caused the entire full-screen flicker or apparent freeze.

A real OpenGL regression test dirties private depth over eight consecutive frames, enters with an empty scissor and disabled depth writes, and verifies the entire private depth resets while main-view depth, framebuffer and caller masks remain intact. The six scope-isolation tests pass; root unit tests: 577 passed; all root GL tests: 23 cases, 17 passed and six existing feature skips. The first three private scope frames now log begin, scene completion, shader-composite completion and forward-layer completion, with no continuing per-frame logging. The previous one-time success message was insufficient evidence of repeated-frame health.

Build: `./gradlew build -PmodBuildNumber=5 --offline --console=plain`. Artifact: `build/libs/angelica-2.2.14_X5.jar`; use with FMUR X206 and DH X71. Not installed automatically. No complete modpack runtime acceptance is claimed; fresh-launch ADS open/hold/release must confirm live scope updates, normal main-view updates and recovery on close before this candidate is accepted.


## X6: confirmed window presentation failure and scope-boundary correction

X5 did not resolve the user's M416/CL6X failure. The 5.72-second recording `Screen Recording 2026-11-11 at 19.58.19.mov` shows the entire client alternating between black and a stale partly-ADS image. All three traced scope frames reached scene, composite and forward completion. Thread/native captures show ongoing rendering rather than a Java deadlock. Shader frame counters continued advancing after ADS released, the active primary pipeline identity remained correct, and framebuffer/VAO/viewport/colour-mask restoration was intact.

A read-only probe attached to the relaunched GLACxd client compared actual OpenGL state with GLSM on scheduled render-thread tasks. On the default framebuffer (draw/read FBO 0), `GL_DRAW_BUFFER` was **GL_NONE (0)** while `GL_READ_BUFFER` was GL_BACK (1029). FFP program and uniform-buffer tracking were consistent. A single scheduled repair called GLSM `glDrawBuffer(DEFAULT_DRAW_BUFFER)` only after checking this exact window/GL_NONE state. It changed draw buffer 0 to 1029; the user immediately reported that the flickering stopped and confirmed recovery. No game classes were redefined or jars replaced for that test. Temporary diagnostic classes remain loaded until that JVM exits; the probes have finished and do not keep sampling.

The scope guard used GLSM's global draw-buffer attribute cache when entered on the window. That cache can retain GL_NONE from a depth-only FBO even while the actual window still selects GL_BACK. During scope teardown, restoring the stale attribute to framebuffer 0 disables window colour output. The regression fixture reproduces exactly this sequence: a depth-only FBO primes cached GL_NONE, window selection is still GL_BACK, then a scoped colour FBO draw/teardown leaves window GL_NONE on X5. Before the fix, the assertion failed with expected 1029 / actual 0.

`ScopeRenderState` now synchronizes the draw-buffer cache from the driver's actual selection **only when the caller draw FBO is 0**, before pushing its attribute snapshot. The scope can then restore the correct window selection. This is a bounded scope-guard change; it does not rewrite primary rendering or hardcode GL_BACK for every framebuffer. X5's depth lifetime correction is retained, but is not described as the cause of this presentation failure.

X6 validation:

- `./gradlew build -PmodBuildNumber=6 --offline --console=plain`: passed.
- Root unit tests: 577 passed.
- Root OpenGL tests: 24 cases, 18 passed, six existing feature skips. All seven scope-isolation cases pass, including the test that failed before the fix.
- Jar CRC passes. Compared with X5, only ScopeRenderState and version-bearing classes change among class files; no class additions/removals.
- Artifact: `build/libs/angelica-2.2.14_X6.jar`.
- SHA-256: `ddb8ff8199bafb6eb5811d3b642a045410000b2aada17aa6ad412540935f53ba`.

Use X6 with FMUR X206 and DH X71. X6 is built, not installed; do not replace jars while the client is running. The one-time runtime repair and user confirmation establish the presentation cause. Fresh-launch X6 ADS/open/hold/release and reload acceptance remains required. The separately reported vehicle shadow flicker while receding has not been corrected or attributed to this window-buffer bug.

Local diagnostic evidence: `/tmp/flans-cl6x-failing-state.txt`, `/tmp/flans-cl6x-failing-pipeline-state-v2.txt`, `/tmp/flans-cl6x-driver-state.txt`, `/tmp/flans-cl6x-driver-buffer-state.txt`, `/tmp/flans-cl6x-window-repair.txt`, `/tmp/angelica-X6-reproduction.log`, `/tmp/angelica-X6-build.log`. The attempted first attach failed after the original client exited; only the later PID 888 captures and repair belong to the controlled relaunched session. No account tokens, launch arguments, chats or gameplay controls were used by these probes.
