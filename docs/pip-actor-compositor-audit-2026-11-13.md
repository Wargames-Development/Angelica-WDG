# Shader PiP actor compositor ownership

## Scope and evidence

The reported tank is visible in the primary shader view on DH X77, but its PiP
colour is absent while its sun shadow remains. The local child log from
20:36:09 through 20:36:24 reports `candidates=1 outside=0 unknown=0 composited=1`.
These counters establish submission and invocation, not successful pixel output.
The inspected run loaded Angelica X11, DH X77, FMUR X215 and MCHR X213.

The original PiP audit was re-read. Its historical read-only instruction applies
to that audit; this change follows the user's subsequent request to trace and fix
the current failure. No shader pack, game installation, world or visibility policy
was modified.

## B6 — automatic entity routing takes ownership back during DH compositing

The relevant source trace is:

1. FMUR `ShaderScopeScene.draw` submits native terrain and shader DH terrain,
   obtains Angelica's private actor targets, and enters `ENTITIES` for actor work.
   `ThermalRenderApi.renderCameraGeometry` collects companion geometry and flushes
   DH's active camera pass. MCHR's callback reuses the tracked animated model.
2. DH `ActorCameraPass.draw` captures model colour/depth through `ModelCapture`
   and Angelica's `EntityGeometryPass`. That existing guard protects the model
   draw, and closes before `ActorCameraPass.compositeShader` starts.
3. `ModelCapture.compositeSource` binds DH's own compositor program and targets,
   then configures blending and other raster state before drawing its quad.
4. Angelica `IrisGLSMBridge` reports a foreign program through
   `onModProgramOverride`. The pipeline already records `modProgramOverrode`
   and the program that must be restored, and clears `current`.
5. The bridge also reports blend and texture-unit changes. In the `ENTITIES`
   phase, `onVanillaBlendChanged` calls `matchPass`; texture changes and entity
   boundaries can also reach the same selector. Previously, `matchPass` ignored
   the recorded external ownership. It called `Pass.use`, replacing both the
   compositor's program and its framebuffer before the compositor draw.

This is a confirmed routing defect, reproduced in a real OpenGL regression:
before the fix, the test expected compositor program 4 and observed native
entity program 3. Successful submission counters cannot detect it. Shadows are
rendered separately and therefore do not prove that the PiP colour pass works.
State-dependent routing also explains why invocation alone is insufficient when
investigating intermittent visibility; the precise earlier one-second episode
has not been replayed or timed in a live game.

## Implementation and review

`DeferredWorldRenderingPipeline.matchPass(RenderCondition)` now respects the
existing external-program ownership flag. Automatic matching resumes through
the existing `restorePassAfterModProgram` boundary when the caller restores the
saved shader or program zero. `refreshingPass` permits that intentional handoff.
Input/phase state still updates so restoration uses the current scene inputs.

This fixes the shared routing boundary rather than changing PiP's phase to hide
the issue, bypassing terrain tests, adding visibility grace periods, or drawing
vehicles over the finished image. No new API or parallel rendering system was
introduced. DH's native/DH depth comparisons, server authorization, actor ranges,
animations, thermal path and shader-off path are unchanged.

Changed files:

- `src/main/java/net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java`:
  four-line ownership guard/comment at the shared pass selector.
- `src/test/java/net/coderbot/iris/pipeline/EntityGeometryGLTest.java`:
  GPU regression using the real pipeline routing and real `Pass.use`, with
  synthetic shaders and isolated targets. Exercises blend, texture-input,
  entity-boundary and rebind callbacks; checks output pixels and preservation of
  the enclosing scene across repeated frames; checks both valid return paths
  and rejection of an unrelated program as a return signal.
- `gradle.properties`: normal packaging build-number advance.
- This report: trace, evidence and validation limits.

Read-only review verdict: ACCEPTABLE. B6 is fixed at the owning boundary.
Dedicated-server review: only an existing client rendering class changed; no
common entry point, network handler, registration or side dependency changed.
The runtime guard uses existing booleans, allocates nothing and adds constant
work per attempted pass selection. Existing Java 8 downgrade and Forge mappings
remain in use. No protocol, save, configuration or public API migration is needed.
An external renderer remains responsible for restoring its saved program or
zero; silently taking its program away on a blend change is not a safe substitute.

## Validation and remaining acceptance

- Pre-fix targeted GPU regression: expected failure, native program replaced the
  compositor. Evidence: `build/pip-compositor-before.log`.
- Post-fix targeted GPU tests: 11 passed, one optional local-pack compilation test
  skipped. Includes existing geometry capture, exception cleanup, primary-view
  isolation and private terrain depth tests. Evidence: `build/pip-compositor-after.log`.
- Final `./gradlew.bat --offline --no-daemon :glCoreTest :assemble`: PASS,
  28 executed GPU tests passed, one optional local-pack compilation test skipped.
  Compilation, JVM downgrade, shadow packaging and reobfuscation completed.
  Evidence: `build/pip-compositor-release.log` and JUnit XML in
  `build/test-results/glCoreTest`.
- `git diff --check`: PASS. Runtime jar inspection confirmed X12 metadata,
  the mixin refmap, and the ownership guard in both the Java 8 base class and
  Java 17 multi-release class selected by Java 21. No tests, local logs or
  workspace/cache directories were found in the runtime jar.

Runtime artifact: `build/libs/angelica-2.2.14_X12.jar` (10,386,044 bytes).
SHA-256: `2ddb465cc18509df79993c6d3d2b514d2689c7ec6d23871ed35bfe43c64cb294`.
Only Angelica needs replacing for this fix; retain DH X77 and the existing
matching FMUR/MCHR builds. Packaging advanced Angelica's local build number
from X11 to X12. The artifact includes the pre-existing migration changes in
this working tree, which have not been reverted or reformatted.

T2: the actual saved-world tank scene, animated vehicles, players, long-range
native/DH boundaries and the full shader pack must still be retested in-game.
The synthetic GPU regression proves the ownership failure and correction, not
complete gameplay acceptance. Linux/macOS runtime and a dedicated-server boot
were not executed here. The fix adds no platform-specific API.

Manual acceptance: with DH X77 retained, replace Angelica X11 with the new
Angelica runtime jar, then compare ADS and non-ADS on the same tank. Repeat
looking away/back, sustained ADS, shader reload, resize and repeated scope
activation; test players and moving/animated aircraft; test partial cover and
complete cover at native/DH transitions through the configured 750-block range.
Verify shader-off and thermal views, including the primary view outside the
optic. An occluded or unauthorized actor must remain hidden.

Deferred: those runtime checks and the migration's existing acceptance gate.
WGMap's retained compatibility-only visual implementation is not removed by
this fix. No commits, branches, releases or installed-mod replacements were made.
