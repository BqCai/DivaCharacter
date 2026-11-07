package divachar;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import static org.lwjgl.opengl.GL43C.*;

/** Character self-shadow depth and a private copy of the active host light program. */
final class HeadShadow {
    static final int SIZE=2048;
    private static final int UNIT=31;
    private static final String SUN_TERM="float lit = sunlit(pos, n, uEye, foliage);";
    static final String GLSL="""
        uniform sampler2DShadow uDivaHairDepth;
        uniform mat4 uDivaHairProjection;
        float divaHairVisibility(vec3 pos,vec3 normal){
            vec3 sc=(uDivaHairProjection*vec4(pos+normal*0.0005,1.0)).xyz*0.5+0.5;
            if(any(lessThanEqual(sc,vec3(0.0)))||any(greaterThanEqual(sc,vec3(1.0)))) return 1.0;
            vec2 texel=1.0/vec2(textureSize(uDivaHairDepth,0));
            float depth=sc.z-0.00015;
            // Tent PCF smooths the projected texel grid without widening the whole shadow.
            float lit=0.0;
            for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
                float weight=float((2-abs(x))*(2-abs(y)));
                lit+=weight*texture(uDivaHairDepth,vec3(sc.xy+vec2(x,y)*texel,depth));
            }
            return lit/16.0;
        }
        """;
    private final Vector3f bindHead=new Vector3f();
    private Assets currentAssets;
    private int head;
    private final Matrix4f projection=new Matrix4f();
    private int texture,fbo,attemptedProgram;
    private Object lighting;
    private Method frameValues;

    private void createDepth(){
        texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);
        glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT24,SIZE,SIZE,0,GL_DEPTH_COMPONENT,GL_UNSIGNED_INT,(ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_MODE,GL_COMPARE_REF_TO_TEXTURE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_FUNC,GL_LEQUAL);
        fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);glDrawBuffer(GL_NONE);glReadBuffer(GL_NONE);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,texture,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete local hair shadow target");
    }

    void draw(Assets assets,Renderer renderer,Matrix4f[] palette,Vector3f sun)throws Exception{
        draw(assets,renderer,palette,sun,0);
    }
    void draw(Assets assets,Renderer renderer,Matrix4f[] palette,Vector3f sun,int headVariant)throws Exception{
        if(assets!=currentAssets){
            head=assets.names.indexOf("j_kao_wj");
            assets.bind[head].getTranslation(bindHead);
            currentAssets=assets;
        }
        Vector3f centre=palette[head].transformPosition(bindHead,new Vector3f());
        Vector3f lightEye=new Vector3f(centre).fma(4f,sun);
        Vector3f up=java.lang.Math.abs(sun.y)>.98f?new Vector3f(0,0,1):new Vector3f(0,1,0);
        Matrix4f view=new Matrix4f().lookAt(lightEye,centre,up);
        Vector3f joint=new Vector3f(),minimum=new Vector3f(Float.POSITIVE_INFINITY),maximum=new Vector3f(Float.NEGATIVE_INFINITY);
        for(int i=0;i<assets.bind.length;i++){
            assets.bind[i].getTranslation(joint);
            palette[i].transformPosition(joint);
            view.transformPosition(joint);
            minimum.min(joint);maximum.max(joint);
        }
        float margin=.12f;
        projection.setOrtho(minimum.x-margin,maximum.x+margin,minimum.y-margin,maximum.y+margin,
            Math.max(.02f,-maximum.z-margin),Math.max(.03f,-minimum.z+margin)).mul(view);
        try(GlState state=new GlState()){
            glActiveTexture(GL_TEXTURE0);
            if(fbo==0)createDepth();
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);glViewport(0,0,SIZE,SIZE);
            glDisable(GL_SCISSOR_TEST);glDepthMask(true);glClearDepth(1);glClear(GL_DEPTH_BUFFER_BIT);
            boolean offset=glIsEnabled(GL_POLYGON_OFFSET_FILL);
            float previousFactor=glGetFloat(GL_POLYGON_OFFSET_FACTOR),previousUnits=glGetFloat(GL_POLYGON_OFFSET_UNITS);
            glEnable(GL_POLYGON_OFFSET_FILL);glPolygonOffset(2f,4f);
            try{renderer.render(projection,view,lightEye,palette,SIZE,false,true,false,headVariant);}
            finally{glPolygonOffset(previousFactor,previousUnits);GlState.enabled(GL_POLYGON_OFFSET_FILL,offset);}
        }
    }

    static String withHairVisibility(String fragment){
        int main=fragment.lastIndexOf("void main() {");
        if(main<0||fragment.indexOf(SUN_TERM,main)<0)throw new IllegalArgumentException("Host light shader has no compatible sun term");
        return fragment.substring(0,main)+GLSL+fragment.substring(main).replace(SUN_TERM,
            SUN_TERM+"\n  if (lit > 0.0 && uShadowsOff < 0.5) lit *= divaHairVisibility(pos, n);");
    }

    Object lighting(Object original){
        try{
            int program=Interop.integer(original,"id");
            if(attemptedProgram==program)return lighting;
            attemptedProgram=program;
            if(lighting!=null){glDeleteProgram(Interop.integer(lighting,"id"));lighting=null;}
            int[] shaders=new int[2],count=new int[1];glGetAttachedShaders(program,count,shaders);
            String vertex=null,fragment=null;
            for(int i=0;i<count[0];i++){
                int shader=shaders[i],type=glGetShaderi(shader,GL_SHADER_TYPE);
                if(type==GL_VERTEX_SHADER)vertex=glGetShaderSource(shader);
                if(type==GL_FRAGMENT_SHADER)fragment=glGetShaderSource(shader);
            }
            if(vertex==null||fragment==null)throw new IllegalStateException("Host light sources unavailable");
            Map<?,?> samplers=(Map<?,?>)Interop.get(original,"samplers");
            if(samplers.containsValue(UNIT))throw new IllegalStateException("Local shadow texture unit is occupied");
            Class<?> type=original.getClass(),sources=Class.forName(type.getName()+"$Sources",false,type.getClassLoader());
            Object code=sources.getConstructor(String.class,String.class,String.class,String.class,String.class)
                .newInstance(vertex,null,null,null,withHairVisibility(fragment));
            Object candidate=type.getMethod("fromSources",String.class,sources,Map.class,Map.class,List.class)
                .invoke(null,"DIVA local hair lighting",code,Map.of(),Map.of(),List.of());
            try{
                Method sampler=type.getMethod("sampler",String.class,int.class);
                for(var entry:samplers.entrySet())sampler.invoke(candidate,entry.getKey(),entry.getValue());
                sampler.invoke(candidate,"uDivaHairDepth",UNIT);
            }catch(Exception error){glDeleteProgram(Interop.integer(candidate,"id"));throw error;}
            lighting=candidate;
            System.out.println("[DivaCharacter] local hair shadow shader ready");
        }catch(Exception error){
            System.out.println("[DivaCharacter] local hair shadow unavailable: "+error);
        }
        return lighting;
    }

    void bind(Object program)throws ReflectiveOperationException{
        int id=Interop.integer(program,"id");
        bind(id,UNIT);
    }

    void bind(int id,int unit){
        glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(unit,0);
        glProgramUniform1i(id,glGetUniformLocation(id,"uDivaHairDepth"),unit);
        glProgramUniformMatrix4fv(id,glGetUniformLocation(id,"uDivaHairProjection"),false,projection.get(new float[16]));
    }

    void applyFrameUniforms(Object program,Object frame)throws ReflectiveOperationException{
        if(frameValues==null){
            Class<?> uniforms=Class.forName("viewpoint.render.FrameUniforms",false,frame.getClass().getClassLoader());
            frameValues=uniforms.getDeclaredMethod("values",program.getClass(),frame.getClass());
            frameValues.setAccessible(true);
        }
        // Viewpoint normally updates only its registered programs at frame start.
        glUseProgram(Interop.integer(program,"id"));
        frameValues.invoke(null,program,frame);
    }
}
