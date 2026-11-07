package divachar;

import org.json.*;
import static org.lwjgl.opengl.GL43C.*;

/** Captured DIVA alpha dilation and RGB-normalised 11x11 scattering kernel. */
public final class ScatteringFilter {
    private static final String VERTEX="""
        #version 430
        out vec2 uv;
        void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.0-1.0,0,1);}
        """;
    private final int dilation,filter,vao,sampler;
    private int width,height,dilated,filtered,dilationFbo,filterFbo;
    public ScatteringFilter(JSONObject data){
        dilation=Assets.program(VERTEX,"""
            #version 430
            in vec2 uv;uniform sampler2D source;uniform vec2 stepSize;out vec4 colour;
            void main(){
                vec4 c=texture(source,uv);
                if(c.a==1.0){colour=c;return;}
                vec4 a=texture(source,uv-vec2(stepSize.x,0));if(a.a>c.a)c=a;
                a=texture(source,uv+vec2(stepSize.x,0));if(a.a>c.a)c=a;
                a=texture(source,uv-vec2(0,stepSize.y));if(a.a>c.a)c=a;
                a=texture(source,uv+vec2(0,stepSize.y));if(a.a>c.a)c=a;
                colour=c;
            }
            """);
        filter=Assets.program(VERTEX,"""
            #version 430
            in vec2 uv;uniform sampler2D source;uniform vec2 stepSize;
            uniform vec3 coefficients[36];uniform vec3 tint;out vec4 colour;
            void main(){
                vec4 centre=textureLod(source,uv,0.0);
                if(centre.a<0.5){colour=centre;return;}
                vec3 sum=centre.rgb*coefficients[0]+vec3(0.00001),weight=coefficients[0]+vec3(0.00001);
                for(int y=-5;y<=5;y++)for(int x=-5;x<=5;x++){
                    if(x==0&&y==0)continue;
                    vec4 sampleValue=textureLod(source,uv+vec2(x,y)*stepSize,0.0);
                    vec3 w=sampleValue.a*coefficients[abs(y)*6+abs(x)];
                    sum+=sampleValue.rgb*w;weight+=w;
                }
                colour=vec4(sum/weight*tint,1.0);
            }
            """);
        vao=glGenVertexArrays();sampler=glGenSamplers();glSamplerParameteri(sampler,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glSamplerParameteri(sampler,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glSamplerParameteri(sampler,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glSamplerParameteri(sampler,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        JSONArray coefficients=data.getJSONArray("coefficients"),tint=data.getJSONArray("colour"),step=data.getJSONArray("step");
        if(step.getFloat(0)!=1||step.getFloat(1)!=1||coefficients.length()<144)throw new IllegalArgumentException("Unsupported captured scattering kernel");
        float[] values=new float[108];for(int i=0;i<36;i++)for(int c=0;c<3;c++)values[i*3+c]=coefficients.getFloat(i*4+c);
        glUseProgram(filter);glUniform3fv(glGetUniformLocation(filter,"coefficients"),values);glUniform3f(glGetUniformLocation(filter,"tint"),tint.getFloat(0),tint.getFloat(1),tint.getFloat(2));
        for(int program:new int[]{filter,dilation}){glUseProgram(program);glUniform1i(glGetUniformLocation(program,"source"),0);}
    }
    static int colourTexture(int width,int height){
        int texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA16F,width,height,0,GL_RGBA,GL_FLOAT,(java.nio.ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return texture;
    }
    static int framebuffer(int texture){
        int fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete scattering filter target");return fbo;
    }
    private void create(int w,int h){
        if(width==w&&height==h)return;
        if(filtered!=0){glDeleteTextures(dilated);glDeleteTextures(filtered);glDeleteFramebuffers(dilationFbo);glDeleteFramebuffers(filterFbo);}
        width=w;height=h;dilated=colourTexture(w,h);filtered=colourTexture(w,h);dilationFbo=framebuffer(dilated);filterFbo=framebuffer(filtered);
    }
    private void setup(){
        glViewport(0,0,width,height);glDisable(GL_DEPTH_TEST);glDisable(GL_STENCIL_TEST);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);
        glColorMask(true,true,true,true);glDepthMask(false);glBindVertexArray(vao);glActiveTexture(GL_TEXTURE0);glBindSampler(0,sampler);
    }
    private int blurInternal(int source){
        glBindFramebuffer(GL_FRAMEBUFFER,filterFbo);glUseProgram(filter);glBindTexture(GL_TEXTURE_2D,source);glUniform2f(glGetUniformLocation(filter,"stepSize"),1f/width,1f/height);glDrawArrays(GL_TRIANGLES,0,3);return filtered;
    }
    public int blur(int source,int width,int height){
        try(GlState state=new GlState()){create(width,height);setup();return blurInternal(source);}
    }
    public int apply(int source,int sourceWidth,int sourceHeight,int width,int height){
        try(GlState state=new GlState()){
            create(width,height);setup();glBindFramebuffer(GL_FRAMEBUFFER,dilationFbo);glUseProgram(dilation);glBindTexture(GL_TEXTURE_2D,source);
            glUniform2f(glGetUniformLocation(dilation,"stepSize"),2f/sourceWidth,2f/sourceHeight);glDrawArrays(GL_TRIANGLES,0,3);return blurInternal(dilated);
        }
    }
}
