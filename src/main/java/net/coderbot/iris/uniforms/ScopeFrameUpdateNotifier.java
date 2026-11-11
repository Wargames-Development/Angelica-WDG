package net.coderbot.iris.uniforms;

import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import java.util.function.Supplier;
import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

/** State owned by the optional Flans scope, never installed on the primary pipeline. */
public final class ScopeFrameUpdateNotifier extends FrameUpdateNotifier {
    private final Supplier<Framebuffer> output;
    private int frame;
    private long lastRender;
    private double frameTime;
    private final ScopeMatrixUniforms matrices = new ScopeMatrixUniforms(() -> frame);

    public ScopeFrameUpdateNotifier(Supplier<Framebuffer> output) { this.output = output; }
    @Override public void onNewFrame() {
        long now = System.nanoTime();
        frameTime = lastRender == 0 ? 0.0 : (now - lastRender) * 1.0e-9;
        lastRender = now; frame = (frame + 1) % 720720; super.onNewFrame();
    }
    void addClockUniforms(UniformHolder uniforms) {
        SystemTimeUniforms.addSystemTimeUniforms(uniforms, () -> frame, () -> frameTime);
    }
    public static Framebuffer framebuffer(FrameUpdateNotifier notifier) {
        return notifier instanceof ScopeFrameUpdateNotifier scope ? scope.output.get() : Minecraft.getMinecraft().getFramebuffer();
    }
    void addViewUniforms(UniformHolder uniforms, PackDirectives directives) {
        uniforms.uniform1f(PER_FRAME, "viewHeight", () -> output.get().framebufferHeight)
            .uniform1f(PER_FRAME, "viewWidth", () -> output.get().framebufferWidth)
            .uniform1f(PER_FRAME, "aspectRatio", () -> (float) output.get().framebufferWidth / output.get().framebufferHeight);
        matrices.addMatrixUniforms(uniforms, directives);
    }
    void addMatrices(UniformHolder uniforms) { matrices.addViewMatrices(uniforms); }
}
