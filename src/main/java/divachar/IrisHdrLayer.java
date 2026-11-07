package divachar;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Map;
import static org.lwjgl.opengl.GL43C.*;

/** Private Eclipse post inputs. The pack's temporal history and scene textures stay owned by Iris. */
final class IrisHdrLayer {
    private static final String QUAD="""
        #version 430 core
        out vec2 uv;
        void main(){uv=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(uv*2.0-1.0,0.0,1.0);}
        """;
    private static final String RESOLVE="""
        #version 430 core
        in vec2 uv;
        layout(location=0) out vec4 hdr;
        layout(location=1) out vec4 fog;
        uniform sampler2D worldHDR,worldFog,worldDepth,character,characterDepth,actorFog;
        uniform bool hasActorFog;
        uniform float hdrGain;
        uniform vec2 sampleOffset;
        void main(){
            ivec2 pixel=ivec2(gl_FragCoord.xy);
            vec4 base=texelFetch(worldHDR,pixel,0);
            vec4 originalFog=texelFetch(worldFog,pixel,0);
            float originalDepth=texelFetch(worldDepth,pixel,0).r;
            vec2 margin=max(vec2(0),.5*(fwidth(uv)-1.0/vec2(textureSize(character,0))));
            vec2 a=min(sampleOffset,margin),b=vec2(a.x,-a.y);
            vec2 samples[4]=vec2[4](uv-a,uv+a,uv-b,uv+b);
            vec4 actor=vec4(0);
            float depth=originalDepth;
            for(int i=0;i<4;i++){
                vec4 part=texture(character,samples[i]);
                actor+=part*.25;
                if(part.a>.5)depth=min(depth,texture(characterDepth,samples[i]).r);
            }
            float coverage=clamp(actor.a,0.0,1.0);
            vec4 air=hasActorFog?texelFetch(actorFog,pixel,0):vec4(0,0,0,1);
            vec3 actorHDR=max(actor.rgb,vec3(0))*hdrGain*air.a+air.rgb*coverage;
            hdr=vec4(actorHDR+base.rgb*(1.0-coverage),base.a);
            // Overworld fog is already integrated into actorHDR. Keep background bloom haze behind the actor.
            fog=vec4(mix(originalFog.r,1.0,coverage),originalFog.gba);
            gl_FragDepth=coverage>=.5?depth:originalDepth;
        }
        """;
    private int width,height,colourFormat,fogFormat,fbo,colour,fog,depth,vao,program,revision=Integer.MIN_VALUE;
    private boolean prepared,consumed,overrideLoaded;

    void resolve(int width,int height,int sourceColour,int sourceFog,int sourceDepth,IrisLegacy.Layer actor,int actorFog){
        try(GlState ignored=new GlState()){
            glActiveTexture(GL_TEXTURE0);
            int hdrFormat=format(sourceColour,width,height),maskFormat=format(sourceFog,width,height);
            int depthFormat=format(sourceDepth,width,height);
            if(hdrFormat!=GL_RGBA16F&&hdrFormat!=GL_RGBA32F||
                maskFormat!=GL_RGBA8&&maskFormat!=GL_RGBA16&&maskFormat!=GL_RGBA16F&&maskFormat!=GL_RGBA32F||
                depthFormat!=GL_DEPTH_COMPONENT32F&&depthFormat!=GL_DEPTH_COMPONENT24)
                throw new IllegalStateException("Unsupported Eclipse HDR input formats");
            create(width,height,hdrFormat,maskFormat);
            reload();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);glViewport(0,0,width,height);
            glDisable(GL_SCISSOR_TEST);glDisable(GL_BLEND);glDisable(GL_STENCIL_TEST);glDisable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);glDepthFunc(GL_ALWAYS);glDepthMask(true);glColorMask(true,true,true,true);
            glUseProgram(program);glBindVertexArray(vao);
            bind("worldHDR",0,sourceColour);bind("worldFog",1,sourceFog);bind("worldDepth",2,sourceDepth);
            bind("character",3,actor.colour());bind("characterDepth",4,actor.depth());
            bind("actorFog",5,actorFog);glUniform1i(glGetUniformLocation(program,"hasActorFog"),actorFog!=0?1:0);
            glUniform1f(glGetUniformLocation(program,"hdrGain"),Tuning.irisHdrGain);
            glUniform2f(glGetUniformLocation(program,"sampleOffset"),actor.supersampled()?.25f/width:0,actor.supersampled()?.25f/height:0);
            glDrawArrays(GL_TRIANGLES,0,3);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,colour);glGenerateMipmap(GL_TEXTURE_2D);
            prepared=true;consumed=false;
        }
    }

    void bindFor(Object program)throws ReflectiveOperationException{
        if(!prepared)return;
        String name=(String)Interop.get(program,"name");
        if(!name.equals("composite7")&&!name.equals("composite11"))return;
        Map<?,?> units=(Map<?,?>)Interop.get(program,"units");
        Integer hdr=(Integer)units.get("colortex5");
        if(hdr==null||hdr<=0)return;
        replace(hdr,colour);
        if(name.equals("composite11")){
            replace((Integer)units.get("colortex7"),fog);
            replace((Integer)units.get("depthtex1"),depth);
            consumed=true;
        }
        glActiveTexture(GL_TEXTURE0);
    }

    boolean used(){return consumed;}
    void reset(){prepared=false;consumed=false;}

    private static void replace(Integer unit,int texture){
        if(unit==null||unit<=0)return;
        glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,texture);
    }

    private void reload(){
        int requested=Tuning.shaderRevision;
        if(revision==requested)return;
        revision=requested;
        Path file=Tuning.FILE.resolveSibling("divachar-shaders").resolve("iris-hdr.frag");
        boolean overrideExists=Files.isRegularFile(file);
        if(!overrideExists&&!overrideLoaded)return;
        try{
            int next=Assets.program(QUAD,overrideExists?Files.readString(file):RESOLVE);
            glDeleteProgram(program);program=next;overrideLoaded=overrideExists;
        }catch(Exception error){System.out.println("[DivaCharacter] kept previous Iris HDR shader: "+error.getMessage());}
    }

    private static int format(int texture,int width,int height){
        glBindTexture(GL_TEXTURE_2D,texture);
        if(glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH)!=width||
            glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_HEIGHT)!=height)return 0;
        return glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_INTERNAL_FORMAT);
    }

    private void create(int w,int h,int hdrFormat,int maskFormat){
        if(fbo==0){program=Assets.program(QUAD,RESOLVE);vao=glGenVertexArrays();fbo=glGenFramebuffers();}
        if(w==width&&h==height&&hdrFormat==colourFormat&&maskFormat==fogFormat)return;
        for(int id:new int[]{colour,fog,depth})glDeleteTextures(id);
        width=w;height=h;colourFormat=hdrFormat;fogFormat=maskFormat;
        colour=texture(colourFormat,GL_RGBA,true);fog=texture(fogFormat,GL_RGBA,false);
        depth=texture(GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT,false);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,colour,0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT1,GL_TEXTURE_2D,fog,0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1});
        if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Incomplete Eclipse HDR input target");
    }

    private int texture(int internal,int format,boolean mipmaps){
        int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);
        glTexImage2D(GL_TEXTURE_2D,0,internal,width,height,0,format,GL_FLOAT,(ByteBuffer)null);
        int filter=format==GL_DEPTH_COMPONENT?GL_NEAREST:GL_LINEAR;
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,mipmaps?GL_LINEAR_MIPMAP_LINEAR:filter);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,filter);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        return id;
    }

    private void bind(String sampler,int unit,int texture){
        glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,texture);
        glBindSampler(unit,0);
        glUniform1i(glGetUniformLocation(program,sampler),unit);
    }
}
