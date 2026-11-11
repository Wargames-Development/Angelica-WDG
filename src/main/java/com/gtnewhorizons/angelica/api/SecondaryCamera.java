package com.gtnewhorizons.angelica.api;

import net.coderbot.iris.pipeline.SecondaryCameraPass;
import net.minecraft.client.shader.Framebuffer;

import java.nio.FloatBuffer;
import java.util.function.BooleanSupplier;

/** Offscreen rendering for a narrower view at the main camera's position. Call on the render thread after the main world pass. */
public final class SecondaryCamera {
    private SecondaryCamera() {}

    public static boolean hasTerrain() {
        return SecondaryCameraPass.hasTerrain();
    }

    public static boolean supportsShaders() {
        return SecondaryCameraPass.supportsShaders();
    }

    /** The caller owns the depth-enabled target. The callback submits scene geometry and returns false if the sample is incomplete. */
    public static boolean render(Framebuffer target, FloatBuffer projection, FloatBuffer modelView,
                                 float partialTicks, boolean shaders, BooleanSupplier drawScene) {
        return SecondaryCameraPass.render(target, projection, modelView, partialTicks, shaders, drawScene);
    }

    /** Submit borrowed terrain for pass 0 (opaque and cutout) or pass 1 (translucent). */
    public static void drawTerrain(int pass, double x, double y, double z) {
        SecondaryCameraPass.drawTerrain(pass, x, y, z);
    }

    public static void beginTranslucents() {
        SecondaryCameraPass.beginTranslucents();
    }

    /** Set the shader geometry phase by its Iris name. This is inert for unshaded views. */
    public static void phase(String phase) {
        SecondaryCameraPass.phase(phase);
    }

    /** Release shader resources associated with a target before the caller deletes it. */
    public static void release(Framebuffer target) {
        SecondaryCameraPass.release(target);
    }
}
