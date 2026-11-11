package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;

public final class PassRebindGate {

    private static int lastProgramGen = -1;
    private static int lastFbGen = -1;

    private PassRebindGate() {}

    public static void rebindIfDirty() {
        if (!Iris.enabled) return;
        if (GLStateManager.getProgramGeneration() == lastProgramGen
            && GLStateManager.getDrawFramebufferGeneration() == lastFbGen) {
            return;
        }
        final WorldRenderingPipeline pipeline = net.coderbot.iris.pipeline.FlansScopePass.renderPipeline();
        if (pipeline != null) {
            pipeline.rebindCurrentPass();
        }
        lastProgramGen = GLStateManager.getProgramGeneration();
        lastFbGen = GLStateManager.getDrawFramebufferGeneration();
    }
}
