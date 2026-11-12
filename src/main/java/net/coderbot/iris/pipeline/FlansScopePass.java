package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.compat.mojang.Camera;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.texture.DepthBufferFormat;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import net.coderbot.iris.Iris;
import net.coderbot.iris.celeritas.IrisCeleritasChunkProgramOverrides;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.rendertarget.DepthTexture;
import net.coderbot.iris.rendertarget.IRenderTargetExt;
import net.coderbot.iris.shadows.ShadowRenderingState;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.embeddedt.embeddium.impl.gl.shader.GlProgram;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;
import java.nio.FloatBuffer;
import java.util.function.BooleanSupplier;

/** Optional Flans scope. Public Iris pipeline getters and primary terrain caches are never replaced. */
public final class FlansScopePass {
    private static final IrisCeleritasChunkProgramOverrides terrainPrograms = new IrisCeleritasChunkProgramOverrides();
    private static boolean rendering, translucent, reportedReady, sceneGeometry;
    private static int diagnosticFrames;
    private static DeferredWorldRenderingPipeline pipeline, primary, failedPrimary;
    private static Framebuffer output;
    private static Object unsupportedPack;
    private static DepthTexture emptyDistantDepth;
    private static net.coderbot.iris.compat.dh.ScopeDistantTerrain distantTerrain;
    private static ScopeRenderState distantState;
    private static int distantPass;
    private static RenderPassConfiguration<?> terrainConfiguration;
    private static int distantWidth, distantHeight, privateVao;
    private FlansScopePass() { }

    public static boolean isRendering() { return rendering; }
    public static boolean isRenderingShaders() { return rendering; }

    /** Only Angelica's internal geometry callbacks opt into this context. External integrations see the primary pipeline. */
    public static WorldRenderingPipeline renderPipeline() {
        return rendering && pipeline != null ? pipeline : Iris.getPipelineManager().getPipelineNullable();
    }

    public static boolean supportsShaders() {
        if (rendering || !GLStateManager.isMainThread() || BackendManager.RENDER_BACKEND.isSDLGPU()
            || !RenderSystem.supportsSamplerObjects()) return false;
        final PipelineManager manager = Iris.getPipelineManagerNullable();
        if (manager == null || !(manager.getPipelineNullable() instanceof DeferredWorldRenderingPipeline main)
            || main == failedPrimary || main.isWorldPassActive() || ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return false;
        final var pack = Iris.getCurrentPack().orElse(null);
        if (pack == null) return false;
        if (!pack.getCustomImages().isEmpty() || !pack.getBufferObjects().isEmpty()) {
            if (unsupportedPack != pack) {
                unsupportedPack = pack;
                Iris.logger.warn("Flans shader scope retains the existing camera for {}: custom images/storage buffers require separate scope ownership", Iris.getCurrentPackName());
            }
            return false;
        }
        return true;
    }

    public static boolean render(Framebuffer target, FloatBuffer projection, FloatBuffer modelView,
                                 float partialTicks, BooleanSupplier drawScene, BooleanSupplier forwardLayer) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (!supportsShaders() || target == null || target == mc.getFramebuffer() || !target.useDepth
            || target.framebufferObject <= 0 || target.framebufferWidth < 16 || target.framebufferHeight < 16
            || mc.theWorld == null || mc.renderViewEntity == null || !Float.isFinite(partialTicks)
            || !matrix(projection) || !matrix(modelView) || drawScene == null || forwardLayer == null
            || !(target instanceof IRenderTargetExt depth) || depth.iris$getDepthTextureId() <= 0) return false;
        Framebuffer mainOutput = mc.getFramebuffer();
        if (!(mainOutput instanceof IRenderTargetExt mainDepth) || !independentOutput(target.framebufferObject, target.framebufferTexture,
                depth.iris$getDepthTextureId(), mainOutput.framebufferObject, mainOutput.framebufferTexture, mainDepth.iris$getDepthTextureId())) return false;
        final DeferredWorldRenderingPipeline main = (DeferredWorldRenderingPipeline) Iris.getPipelineManager().getPipelineNullable();
        try (ScopeRenderState saved = new ScopeRenderState()) {
            if (primary != main || output != target) discardResources();
            if (privateVao == 0) privateVao = GLStateManager.glGenVertexArrays();
            GLStateManager.glBindVertexArray(privateVao);
            RenderingState.INSTANCE.setProjectionMatrix(projection);
            RenderingState.INSTANCE.setModelViewMatrix(modelView);
            RenderingState.INSTANCE.setFov((float) Math.toDegrees(2 * Math.atan(1.0 / projection.get(projection.position() + 5))));
            CapturedRenderingState.INSTANCE.setTickDelta(partialTicks);
            Program.unbind();
            GLStateManager.glMatrixMode(GL11.GL_PROJECTION); GLStateManager.glLoadMatrix(projection);
            GLStateManager.glMatrixMode(GL11.GL_MODELVIEW); GLStateManager.glLoadMatrix(modelView);
            rendering = true; translucent = false;
            try {
                prepareEmptyDistantDepth(target);
                if (pipeline == null) {
                    pipeline = new DeferredWorldRenderingPipeline(Iris.getCurrentPack().orElseThrow().getProgramSet(Iris.getCurrentDimensionName()), () -> target, main);
                    output = target; primary = main;
                }
                if (distantTerrain == null && net.coderbot.iris.compat.dh.DHCompat.isPresent()
                    && net.coderbot.iris.compat.dh.ScopeDistantTerrain.supported(pipeline)) {
                    distantTerrain = new net.coderbot.iris.compat.dh.ScopeDistantTerrain(pipeline, target.framebufferWidth, target.framebufferHeight);
                }
                if (distantTerrain != null) distantTerrain.clear();
                final int traceFrame = diagnosticFrames < 3 ? ++diagnosticFrames : 0;
                if (traceFrame != 0) Iris.logger.info("Flans scope frame {}: begin private scene", traceFrame);
                GLStateManager.setShaderColor(1, 1, 1, 1);
                clearScopeDepth(target.framebufferObject);
                pipeline.beginLevelRendering();
                pipeline.setPhase(WorldRenderingPhase.CUSTOM_SKY);
                mc.renderGlobal.renderSky(partialTicks);
                pipeline.setPhase(WorldRenderingPhase.NONE);
                pipeline.renderShadows(mc.entityRenderer, Camera.INSTANCE); // Prepare passes only; shadow maps are read-only.
                sceneGeometry = true;
                try { if (!drawScene.getAsBoolean()) return false; } finally { sceneGeometry = false; }
                if (traceFrame != 0) Iris.logger.info("Flans scope frame {}: geometry complete", traceFrame);
                pipeline.finalizeLevelRendering();
                if (traceFrame != 0) Iris.logger.info("Flans scope frame {}: shader composite complete", traceFrame);
                // Existing distant camera geometry has its own depth encoding. Keep it
                // in a forward layer after pack post-processing, inside the private context.
                target.bindFramebuffer(true);
                Program.unbind();
                GLStateManager.glMatrixMode(GL11.GL_PROJECTION); GLStateManager.glLoadMatrix(projection);
                GLStateManager.glMatrixMode(GL11.GL_MODELVIEW); GLStateManager.glLoadMatrix(modelView);
                if (!forwardLayer.getAsBoolean()) return false;
                if (traceFrame != 0) Iris.logger.info("Flans scope frame {}: forward layer complete", traceFrame);
                if (!reportedReady) {
                    Iris.logger.info("Flans shader scope rendered to private {}x{} output; primary pipeline retained", target.framebufferWidth, target.framebufferHeight);
                    reportedReady = true;
                }
                return true;
            } finally {
                if (distantState != null) endDistantTerrain();
                if (pipeline != null && pipeline.isWorldPassActive()) pipeline.abortSecondaryView();
            }
        } catch (RuntimeException | LinkageError failure) {
            failedPrimary = main;
            Iris.logger.warn("Flans shader scope failed; retaining the existing lightweight camera", failure);
            // Release on the next explicit close/reload, under its own state guard.
            return false;
        } finally { rendering = false; }
    }

    /** Vanilla clears the main scene every frame; this private scene bypasses that lifecycle. */
    static void clearScopeDepth(int framebuffer) {
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);
        GLStateManager.glDepthMask(true);
        GLStateManager.glClearDepth(1.0);
        GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    static boolean independentOutput(int framebuffer, int colour, int depth, int mainFramebuffer, int mainColour, int mainDepth) {
        return framebuffer > 0 && colour > 0 && depth > 0 && framebuffer != mainFramebuffer && colour != mainColour && depth != mainDepth;
    }

    private static boolean matrix(FloatBuffer values) {
        if (values == null || values.remaining() != 16) return false;
        for (int i = values.position(); i < values.limit(); i++) if (!Float.isFinite(values.get(i))) return false;
        return true;
    }
    static int getEmptyDistantDepth() { return emptyDistantDepth.getTextureId(); }
    static int getDistantDepth(boolean opaque) {
        return distantTerrain == null ? getEmptyDistantDepth() : distantTerrain.depth(opaque);
    }

    public static int[] actorTargets() {
        if (!sceneGeometry || !translucent || distantTerrain == null) return null;
        return pipeline.actorTargets(distantTerrain.depth(false), distantTerrain.depth(true));
    }
    public static FloatBuffer actorProjection() {
        return sceneGeometry && distantTerrain != null ? distantTerrain.actorProjection() : null;
    }

    public static boolean hasDistantTerrain() { return rendering && distantTerrain != null; }

    public static void beginDistantTerrain(int pass, float partialTicks) {
        if (!hasDistantTerrain() || distantState != null) throw new IllegalStateException("No available scope DH pass");
        distantState = new ScopeRenderState(); distantPass = pass;
        try { distantTerrain.begin(pass, partialTicks); }
        catch (RuntimeException | LinkageError failure) { endDistantTerrain(); throw failure; }
    }

    public static void distantTerrainOffset(float x, float y, float z) {
        if (distantState == null) throw new IllegalStateException("No active scope DH pass");
        distantTerrain.offset(x, y, z);
    }

    public static void endDistantTerrain() {
        if (distantState == null) return;
        final ScopeRenderState saved = distantState; distantState = null;
        try { distantTerrain.end(distantPass); } finally { saved.close(); }
    }
    private static void prepareEmptyDistantDepth(Framebuffer target) {
        if (emptyDistantDepth != null && distantWidth == target.framebufferWidth && distantHeight == target.framebufferHeight) return;
        if (emptyDistantDepth != null) emptyDistantDepth.destroy();
        distantWidth = target.framebufferWidth; distantHeight = target.framebufferHeight;
        emptyDistantDepth = new DepthTexture(distantWidth, distantHeight, DepthBufferFormat.DEPTH32F);
        final GlFramebuffer clear = new GlFramebuffer();
        try {
            clear.addDepthAttachment(emptyDistantDepth.getTextureId()); clear.noDrawBuffers();
            RenderSystem.readBuffer(clear.getId(), GL11.GL_NONE); clear.bind();
            GLStateManager.glDisable(GL11.GL_SCISSOR_TEST); GLStateManager.glDepthMask(true);
            GLStateManager.glClearDepth(1); GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        } finally { clear.destroy(); }
    }
    public static void beginTranslucents() {
        if (!rendering || pipeline == null) throw new IllegalStateException("No active Flans shader scope");
        if (!translucent) { pipeline.beginHand(); pipeline.beginTranslucents(); translucent = true; }
    }
    public static void phase(String name) {
        if (!rendering || pipeline == null) throw new IllegalStateException("No active Flans shader scope");
        pipeline.setPhase(WorldRenderingPhase.valueOf(name));
    }
    public static GlProgram<? extends ChunkShaderInterface> terrainProgram(TerrainRenderPass pass, RenderPassConfiguration<?> configuration) {
        if (!rendering || pipeline == null) throw new IllegalStateException("No active Flans shader scope");
        if (terrainConfiguration != configuration) {
            terrainPrograms.deleteShaders(); terrainPrograms.createShaders(pipeline.getCeleritasTerrainPipeline(), configuration);
            terrainConfiguration = configuration;
        }
        pipeline.setPhase(pass.isReverseOrder() ? WorldRenderingPhase.TERRAIN_TRANSLUCENT
            : pass.supportsFragmentDiscard() ? WorldRenderingPhase.TERRAIN_CUTOUT : WorldRenderingPhase.TERRAIN_SOLID);
        return terrainPrograms.getScopeProgram(pass);
    }
    public static void release(Framebuffer target) { if (target == output) discard(); }
    static void discard() {
        if (rendering) throw new IllegalStateException("Cannot destroy an active Flans shader scope");
        failedPrimary = null; unsupportedPack = null;
        if (pipeline == null && emptyDistantDepth == null && privateVao == 0) return;
        try (ScopeRenderState saved = new ScopeRenderState()) { discardResources(); }
    }
    private static void discardResources() {
        terrainPrograms.deleteShaders(); terrainConfiguration = null;
        final DeferredWorldRenderingPipeline old = pipeline;
        pipeline = null; primary = null; output = null; reportedReady = false; diagnosticFrames = 0;
        if (distantTerrain != null) { distantTerrain.destroy(); distantTerrain = null; }
        if (old != null) old.destroy();
        if (emptyDistantDepth != null) { emptyDistantDepth.destroy(); emptyDistantDepth = null; }
        if (privateVao != 0) { GLStateManager.glDeleteVertexArrays(privateVao); privateVao = 0; }
    }
}
