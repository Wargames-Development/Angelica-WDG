package com.gtnewhorizons.angelica.api;

import net.coderbot.iris.pipeline.FlansScopePass;
import net.minecraft.client.shader.Framebuffer;
import java.nio.FloatBuffer;
import java.util.function.BooleanSupplier;

/** Optional shader output for Flans PiP scopes. Calls are valid only after the primary world pass. */
public final class FlansScope {
    public static final int API_VERSION = 1;
    private FlansScope() { }
    public static boolean supportsShaders() { return FlansScopePass.supportsShaders(); }
    public static boolean render(Framebuffer target, FloatBuffer projection, FloatBuffer modelView,
                                 float partialTicks, BooleanSupplier drawScene, BooleanSupplier forwardLayer) {
        return FlansScopePass.render(target, projection, modelView, partialTicks, drawScene, forwardLayer);
    }
    /** Additive actor submission contract, valid only before private finalization.
     * Scene colour/material/normal, full/opaque DH depth, native depth, width/height, opaque/no-hand native depth.
     * Use immutable copies for sampled depths; only the returned private attachments may be written. */
    public static int[] actorTargets() { return FlansScopePass.actorTargets(); }
    public static FloatBuffer actorProjection() { return FlansScopePass.actorProjection(); }
    public static void beginTranslucents() { FlansScopePass.beginTranslucents(); }
    public static void phase(String name) { FlansScopePass.phase(name); }
    /** Optional additive v1 extension. Only valid inside the scene callback.
     * Submit DH's 16-byte terrain vertices with integer position/extra attributes
     * at indices 0/2 and normalized colour at index 1, under the borrower's read locks.
     * Pass 0 precedes beginTranslucents; pass 1 follows it. Always balance begin/end. */
    public static boolean hasDistantTerrain() { return FlansScopePass.hasDistantTerrain(); }
    public static void beginDistantTerrain(int pass, float partialTicks) { FlansScopePass.beginDistantTerrain(pass, partialTicks); }
    public static void distantTerrainOffset(float x, float y, float z) { FlansScopePass.distantTerrainOffset(x, y, z); }
    public static void endDistantTerrain() { FlansScopePass.endDistantTerrain(); }
    /** Opaque optional handle for a private Celeritas terrain renderer. No primary draw lists are submitted. */
    public static Object terrainProgram(Object pass, Object configuration) {
        return FlansScopePass.terrainProgram(
            (org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass) pass,
            (org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration<?>) configuration);
    }
    public static void release(Framebuffer target) { FlansScopePass.release(target); }
}
