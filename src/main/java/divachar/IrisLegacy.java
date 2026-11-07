package divachar;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import static org.lwjgl.opengl.GL43C.*;

/** Render the original DIVA shaders against Iris world depth, after the pack's final colour pass. */
final class IrisLegacy {
    // 2x at 1280x800, 1.5x at 1920x1080. Keep larger viewports at native resolution.
    private static final long SAMPLE_BUDGET=4_718_592;
    private static final String QUAD="""
        #version 430 core
        out vec2 uv;
        void main(){uv=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(uv*2.0-1.0,0.0,1.0);}
        """;
    private static final String ENVIRONMENT_SOURCE="""
    uniform bool useSceneLighting;
    uniform sampler2D sceneLighting;
    uniform int torchCount;
    uniform vec4 torchPosition[8],torchDirection[8];
    uniform vec3 torchColour[8];
    vec4 sceneLight(vec3 sky){
        vec4 fallback=vec4(sky,1.0);
        if(!useSceneLighting)return fallback;
        vec4 scene=texture(sceneLighting,uv);
        if(scene.r<0.0 || any(isnan(scene)) || any(isinf(scene)))return fallback;
        return vec4(mix(sky,clamp(scene.rgb,0.0,2.0),0.55),scene.a);
    }
    vec3 torchLight(vec3 position,vec3 normal){
        vec3 sum=vec3(0);
        for(int i=0;i<torchCount;i++){
            vec3 delta=position-torchPosition[i].xyz;
            float distance=length(delta),reach=torchPosition[i].w;
            if(distance>=reach)continue;
            vec3 ray=delta/max(distance,1e-4);
            float edge=torchDirection[i].w;
            float beam=edge<=-1.0?1.0:smoothstep(edge,mix(edge,1.0,0.3),dot(ray,torchDirection[i].xyz));
            sum+=torchColour[i]*beam*(0.35+0.65*max(dot(normal,-ray),0.0))*(1.0-distance/reach);
        }
        return sum;
    }
    """;
    private static final String LIGHT_SOURCE="""
    #version 430 core
    in vec2 uv;out vec4 colour;
    uniform sampler2D surfaceMask,surfaceNormal,engineLight,surfaceDepth;
    uniform mat4 inverseProjection;
    uniform vec3 sunDirection,skyLight,sunLight;
    uniform vec2 weather,projectionScale;
    uniform bool useHairShadow;
    """+HeadShadow.GLSL+ENVIRONMENT_SOURCE+"""
    const vec2 directions[8]=vec2[8](vec2(1,0),vec2(-1,0),vec2(0,1),vec2(0,-1),
        vec2(.707,.707),vec2(-.707,.707),vec2(.707,-.707),vec2(-.707,-.707));
    vec4 worldAt(ivec2 pixel){
        vec2 centre=(vec2(pixel)+0.5)/vec2(textureSize(surfaceDepth,0));
        return inverseProjection*vec4(centre*2.0-1.0,texelFetch(surfaceDepth,pixel,0).r*2.0-1.0,1.0);
    }
    float contactVisibility(vec4 position,vec3 n){
        vec3 world=position.xyz/position.w;
        vec2 radius=clamp(projectionScale*(0.036*position.w),vec2(0),vec2(.065));
        float occlusion=0.0;
        for(int i=0;i<8;i++)for(int ring=1;ring<=2;ring++){
            vec2 point=uv+directions[i]*radius*(float(ring)*0.5);
            if(any(lessThan(point,vec2(0)))||any(greaterThanEqual(point,vec2(1))))continue;
            // Scene geometry is only used for the depth test. It never contributes to this AO.
            ivec2 pixel=ivec2(point*vec2(textureSize(surfaceDepth,0)));
            if(texelFetch(surfaceMask,pixel,0).a==0.0)continue;
            vec4 samplePosition=worldAt(pixel);
            vec3 delta=samplePosition.xyz/samplePosition.w-world;
            float distance=length(delta);
            float horizon=max(dot(n,delta)/max(distance,1e-5)-0.12,0.0);
            occlusion+=horizon*(1.0-smoothstep(.018,.075,distance));
        }
        return 1.0-min(0.42,0.19*occlusion);
    }
    void main(){
        if(texture(surfaceMask,uv).a==0.0){colour=vec4(0.0);return;}
        vec3 n=normalize(texture(surfaceNormal,uv).xyz);
        vec4 local=texture(engineLight,uv);
        float sky=clamp(local.a,0.0,1.0);
        float up=clamp(0.5+0.5*n.y,0.0,1.0);
        vec4 scene=sceneLight(max(skyLight,vec3(0.0)));
        float sun=max(weather.x,0.0)*sky*scene.a;
        vec3 skylight=scene.rgb;
        float skyLuminance=dot(skylight,vec3(0.2126,0.7152,0.0722));
        // Skylight favours upward faces. Warmer ground bounce fills the opposite hemisphere.
        vec3 ambient=mix(vec3(skyLuminance),skylight,0.55)*sky*(0.16+0.54*up*up);
        vec3 bounce=vec3(1.0,0.86,0.72)*(1.0-up)*(0.12*sun);
        vec3 direct=max(sunLight,vec3(0.0))*(1.10*sun*max(dot(n,sunDirection),0.0));
        vec4 p=worldAt(ivec2(gl_FragCoord.xy));
        if(useHairShadow)direct*=divaHairVisibility(p.xyz/p.w,n);
        vec3 fill=ambient+bounce+local.rgb*mix(0.55,0.25,sky)+torchLight(p.xyz/p.w,n)+vec3(max(weather.y,0.0)*0.03);
        colour=vec4(fill*contactVisibility(p,n)+direct,1.0);
    }
    """;
    private static final String COMPOSITE_SOURCE="""
    #version 430 core
    in vec2 uv;out vec4 colour;
    uniform sampler2D character;
    uniform vec2 sampleOffset;
    vec4 displaySample(vec2 point){
        vec4 c=texture(character,point);
        if(c.a<=0.0)return vec4(0.0);
        vec3 hdr=max(vec3(0.0),c.rgb/c.a)*0.82;
        float peak=max(hdr.r,max(hdr.g,hdr.b));
        // Keep midtones proportional. Apply one shared shoulder to highlights to preserve their hue.
        float excess=max(peak-0.62,0.0);
        float mapped=min(peak,0.62)+excess/(1.0+excess/0.38);
        vec3 chroma=hdr/max(peak,1e-6);
        vec3 display=mix(pow(chroma,vec3(1.0/2.2)),chroma,0.18)*pow(mapped,1.0/2.2);
        return vec4(display*c.a,c.a);
    }
    void main(){
        vec4 result=displaySample(uv);
        if(sampleOffset.x>0.0){
            // Keep the bilinear footprint inside this output pixel, even at a fractional render scale.
            vec2 margin=max(vec2(0),0.5*(fwidth(uv)-1.0/vec2(textureSize(character,0))));
            vec2 a=min(sampleOffset,margin),b=vec2(a.x,-a.y);
            result=(displaySample(uv-a)+displaySample(uv+a)+displaySample(uv-b)+displaySample(uv+b))*0.25;
        }
        if(result.a<=0.0)discard;
        colour=result;
    }
    """;
    private final HeadShadow hair=new HeadShadow();
    private Scattering scattering;
    private int lightProgram,compositeProgram,vao;
    private int shaderRevision=Integer.MIN_VALUE;
    private int width,height,sceneFbo,geometryFbo,colourFbo,lightFbo,depth,colourDepth,mask,normal,engine,light,colour;
    private final float[] torchPositions=new float[32],torchDirections=new float[32],torchColours=new float[24];
    record Layer(int colour,int depth,int width,int height,boolean supersampled){}
    interface SampleLighting {int draw(Layer surface)throws ReflectiveOperationException;}

    void draw(Object pipeline,Object frame,Assets assets,Renderer renderer,Pose pose)throws Exception{
        draw(pipeline,frame,assets,renderer,pose,Interop.integer(frame,"outputDrawFbo"),(int[])Interop.get(frame,"viewport"));
    }

    void draw(Object pipeline,Object frame,Assets assets,Renderer renderer,Pose pose,int output,int[] viewport)throws Exception{
        display(renderLayer(pipeline,frame,assets,renderer,pose,viewport[2],viewport[3]),output,viewport);
    }

    Layer renderLayer(Object pipeline,Object frame,Assets assets,Renderer renderer,Pose pose,int outputWidth,int outputHeight)throws Exception{
        return renderLayer(pipeline,frame,assets,renderer,pose,outputWidth,outputHeight,null);
    }

    Layer renderLayer(Object pipeline,Object frame,Assets assets,Renderer renderer,Pose pose,int outputWidth,int outputHeight,
                      SampleLighting sampleLighting)throws Exception{
        Object targets=Interop.get(pipeline,"targets"),uniforms=Interop.get(pipeline,"uniforms"),scene=Interop.get(frame,"scene");
        int sceneDepth=Interop.integer(targets,"depth"),w=Interop.integer(targets,"width"),h=Interop.integer(targets,"height");
        Matrix4f view=(Matrix4f)Interop.get(frame,"view");
        Matrix4f projection=(Matrix4f)Interop.get(uniforms,"projection"),vp=new Matrix4f(projection).mul(view);
        Vector3f eye=new Vector3f(Interop.number(frame,"eyeX"),Interop.number(frame,"eyeY"),Interop.number(frame,"eyeZ"));
        Vector3f sun=vector(scene,"sunX","sunY","sunZ").normalize();
        Vector3f sky=vector(scene,"skyR","skyG","skyB");
        Vector3f daylight=vector(scene,"sunR","sunG","sunB");
        float direct=Interop.number(scene,"sunStrength"),night=Interop.number(scene,"nightLight");
        return renderLayer(sceneDepth,w,h,outputWidth,outputHeight,assets,renderer,pose.palette,pose.engineLight,pose.firstPerson,vp,view,eye,sun,sky,
            daylight,direct,night,sampleLighting,sampleLighting!=null&&Tuning.irisSceneLight?scene:null,pose.headVariant);
    }

    void draw(int sceneDepth,int sceneWidth,int sceneHeight,int output,int[] viewport,Assets assets,Renderer renderer,Matrix4f[] palette,
              Vector4f engineLight,boolean firstPerson,Matrix4f vp,Matrix4f view,Vector3f eye,Vector3f sun,Vector3f sky,
              Vector3f daylight,float direct,float night)throws Exception{
        display(renderLayer(sceneDepth,sceneWidth,sceneHeight,viewport[2],viewport[3],assets,renderer,palette,engineLight,
            firstPerson,vp,view,eye,sun,sky,daylight,direct,night),output,viewport);
    }

    Layer renderLayer(int sceneDepth,int sceneWidth,int sceneHeight,int outputWidth,int outputHeight,Assets assets,
                      Renderer renderer,Matrix4f[] palette,Vector4f engineLight,boolean firstPerson,Matrix4f vp,
                      Matrix4f view,Vector3f eye,Vector3f sun,Vector3f sky,Vector3f daylight,float direct,float night)throws Exception{
        return renderLayer(sceneDepth,sceneWidth,sceneHeight,outputWidth,outputHeight,assets,renderer,palette,engineLight,firstPerson,
            vp,view,eye,sun,sky,daylight,direct,night,null,null,0);
    }

    private Layer renderLayer(int sceneDepth,int sceneWidth,int sceneHeight,int outputWidth,int outputHeight,Assets assets,
                              Renderer renderer,Matrix4f[] palette,Vector4f engineLight,boolean firstPerson,Matrix4f vp,
                              Matrix4f view,Vector3f eye,Vector3f sun,Vector3f sky,Vector3f daylight,float direct,float night,
                              SampleLighting sampleLighting,Object scene,int headVariant)throws Exception{
        long outputPixels=(long)outputWidth*outputHeight;
        float scale=outputPixels*4<=SAMPLE_BUDGET?2f:outputPixels*2.25<=SAMPLE_BUDGET?1.5f:1f;
        int w=Math.max(sceneWidth,(int)(outputWidth*scale)),h=Math.max(sceneHeight,(int)(outputHeight*scale));
        int oldLight=Renderer.hostLight,oldScattering=Renderer.hostScattering;
        Vector3f oldSun=new Vector3f(Renderer.sunDirection);
        try(GlState ignored=new GlState()){
            glActiveTexture(GL_TEXTURE0);
            create(w,h);
            reload(Tuning.FILE.resolveSibling("divachar-shaders"),Tuning.shaderRevision);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,sceneFbo);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,sceneDepth,0);
            if(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Unavailable Iris scene depth");
            glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);glDepthMask(true);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,colourFbo);
            glBlitFramebuffer(0,0,sceneWidth,sceneHeight,0,0,w,h,GL_DEPTH_BUFFER_BIT,GL_NEAREST);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,geometryFbo);
            glBlitFramebuffer(0,0,sceneWidth,sceneHeight,0,0,w,h,GL_DEPTH_BUFFER_BIT,GL_NEAREST);
            glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);
            HostLighting.writeSurface(geometryFbo,w,h,renderer,palette,engineLight,vp,view,eye,firstPerson,headVariant);
            int sceneLight=sampleLighting==null?0:sampleLighting.draw(new Layer(mask,depth,w,h,scale>1f));

            boolean hairShadow=Tuning.hairShadow&&direct>.02f&&sun.isFinite();
            if(hairShadow)hair.draw(assets,renderer,palette,sun,headVariant);
            light(vp,view,sun,sky,daylight,direct,night,hairShadow,sceneLight,scene);
            Renderer.sunDirection.set(sun.isFinite()?sun:new Vector3f(0,1,0));
            Renderer.hostLight=light;Renderer.hostScattering=0;
            if(Tuning.scattering&&assets.manifest.has("scattering")){
                if(scattering==null)scattering=new Scattering(assets);
                Renderer.hostScattering=scattering.draw(geometryFbo,GL_DEPTH_COMPONENT32F,w,h,outputWidth,outputHeight,
                    renderer,vp,view,eye,palette,firstPerson,headVariant);
            }
            glBindFramebuffer(GL_FRAMEBUFFER,colourFbo);glViewport(0,0,w,h);
            glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);
            renderer.renderColourLayer(vp,view,eye,palette,h,firstPerson,headVariant);
            return new Layer(colour,colourDepth,w,h,scale>1f);
        }finally{
            Renderer.hostLight=oldLight;Renderer.hostScattering=oldScattering;Renderer.sunDirection.set(oldSun);
        }
    }

    private void display(Layer layer,int output,int[] viewport){
        try(GlState ignored=new GlState()){
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,output);
            glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            int program=compositeProgram;
            quad(program);glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);
            glBlendFuncSeparate(GL_ONE,GL_ONE_MINUS_SRC_ALPHA,GL_ZERO,GL_ONE);
            bind(program,"character",0,layer.colour);
            glUniform2f(glGetUniformLocation(program,"sampleOffset"),
                layer.supersampled?.25f/viewport[2]:0,layer.supersampled?.25f/viewport[3]:0);
            glDrawArrays(GL_TRIANGLES,0,3);
        }
    }

    private void light(Matrix4f vp,Matrix4f view,Vector3f sun,Vector3f sky,Vector3f daylight,float direct,float night,
                       boolean hairShadow,int sceneLight,Object scene)throws ReflectiveOperationException{
        glBindFramebuffer(GL_FRAMEBUFFER,lightFbo);glViewport(0,0,width,height);quad(lightProgram);
        bind(lightProgram,"surfaceMask",0,mask);bind(lightProgram,"surfaceNormal",1,normal);
        bind(lightProgram,"engineLight",2,engine);bind(lightProgram,"surfaceDepth",3,depth);
        vector(lightProgram,"sunDirection",sun.isFinite()?sun:new Vector3f(0,1,0));
        vector(lightProgram,"skyLight",sky);vector(lightProgram,"sunLight",daylight);
        glUniform2f(glGetUniformLocation(lightProgram,"weather"),direct,night);
        Matrix4f projection=new Matrix4f(vp).mul(new Matrix4f(view).invert());
        glUniform2f(glGetUniformLocation(lightProgram,"projectionScale"),projection.m00(),projection.m11());
        glUniformMatrix4fv(glGetUniformLocation(lightProgram,"inverseProjection"),false,new Matrix4f(vp).invert().get(new float[16]));
        glUniform1i(glGetUniformLocation(lightProgram,"useHairShadow"),hairShadow?1:0);
        if(hairShadow)hair.bind(lightProgram,4);
        else glUniform1i(glGetUniformLocation(lightProgram,"uDivaHairDepth"),4);
        bind(lightProgram,"sceneLighting",5,sceneLight);
        glUniform1i(glGetUniformLocation(lightProgram,"useSceneLighting"),sceneLight!=0?1:0);
        torches(scene);
        glDrawArrays(GL_TRIANGLES,0,3);
    }

    private void torches(Object scene)throws ReflectiveOperationException{
        int count=scene==null?0:Math.min(8,Interop.integer(scene,"torchCount"));
        glUniform1i(glGetUniformLocation(lightProgram,"torchCount"),count);
        if(count<=0)return;
        float[] torches=(float[])Interop.get(scene,"torches");
        for(int i=0;i<count;i++){
            System.arraycopy(torches,i*11,torchPositions,i*4,4);
            System.arraycopy(torches,i*11+4,torchDirections,i*4,4);
            System.arraycopy(torches,i*11+8,torchColours,i*3,3);
        }
        glUniform4fv(glGetUniformLocation(lightProgram,"torchPosition"),torchPositions);
        glUniform4fv(glGetUniformLocation(lightProgram,"torchDirection"),torchDirections);
        glUniform3fv(glGetUniformLocation(lightProgram,"torchColour"),torchColours);
    }

    private void quad(int program){
        glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_STENCIL_TEST);glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);glDisable(GL_BLEND);glColorMask(true,true,true,true);
        glUseProgram(program);glBindVertexArray(vao);
    }

    void reload(Path directory,int revision){
        if(shaderRevision==revision)return;
        boolean first=shaderRevision==Integer.MIN_VALUE;
        shaderRevision=revision;
        Path lightFile=directory.resolve("iris-light.frag"),compositeFile=directory.resolve("iris-composite.frag");
        if(first&&!Files.isRegularFile(lightFile)&&!Files.isRegularFile(compositeFile))return;
        try{
            String lighting=Files.isRegularFile(lightFile)
                ?Files.readString(lightFile).replace("#include \"diva-head-shadow.glsl\"",HeadShadow.GLSL)
                    .replace("#include \"diva-iris-environment.glsl\"",ENVIRONMENT_SOURCE):LIGHT_SOURCE;
            String display=Files.isRegularFile(compositeFile)?Files.readString(compositeFile):COMPOSITE_SOURCE;
            installPrograms(lighting,display);
            System.out.println("[DivaCharacter] Iris shader reload ready: "+revision);
        }catch(Exception error){
            System.out.println("[DivaCharacter] kept previous Iris shaders: "+error.getMessage());
        }
    }

    private void installPrograms(String lighting,String display){
        int newLight=0,newComposite=0;
        try{
            newLight=Assets.program(QUAD,lighting);
            newComposite=Assets.program(QUAD,display);
        }catch(RuntimeException error){
            glDeleteProgram(newLight);glDeleteProgram(newComposite);throw error;
        }
        glDeleteProgram(lightProgram);glDeleteProgram(compositeProgram);
        lightProgram=newLight;compositeProgram=newComposite;
    }

    private void create(int w,int h){
        if(lightProgram==0){
            installPrograms(LIGHT_SOURCE,COMPOSITE_SOURCE);
            vao=glGenVertexArrays();sceneFbo=glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER,sceneFbo);glDrawBuffer(GL_NONE);glReadBuffer(GL_NONE);
        }
        if(w==width&&h==height)return;
        for(int fbo:new int[]{geometryFbo,colourFbo,lightFbo})glDeleteFramebuffers(fbo);
        for(int texture:new int[]{depth,colourDepth,mask,normal,engine,light,colour})glDeleteTextures(texture);
        width=w;height=h;
        depth=depthTexture(w,h);colourDepth=depthTexture(w,h);
        mask=ScatteringFilter.colourTexture(w,h);normal=ScatteringFilter.colourTexture(w,h);engine=ScatteringFilter.colourTexture(w,h);
        light=ScatteringFilter.colourTexture(w,h);colour=ScatteringFilter.colourTexture(w,h);
        geometryFbo=ScatteringFilter.framebuffer(mask);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT1,GL_TEXTURE_2D,normal,0);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT2,GL_TEXTURE_2D,engine,0);
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1,GL_COLOR_ATTACHMENT2});
        attachDepth(depth);
        colourFbo=ScatteringFilter.framebuffer(colour);attachDepth(colourDepth);
        lightFbo=ScatteringFilter.framebuffer(light);
    }

    private static int depthTexture(int w,int h){
        int texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);
        glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT32F,w,h,0,GL_DEPTH_COMPONENT,GL_FLOAT,(ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        return texture;
    }

    private void attachDepth(int depth){
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete DIVA composite depth target");
    }

    private static void bind(int program,String name,int unit,int texture){
        glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,texture);glBindSampler(unit,0);
        glUniform1i(glGetUniformLocation(program,name),unit);
    }

    private static Vector3f vector(Object object,String x,String y,String z)throws ReflectiveOperationException{
        return new Vector3f(Interop.number(object,x),Interop.number(object,y),Interop.number(object,z));
    }

    private static void vector(int program,String name,Vector3f v){glUniform3f(glGetUniformLocation(program,name),v.x,v.y,v.z);}
}
