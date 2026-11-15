package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.GLCoreTest;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.FloatBuffer;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional cross-repository check using the real FMUR jar and Angelica's actual matrix state. */
@GLCoreTest
class FmurCameraStateGLTest {
    @Test void companionRestoresSceneWithoutReplacingCallerStacks() throws Exception {
        String runtime = System.getProperty("fmur.camera.runtime");
        assumeTrue(runtime != null, "Supply -PfmurCameraRuntime=<FMUR jar or classes directory>");
        RenderingState scene = RenderingState.INSTANCE;
        Matrix4f savedSceneP = new Matrix4f(scene.getProjectionMatrix());
        Matrix4f savedSceneM = new Matrix4f(scene.getModelViewMatrix());
        Matrix4f savedLiveP = new Matrix4f(GLStateManager.getProjectionMatrix());
        Matrix4f savedLiveM = new Matrix4f(GLStateManager.getModelViewMatrix());
        int savedMode = GLStateManager.glGetInteger(GL11.GL_MATRIX_MODE);
        float savedFov = scene.getFov();
        try (CompanionLoader loader = new CompanionLoader(Paths.get(runtime).toUri().toURL())) {
            Class<?> compat = loader.loadClass("com.flansmod.client.ScopeRenderCompatibility");
            Class<?> state = loader.loadClass("com.flansmod.client.ScopeRenderCompatibility$RenderState");
            bind(compat, "renderingState", scene);
            for (String name : new String[]{"Projection", "ModelView"}) {
                bind(compat, "renderingStateGet" + name + "Buffer", RenderingState.class.getMethod("get" + name + "Buffer"));
                bind(compat, "renderingStateSet" + name + "Matrix", RenderingState.class.getMethod("set" + name + "Matrix", FloatBuffer.class));
                // Also runs against the previous jar to demonstrate the original failure.
                optional(compat, "renderingStateGet" + name + "Matrix", RenderingState.class.getMethod("get" + name + "Matrix"));
                optional(compat, "angelicaSet" + name + "Matrix", GLStateManager.class.getMethod("set" + name + "Matrix", Matrix4fc.class));
            }
            bind(compat, "renderingStateGetFov", RenderingState.class.getMethod("getFov"));
            bind(compat, "renderingStateSetFov", RenderingState.class.getMethod("setFov", float.class));
            Constructor<?> constructor = state.getDeclaredConstructor(); constructor.setAccessible(true);
            Method capture = compat.getDeclaredMethod("captureRenderingState", state); capture.setAccessible(true);
            Method restore = compat.getDeclaredMethod("restoreRenderingState", state); restore.setAccessible(true);
            for (int frame = 0; frame < 24; frame++) {
                Matrix4f sceneP = new Matrix4f().perspective((float)Math.toRadians(70), 1.6f, .05f, 1024);
                Matrix4f sceneM = new Matrix4f().rotateY(frame * .1f).translate(0, -1.62f, 0);
                Matrix4f liveP = frame % 2 == 0 ? new Matrix4f().perspective(.2f, 1, 1, 100)
                    : new Matrix4f().ortho(0, 1920, 1080, 0, 1000, 3000);
                Matrix4f liveM = new Matrix4f(sceneM).translate(frame * 10, 40, -200);
                scene.setProjectionMatrix(buffer(sceneP)); scene.setModelViewMatrix(buffer(sceneM)); scene.setFov(70);
                GLStateManager.setProjectionMatrix(liveP); GLStateManager.setModelViewMatrix(liveM);
                Object snapshot = constructor.newInstance(); capture.invoke(null, snapshot);
                GLStateManager.glMatrixMode(GL11.GL_PROJECTION); GLStateManager.glPushMatrix();
                GLStateManager.glMatrixMode(GL11.GL_MODELVIEW); GLStateManager.glPushMatrix();
                try {
                    GLStateManager.setProjectionMatrix(new Matrix4f().scaling(7));
                    GLStateManager.setModelViewMatrix(new Matrix4f().translation(-9000, 200, 3000));
                    scene.setProjectionMatrix(buffer(new Matrix4f().scaling(3)));
                    scene.setModelViewMatrix(buffer(new Matrix4f().translation(9, 8, 7))); scene.setFov(15);
                    if (frame % 3 == 0) throw new IllegalArgumentException("capture failure");
                } catch (IllegalArgumentException expected) {
                    assertEquals("capture failure", expected.getMessage());
                } finally {
                    GLStateManager.glMatrixMode(GL11.GL_MODELVIEW); GLStateManager.glPopMatrix();
                    GLStateManager.glMatrixMode(GL11.GL_PROJECTION); GLStateManager.glPopMatrix();
                    GLStateManager.glMatrixMode(GL11.GL_TEXTURE);
                    restore.invoke(null, snapshot);
                }
                assertTrue(liveP.equals(GLStateManager.getProjectionMatrix(), .00001f), "live projection replaced by the world snapshot");
                assertTrue(liveM.equals(GLStateManager.getModelViewMatrix(), .00001f), "live transform replaced by the world snapshot");
                assertTrue(sceneP.equals(scene.getProjectionMatrix(), .00001f));
                assertTrue(sceneM.equals(scene.getModelViewMatrix(), .00001f));
                assertEquals(70, scene.getFov()); assertEquals(GL11.GL_TEXTURE, GLStateManager.glGetInteger(GL11.GL_MATRIX_MODE));
            }
        } finally {
            scene.setProjectionMatrix(buffer(savedSceneP)); scene.setModelViewMatrix(buffer(savedSceneM)); scene.setFov(savedFov);
            GLStateManager.setProjectionMatrix(savedLiveP); GLStateManager.setModelViewMatrix(savedLiveM);
            GLStateManager.glMatrixMode(savedMode);
        }
    }
    private static FloatBuffer buffer(Matrix4fc matrix) { return matrix.get(BufferUtils.createFloatBuffer(16)); }
    private static void bind(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private static void optional(Class<?> type, String name, Object value) throws Exception {
        try { bind(type, name, value); } catch (NoSuchFieldException removedLegacySetter) { }
    }
    private static final class CompanionLoader extends URLClassLoader {
        CompanionLoader(URL runtime) { super(new URL[]{runtime}, FmurCameraStateGLTest.class.getClassLoader()); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.flansmod.client.ScopeRenderCompatibility")) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                URL resource = findResource(name.replace('.', '/') + ".class");
                if (resource == null) throw new ClassNotFoundException(name);
                try (InputStream input = resource.openStream()) {
                    ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0);
                    new GLSMRedirector().transformClassNode(name, node);
                    ClassWriter writer = new ClassWriter(0); node.accept(writer);
                    byte[] bytes = writer.toByteArray(); loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
            }
            if (resolve) resolveClass(loaded); return loaded;
        }
    }
}
