package divachar;

import zombie.core.textures.Texture;
import static org.lwjgl.opengl.GL43C.*;

/** Bind one material's PBR textures for its Viewpoint batch, then restore the pack's own samplers. */
public final class IrisMaterialBindings {
    private static final ThreadLocal<State> current=ThreadLocal.withInitial(State::new);
    private static boolean tangentPatched,logged;
    private static final String TANGENT="vp_tangent = vec4(vp_toMinecraft(tangent), 1.0);";

    public static String vertex(String source){
        if(!source.contains(TANGENT))return source;
        tangentPatched=true;
        return source.replace(TANGENT,"""
            float divaSign=1.0;
            if((int(indices.w) & 2048) != 0){
                float azimuth=(abs(vp_aUv2.x)-1.5)*6.28318530718;
                float z=clamp(vp_aUv2.y,-1.0,1.0);
                vec3 local=vec3(vec2(cos(azimuth),sin(azimuth))*sqrt(max(0.0,1.0-z*z)),z);
                divaSign=vp_aUv2.x<0.0 ? -1.0 : 1.0;
                int palette=int(uShadowPalette==1 ? indices.y : indices.x);
                if(palette>=0 && skinned){
                    vec4 r0,r1,r2;
                    vp_skin(palette,r0,r1,r2);
                    local=vec3(dot(r0.xyz,local),dot(r1.xyz,local),dot(r2.xyz,local));
                    if(dot(cross(r0.xyz,r1.xyz),r2.xyz)<0.0) divaSign=-divaSign;
                }
                vec3 worldTangent=mat3(model)*local;
                worldTangent-=vp_vNormal*dot(worldTangent,vp_vNormal);
                if(dot(worldTangent,worldTangent)>1.0e-8) tangent=normalize(worldTangent);
            }
            vp_tangent = vec4(vp_toMinecraft(tangent), divaSign);
            """);
    }

    public static boolean canBatch(Object batches,int first,int second){
        try{
            Texture[] textures=(Texture[])Interop.get(batches,"textures");
            Texture a=textures[first],b=textures[second];
            return a==b||(!(a instanceof IrisEntities.Albedo)&&!(b instanceof IrisEntities.Albedo));
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Viewpoint model batch textures",e);}
    }

    public static void begin(){State state=current.get();state.open=true;state.program=0;}

    static void bind(IrisMaterialMaps.Maps maps){
        State state=current.get();
        if(!state.open)return;
        int program=glGetInteger(GL_CURRENT_PROGRAM);
        if(program==0)return;
        if(state.program!=program){
            state.program=program;
            state.normals.unit=samplerUnit(program,"normals");
            state.specular.unit=samplerUnit(program,"specular");
        }
        int active=glGetInteger(GL_ACTIVE_TEXTURE);
        if(tangentPatched)state.normals.bind(maps.normal());
        state.specular.bind(maps.specular());
        glActiveTexture(active);
        if(!logged&&(state.normals.bound||state.specular.bound)){
            logged=true;
            System.out.println("[DivaCharacter] Iris material samplers bound normals="+state.normals.bound+" specular="+state.specular.bound);
        }
    }

    private static int samplerUnit(int program,String name){
        int location=glGetUniformLocation(program,name);
        return location<0?-1:glGetUniformi(program,location);
    }

    public static void restore(){
        State state=current.get();
        if(!state.normals.bound&&!state.specular.bound)return;
        int active=glGetInteger(GL_ACTIVE_TEXTURE);
        state.specular.restore();state.normals.restore();
        glActiveTexture(active);
    }

    public static void end(){restore();current.get().open=false;}

    private static final class State {
        final Binding normals=new Binding(),specular=new Binding();
        boolean open;
        int program;
    }

    private static final class Binding {
        int unit,previous,sampler;
        boolean bound;
        void bind(int texture){
            // The first sixteen units belong to Viewpoint's albedo batch.
            if(unit<16||texture==0)return;
            glActiveTexture(GL_TEXTURE0+unit);
            previous=glGetInteger(GL_TEXTURE_BINDING_2D);
            sampler=glGetInteger(GL_SAMPLER_BINDING);
            glBindSampler(unit,0);glBindTexture(GL_TEXTURE_2D,texture);bound=true;
        }
        void restore(){
            if(!bound)return;
            glActiveTexture(GL_TEXTURE0+unit);
            glBindTexture(GL_TEXTURE_2D,previous);glBindSampler(unit,sampler);bound=false;
        }
    }
}
