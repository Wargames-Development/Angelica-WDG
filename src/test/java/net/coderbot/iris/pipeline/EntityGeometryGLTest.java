package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.GLCoreTest;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.gl.program.ProgramBuilder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@GLCoreTest
class EntityGeometryGLTest {
    private static Object oldMinecraft;
    @BeforeAll static void setup() throws Exception {
        Field field=net.minecraft.client.Minecraft.class.getDeclaredField("theMinecraft");field.setAccessible(true);
        oldMinecraft=field.get(null);field.set(null,mock(net.minecraft.client.Minecraft.class));
    }
    @AfterAll static void cleanup() throws Exception {
        Field field=net.minecraft.client.Minecraft.class.getDeclaredField("theMinecraft");field.setAccessible(true);field.set(null,oldMinecraft);
    }
    @Test void nestedPassCallbacksKeepHdrCoverageAndLocalProjectionOffThePrimaryTarget() throws Exception {
        int primary=target(), capture=target(), vao=GLStateManager.glGenVertexArrays();
        Program program=ProgramBuilder.begin("entity_geometry_contract",
            "#version 330 core\nuniform mat4 gbufferProjection;uniform mat4 gbufferProjectionInverse;\n"+
            "void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2)*2.-1.;"+
            "gl_Position=vec4(p,0,1);gl_Position.z=(gbufferProjection*gbufferProjectionInverse)[2][2]-1.;}", null,
            "#version 330 core\nlayout(location=0) out vec4 colour;layout(location=1) out vec4 extra;"+
            "void main(){colour=vec4(4.,2.,1.,.5);extra=vec4(1,0,0,1);}",com.google.common.collect.ImmutableSet.of()).build();
        int p=GLStateManager.glGetUniformLocation(program.getProgramId(),"gbufferProjection");
        int inv=GLStateManager.glGetUniformLocation(program.getProgramId(),"gbufferProjectionInverse");
        FloatBuffer original=BufferUtils.createFloatBuffer(16);new Matrix4f().scaling(7).get(original);
        DeferredWorldRenderingPipeline pipeline=mock(DeferredWorldRenderingPipeline.class,CALLS_REAL_METHODS);
        DeferredWorldRenderingPipeline publicPrimary=mock(DeferredWorldRenderingPipeline.class,CALLS_REAL_METHODS);
        Field guard=DeferredWorldRenderingPipeline.class.getDeclaredField("entityGeometry");guard.setAccessible(true);
        try {
            program.use();GLStateManager.glUniformMatrix4(p,false,original);GLStateManager.glUniformMatrix4(inv,false,original);
            GLStateManager.glBindVertexArray(vao);GLStateManager.glViewport(0,0,4,4);
            GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);GLStateManager.glDisable(GL11.GL_DEPTH_TEST);
            GLStateManager.glColorMask(true,true,true,true);
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER,primary);
            GLStateManager.glClearColor(.125f,.25f,.5f,1);GLStateManager.glClear(GL11.GL_COLOR_BUFFER_BIT);
            new Matrix4f().get(RenderingState.INSTANCE.getProjectionBuffer());
            RenderingState.INSTANCE.setProjectionMatrix(RenderingState.INSTANCE.getProjectionBuffer());
            new Matrix4f().get(RenderingState.INSTANCE.getModelViewBuffer());
            RenderingState.INSTANCE.setModelViewMatrix(RenderingState.INSTANCE.getModelViewBuffer());
            Matrix4f caller=new Matrix4f(RenderingState.INSTANCE.getProjectionMatrix());
            for(int frame=0;frame<3;frame++) {
                GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER,capture);
                GLStateManager.glClearColor(0,0,0,0);GLStateManager.glClear(GL11.GL_COLOR_BUFFER_BIT);
                GLStateManager.glMatrixMode(GL11.GL_PROJECTION);GLStateManager.glLoadIdentity();GLStateManager.glScalef(frame+2,frame+2,frame+2);
                net.coderbot.iris.gl.blending.BlendModeOverride.OFF.apply();
                net.coderbot.iris.gl.blending.AlphaTestOverride.OFF.apply();
                net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntityAndItem(50112,87);
                EntityGeometryPass pass=new EntityGeometryPass(program,()->{},()->{});guard.set(pipeline,pass);
                try {
                    pass.use();
                    assertEquals(-1,net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedEntity());
                    assertEquals(0,net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedItem());
                    GLStateManager.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,primary);
                    pipeline.beginPass(null);assertEquals(capture,GLStateManager.getDrawFramebuffer());
                    GLStateManager.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,primary);
                    pipeline.rebindCurrentPass();assertEquals(capture,GLStateManager.getDrawFramebuffer());
                    GLStateManager.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,primary);
                    publicPrimary.rebindCurrentPass();assertEquals(capture,GLStateManager.getDrawFramebuffer());
                    publicPrimary.onModProgramOverride();
                    GLStateManager.glDrawArrays(GL11.GL_TRIANGLES,0,3);
                    FloatBuffer colour=pixel(capture);
                    assertEquals(2f,colour.get(0),.002f);assertEquals(1f,colour.get(1),.002f);
                    assertEquals(.5f,colour.get(2),.002f);assertEquals(.5f,colour.get(3),.002f);
                    if(frame==1) throw new IllegalStateException("renderer failure");
                } catch(IllegalStateException expected) { assertEquals("renderer failure",expected.getMessage()); }
                finally { pass.close();guard.set(pipeline,null); }
                assertEquals(50112,net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedEntity());
                assertEquals(87,net.coderbot.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedItem());
                assertTrue(net.coderbot.iris.gl.blending.BlendModeStorage.isBlendLocked());
                assertTrue(net.coderbot.iris.gl.blending.AlphaTestStorage.isAlphaTestLocked());
                net.coderbot.iris.gl.blending.BlendModeOverride.restore();
                net.coderbot.iris.gl.blending.AlphaTestOverride.restore();
                assertEquals(caller,RenderingState.INSTANCE.getProjectionMatrix());
                FloatBuffer restored=BufferUtils.createFloatBuffer(16);GLStateManager.glGetUniform(program.getProgramId(),p,restored);
                for(int i=0;i<16;i++) assertEquals(original.get(i),restored.get(i),1e-6f);
                GLStateManager.glGetUniform(program.getProgramId(),inv,restored);
                for(int i=0;i<16;i++) assertEquals(original.get(i),restored.get(i),1e-6f);
                FloatBuffer primaryPixel=pixel(primary);assertEquals(.125f,primaryPixel.get(0),.001f);
                assertEquals(.25f,primaryPixel.get(1),.001f);assertEquals(.5f,primaryPixel.get(2),.001f);
            }
            assertEquals(GL11.GL_NO_ERROR,GLStateManager.glGetError());
        } finally {
            guard.set(pipeline,null);program.destroy();GLStateManager.glBindVertexArray(0);GLStateManager.glDeleteVertexArrays(vao);
            destroyTarget(primary);destroyTarget(capture);GLStateManager.glMatrixMode(GL11.GL_PROJECTION);GLStateManager.glLoadIdentity();GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        }
    }
    private static int target() {
        int texture=GLStateManager.glGenTextures(), fbo=GLStateManager.glGenFramebuffers();
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GLStateManager.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA16F,4,4,0,GL11.GL_RGBA,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        GLStateManager.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
        GLStateManager.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GLStateManager.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);return fbo;
    }
    private static FloatBuffer pixel(int fbo) {
        GLStateManager.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,fbo);
        FloatBuffer value=BufferUtils.createFloatBuffer(4);GLStateManager.glReadPixels(2,2,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,value);return value;
    }
    private static void destroyTarget(int fbo) {
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        int texture=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GLStateManager.glDeleteFramebuffers(fbo);GLStateManager.glDeleteTextures(texture);
    }
}
