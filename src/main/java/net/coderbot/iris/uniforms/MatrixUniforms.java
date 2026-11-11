package net.coderbot.iris.uniforms;

import com.gtnewhorizons.angelica.rendering.RenderingState;
import net.coderbot.iris.compat.dh.DHCompat;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.pipeline.ShadowRenderer;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.shadow.ShadowMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

final class MatrixUniforms {
	private static final Matrix4f GBUFFER_MODELVIEW_SCRATCH = new Matrix4f();
    // Preserve 2.2.14's shared primary history and the clock used by ProgramUniforms.
    private static final MatrixUniforms PRIMARY = new MatrixUniforms(SystemTimeUniforms.COUNTER);

    static MatrixUniforms primary() {
        return PRIMARY;
    }
	private final Previous modelView;
	private final Previous projection;
	private final Previous dhProjection;

	MatrixUniforms(IntSupplier frameIndex) {
		modelView = new Previous(MatrixUniforms::getGbufferModelView, frameIndex);
		projection = new Previous(RenderingState.INSTANCE::getProjectionMatrix, frameIndex);
		dhProjection = new Previous(DHCompat::getProjection, frameIndex);
	}

	private static Matrix4fc getGbufferModelView() {
		final Matrix4fc raw = RenderingState.INSTANCE.getModelViewMatrix();
		final Entity view = Minecraft.getMinecraft().renderViewEntity;
		if (view == null) {
			return raw;
		}
		return GBUFFER_MODELVIEW_SCRATCH.set(raw).translate(0f, view.getEyeHeight(), 0f);
	}

	void addMatrixUniforms(UniformHolder uniforms, PackDirectives directives) {
		addViewMatrices(uniforms);
		addShadowMatrix(uniforms, "ModelView", () -> ShadowRenderer.createShadowModelView(directives.getSunPathRotation(), directives.getShadowDirectives().getIntervalSize()).peek().getModel());
		addShadowMatrix(uniforms, "Projection", () -> ShadowMatrices.createOrthoMatrix(directives.getShadowDirectives().getDistance(), directives.getShadowDirectives().getNearPlane() < 0 ? -DHCompat.getRenderDistance() : directives.getShadowDirectives().getNearPlane(),
			directives.getShadowDirectives().getFarPlane() < 0 ? DHCompat.getRenderDistance() : directives.getShadowDirectives().getFarPlane()));
	}

	void addViewMatrices(UniformHolder uniforms) {
		addMatrix(uniforms, "gbuffer", "ModelView", modelView);
		// TODO: In some cases, gbufferProjectionInverse takes on a value much different than OptiFine...
		// We need to audit Mojang's linear algebra.
		addMatrix(uniforms, "gbuffer", "Projection", projection);
		addMatrix(uniforms, "dh", "Projection", dhProjection);
	}

	private static void addMatrix(UniformHolder uniforms, String prefix, String name, Previous history) {
		uniforms
			.uniformMatrix(PER_FRAME, prefix + name, history.parent)
			.uniformMatrix(PER_FRAME, prefix + name + "Inverse", new Inverted(history.parent))
			.uniformMatrix(PER_FRAME, prefix + "Previous" + name, history);
	}


	private static void addShadowMatrix(UniformHolder uniforms, String name, Supplier<Matrix4fc> supplier) {
		uniforms
				.uniformMatrix(PER_FRAME, "shadow" + name, supplier)
				.uniformMatrix(PER_FRAME, "shadow" + name + "Inverse", new Inverted(supplier));
	}

	private static class Inverted implements Supplier<Matrix4fc> {
		private final Supplier<Matrix4fc> parent;

		Inverted(Supplier<Matrix4fc> parent) {
			this.parent = parent;
		}

		@Override
		public Matrix4f get() {
			// PERF: Don't copy + allocate this matrix every time?
            final Matrix4f copy = new Matrix4f(parent.get());

			copy.invert();

			return copy;
		}
	}


	static final class Previous implements Supplier<Matrix4fc> {
		private final Supplier<Matrix4fc> parent;
		private final IntSupplier frameIndex;
		private final Matrix4f previous = new Matrix4f();
		private final Matrix4f current = new Matrix4f();
		private int lastFrame = -1;

		Previous(Supplier<Matrix4fc> parent, IntSupplier frameIndex) {
			this.parent = parent;
			this.frameIndex = frameIndex;
		}

		@Override
		public Matrix4fc get() {
			final int frame = frameIndex.getAsInt();
			if (frame != lastFrame) {
				lastFrame = frame;
				previous.set(current);
				current.set(parent.get());
			}
			return previous;
		}
	}
}
