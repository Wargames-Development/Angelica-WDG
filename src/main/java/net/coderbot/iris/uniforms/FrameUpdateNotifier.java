package net.coderbot.iris.uniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Frame updates and shader uniform state for a single world rendering pipeline. */
public class FrameUpdateNotifier {
	private final List<Runnable> listeners;
	private final Supplier<Framebuffer> outputFramebuffer;
	private int frame;
	final MatrixUniforms matrices = new MatrixUniforms(() -> frame);

	public FrameUpdateNotifier() {
		this(() -> Minecraft.getMinecraft().getFramebuffer());
	}

	public FrameUpdateNotifier(Supplier<Framebuffer> outputFramebuffer) {
		this.outputFramebuffer = outputFramebuffer;
		listeners = new ArrayList<>();
	}

	public Framebuffer getOutputFramebuffer() {
		return outputFramebuffer.get();
	}

	public void addListener(Runnable onNewFrame) {
		listeners.add(onNewFrame);
	}

	public void onNewFrame() {
		frame++;
		listeners.forEach(Runnable::run);
	}
}
