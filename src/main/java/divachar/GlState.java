package divachar;

import java.nio.ByteBuffer;
import static org.lwjgl.opengl.GL43C.*;

/** Restore every context binding touched by the adapter, including indexed UBO bindings. */
public final class GlState implements AutoCloseable {
    private final int program=glGetInteger(GL_CURRENT_PROGRAM),vao=glGetInteger(GL_VERTEX_ARRAY_BINDING),
        array=glGetInteger(GL_ARRAY_BUFFER_BINDING),uniform=glGetInteger(GL_UNIFORM_BUFFER_BINDING),
        active=glGetInteger(GL_ACTIVE_TEXTURE),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),
        depthFunc=glGetInteger(GL_DEPTH_FUNC),front=glGetInteger(GL_FRONT_FACE),
        srcRgb=glGetInteger(GL_BLEND_SRC_RGB),dstRgb=glGetInteger(GL_BLEND_DST_RGB),srcAlpha=glGetInteger(GL_BLEND_SRC_ALPHA),dstAlpha=glGetInteger(GL_BLEND_DST_ALPHA),
        eqRgb=glGetInteger(GL_BLEND_EQUATION_RGB),eqAlpha=glGetInteger(GL_BLEND_EQUATION_ALPHA);
    private final boolean depth=glIsEnabled(GL_DEPTH_TEST),cull=glIsEnabled(GL_CULL_FACE),blend=glIsEnabled(GL_BLEND),scissor=glIsEnabled(GL_SCISSOR_TEST),stencil=glIsEnabled(GL_STENCIL_TEST),seamless=glIsEnabled(GL_TEXTURE_CUBE_MAP_SEAMLESS),mask=glGetBoolean(GL_DEPTH_WRITEMASK);
    private final int cullMode=glGetInteger(GL_CULL_FACE_MODE);
    private final int renderbuffer=glGetInteger(GL_RENDERBUFFER_BINDING);
    private final double clearDepth=glGetDouble(GL_DEPTH_CLEAR_VALUE);
    private final float[] clearColour=new float[4];
    private final int[] viewport=new int[4],ubos=new int[13],tex2d,cube,tex3d,texArray,cubeArray,samplers;
    private final long[] starts=new long[13],sizes=new long[13];
    private final ByteBuffer colourMask=ByteBuffer.allocateDirect(4);
    public GlState(){this(false);}
    public GlState(boolean hostTextures){
        int count=hostTextures?glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS):32;
        tex2d=new int[count];cube=new int[count];tex3d=new int[count];texArray=new int[count];cubeArray=new int[count];samplers=new int[count];
        glGetIntegerv(GL_VIEWPORT,viewport);
        glGetFloatv(GL_COLOR_CLEAR_VALUE,clearColour);
        glGetBooleanv(GL_COLOR_WRITEMASK,colourMask);
        for(int i=0;i<ubos.length;i++){ubos[i]=glGetIntegeri(GL_UNIFORM_BUFFER_BINDING,i);starts[i]=glGetInteger64i(GL_UNIFORM_BUFFER_START,i);sizes[i]=glGetInteger64i(GL_UNIFORM_BUFFER_SIZE,i);}
        for(int i=0;i<tex2d.length;i++){
            glActiveTexture(GL_TEXTURE0+i);tex2d[i]=glGetInteger(GL_TEXTURE_BINDING_2D);cube[i]=glGetInteger(GL_TEXTURE_BINDING_CUBE_MAP);samplers[i]=glGetIntegeri(GL_SAMPLER_BINDING,i);
            tex3d[i]=glGetInteger(GL_TEXTURE_BINDING_3D);texArray[i]=glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);cubeArray[i]=glGetInteger(GL_TEXTURE_BINDING_CUBE_MAP_ARRAY);
        }
        glActiveTexture(active);
    }
    static void enabled(int feature,boolean value){if(value)glEnable(feature);else glDisable(feature);}
    public void close(){
        glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,array);
        for(int i=0;i<ubos.length;i++){
            if(ubos[i]!=0&&sizes[i]>0)glBindBufferRange(GL_UNIFORM_BUFFER,i,ubos[i],starts[i],sizes[i]);else glBindBufferBase(GL_UNIFORM_BUFFER,i,ubos[i]);
        }
        glBindBuffer(GL_UNIFORM_BUFFER,uniform);
        for(int i=0;i<tex2d.length;i++){
            glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,tex2d[i]);glBindTexture(GL_TEXTURE_CUBE_MAP,cube[i]);glBindSampler(i,samplers[i]);
            glBindTexture(GL_TEXTURE_3D,tex3d[i]);glBindTexture(GL_TEXTURE_2D_ARRAY,texArray[i]);glBindTexture(GL_TEXTURE_CUBE_MAP_ARRAY,cubeArray[i]);
        }
        glActiveTexture(active);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);
        glBindRenderbuffer(GL_RENDERBUFFER,renderbuffer);glClearColor(clearColour[0],clearColour[1],clearColour[2],clearColour[3]);glClearDepth(clearDepth);
        glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glDepthFunc(depthFunc);glDepthMask(mask);glFrontFace(front);glCullFace(cullMode);
        glColorMask(colourMask.get(0)!=0,colourMask.get(1)!=0,colourMask.get(2)!=0,colourMask.get(3)!=0);
        glBlendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);glBlendEquationSeparate(eqRgb,eqAlpha);
        enabled(GL_DEPTH_TEST,depth);enabled(GL_CULL_FACE,cull);enabled(GL_BLEND,blend);enabled(GL_SCISSOR_TEST,scissor);enabled(GL_STENCIL_TEST,stencil);enabled(GL_TEXTURE_CUBE_MAP_SEAMLESS,seamless);
    }
}
