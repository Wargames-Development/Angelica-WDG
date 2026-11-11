package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.compat.mojang.Camera;
import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.texture.DepthBufferFormat;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import com.gtnewhorizons.angelica.rendering.celeritas.BlockRenderLayer;
import com.gtnewhorizons.angelica.rendering.celeritas.CeleritasWorldRenderer;
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
import org.embeddedt.embeddium.impl.gl.device.RenderDevice;
import org.embeddedt.embeddium.impl.gl.shader.GlProgram;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.function.BooleanSupplier;

/** Secondary shader resources live alongside the primary pipeline and are retired before it. */
public final class SecondaryCameraPass {
    private static final FloatBuffer savedProjection = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer savedModelView = BufferUtils.createFloatBuffer(16);
    private static final Matrix4f savedLightmapMatrix = new Matrix4f();
    private static final IrisCeleritasChunkProgramOverrides terrainPrograms = new IrisCeleritasChunkProgramOverrides();

    private static boolean rendering;
    private static boolean shaderView;
    private static DeferredWorldRenderingPipeline pipeline;
    private static DeferredWorldRenderingPipeline primary;
    private static DeferredWorldRenderingPipeline failedPrimary;
    private static Framebuffer output;
    private static boolean translucent;
    private static boolean reportedReady;
    private static DepthTexture emptyDistantDepth;
    private static int distantWidth, distantHeight;

    private SecondaryCameraPass() {}

    public static boolean isRendering() { return rendering; }
    public static boolean isRenderingShaders() { return shaderView; }

    public static boolean hasTerrain() {
        final CeleritasWorldRenderer world = CeleritasWorldRenderer.getInstanceOrNull();
        return world != null && world.isActive() && world.getLastViewport() != null;
    }

    public static boolean supportsShaders() {
        if (rendering || !GLStateManager.isMainThread() || BackendManager.RENDER_BACKEND.isSDLGPU() || !hasTerrain()) return false;
        final PipelineManager manager = Iris.getPipelineManagerNullable();
        if (manager == null || !(manager.getPipelineNullable() instanceof DeferredWorldRenderingPipeline main)
            || main == failedPrimary || main.isWorldPassActive()) return false;
        final var pack = Iris.getCurrentPack().orElse(null);
        if (pack == null) return false;
        if (!pack.getCustomImages().isEmpty() || !pack.getBufferObjects().isEmpty()) {
            failedPrimary = main;
            Iris.logger.info("Secondary shader camera uses the unshaded pass for {} with custom images or storage buffers", Iris.getCurrentPackName());
            return false;
        }
        return true;
    }

    public static boolean render(Framebuffer target, FloatBuffer projection, FloatBuffer modelView,
                                 float partialTicks, boolean shaders, BooleanSupplier drawScene) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (rendering || !GLStateManager.isMainThread() || target == null || target == mc.getFramebuffer()
            || mc.theWorld == null || mc.renderViewEntity == null || target.framebufferObject <= 0
            || !target.useDepth || projection == null || projection.remaining() != 16
            || modelView == null || modelView.remaining() != 16 || drawScene == null
            || ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return false;
        final PipelineManager manager = Iris.getPipelineManagerNullable();
        final DeferredWorldRenderingPipeline main = manager != null
            && manager.getPipelineNullable() instanceof DeferredWorldRenderingPipeline deferred ? deferred : null;
        if (main != null && main.isWorldPassActive()) return false;
        if (shaders && (!supportsShaders() || target.framebufferWidth < 16 || target.framebufferHeight < 16
            || !(target instanceof IRenderTargetExt depth) || depth.iris$getDepthTextureId() <= 0)) return false;

        final RenderingState state = RenderingState.INSTANCE;
        final CapturedRenderingState captured = CapturedRenderingState.INSTANCE;
        final float tick = captured.getTickDelta();
        final float fov = state.getFov();
        final boolean world = state.isRenderingWorld();
        save(state.getProjectionBuffer(), savedProjection);
        save(state.getModelViewBuffer(), savedModelView);
        savedLightmapMatrix.set(GLSMTextures.getTextures().getTextureUnitMatrix(1));
        final float lightX = GLStateManager.ctx().lastBrightnessX;
        final float lightY = GLStateManager.ctx().lastBrightnessY;

        final int read = GLSMFramebuffer.getReadFramebuffer();
        final int draw = GLSMFramebuffer.getDrawFramebuffer();
        final int program = GLSMPrograms.getActiveProgram();
        final int vao = GLSMVertexInput.getBoundVAO();
        final int vbo = GLSMBuffers.getBoundVBO();
        final int mode = GLSMQueries.glGetInteger(GL11.GL_MATRIX_MODE);
        GLSMAttribStack.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glPushMatrix();
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glPushMatrix();
        rendering = true;
        shaderView = shaders;
        translucent = false;
        try {
            state.setRenderingWorld(true);
            state.setProjectionMatrix(projection);
            state.setModelViewMatrix(modelView);
            state.setFov((float) Math.toDegrees(2 * Math.atan(1.0 / projection.get(projection.position() + 5))));
            captured.setTickDelta(partialTicks);
            Program.unbind();
            GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
            GLSMMatrices.glLoadMatrix(projection);
            GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
            GLSMMatrices.glLoadMatrix(modelView);
            if (shaders) {
                final boolean newPipeline = primary != main || output != target;
                if (newPipeline) discard();
                prepareEmptyDistantDepth(target);
                if (newPipeline) {
                    final var pack = Iris.getCurrentPack().orElseThrow();
                    pipeline = new DeferredWorldRenderingPipeline(pack.getProgramSet(Iris.getCurrentDimensionName()), () -> target, main);
                    output = target;
                    primary = main;
                }
                manager.setSecondaryPipeline(pipeline);
                target.bindFramebuffer(true);
                GLSMFramebuffer.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GLSMCurrentAttribs.setShaderColor(1, 1, 1, 1);
                pipeline.beginLevelRendering();
                pipeline.setPhase(WorldRenderingPhase.CUSTOM_SKY);
                mc.renderGlobal.renderSky(partialTicks);
                pipeline.setPhase(WorldRenderingPhase.NONE);
                pipeline.renderShadows(mc.entityRenderer, Camera.INSTANCE); // Runs the scope's prepare passes only.
            } else {
                target.bindFramebuffer(true);
            }
            if (!drawScene.getAsBoolean()) return false;
            if (shaders) {
                pipeline.finalizeLevelRendering();
                if (!reportedReady) {
                    Iris.logger.info("Secondary shader camera rendered with {} at {}x{}", Iris.getCurrentPackName(), target.framebufferWidth, target.framebufferHeight);
                    reportedReady = true;
                }
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            if (!shaders) throw failure;
            failedPrimary = main;
            Iris.logger.warn("Secondary shader camera failed for {}; using the unshaded camera", Iris.getCurrentPackName(), failure);
            return false;
        } finally {
            try {
                if (shaders && pipeline != null && pipeline.isWorldPassActive()) pipeline.abortSecondaryView();
            } finally {
                if (shaders) manager.setSecondaryPipeline(null);
                shaderView = false;
                rendering = false;
                try {
                    if (shaders && failedPrimary == main) discardResources();
                } finally {
                    Program.unbind();
                    state.setProjectionMatrix(savedProjection);
                    state.setModelViewMatrix(savedModelView);
                    state.setFov(fov);
                    state.setRenderingWorld(world);
                    captured.setTickDelta(tick);
                    GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
                    GLSMMatrices.glPopMatrix();
                    GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
                    GLSMMatrices.glPopMatrix();
                    GLSMMatrices.glMatrixMode(mode);
                    GLSMMatrices.setTextureMatrix(1, savedLightmapMatrix);
                    GLSMAttribStack.glPopAttrib();
                    GLSMCurrentAttribs.setLightmapTextureCoords(GL13.GL_TEXTURE1, lightX, lightY);
                    GLSMVertexInput.glBindVertexArray(vao);
                    GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                    GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
                    GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
                    GLSMPrograms.glUseProgram(program);
                }
            }
        }
    }

    private static void save(FloatBuffer source, FloatBuffer destination) {
        for (int i = 0; i < 16; i++) destination.put(i, source.get(i));
    }

    static int getEmptyDistantDepth() {
        return emptyDistantDepth.getTextureId();
    }

    private static void prepareEmptyDistantDepth(Framebuffer target) {
        if (emptyDistantDepth == null || distantWidth != target.framebufferWidth || distantHeight != target.framebufferHeight) {
            final int read = GLSMFramebuffer.getReadFramebuffer();
            final int draw = GLSMFramebuffer.getDrawFramebuffer();
            final GlFramebuffer clearTarget = new GlFramebuffer();
            GLSMAttribStack.glPushAttrib(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_SCISSOR_BIT);
            try {
                if (emptyDistantDepth != null) emptyDistantDepth.destroy();
                distantWidth = target.framebufferWidth;
                distantHeight = target.framebufferHeight;
                emptyDistantDepth = new DepthTexture(distantWidth, distantHeight, DepthBufferFormat.DEPTH32F);
                clearTarget.addDepthAttachment(emptyDistantDepth.getTextureId());
                clearTarget.noDrawBuffers();
                RenderSystem.readBuffer(clearTarget.getId(), GL11.GL_NONE);
                clearTarget.bind();
                GLSMEnable.glDisable(GL11.GL_SCISSOR_TEST);
                GLSMDepthStencilAlpha.glDepthMask(true);
                GLSMDepthStencilAlpha.glClearDepth(1);
                GLSMFramebuffer.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            } finally {
                clearTarget.destroy();
                GLSMAttribStack.glPopAttrib();
                GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
                GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            }
        }
    }

    public static void beginTranslucents() {
        if (shaderView && !translucent) {
            pipeline.beginHand(); // Captures depthtex2; no hand is drawn into the scope.
            pipeline.beginTranslucents();
            translucent = true;
        }
    }

    public static void phase(String name) {
        if (shaderView) pipeline.setPhase(WorldRenderingPhase.valueOf(name));
    }

    public static GlProgram<ChunkShaderInterface> terrainProgram(TerrainRenderPass pass, RenderPassConfiguration<?> configuration) {
        return shaderView ? terrainPrograms.getProgramOverride(pass, configuration) : null;
    }

    public static void drawTerrain(int pass, double x, double y, double z) {
        if (!rendering || !hasTerrain()) throw new IllegalStateException("No active secondary terrain pass");
        final CeleritasWorldRenderer renderer = CeleritasWorldRenderer.getInstance();
        final double eyeY = y + Minecraft.getMinecraft().renderViewEntity.getEyeHeight();
        RenderDevice.enterManagedCode();
        try {
            if (pass == 0) {
                phase("TERRAIN_SOLID");
                renderer.drawChunkLayer(BlockRenderLayer.SOLID, x, eyeY, z);
                phase("TERRAIN_CUTOUT_MIPPED");
                renderer.drawChunkLayer(BlockRenderLayer.CUTOUT_MIPPED, x, eyeY, z);
            } else {
                phase("TERRAIN_TRANSLUCENT");
                renderer.drawChunkLayer(BlockRenderLayer.TRANSLUCENT, x, eyeY, z);
            }
        } finally {
            RenderDevice.exitManagedCode();
            phase("NONE");
        }
    }

    public static void release(Framebuffer target) {
        if (target == null || target != output || rendering) return;
        final int read = GLSMFramebuffer.getReadFramebuffer();
        final int draw = GLSMFramebuffer.getDrawFramebuffer();
        GLSMAttribStack.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            discardResources();
        } finally {
            GLSMAttribStack.glPopAttrib();
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        }
    }

    static void discard() {
        failedPrimary = null;
        discardResources();
    }

    private static void discardResources() {
        terrainPrograms.deleteShaders();
        if (emptyDistantDepth != null) {
            emptyDistantDepth.destroy();
            emptyDistantDepth = null;
        }
        final DeferredWorldRenderingPipeline old = pipeline;
        pipeline = null;
        primary = null;
        output = null;
        reportedReady = false;
        if (old != null) old.destroy();
    }
}
