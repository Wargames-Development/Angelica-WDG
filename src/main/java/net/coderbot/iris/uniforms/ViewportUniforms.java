package net.coderbot.iris.uniforms;

import net.coderbot.iris.gl.uniform.UniformHolder;
import net.minecraft.client.shader.Framebuffer;

import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

/**
 * Implements uniforms relating the current viewport
 *
 * @see <a href="https://github.com/IrisShaders/ShaderDoc/blob/master/uniforms.md#viewport">Uniforms: Viewport</a>
 */
public final class ViewportUniforms {
	// cannot be constructed
	private ViewportUniforms() {
	}

	/**
	 * Makes the viewport uniforms available to the given program
	 *
	 * @param uniforms the program to make the uniforms available to
	 */
	public static void addViewportUniforms(UniformHolder uniforms, FrameUpdateNotifier notifier) {
		// TODO: What about the custom scale.composite3 property?
		// NB: It is not safe to cache the render target due to mods like Resolution Control modifying the render target field.
		uniforms
			.uniform1f(PER_FRAME, "viewHeight", () -> notifier.getOutputFramebuffer().framebufferHeight)
			.uniform1f(PER_FRAME, "viewWidth", () -> notifier.getOutputFramebuffer().framebufferWidth)
			.uniform1f(PER_FRAME, "aspectRatio", () -> getAspectRatio(notifier.getOutputFramebuffer()));
	}

	/**
	 * @return the aspect ratio of this view's output framebuffer
	 */
	private static float getAspectRatio(Framebuffer output) {
		return (float) output.framebufferWidth / output.framebufferHeight;
	}
}
