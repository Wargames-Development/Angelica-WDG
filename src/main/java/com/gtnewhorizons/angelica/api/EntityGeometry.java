package com.gtnewhorizons.angelica.api;

import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.pipeline.FlansScopePass;

/** Render-thread-only geometry capture. The caller owns a private floating-point RGBA/depth FBO,
 * viewport, scissor and local GL matrices. Returns null if no compatible world scene is active.
 * Outputs premultiplied pack scene colour at attachment zero; no post-processing is run.
 * Always close before compositing, including on renderer failure. No public pipeline is replaced. */
public final class EntityGeometry {
    public static final int API_VERSION = 1;
    private EntityGeometry() { }
    public static void refresh() {
        if (FlansScopePass.renderPipeline() instanceof DeferredWorldRenderingPipeline pipeline) pipeline.rebindCurrentPass();
    }
    public static AutoCloseable begin() {
        return FlansScopePass.renderPipeline() instanceof DeferredWorldRenderingPipeline pipeline
            ? pipeline.beginEntityGeometry() : null;
    }
}
