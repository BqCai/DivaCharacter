package divachar;

import java.nio.ByteBuffer;
import java.lang.Math;
import org.joml.*;
import static org.lwjgl.opengl.GL43C.*;

/** Original character illumination/control prepass, followed by its captured filter. */
public final class Scattering {
    private final ScatteringFilter filter;
    private int width,height,depthFormat,quarterWidth,quarterHeight,geometry,depth,quarter,geometryFbo,quarterFbo;
    public Scattering(Assets assets){filter=new ScatteringFilter(assets.manifest.getJSONObject("scattering"));}
    private void create(int w,int h,int format,int qw,int qh){
        if(w==width&&h==height&&format==depthFormat&&qw==quarterWidth&&qh==quarterHeight)return;
        if(geometry!=0){for(int t:new int[]{geometry,depth,quarter})glDeleteTextures(t);glDeleteFramebuffers(geometryFbo);glDeleteFramebuffers(quarterFbo);}
        width=w;height=h;depthFormat=format;quarterWidth=qw;quarterHeight=qh;
        geometry=ScatteringFilter.colourTexture(w,h);quarter=ScatteringFilter.colourTexture(qw,qh);
        boolean stencil=format==GL_DEPTH24_STENCIL8;
        depth=glGenTextures();glBindTexture(GL_TEXTURE_2D,depth);glTexImage2D(GL_TEXTURE_2D,0,format,w,h,0,
            stencil?GL_DEPTH_STENCIL:GL_DEPTH_COMPONENT,stencil?GL_UNSIGNED_INT_24_8:GL_FLOAT,(ByteBuffer)null);
        geometryFbo=ScatteringFilter.framebuffer(geometry);glFramebufferTexture2D(GL_FRAMEBUFFER,stencil?GL_DEPTH_STENCIL_ATTACHMENT:GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete scattering geometry target");
        quarterFbo=ScatteringFilter.framebuffer(quarter);
    }
    public int draw(int hostGbuffer,int width,int height,Renderer renderer,Matrix4f vp,Matrix4f view,Vector3f eye,Matrix4f[] palette,boolean hideHead)throws Exception{
        return draw(hostGbuffer,width,height,renderer,vp,view,eye,palette,hideHead,0);
    }
    int draw(int hostGbuffer,int width,int height,Renderer renderer,Matrix4f vp,Matrix4f view,Vector3f eye,Matrix4f[] palette,boolean hideHead,int headVariant)throws Exception{
        return draw(hostGbuffer,GL_DEPTH24_STENCIL8,width,height,width,height,renderer,vp,view,eye,palette,hideHead,headVariant);
    }
    int draw(int hostGbuffer,int format,int width,int height,int outputWidth,int outputHeight,Renderer renderer,
             Matrix4f vp,Matrix4f view,Vector3f eye,Matrix4f[] palette,boolean hideHead,int headVariant)throws Exception{
        int qw=Math.max(1,outputWidth/4),qh=Math.max(1,outputHeight/4);
        try(GlState state=new GlState()){
            create(width,height,format,qw,qh);glBindFramebuffer(GL_FRAMEBUFFER,geometryFbo);glViewport(0,0,width,height);glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,hostGbuffer);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,geometryFbo);
            glBlitFramebuffer(0,0,width,height,0,0,width,height,GL_DEPTH_BUFFER_BIT,GL_NEAREST);glBindFramebuffer(GL_FRAMEBUFFER,geometryFbo);
            renderer.render(vp,view,eye,palette,height,hideHead,false,true,headVariant);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,geometryFbo);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,quarterFbo);
            glBlitFramebuffer(0,0,width,height,0,0,qw,qh,GL_COLOR_BUFFER_BIT,GL_LINEAR);
            return filter.apply(quarter,qw,qh,Math.max(1,outputWidth/8),Math.max(1,outputHeight/8));
        }
    }
}
