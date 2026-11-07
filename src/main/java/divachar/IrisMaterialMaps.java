package divachar;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import static org.lwjgl.opengl.GL43C.*;

/** Convert source textures to the normal and LabPBR specular channels consumed by Iris packs. */
final class IrisMaterialMaps implements AutoCloseable {
    record Maps(int normal,int specular){}
    private final Map<Integer,Integer> normals=new HashMap<>();
    private final Set<Integer> output;
    private int program,framebuffer,vao;

    IrisMaterialMaps(Set<Integer> output){
        this.output=output;
        program=Assets.program("""
            #version 430 core
            out vec2 uv;
            void main(){
                uv=vec2((gl_VertexID << 1) & 2,gl_VertexID & 2);
                gl_Position=vec4(uv*2.0-1.0,0.0,1.0);
            }
            ""","""
            #version 430 core
            in vec2 uv;
            layout(location=0) out vec4 material;
            uniform sampler2D source;
            uniform bool normalMap,hasSpecular;
            uniform vec3 specularTint;
            uniform vec3 coefficients;
            void main(){
                vec4 sampleValue=textureLod(source,uv,0.0);
                if(normalMap){
                    material=vec4(sampleValue.rg,1.0,1.0);
                }else{
                    float strength=hasSpecular ? dot(sampleValue.rgb*specularTint,vec3(0.2126,0.7152,0.0722)) : 0.0;
                    float environment=hasSpecular ? sampleValue.a : 0.0;
                    float f0=clamp(0.02+strength*0.26+environment*0.05,0.02,0.65);
                    material=vec4(coefficients.x,f0,coefficients.y,coefficients.z);
                }
            }
            """);
        framebuffer=glGenFramebuffers();vao=glGenVertexArrays();
    }

    Maps convert(Assets assets,Assets.Material material){
        Assets.Texture normal=source(assets,material,"ps:2"),specular=source(assets,material,"ps:3");
        String family=material.source.getString("ShaderName");
        JSONArray tint=material.source.getJSONArray("Specular"),emission=material.source.getJSONArray("Emission");
        float peak=Math.max(emission.getFloat(0),Math.max(emission.getFloat(1),emission.getFloat(2)));
        float shininess=Math.max(0,material.source.getFloat("Shininess"));
        float smoothness=1-(float)Math.pow(2/(shininess+2),.25);
        float scattering=family.equals("SKIN")?112f/255:0;
        float glow=peak>0?peak/(peak+2):0;
        int normalId=normal==null?0:normals.computeIfAbsent(normal.id,key->render(normal,true,tint,0,0,0));
        int specularId=render(specular,false,tint,smoothness,scattering,glow);
        return new Maps(normalId,specularId);
    }

    private static Assets.Texture source(Assets assets,Assets.Material material,String slot){
        String key=material.data.getJSONObject("textures").optString(slot,"");
        if(key.isEmpty()||key.equals("800")||key.equals("798")||key.equals("black")||key.equals("white"))return null;
        Assets.Texture texture=assets.textures.get(key);
        return texture!=null&&texture.target==GL_TEXTURE_2D?texture:null;
    }

    private int render(Assets.Texture texture,boolean normal,JSONArray tint,float gloss,float sss,float emission){
        int width=texture==null?1:texture.data.getInt("width"),height=texture==null?1:texture.data.getInt("height");
        int result=glGenTextures();
        try{
            glActiveTexture(GL_TEXTURE0);glBindSampler(0,0);glBindTexture(GL_TEXTURE_2D,result);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,width>1||height>1?GL_LINEAR_MIPMAP_LINEAR:GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,result,0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Iris material conversion framebuffer");
            glViewport(0,0,width,height);
            glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDisable(GL_STENCIL_TEST);glDisable(GL_SCISSOR_TEST);glDisable(GL_CULL_FACE);
            glColorMask(true,true,true,true);glUseProgram(program);glBindVertexArray(vao);
            glBindTexture(GL_TEXTURE_2D,texture==null?0:texture.id);
            glUniform1i(glGetUniformLocation(program,"normalMap"),normal?1:0);
            glUniform1i(glGetUniformLocation(program,"hasSpecular"),texture==null?0:1);
            glUniform3f(glGetUniformLocation(program,"specularTint"),tint.getFloat(0),tint.getFloat(1),tint.getFloat(2));
            glUniform3f(glGetUniformLocation(program,"coefficients"),gloss,sss,emission);
            glDrawArrays(GL_TRIANGLES,0,3);
            glBindTexture(GL_TEXTURE_2D,result);glGenerateMipmap(GL_TEXTURE_2D);
            output.add(result);
            return result;
        }catch(RuntimeException|LinkageError error){glDeleteTextures(result);throw error;}
    }

    @Override public void close(){glDeleteFramebuffers(framebuffer);glDeleteVertexArrays(vao);glDeleteProgram(program);}
}
