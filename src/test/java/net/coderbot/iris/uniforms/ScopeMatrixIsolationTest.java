package net.coderbot.iris.uniforms;

import com.gtnewhorizons.angelica.rendering.RenderingState;
import net.coderbot.iris.gl.uniform.UniformHolder;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ScopeMatrixIsolationTest {
    @Test void scopeFramesCannotAdvancePrimaryHistory() {
        ScopeFrameUpdateNotifier first = new ScopeFrameUpdateNotifier(() -> null);
        ScopeFrameUpdateNotifier second = new ScopeFrameUpdateNotifier(() -> null);
        Supplier<Matrix4fc> a = previous(first), b = previous(second);
        Matrix4f projection = RenderingState.INSTANCE.getProjectionMatrix();
        Matrix4f saved = new Matrix4f(projection);
        MatrixUniforms.Previous main = MatrixUniforms.Previous.shared("scopeIsolationPrimary", () -> projection);
        try {
            SystemTimeUniforms.COUNTER.beginFrame(); projection.scaling(2); main.get();
            first.onNewFrame(); projection.scaling(8); a.get();
            second.onNewFrame(); projection.scaling(12); b.get();
            first.onNewFrame(); projection.scaling(16); assertEquals(8f, a.get().m00());
            assertNotSame(a, b); assertSame(a, previous(first));
            SystemTimeUniforms.COUNTER.beginFrame(); projection.scaling(3);
            assertEquals(2f, main.get().m00());
            second.onNewFrame(); projection.scaling(24); assertEquals(12f, b.get().m00());
            assertEquals(2f, main.get().m00());
        } finally { projection.set(saved); }
    }
    @Test void scopeJitterClockCannotAdvanceThePrimaryClock() {
        ScopeFrameUpdateNotifier scope = new ScopeFrameUpdateNotifier(() -> null);
        AtomicReference<java.util.function.IntSupplier> counter = new AtomicReference<>();
        UniformHolder holder = (UniformHolder) Proxy.newProxyInstance(UniformHolder.class.getClassLoader(), new Class<?>[]{UniformHolder.class},
            (proxy, method, args) -> { if (method.getName().equals("uniform1i") && args[1].equals("frameCounter")) counter.set((java.util.function.IntSupplier) args[2]); return proxy; });
        scope.addClockUniforms(holder);
        int primaryFrame = SystemTimeUniforms.COUNTER.getAsInt();
        assertEquals(0, counter.get().getAsInt());
        scope.onNewFrame(); assertEquals(1, counter.get().getAsInt());
        scope.onNewFrame(); assertEquals(2, counter.get().getAsInt());
        assertEquals(primaryFrame, SystemTimeUniforms.COUNTER.getAsInt());
        SystemTimeUniforms.COUNTER.beginFrame(); assertEquals(2, counter.get().getAsInt());
    }

    @SuppressWarnings("unchecked") private Supplier<Matrix4fc> previous(ScopeFrameUpdateNotifier scope) {
        AtomicReference<Supplier<Matrix4fc>> result = new AtomicReference<>();
        UniformHolder holder = (UniformHolder) Proxy.newProxyInstance(UniformHolder.class.getClassLoader(), new Class<?>[]{UniformHolder.class},
            (proxy, method, args) -> { if (method.getName().equals("uniformMatrix") && args[1].equals("gbufferPreviousProjection")) result.set((Supplier<Matrix4fc>) args[2]); return proxy; });
        scope.addMatrices(holder); return result.get();
    }
}
