package divachar;

import java.io.IOException;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.json.*;
import static org.lwjgl.opengl.GL43C.*;

/** Material state built from the player's imported outfit. No reference capture is required. */
final class LocalMaterials {
    static JSONObject manifest(){
        // ReDIVA sss_calc_coef, MIT. Keep the development reference's diffusion scale.
        double[][] radii={{1,2,5},{.2,.4,1.2},{.3,.7,2}};
        double[] profileWeights={.4,.3,.3};
        float[] coefficients=new float[144];
        for(int channel=0;channel<3;channel++)for(int profile=0;profile<3;profile++){
            double[] kernel=new double[6];double sum=0;
            double radius=.22740796394720675*radii[channel][profile];
            for(int i=0;i<6;i++){kernel[i]=Math.exp(-.5*i*i/(radius*radius));sum+=kernel[i];}
            for(int i=0;i<6;i++)kernel[i]/=sum;
            for(int y=0;y<6;y++)for(int x=0;x<6;x++){
                int index=(y*6+x)*4+channel;
                coefficients[index]=(float)(coefficients[index]+kernel[y]*profileWeights[profile]*kernel[x]);
            }
        }
        return new JSONObject().put("textures",new JSONObject()).put("scattering",new JSONObject()
            .put("coefficients",new JSONArray(coefficients)).put("colour",new JSONArray(new float[]{1,1,1})).put("step",new JSONArray(new float[]{1,1})));
    }

    static JSONObject create(JSONObject source,int index)throws IOException{
        boolean blend=ImportMaterials.blended(source);
        String family=source.getString("ShaderName");
        JSONObject textures=new JSONObject();
        int mask=0;
        for(Object value:source.getJSONArray("MaterialTextures")){
            JSONObject slot=(JSONObject)value;
            int binding=switch(slot.getString("Type")){
                case "Color" -> 0;
                case "ColorL1","Translucency" -> 1;
                case "Normal" -> 2;
                case "Specular" -> 3;
                case "EnvironmentCube" -> 5;
                default -> -1;
            };
            if(binding<0||(mask&(1<<binding))!=0)continue;
            String resource=slot.optString("resource","");
            if(resource.isEmpty())throw new IOException("Missing "+slot.getString("Type")+" texture for "+source.getString("Name"));
            textures.put("ps:"+binding,resource);
            mask|=1<<binding;
        }
        if(!textures.has("ps:0"))textures.put("ps:0","white");
        JSONObject target=new JSONObject().put("BlendEnable",blend)
            .put("SrcBlend",factor(source.getString("SrcBlendFactor"))).put("DestBlend",factor(source.getString("DstBlendFactor")))
            .put("SrcBlendAlpha","D3D11_BLEND_ONE").put("DestBlendAlpha","D3D11_BLEND_INV_SRC_ALPHA");
        JSONObject pipeline=new JSONObject().put("blend",new JSONObject().put("RenderTarget",new JSONObject().put("0",target)))
            .put("depth",new JSONObject().put("DepthWriteMask",blend?"ZERO":"ALL"))
            .put("raster",new JSONObject().put("CullMode",source.getBoolean("DoubleSided")?"NONE":"BACK"));
        JSONObject result=new JSONObject().put("source",source).put("name",source.getString("Name")).put("part",source.getString("part"))
            .put("vertex","local-material").put("pixel","local-material").put("buffers",new JSONObject()).put("samplers",new JSONObject())
            .put("textures",textures).put("pipeline",pipeline).put("captureChunk",(blend?10_000:0)+index).put("mapMask",mask);
        if(family.equals("SKIN")&&!blend)result.put("scattering",new JSONObject(result.toString()));
        return result;
    }

    private static String factor(String source){
        return switch(source){
            case "Zero"->"D3D11_BLEND_ZERO";
            case "One"->"D3D11_BLEND_ONE";
            case "SrcAlpha"->"D3D11_BLEND_SRC_ALPHA";
            case "InverseSrcAlpha"->"D3D11_BLEND_INV_SRC_ALPHA";
            case "DstAlpha"->"D3D11_BLEND_DEST_ALPHA";
            case "InverseDstAlpha"->"D3D11_BLEND_INV_DEST_ALPHA";
            default->throw new IllegalArgumentException("Unsupported source blend: "+source);
        };
    }

    static void lighting(JSONObject outfit,JSONObject lighting){
        JSONObject bindings=lighting.getJSONObject("textures");
        for(Object value:outfit.getJSONArray("materials")){
            JSONObject material=(JSONObject)value;
            for(String binding:bindings.keySet()){
                material.getJSONObject("textures").put("ps:"+binding,bindings.getString(binding));
                material.put("mapMask",material.getInt("mapMask")|(1<<Integer.parseInt(binding)));
            }
            material.put("sourceLightDirection",lighting.getJSONArray("direction"));
        }
    }

    static void bind(Assets.Material material,Matrix4f vp,Matrix4f jointInverse,Vector3f eye,boolean scattering){
        int program=material.program;
        JSONObject source=material.source;
        glUniformMatrix4fv(glGetUniformLocation(program,"uProjection"),false,vp.get(new float[16]));
        glUniformMatrix4fv(glGetUniformLocation(program,"uInverseHead"),false,jointInverse.get(new float[16]));
        glUniform3f(glGetUniformLocation(program,"uEye"),eye.x,eye.y,eye.z);
        Vector3f sun=Renderer.sunDirection;
        glUniform3f(glGetUniformLocation(program,"uSun"),sun.x,sun.y,sun.z);
        glUniform1i(glGetUniformLocation(program,"uMaps"),material.data.getInt("mapMask"));
        glUniform1i(glGetUniformLocation(program,"uAnisotropy"),1+((source.getInt("ShaderFlags")>>21)&3));
        glUniform1i(glGetUniformLocation(program,"uFaceMaterial"),source.getString("part").equals("head")&&source.getString("ShaderName").equals("SKIN")?1:0);
        glUniform1f(glGetUniformLocation(program,"uFaceFillStrength"),Main.getFaceSoftFill()/1000f);
        JSONArray direction=material.data.optJSONArray("sourceLightDirection");
        if(direction!=null)glUniform3f(glGetUniformLocation(program,"uSourceKey"),direction.getFloat(0),direction.getFloat(1),direction.getFloat(2));
        glUniform1i(glGetUniformLocation(program,"uFamily"),switch(source.getString("ShaderName")){
            case "SKIN"->1;case "HAIR"->2;case "EYEBALL"->3;case "CLOTH"->4;default->0;
        });
        glUniform1i(glGetUniformLocation(program,"uAlpha"),source.getBoolean("PunchThrough")?1:ImportMaterials.blended(source)?2:0);
        glUniform1i(glGetUniformLocation(program,"uAlphaTexture"),source.getBoolean("AlphaTexture")?1:0);
        glUniform1i(glGetUniformLocation(program,"uAlphaMaterial"),source.getBoolean("AlphaMaterial")?1:0);
        glUniform1i(glGetUniformLocation(program,"uScatterPass"),scattering?1:0);
        glUniform1f(glGetUniformLocation(program,"uShininess"),source.getFloat("Shininess"));
        colour(program,"uDiffuse",source.getJSONArray("Diffuse"));
        colour(program,"uSpecular",source.getJSONArray("Specular"));
        colour(program,"uEmission",source.getJSONArray("Emission"));
    }

    private static void colour(int program,String name,JSONArray value){
        glUniform4f(glGetUniformLocation(program,name),value.getFloat(0),value.getFloat(1),value.getFloat(2),value.getFloat(3));
    }
}
