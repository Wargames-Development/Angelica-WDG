package net.coderbot.iris.uniforms;

import com.gtnewhorizons.angelica.rendering.RenderingState;
import net.coderbot.iris.gl.uniform.UniformHolder;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class PreviousMatrixSharingTest {

    private final Matrix4f source = new Matrix4f();

    @Test
    void previousProjectionIsSharedByProgramsWithinOneView() {
        FrameUpdateNotifier mainView = new FrameUpdateNotifier();
        FrameUpdateNotifier scopeView = new FrameUpdateNotifier();
        Supplier<Matrix4fc> mainPrevious = previousProjection(mainView);
        Supplier<Matrix4fc> scopePrevious = previousProjection(scopeView);
        assertSame(mainPrevious, previousProjection(mainView));
        assertNotSame(mainPrevious, scopePrevious);

        Matrix4f projection = RenderingState.INSTANCE.getProjectionMatrix();
        Matrix4f saved = new Matrix4f(projection);
        try {
            mainView.onNewFrame();
            projection.scaling(2);
            mainPrevious.get();
            scopeView.onNewFrame();
            projection.scaling(8);
            scopePrevious.get();

            mainView.onNewFrame();
            projection.scaling(3);
            assertEquals(2f, mainPrevious.get().m00());
            scopeView.onNewFrame();
            projection.scaling(12);
            assertEquals(8f, scopePrevious.get().m00());
            assertEquals(2f, mainPrevious.get().m00());
        } finally {
            projection.set(saved);
        }
    }

    @Test
    void advancesOncePerFrameAndReturnsLastFrameValue() {
        AtomicInteger frame = new AtomicInteger();
        final MatrixUniforms.Previous prev = new MatrixUniforms.Previous(() -> source, frame::get);

        source.translation(1f, 0f, 0f);
        assertEquals(new Matrix4f(), new Matrix4f(prev.get()), "first frame previous is identity");

        frame.incrementAndGet();
        source.translation(2f, 0f, 0f);
        final Matrix4fc frame2First = new Matrix4f(prev.get());
        assertEquals(new Matrix4f().translation(1f, 0f, 0f), frame2First, "frame 2 sees frame 1 value");

        source.translation(3f, 0f, 0f);
        assertEquals(frame2First, new Matrix4f(prev.get()));

        frame.incrementAndGet();
        assertEquals(new Matrix4f().translation(2f, 0f, 0f), new Matrix4f(prev.get()), "frame 3 sees the value captured at frame 2's first read, not later mid-frame mutations");
    }

    @SuppressWarnings("unchecked")
    private static Supplier<Matrix4fc> previousProjection(FrameUpdateNotifier view) {
        AtomicReference<Supplier<Matrix4fc>> supplier = new AtomicReference<>();
        UniformHolder holder = (UniformHolder) Proxy.newProxyInstance(
            UniformHolder.class.getClassLoader(), new Class<?>[] { UniformHolder.class }, (proxy, method, args) -> {
                if (method.getName().equals("uniformMatrix") && args[1].equals("gbufferPreviousProjection")) {
                    supplier.set((Supplier<Matrix4fc>) args[2]);
                }
                return proxy;
            });
        view.matrices.addViewMatrices(holder);
        return supplier.get();
    }
}
