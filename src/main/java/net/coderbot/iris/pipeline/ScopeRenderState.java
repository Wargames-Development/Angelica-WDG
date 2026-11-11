package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import net.coderbot.iris.gbuffer_overrides.state.StateTracker;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import org.lwjgl.BufferUtils;

/** Saved caller state. Scope geometry uses a private VAO, never the caller's client arrays. */
final class ScopeRenderState implements AutoCloseable {
    private static final ThreadLocal<ArrayDeque<Buffers>> BUFFER_POOL = ThreadLocal.withInitial(ArrayDeque::new);
    private static final class Buffers {
        final FloatBuffer projection, modelView;
        final FloatBuffer[] textures = new FloatBuffer[GLStateManager.MAX_TEXTURE_UNITS];
        Buffers() {
            // JOML's Unsafe path requires native addresses. Reuse one direct allocation,
            // including its slices, instead of allocating native buffers for every scope frame.
            FloatBuffer storage = BufferUtils.createFloatBuffer((textures.length + 2) * 16);
            projection = slice(storage, 0); modelView = slice(storage, 1);
            for (int i = 0; i < textures.length; i++) textures[i] = slice(storage, i + 2);
        }
        private static FloatBuffer slice(FloatBuffer storage, int index) {
            FloatBuffer view = storage.duplicate(); view.position(index * 16); view.limit((index + 1) * 16);
            return view.slice();
        }
    }
    private static Buffers takeBuffers() {
        Buffers available = BUFFER_POOL.get().pollFirst();
        return available == null ? new Buffers() : available;
    }
    private final Buffers buffers = takeBuffers();
    private boolean closed;
    private net.coderbot.iris.layer.GbufferPrograms.ScopedState phases;
    private final RenderingState view = RenderingState.INSTANCE;
    private final CapturedRenderingState captured = CapturedRenderingState.INSTANCE;
    private final FloatBuffer projection = buffers.projection;
    private final FloatBuffer modelView = buffers.modelView;
    private final FloatBuffer[] textureMatrices = buffers.textures;
    private final int[] samplers = new int[GLStateManager.MAX_TEXTURE_UNITS];
    private final Vector3d camera = new Vector3d(view.getCameraPosition());
    private final Vector4f entityColour = new Vector4f(captured.getCurrentEntityColor());
    private final float fov = view.getFov(), tick = captured.getTickDelta();
    private final float lightX = GLSMConfig.lastBrightnessX, lightY = GLSMConfig.lastBrightnessY;
    private final int entity = captured.getCurrentRenderedEntity(), item = captured.getCurrentRenderedItem();
    private final int blockEntity = captured.getCurrentRenderedBlockEntity();
    private final Class<?> renderable = com.gtnewhorizons.angelica.rendering.tesr.TesrAttribution.currentRenderable;
    private final boolean albedo = StateTracker.INSTANCE.albedoSampler, lightmap = StateTracker.INSTANCE.lightmapSampler;
    private final int read = GLStateManager.getReadFramebuffer(), draw = GLStateManager.getDrawFramebuffer();
    private final int program = GLStateManager.getActiveProgram(), vao = GLStateManager.getBoundVAO(), vbo = GLStateManager.getBoundVBO();
    private final int activeTexture = GLStateManager.getActiveTextureUnit();
    private final int mode = GLStateManager.glGetInteger(GL11.GL_MATRIX_MODE);

    ScopeRenderState() {
        // Draw-buffer selection belongs to each FBO; the attrib cache is global and
        // may still contain GL_NONE from a depth-only offscreen pass. Save the
        // window's actual selection so popping the scope cannot disable presentation.
        if (draw == 0) GLStateManager.glDrawBuffer(
            com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND.getInteger(GL11.GL_DRAW_BUFFER));
        for (int i = 0; i < 16; i++) {
            projection.put(i, view.getProjectionBuffer().get(i));
            modelView.put(i, view.getModelViewBuffer().get(i));
        }
        for (int unit = 0; unit < samplers.length; unit++) {
            textureMatrices[unit].clear();
            GLStateManager.getTextures().getTextureUnitMatrix(unit).get(textureMatrices[unit]);
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            samplers[unit] = GLStateManager.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + activeTexture);
        GLStateManager.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GLStateManager.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT | GL11.GL_CLIENT_PIXEL_STORE_BIT);
        GLStateManager.glMatrixMode(GL11.GL_PROJECTION); GLStateManager.glPushMatrix();
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW); GLStateManager.glPushMatrix();
        phases = net.coderbot.iris.layer.GbufferPrograms.beginScopedView();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
        Program.unbind();
        view.setProjectionMatrix(projection); view.setModelViewMatrix(modelView);
        view.setFov(fov); view.getCameraPosition().set(camera); captured.setTickDelta(tick);
        captured.setCurrentEntityAndItem(entity, item); captured.setCurrentBlockEntity(blockEntity);
        captured.setCurrentEntityColor(entityColour.x, entityColour.y, entityColour.z, entityColour.w);
        com.gtnewhorizons.angelica.rendering.tesr.TesrAttribution.currentRenderable = renderable;
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW); GLStateManager.glPopMatrix();
        GLStateManager.glMatrixMode(GL11.GL_PROJECTION); GLStateManager.glPopMatrix();
        for (int unit = 0; unit < samplers.length; unit++) {
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GLStateManager.glMatrixMode(GL11.GL_TEXTURE); GLStateManager.glLoadMatrix(textureMatrices[unit]);
            RenderSystem.bindSamplerToUnit(unit, samplers[unit]);
        }
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + activeTexture);
        GLStateManager.glMatrixMode(mode);
        GLStateManager.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GLStateManager.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GLStateManager.glPopAttrib();
        GLStateManager.glBindVertexArray(vao); GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLStateManager.glPopClientAttrib();
        GLStateManager.setLightmapTextureCoords(GL13.GL_TEXTURE1, lightX, lightY);
        StateTracker.INSTANCE.albedoSampler = albedo; StateTracker.INSTANCE.lightmapSampler = lightmap;
        GLStateManager.glUseProgram(program);
        } finally { try { phases.close(); } finally { BUFFER_POOL.get().addFirst(buffers); } }
    }
}
