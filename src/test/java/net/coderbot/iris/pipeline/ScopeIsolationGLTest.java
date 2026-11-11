package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.GLCoreTest;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import net.coderbot.iris.Iris;
import net.coderbot.iris.layer.GbufferPrograms;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@GLCoreTest
class ScopeIsolationGLTest {
    @Test void publicPipelineRemainsPrimaryDuringScopedRendering() throws Exception {
        Field managerField = Iris.class.getDeclaredField("pipelineManager"); managerField.setAccessible(true);
        Field scopeField = FlansScopePass.class.getDeclaredField("pipeline"); scopeField.setAccessible(true);
        Field renderingField = FlansScopePass.class.getDeclaredField("rendering"); renderingField.setAccessible(true);
        Object oldManager = managerField.get(null), oldScope = scopeField.get(null), oldRendering = renderingField.get(null);
        DeferredWorldRenderingPipeline primary = mock(DeferredWorldRenderingPipeline.class);
        DeferredWorldRenderingPipeline scope = mock(DeferredWorldRenderingPipeline.class);
        PipelineManager manager = new PipelineManager(dimension -> primary);
        manager.preparePipeline("scope-isolation-test");
        try {
            managerField.set(null, manager); scopeField.set(null, scope); renderingField.set(null, true);
            assertSame(primary, manager.getPipelineNullable()); assertSame(primary, manager.getPipeline().orElse(null));
            assertSame(scope, FlansScopePass.renderPipeline());
            renderingField.set(null, false); assertSame(primary, FlansScopePass.renderPipeline());
        } finally {
            renderingField.set(null, oldRendering); scopeField.set(null, oldScope); managerField.set(null, oldManager);
        }
    }

    @Test void exceptionRestoresCallerVertexBindingsMatricesAndEntityUniforms() {
        int primaryVao = GLStateManager.glGenVertexArrays(), scopeVao = GLStateManager.glGenVertexArrays();
        int primaryVbo = GLStateManager.glGenBuffers(), scopeVbo = GLStateManager.glGenBuffers();
        int sampler = RenderSystem.genSampler();
        GLStateManager.glBindVertexArray(primaryVao); GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, primaryVbo);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 64L, GL15.GL_STATIC_DRAW);
        GLStateManager.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE2); RenderSystem.bindSamplerToUnit(2, sampler);
        Matrix4f projection = new Matrix4f().scaling(2);
        projection.get(RenderingState.INSTANCE.getProjectionBuffer());
        RenderingState.INSTANCE.setProjectionMatrix(RenderingState.INSTANCE.getProjectionBuffer());
        CapturedRenderingState.INSTANCE.setCurrentEntityAndItem(17, 23);
        CapturedRenderingState.INSTANCE.setTickDelta(0.25f);
        Matrix4f texture = new Matrix4f(GLStateManager.getTextures().getTextureUnitMatrix(2));
        try {
            assertThrows(IllegalStateException.class, () -> {
                try (ScopeRenderState saved = new ScopeRenderState()) {
                    GLStateManager.glBindVertexArray(scopeVao); GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, scopeVbo);
                    GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 64L, GL15.GL_STATIC_DRAW);
                    GLStateManager.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
                    GLStateManager.glActiveTexture(GL13.GL_TEXTURE2); RenderSystem.bindSamplerToUnit(2, 0);
                    GLStateManager.glMatrixMode(GL11.GL_TEXTURE); GLStateManager.glLoadIdentity(); GLStateManager.glScalef(7, 7, 7);
                    CapturedRenderingState.INSTANCE.setCurrentEntityAndItem(44, 55);
                    CapturedRenderingState.INSTANCE.setTickDelta(0.75f);
                    RenderingState.INSTANCE.getProjectionMatrix().scaling(9);
                    throw new IllegalStateException("scope draw failed");
                }
            });
            assertEquals(primaryVao, GLStateManager.getBoundVAO()); assertEquals(primaryVbo, GLStateManager.getBoundVBO());
            assertEquals(primaryVbo, GLStateManager.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING));
            assertEquals(2, GLStateManager.getActiveTextureUnit()); assertEquals(sampler, GL11.glGetInteger(GL33.GL_SAMPLER_BINDING));
            assertEquals(projection, RenderingState.INSTANCE.getProjectionMatrix());
            assertEquals(texture, GLStateManager.getTextures().getTextureUnitMatrix(2));
            assertEquals(17, CapturedRenderingState.INSTANCE.getCurrentRenderedEntity());
            assertEquals(23, CapturedRenderingState.INSTANCE.getCurrentRenderedItem());
            assertEquals(0.25f, CapturedRenderingState.INSTANCE.getTickDelta());
        } finally {
            RenderSystem.bindSamplerToUnit(2, 0); RenderSystem.destroySampler(sampler);
            GLStateManager.glBindVertexArray(0); GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glDeleteBuffers(primaryVbo); GLStateManager.glDeleteBuffers(scopeVbo);
            GLStateManager.glDeleteVertexArrays(primaryVao); GLStateManager.glDeleteVertexArrays(scopeVao);
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0); GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        }
    }

    @Test void outputCannotAliasPrimaryFramebufferOrTextures() {
        assertTrue(FlansScopePass.independentOutput(1, 2, 3, 4, 5, 6));
        assertFalse(FlansScopePass.independentOutput(4, 2, 3, 4, 5, 6));
        assertFalse(FlansScopePass.independentOutput(1, 5, 3, 4, 5, 6));
        assertFalse(FlansScopePass.independentOutput(1, 2, 6, 4, 5, 6));
        assertFalse(FlansScopePass.independentOutput(0, 2, 3, 4, 5, 6));
    }

    @Test void failedOffscreenDrawDoesNotChangePrimaryPixels() {
        int primaryTexture = GLStateManager.glGenTextures(), scopeTexture = GLStateManager.glGenTextures();
        int primary = GLStateManager.glGenFramebuffers(), scope = GLStateManager.glGenFramebuffers();
        try {
            colourTarget(primary, primaryTexture); colourTarget(scope, scopeTexture);
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, primary);
            GLStateManager.glClearColor(0.125f, 0.25f, 0.5f, 1); GLStateManager.glClear(GL11.GL_COLOR_BUFFER_BIT);
            assertThrows(IllegalStateException.class, () -> {
                try (ScopeRenderState saved = new ScopeRenderState()) {
                    GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, scope);
                    GLStateManager.glClearColor(0.75f, 0.5f, 0.25f, 1); GLStateManager.glClear(GL11.GL_COLOR_BUFFER_BIT);
                    throw new IllegalStateException("scope draw failed");
                }
            });
            assertEquals(primary, GLStateManager.getReadFramebuffer()); assertEquals(primary, GLStateManager.getDrawFramebuffer());
            java.nio.FloatBuffer pixel = org.lwjgl.BufferUtils.createFloatBuffer(4);
            GLStateManager.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
            assertEquals(0.125f, pixel.get(0), 1.0f / 255); assertEquals(0.25f, pixel.get(1), 1.0f / 255);
            assertEquals(0.5f, pixel.get(2), 1.0f / 255);
        } finally {
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLStateManager.glDeleteFramebuffers(primary); GLStateManager.glDeleteFramebuffers(scope);
            GLStateManager.glDeleteTextures(primaryTexture); GLStateManager.glDeleteTextures(scopeTexture);
        }
    }
    @Test void scopePreservesWindowDrawBufferWhenCacheCameFromAnOffscreenPass() {
        int windowBuffer = GLStateManager.DEFAULT_DRAW_BUFFER;
        int depthFramebuffer = GLStateManager.glGenFramebuffers(), colourFramebuffer = GLStateManager.glGenFramebuffers();
        int depth = depthTarget(depthFramebuffer), colour = GLStateManager.glGenTextures();
        try {
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND.drawBuffer(windowBuffer);
            // Draw-buffer selection belongs to each FBO, but GLSM's attrib stack has one cached scalar.
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthFramebuffer);
            GLStateManager.glDrawBuffer(GL11.GL_NONE);
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            assertEquals(GL11.GL_NONE, GLStateManager.glGetInteger(GL11.GL_DRAW_BUFFER));
            assertEquals(windowBuffer, com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND.getInteger(GL11.GL_DRAW_BUFFER));
            try (ScopeRenderState saved = new ScopeRenderState()) {
                colourTarget(colourFramebuffer, colour);
                GLStateManager.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            }
            assertEquals(0, GLStateManager.getDrawFramebuffer());
            assertEquals(windowBuffer, com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND.getInteger(GL11.GL_DRAW_BUFFER));
        } finally {
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLStateManager.glDrawBuffer(windowBuffer);
            GLStateManager.glDeleteFramebuffers(depthFramebuffer); GLStateManager.glDeleteFramebuffers(colourFramebuffer);
            GLStateManager.glDeleteTextures(depth); GLStateManager.glDeleteTextures(colour);
        }
    }

    @Test void everyScopeFrameClearsOnlyPrivateDepthDespiteInheritedMasks() {
        int main = GLStateManager.glGenFramebuffers(), scope = GLStateManager.glGenFramebuffers();
        int mainDepth = depthTarget(main), scopeDepth = depthTarget(scope);
        try {
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, main);
            GLStateManager.glDisable(GL11.GL_SCISSOR_TEST); GLStateManager.glDepthMask(true);
            GLStateManager.glClearDepth(0.375); GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            for (int frame = 0; frame < 8; frame++) {
                GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, scope);
                GLStateManager.glDisable(GL11.GL_SCISSOR_TEST); GLStateManager.glDepthMask(true);
                GLStateManager.glClearDepth(0.1 + frame * 0.05); GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, main);
                GLStateManager.glEnable(GL11.GL_SCISSOR_TEST); GLStateManager.glScissor(0, 0, 0, 0);
                GLStateManager.glDepthMask(false);
                try (ScopeRenderState saved = new ScopeRenderState()) {
                    FlansScopePass.clearScopeDepth(scope);
                    assertEquals(1.0f, readDepth(3, 3), 1.0e-6f);
                }
                assertEquals(main, GLStateManager.getDrawFramebuffer());
                assertEquals(0.375f, readDepth(3, 3), 1.0e-6f);
                assertTrue(GLStateManager.glIsEnabled(GL11.GL_SCISSOR_TEST));
                assertFalse(GLStateManager.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            }
        } finally {
            GLStateManager.glDisable(GL11.GL_SCISSOR_TEST); GLStateManager.glDepthMask(true);
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLStateManager.glDeleteFramebuffers(main); GLStateManager.glDeleteFramebuffers(scope);
            GLStateManager.glDeleteTextures(mainDepth); GLStateManager.glDeleteTextures(scopeDepth);
        }
    }
    private static float readDepth(int x, int y) {
        java.nio.FloatBuffer pixel = org.lwjgl.BufferUtils.createFloatBuffer(1);
        GLStateManager.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixel);
        return pixel.get(0);
    }
    private static int depthTarget(int framebuffer) {
        int texture = GLStateManager.glGenTextures();
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GLStateManager.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, 4, 4, 0,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GLStateManager.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
        GLStateManager.glDrawBuffer(GL11.GL_NONE); GLStateManager.glReadBuffer(GL11.GL_NONE);
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GLStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
        return texture;
    }

    private static void colourTarget(int framebuffer, int texture) {
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GLStateManager.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 4, 4, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GLStateManager.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GLStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
    }

    @Test void failedScopeCannotLeaveEntityPhaseActive() {
        try (GbufferPrograms.ScopedState original = GbufferPrograms.beginScopedView()) {
            assertThrows(IllegalStateException.class, () -> {
                try (ScopeRenderState saved = new ScopeRenderState()) {
                    GbufferPrograms.beginEntityLoop();
                    assertTrue(GbufferPrograms.isEntityLoopActive());
                    throw new IllegalStateException("scope entity renderer failed");
                }
            });
            assertFalse(GbufferPrograms.isEntityLoopActive());
            GbufferPrograms.beginEntities(); GbufferPrograms.endEntities();
        }
    }
}
