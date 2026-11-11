package net.coderbot.iris.compat.dh;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.texture.DepthBufferFormat;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.texture.DepthCopyStrategy;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.rendertarget.DepthTexture;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/** Scope-owned pack programs/depth. Never installs or removes a primary DH override. */
public final class ScopeDistantTerrain {
    private final int width, height;
    private final DepthTexture depth, opaqueDepth;
    private final GlFramebuffer solidTarget, waterTarget;
    private IrisLodRenderProgram solid, water, active;
    private final Matrix4f projection = new Matrix4f();
    private final DhApiVec3f offset = new DhApiVec3f(0, 0, 0);

    public ScopeDistantTerrain(DeferredWorldRenderingPipeline pipeline, int width, int height) {
        this.width = width; this.height = height;
        depth = new DepthTexture(width, height, DepthBufferFormat.DEPTH32F);
        opaqueDepth = new DepthTexture(width, height, DepthBufferFormat.DEPTH32F);
        solidTarget = pipeline.createDHFramebuffer(pipeline.getDHTerrainShader().get(), false);
        waterTarget = pipeline.createDHFramebuffer(pipeline.getDHWaterShader().get(), true);
        try {
            solidTarget.addDepthAttachment(depth.getTextureId());
            waterTarget.addDepthAttachment(depth.getTextureId());
            final var terrain = pipeline.getDHTerrainShader().get();
            solid = IrisLodRenderProgram.createProgram(terrain.getName() + "_scope", false, false,
                terrain, pipeline.getCustomUniforms(), pipeline);
            final var waterSource = pipeline.getDHWaterShader().get();
            water = IrisLodRenderProgram.createProgram(waterSource.getName() + "_scope", false, true,
                waterSource, pipeline.getCustomUniforms(), pipeline);
        } catch (RuntimeException | LinkageError failure) {
            destroy(); throw failure;
        }
    }

    public static boolean supported(DeferredWorldRenderingPipeline pipeline) {
        return DHCompat.isPresent() && pipeline.getDHTerrainShader().isPresent() && pipeline.getDHWaterShader().isPresent();
    }

    public void clear() {
        // Both textures must describe this sample, including empty-world samples.
        solidTarget.addDepthAttachment(opaqueDepth.getTextureId());
        clearDepth(solidTarget);
        solidTarget.addDepthAttachment(depth.getTextureId());
        clearDepth(solidTarget);
    }

    private static void clearDepth(GlFramebuffer target) {
        target.bind();
        GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);
        GLStateManager.glDepthMask(true); GLStateManager.glClearDepth(1);
        GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    public void begin(int pass, float partialTicks) {
        if (active != null || (pass != 0 && pass != 1)) throw new IllegalStateException("Invalid scope DH pass");
        final Matrix4f viewProjection = RenderingState.INSTANCE.getProjectionMatrix();
        projection.setPerspective(viewProjection.perspectiveFov(), viewProjection.m11() / viewProjection.m00(),
            DHCompat.getNearPlane(), DHCompat.getFarPlane());
        active = pass == 0 ? solid : water;
        (pass == 0 ? solidTarget : waterTarget).bind();
        GLStateManager.glViewport(0, 0, width, height);
        GLStateManager.glEnable(GL11.GL_DEPTH_TEST); GLStateManager.glDepthFunc(GL11.GL_LEQUAL);
        GLStateManager.glDepthMask(true); GLStateManager.glDisable(GL11.GL_ALPHA_TEST);
        GLStateManager.glDisable(GL11.GL_FOG); GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);
        GLStateManager.glColorMask(true, true, true, true);
        GLStateManager.setShaderColor(1, 1, 1, 1);
        if (pass == 0) { GLStateManager.glDisable(GL11.GL_BLEND); GLStateManager.glEnable(GL11.GL_CULL_FACE); }
        else {
            GLStateManager.glDisable(GL11.GL_CULL_FACE); GLStateManager.glEnable(GL11.GL_BLEND);
            GLStateManager.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        active.bind();
        active.fillUniformData(projection, RenderingState.INSTANCE.getModelViewMatrix(), 0, partialTicks);
    }

    public void offset(float x, float y, float z) {
        if (active == null) throw new IllegalStateException("No scope DH pass");
        offset.x = x; offset.y = y; offset.z = z;
        active.setModelPos(offset);
    }

    public void end(int pass) {
        try {
            if (active != null) active.unbind();
            if (pass == 0) DepthCopyStrategy.fastest(false).copy(solidTarget, depth.getTextureId(), null,
                opaqueDepth.getTextureId(), width, height);
        } finally { active = null; LodRendererEvents.popDhMatrices(); }
    }

    public int depth(boolean opaque) { return (opaque ? opaqueDepth : depth).getTextureId(); }

    public void destroy() {
        if (solid != null) solid.free();
        if (water != null) water.free();
        solid = water = null;
        // RenderTargets owns the colour FBOs and disposes them with its pipeline.
        depth.destroy(); opaqueDepth.destroy();
    }
}
