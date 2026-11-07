package divachar;

import java.lang.reflect.*;
import java.lang.Math;
import org.joml.*;
import static org.lwjgl.opengl.GL43C.*;

/** Ask the installed host lighting pass to evaluate this character's unit-colour surface. */
public final class HostLighting {
    private int width,height,geometryFbo,lightFbo,albedo,normal,engine,depth,light;
    private Method hostPass;
    private HeadShadow headShadow;
    private int diagnosticRevision=-1;
    /** Populate the host surface before its screen-space occlusion and bounce passes. */
    public static void writeSurface(int fbo,int width,int height,Renderer renderer,Matrix4f[] palette,Vector4f engineLight,Matrix4f vp,Matrix4f view,Vector3f eye,boolean hideHead)throws Exception{
        writeSurface(fbo,width,height,renderer,palette,engineLight,vp,view,eye,hideHead,0);
    }
    static void writeSurface(int fbo,int width,int height,Renderer renderer,Matrix4f[] palette,Vector4f engineLight,Matrix4f vp,Matrix4f view,Vector3f eye,boolean hideHead,int headVariant)throws Exception{
        try(GlState state=new GlState()){
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);glViewport(0,0,width,height);
            Renderer.engineLight.set(engineLight);Renderer.lightProxy=true;
            renderer.render(vp,view,eye,palette,height,hideHead,false,false,headVariant);
        }finally{Renderer.lightProxy=false;}
    }
    private void create(int w,int h){
        if(w==width&&h==height)return;
        if(geometryFbo!=0){glDeleteFramebuffers(geometryFbo);glDeleteFramebuffers(lightFbo);for(int id:new int[]{albedo,normal,engine,depth,light})glDeleteTextures(id);}
        width=w;height=h;albedo=texture(GL_RGBA16F,GL_RGBA);normal=texture(GL_RGBA16F,GL_RGBA);engine=texture(GL_RGBA16F,GL_RGBA);light=texture(GL_RGBA16F,GL_RGBA);depth=texture(GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT);
        geometryFbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,geometryFbo);
        int[] textures={albedo,normal,engine};for(int i=0;i<3;i++)glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0+i,GL_TEXTURE_2D,textures[i],0);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1,GL_COLOR_ATTACHMENT2});
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete character light geometry target");
        lightFbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,lightFbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,light,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete character illumination target");
    }
    private int texture(int internal,int format){int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);glTexImage2D(GL_TEXTURE_2D,0,internal,width,height,0,format,GL_FLOAT,(java.nio.ByteBuffer)null);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return id;}
    public int draw(Object frame,Object targets,Assets assets,Renderer renderer,Pose pose,Matrix4f vp,Matrix4f view,Vector3f eye)throws Exception{
        create(Interop.integer(frame,"width"),Interop.integer(frame,"height"));
        glBindFramebuffer(GL_FRAMEBUFFER,geometryFbo);glViewport(0,0,width,height);glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);glDepthMask(true);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        Renderer.engineLight.set(pose.engineLight);Renderer.lightProxy=true;
        try(GlState state=new GlState()){renderer.render(vp,view,eye,pose.palette,height,pose.firstPerson,false,false,pose.headVariant);}finally{Renderer.lightProxy=false;}
        glBindFramebuffer(GL_FRAMEBUFFER,lightFbo);glViewport(0,0,width,height);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);
        Object surface=Interop.get(Class.forName("viewpoint.render.WorldRenderer"),"surfaces");
        if(hostPass==null){hostPass=surface.getClass().getDeclaredMethod("light",frame.getClass());hostPass.setAccessible(true);}
        Object scene=Interop.get(frame,"scene");
        Renderer.sunDirection.set(Interop.number(scene,"sunX"),Interop.number(scene,"sunY"),Interop.number(scene,"sunZ")).normalize();
        Object original=Interop.get(surface,"light"),local=null;
        if(Tuning.hairShadow&&(Boolean)Interop.get(frame,"sun")){
            if(headShadow==null)headShadow=new HeadShadow();
            local=headShadow.lighting(original);
            if(local!=null){
                headShadow.draw(assets,renderer,pose.palette,Renderer.sunDirection,pose.headVariant);
                headShadow.applyFrameUniforms(local,frame);
                headShadow.bind(local);
            }
        }
        String[] fields={"albedo","normal","engineLight","depth"};int[] saved=new int[4],replacement={albedo,normal,engine,depth};
        for(int i=0;i<fields.length;i++)saved[i]=Interop.integer(targets,fields[i]);
        try{
            for(int i=0;i<fields.length;i++)Interop.set(targets,fields[i],replacement[i]);
            if(local!=null)Interop.set(surface,"light",local);
            hostPass.invoke(surface,frame);
        }finally{
            if(local!=null)Interop.set(surface,"light",original);
            for(int i=0;i<fields.length;i++)Interop.set(targets,fields[i],saved[i]);
        }
        if(diagnosticRevision!=Tuning.captureRevision){
            diagnosticRevision=Tuning.captureRevision;
            java.nio.FloatBuffer values=java.nio.ByteBuffer.allocateDirect(width*height*16).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();glReadPixels(0,0,width,height,GL_RGBA,GL_FLOAT,values);
            double sum=0;float maximum=0;int pixels=0;
            for(int i=0;i<width*height;i++)if(values.get(i*4+3)>.5f){for(int c=0;c<3;c++){float value=values.get(i*4+c);sum+=value;maximum=Math.max(maximum,value);}pixels++;}
            org.json.JSONObject result=new org.json.JSONObject().put("pixels",pixels).put("mean",pixels==0?0:sum/(pixels*3)).put("maximum",maximum).put("engineLight",new org.json.JSONArray(new float[]{pose.engineLight.x,pose.engineLight.y,pose.engineLight.z,pose.engineLight.w})).put("firstPerson",pose.firstPerson).put("hairShadow",local!=null);
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"DivaCharacter-lighting.json"),result.toString(2));
            System.out.println("[DivaCharacter] host illumination diagnostic: "+result);
        }
        return light;
    }
}
