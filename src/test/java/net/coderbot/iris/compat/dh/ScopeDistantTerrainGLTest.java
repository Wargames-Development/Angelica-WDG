package net.coderbot.iris.compat.dh;

import com.gtnewhorizons.angelica.glsm.GLCoreTest;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.program.ProgramSamplers;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.pipeline.transform.ShaderTransformer;
import net.coderbot.iris.rendertarget.DepthTexture;
import com.gtnewhorizons.angelica.glsm.texture.DepthBufferFormat;
import net.coderbot.iris.shaderpack.ProgramDirectives;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.uniforms.custom.CustomUniforms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@GLCoreTest
class ScopeDistantTerrainGLTest {
    private static Object oldConfig, oldDeobf, oldMinecraft;
    private static final java.util.Map<Field,Object> notifiers = new java.util.HashMap<>();
    @BeforeAll static void initialiseDebugEnvironment() throws Exception {
        oldDeobf = net.minecraft.launchwrapper.Launch.blackboard.put("fml.deobfuscatedEnvironment", false);
        Field config = net.coderbot.iris.Iris.class.getDeclaredField("irisConfig"); config.setAccessible(true);
        oldConfig = config.get(null); config.set(null, mock(net.coderbot.iris.config.IrisConfig.class));
        Field minecraft = net.minecraft.client.Minecraft.class.getDeclaredField("theMinecraft"); minecraft.setAccessible(true);
        oldMinecraft = minecraft.get(null); minecraft.set(null,mock(net.minecraft.client.Minecraft.class));
        for (Field notifier : net.coderbot.iris.gl.state.StateUpdateNotifiers.class.getFields()) {
            notifiers.put(notifier,notifier.get(null));
            notifier.set(null,mock(net.coderbot.iris.gl.state.ValueUpdateNotifier.class));
        }
    }
    @AfterAll static void restoreDebugEnvironment() throws Exception {
        if (oldDeobf == null) net.minecraft.launchwrapper.Launch.blackboard.remove("fml.deobfuscatedEnvironment");
        else net.minecraft.launchwrapper.Launch.blackboard.put("fml.deobfuscatedEnvironment",oldDeobf);
        Field config = net.coderbot.iris.Iris.class.getDeclaredField("irisConfig"); config.setAccessible(true); config.set(null,oldConfig);
        Field minecraft = net.minecraft.client.Minecraft.class.getDeclaredField("theMinecraft"); minecraft.setAccessible(true); minecraft.set(null,oldMinecraft);
        for (java.util.Map.Entry<Field,Object> entry : notifiers.entrySet()) entry.getKey().set(null,entry.getValue());
    }
    private static ProgramSource source() {
        ProgramSource source = mock(ProgramSource.class);
        when(source.getName()).thenReturn("scope_dh_test");
        when(source.getVertexSource()).thenReturn(Optional.of("#version 330 core\nvoid main(){gl_Position=vec4((gl_Vertex.xy-vec2(8.0))/8.0,0.0,1.0);}"));
        when(source.getFragmentSource()).thenReturn(Optional.of("#version 330 core\nvoid main(){gl_FragData[0]=vec4(0,1,0,1);}"));
        when(source.getTessControlSource()).thenReturn(Optional.empty()); when(source.getTessEvalSource()).thenReturn(Optional.empty());
        when(source.getGeometrySource()).thenReturn(Optional.empty());
        ProgramDirectives directives = mock(ProgramDirectives.class);
        when(source.getDirectives()).thenReturn(directives);
        when(directives.getBufferBlendOverrides()).thenReturn(Collections.emptyList());
        when(directives.getBlendModeOverride()).thenReturn(Optional.empty());
        when(directives.getDrawBuffers()).thenReturn(new int[]{0});
        return source;
    }

    @Test void configuredLocalShaderPackCompilesBothPrivateDhPrograms() throws Exception {
        String path = System.getProperty("angelica.scopePack");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null, "Supply a local pack shaders directory for companion validation");
        ShaderTransformer.init();
        java.util.List<net.coderbot.iris.shaderpack.StringPair> defines = new java.util.ArrayList<>();
        for (net.coderbot.iris.shaderpack.StringPair define : net.coderbot.iris.gl.shader.StandardMacros.createStandardEnvironmentDefines()) defines.add(define);
        defines.add(new net.coderbot.iris.shaderpack.StringPair("DISTANT_HORIZONS", ""));
        net.coderbot.iris.shaderpack.ShaderPack pack = new net.coderbot.iris.shaderpack.ShaderPack(java.nio.file.Paths.get(path),defines);
        net.coderbot.iris.shaderpack.ProgramSet programs = pack.getProgramSet("Overworld");
        DeferredWorldRenderingPipeline pipeline = pipeline(0);
        for (ProgramSource source : new ProgramSource[]{programs.getDhTerrain().get(),programs.getDhWater().get()}) {
            IrisLodRenderProgram program = IrisLodRenderProgram.createProgram(source.getName()+"_scope",false,
                source == programs.getDhWater().get(),source,pipeline.getCustomUniforms(),pipeline);
            program.free();
        }
    }

    private static DeferredWorldRenderingPipeline pipeline(int nativeDepth) {
        DeferredWorldRenderingPipeline pipeline = mock(DeferredWorldRenderingPipeline.class);
        when(pipeline.isSecondaryView()).thenReturn(true);
        when(pipeline.getSecondaryNativeDepth()).thenReturn(nativeDepth);
        when(pipeline.getSecondaryWidth()).thenReturn(4); when(pipeline.getSecondaryHeight()).thenReturn(4);
        when(pipeline.getCustomUniforms()).thenReturn(mock(CustomUniforms.class));
        when(pipeline.getTextureMap()).thenReturn(Object2ObjectMaps.emptyMap());
        return pipeline;
    }

    @Test void actualPackProgramUsesIntegerVerticesAndPreservesNativePixelsAcrossMovingEdges() throws Exception {
        ShaderTransformer.init();
        DepthTexture nativeDepth = new DepthTexture(4, 4, DepthBufferFormat.DEPTH32F);
        GlFramebuffer nativeTarget = new GlFramebuffer(); nativeTarget.addDepthAttachment(nativeDepth.getTextureId()); nativeTarget.noDrawBuffers();
        int colour = colour(); GlFramebuffer target = new GlFramebuffer(); target.addColorAttachment(0, colour); target.drawBuffers(new int[]{0});
        int vao = GLStateManager.glGenVertexArrays(), vbo = GLStateManager.glGenBuffers();
        IrisLodRenderProgram program = null;
        try {
            DeferredWorldRenderingPipeline pipeline = pipeline(nativeDepth.getTextureId());
            program = IrisLodRenderProgram.createProgram("scope_dh_test", false, false, source(), pipeline.getCustomUniforms(), pipeline);
            GLStateManager.glBindVertexArray(vao); GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            ByteBuffer vertices = BufferUtils.createByteBuffer(6 * 16);
            for (int[] p : new int[][]{{0,0},{16,0},{16,16},{0,0},{16,16},{0,16}}) {
                vertices.putShort((short)p[0]).putShort((short)p[1]).putShort((short)0).putShort((short)255);
                vertices.putInt(-1).putInt(0);
            }
            vertices.flip(); GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
            GL30.glVertexAttribIPointer(0, 4, GL11.GL_UNSIGNED_SHORT, 16, 0L);
            GLStateManager.glEnableVertexAttribArray(0);
            GLStateManager.glViewport(0,0,4,4); GLStateManager.glDisable(GL11.GL_DEPTH_TEST);
            GLStateManager.glDisable(GL11.GL_BLEND); GLStateManager.glColorMask(true,true,true,true);
            for (int frame = 0; frame < 8; frame++) {
                int edge = frame % 3 + 1;
                nativeTarget.bind(); GLStateManager.glDepthMask(true); GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);
                GLStateManager.glClearDepth(1); GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GLStateManager.glEnable(GL11.GL_SCISSOR_TEST); GLStateManager.glScissor(0,0,edge,4);
                GLStateManager.glClearDepth(.4); GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT); GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);
                target.bind(); GLStateManager.glClearColor(1,0,0,1); GLStateManager.glClear(GL11.GL_COLOR_BUFFER_BIT);
                program.bind();
                ((ProgramSamplers)field(program,"samplers")).update();
                ((ProgramUniforms)field(program,"uniforms")).update();
                GLStateManager.glDrawArrays(GL11.GL_TRIANGLES,0,6);
                for (int x = 0; x < 4; x++) {
                    java.nio.FloatBuffer pixel = BufferUtils.createFloatBuffer(4);
                    GLStateManager.glReadPixels(x,2,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,pixel);
                    assertEquals(x < edge ? 1f : 0f, pixel.get(0), .01f);
                    assertEquals(x < edge ? 0f : 1f, pixel.get(1), .01f);
                }
                program.unbind();
            }
            assertEquals(GL11.GL_NO_ERROR, GLStateManager.glGetError());
        } finally {
            if (program != null) program.free();
            GLStateManager.glBindVertexArray(0); GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER,0);
            GLStateManager.glDeleteBuffers(vbo); GLStateManager.glDeleteVertexArrays(vao);
            nativeTarget.destroy(); nativeDepth.destroy(); target.destroy(); GLStateManager.glDeleteTextures(colour);
        }
    }

    @Test void privateOpaqueDepthSnapshotSurvivesWaterAndBothDepthsResetEachSample() {
        ShaderTransformer.init();
        DepthTexture nativeDepth = new DepthTexture(4,4,DepthBufferFormat.DEPTH32F);
        DeferredWorldRenderingPipeline pipeline = pipeline(nativeDepth.getTextureId());
        ProgramSource source = source(); when(pipeline.getDHTerrainShader()).thenReturn(Optional.of(source));
        when(pipeline.getDHWaterShader()).thenReturn(Optional.of(source));
        GlFramebuffer solid = new GlFramebuffer(), water = new GlFramebuffer(), reader = new GlFramebuffer();
        int colour = colour();
        solid.addColorAttachment(0,colour); solid.drawBuffers(new int[]{0});
        water.addColorAttachment(0,colour); water.drawBuffers(new int[]{0}); reader.noDrawBuffers();
        when(pipeline.createDHFramebuffer(source,false)).thenReturn(solid);
        when(pipeline.createDHFramebuffer(source,true)).thenReturn(water);
        ScopeDistantTerrain terrain = new ScopeDistantTerrain(pipeline,4,4);
        try {
            assertNotEquals(terrain.depth(false), terrain.depth(true));
            assertNotEquals(nativeDepth.getTextureId(),terrain.depth(false));
            for (int sample = 0; sample < 3; sample++) {
                terrain.clear(); assertDepth(reader,terrain.depth(true),1); assertDepth(reader,terrain.depth(false),1);
                solid.bind(); GLStateManager.glClearDepth(.6); GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                terrain.end(0);
                water.bind(); GLStateManager.glClearDepth(.3); GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                assertDepth(reader,terrain.depth(true),.6f); assertDepth(reader,terrain.depth(false),.3f);
            }
            assertEquals(GL11.GL_NO_ERROR,GLStateManager.glGetError());
        } finally { terrain.destroy(); nativeDepth.destroy(); solid.destroy(); water.destroy(); reader.destroy(); GLStateManager.glDeleteTextures(colour); }
    }

    private static void assertDepth(GlFramebuffer reader, int texture, float expected) {
        reader.addDepthAttachment(texture); reader.bind();
        java.nio.FloatBuffer value = BufferUtils.createFloatBuffer(1);
        GLStateManager.glReadPixels(1,1,1,1,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,value);
        assertEquals(expected,value.get(0),1e-6f);
    }
    private static Object field(Object owner,String name) throws Exception { Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static int colour() {
        int texture=GLStateManager.glGenTextures(); GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GLStateManager.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,4,4,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
        return texture;
    }
}
