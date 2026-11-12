package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.coderbot.iris.gl.program.Program;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import java.nio.FloatBuffer;

/** A geometry-only routing guard. Shared program uniforms are restored before the caller resumes.
 * RenderingState and frame/history suppliers continue to describe the enclosing scene throughout. */
final class EntityGeometryPass implements AutoCloseable {
    private final ScopeRenderState state;
    private final Program program;
    private final Runnable alphaState,blendState,depthState;
    private final Runnable custom,alphaOverride;
    private final int framebuffer;
    private final int[] viewport = new int[4];
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer inverse = BufferUtils.createFloatBuffer(16);
    private final int[] locations = new int[2];
    private final FloatBuffer[] previous = {BufferUtils.createFloatBuffer(16), BufferUtils.createFloatBuffer(16)};
    private boolean saved, binding, closed,initialised;

    EntityGeometryPass(Program program, Runnable custom, Runnable alphaOverride) {
        this.program = program; this.custom = custom;this.alphaOverride=alphaOverride;
        framebuffer = GLStateManager.getDrawFramebuffer();
        if (framebuffer == 0) throw new IllegalArgumentException("Entity capture requires a private framebuffer");
        java.nio.IntBuffer values = BufferUtils.createIntBuffer(16);
        GLStateManager.glGetInteger(GL11.GL_VIEWPORT, values);
        for (int i = 0; i < 4; i++) viewport[i] = values.get(i);
        GLStateManager.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        new Matrix4f(projection).invert().get(inverse);
        state = new ScopeRenderState();
        alphaState=net.coderbot.iris.gl.blending.AlphaTestStorage.suspendOverrides();
        blendState=net.coderbot.iris.gl.blending.BlendModeStorage.suspendOverrides();
        depthState=net.coderbot.iris.gl.blending.DepthColorStorage.suspendOverrides();
    }

    void use() {
        if (binding || closed) return;
        binding = true;
        try {
            if(!initialised) {
                // Detach the caller's uniform listeners only after the routing guard is installed.
                Program.unbind();alphaOverride.run();
                GLStateManager.glColorMask(true,true,true,true);GLStateManager.glDepthMask(true);
                net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntityAndItem(-1,0);
                net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentBlockEntity(0);
                net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntityColor(0,0,0,0);
                initialised=true;
            }
            GLStateManager.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            // Discard the entity shader's extra MRT outputs; the actor compositor owns material/history markers.
            GLStateManager.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GLStateManager.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            program.use(); custom.run();
            if (!saved) {
                String[] names = {"gbufferProjection", "gbufferProjectionInverse"};
                for (int i = 0; i < names.length; i++) {
                    locations[i] = GLStateManager.glGetUniformLocation(program.getProgramId(), names[i]);
                    if (locations[i] >= 0) GLStateManager.glGetUniform(program.getProgramId(), locations[i], previous[i]);
                }
                saved = true;
            }
            GLStateManager.glUniformMatrix4(locations[0], false, projection);
            GLStateManager.glUniformMatrix4(locations[1], false, inverse);
            // The capture is transparent black: RGB and coverage must accumulate separately.
            GLStateManager.glEnable(GL11.GL_BLEND);
            GLStateManager.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } finally { binding = false; }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            if (saved) {
                GLStateManager.glUseProgram(program.getProgramId());
                for (int i = 0; i < locations.length; i++)
                    GLStateManager.glUniformMatrix4(locations[i], false, previous[i]);
            }
        } finally {
            try {
                net.coderbot.iris.gl.blending.AlphaTestOverride.restore();
                net.coderbot.iris.gl.blending.BlendModeOverride.restore();
                net.coderbot.iris.gl.blending.DepthColorStorage.unlockDepthColor();
                state.close();
            } finally { depthState.run();blendState.run();alphaState.run(); }
        }
    }
}
