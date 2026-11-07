package divachar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.json.*;

/** Apply the verified shader families to an outfit's own texture and material data. */
final class ImportMaterials {
    private static final Map<String,String> FORMATS=Map.of(
        "DXT1","DXGI_FORMAT_BC1_UNORM","DXT1a","DXGI_FORMAT_BC1_UNORM",
        "DXT3","DXGI_FORMAT_BC2_UNORM","DXT5","DXGI_FORMAT_BC3_UNORM",
        "ATI2","DXGI_FORMAT_BC5_UNORM","RGBA8","DXGI_FORMAT_R8G8B8A8_UNORM",
        "RGBA16F","DXGI_FORMAT_R16G16B16A16_FLOAT");
    private static final Map<String,String> CONSTANTS=Map.of(
        "g_material_state_diffuse","Diffuse","g_material_state_ambient","Ambient",
        "g_material_state_specular","Specular","g_material_state_emission","Emission");

    static JSONObject manifest(JSONObject raw,boolean local)throws IOException {
        JSONObject reference=local?LocalMaterials.manifest():new JSONObject(Assets.string("manifest.json"));
        appendTextures(reference.getJSONObject("textures"),raw.getJSONObject("textures"));
        JSONArray materials=new JSONArray();
        appendMaterials(materials,reference.optJSONArray("materials"),raw.getJSONArray("materials"),local);
        JSONArray bones=raw.getJSONArray("bones");
        Set<String> names=new HashSet<>();
        for(Object value:bones)names.add(((JSONObject)value).getString("name"));
        if(!names.containsAll(Set.of("j_kao_wj","kl_kosi_etc_wj","kl_mune_b_wj","kl_asi_l_wj_co","kl_asi_r_wj_co")))
            throw new IOException("Imported outfit is missing required Miku bones.");
        for(Object value:bones) {
            JSONObject bone=(JSONObject)value;
            switch(bone.getString("name")) {
                case "kl_te_l_wj" -> bone.put("runtimeParent","j_ude_l_wj");
                case "kl_te_r_wj" -> bone.put("runtimeParent","j_ude_r_wj");
                case "kl_mune_b_wj" -> { if(bone.getString("parent").isEmpty())bone.put("runtimeParent","kl_kosi_etc_wj"); }
                // Some outfits omit the first thigh helper and export the remaining chain under the pelvis.
                // Reattach the surviving root to the thigh; Assets derives the new local matrix from its bind pose.
                case "n_momo_b_l_wj_ex", "n_momo_c_l_wj_ex" -> attachThighHelper(bone,names,"l");
                case "n_momo_b_r_wj_ex", "n_momo_c_r_wj_ex" -> attachThighHelper(bone,names,"r");
            }
        }
        return reference.put("bones",bones).put("meshes",raw.getJSONArray("meshes")).put("materials",materials)
            .put("module",raw.getJSONObject("module")).put("parts",raw.getJSONArray("parts"));
    }

    static void appendFaces(JSONObject outfit,JSONObject raw,boolean local)throws IOException {
        JSONArray materials=outfit.getJSONArray("materials");
        int materialBase=materials.length();
        JSONArray templates=local?null:new JSONObject(Assets.string("manifest.json")).getJSONArray("materials");
        appendMaterials(materials,templates,raw.getJSONArray("materials"),local);
        appendTextures(outfit.getJSONObject("textures"),raw.getJSONObject("textures"));
        JSONArray bones=outfit.getJSONArray("bones"), meshes=outfit.getJSONArray("meshes");
        Map<String,Integer> byName=new HashMap<>();
        for(int i=0;i<bones.length();i++)byName.put(bones.getJSONObject(i).getString("name"),i);
        JSONArray source=raw.getJSONArray("bones");
        int[] indices=new int[source.length()];
        for(int i=0;i<indices.length;i++){
            JSONObject bone=source.getJSONObject(i);
            String name=bone.getString("name");
            Integer index=byName.get(name);
            if(index==null){
                index=bones.length();
                bones.put(bone);
                byName.put(name,index);
            }
            indices[i]=index;
        }
        for(Object value:raw.getJSONArray("meshes")){
            JSONObject mesh=(JSONObject)value;
            JSONArray palette=mesh.getJSONArray("palette");
            for(int i=0;i<palette.length();i++)palette.put(i,indices[palette.getInt(i)]);
            meshes.put(mesh.put("material",materialBase+mesh.getInt("material")));
        }
        if(local&&raw.has("lighting"))LocalMaterials.lighting(outfit,raw.getJSONObject("lighting"));
    }

    private static void appendTextures(JSONObject destination,JSONObject source)throws IOException {
        for(String key:source.keySet()){
            JSONObject info=source.getJSONObject(key);
            String format=FORMATS.get(info.getString("format"));
            if(format==null)throw new IOException("Unsupported texture format " + info.getString("format") + " for " + info.getString("name"));
            destination.put(key,info.put("format",format));
        }
    }

    private static void appendMaterials(JSONArray destination,JSONArray templates,JSONArray source,boolean local)throws IOException {
        for(Object value:source){
            JSONObject material=(JSONObject)value;
            if(local){destination.put(LocalMaterials.create(material,destination.length()));continue;}
            JSONObject template=choose(templates,material), adapted=adapt(template,material);
            if(template.has("scattering"))adapted.put("scattering",adapt(template.getJSONObject("scattering"),material));
            destination.put(adapted);
        }
    }

    private static void attachThighHelper(JSONObject bone,Set<String> names,String side) {
        String parent=bone.getString("parent"),thigh="j_momo_"+side+"_wj";
        if(names.contains(thigh)&&(parent.isEmpty()||parent.equals("kl_kosi_etc_wj")))bone.put("runtimeParent",thigh);
    }

    private static JSONObject choose(JSONArray templates,JSONObject source)throws IOException {
        JSONObject best=null;
        int lowest=Integer.MAX_VALUE;
        for(Object value:templates) {
            JSONObject template=(JSONObject)value, original=template.getJSONObject("source");
            if(!original.getString("ShaderName").equals(source.getString("ShaderName"))||
                original.getBoolean("PunchThrough")!=source.getBoolean("PunchThrough")||
                blended(original)!=blended(source))continue;
            int score=16*Integer.bitCount(original.getInt("ShaderFlags")^source.getInt("ShaderFlags"));
            score+=template.getString("part").equals(source.getString("part"))?0:8;
            score+=original.getString("Name").equals(source.getString("Name"))?0:32;
            score+=Math.min(16,Math.round(Math.abs(original.getFloat("Shininess")-source.getFloat("Shininess"))/8));
            if(score<lowest){lowest=score;best=template;}
        }
        if(best==null)throw new IOException("Unsupported material variant: " + source.getString("Name"));
        return best;
    }

    static boolean blended(JSONObject material) {
        return (material.getBoolean("AlphaTexture")||material.getBoolean("AlphaMaterial"))&&!material.getBoolean("PunchThrough");
    }

    private static JSONObject adapt(JSONObject template,JSONObject source)throws IOException {
        JSONObject result=new JSONObject(template.toString());
        result.remove("scattering");
        result.put("source",source).put("name",source.getString("Name")).put("part",source.getString("part")).put("imported",true);
        JSONObject bindings=result.getJSONObject("textures"), samplers=result.getJSONObject("samplers");
        JSONArray sourceSlots=source.getJSONArray("MaterialTextures");
        for(String key:bindings.keySet()) {
            if(!key.startsWith("ps:"))continue;
            String type=switch(key) {
                case "ps:0" -> "Color";
                case "ps:1" -> source.getString("ShaderName").equals("HAIR")?"Translucency":"ColorL1";
                case "ps:2" -> "Normal";
                case "ps:3" -> "Specular";
                case "ps:5" -> "EnvironmentCube";
                default -> "";
            };
            if(type.isEmpty())continue;
            JSONObject slot=null;
            for(Object value:sourceSlots)if(((JSONObject)value).getString("Type").equals(type)){slot=(JSONObject)value;break;}
            if(slot!=null) {
                String resource=slot.optString("resource","");
                if(resource.isEmpty())throw new IOException("Missing " + type + " texture for " + source.getString("Name"));
                bindings.put(key,resource);
                JSONObject state=samplers.optJSONObject(key);
                if(state!=null)state.put("AddressU",address(slot,"RepeatU")).put("AddressV",address(slot,"RepeatV"));
            } else {
                bindings.put(key,switch(type){case "Normal"->"800";case "EnvironmentCube"->"802";case "Specular"->"798";default->"white";});
            }
        }
        if(source.getBoolean("DoubleSided"))result.getJSONObject("pipeline").getJSONObject("raster").put("CullMode","D3D11_CULL_NONE");
        return result;
    }

    private static String address(JSONObject slot,String axis) {
        return slot.getBoolean(axis)?"D3D11_TEXTURE_ADDRESS_WRAP":"D3D11_TEXTURE_ADDRESS_CLAMP";
    }

    static void updateBatch(JSONObject manifest,JSONObject material,String shader,ByteBuffer buffer) {
        JSONArray fields=manifest.getJSONObject("shaderLayouts").getJSONObject(shader).getJSONObject("constantBuffers").optJSONArray("Batch");
        if(fields==null)return;
        JSONObject source=material.getJSONObject("source");
        int flags=source.getInt("ShaderFlags");
        boolean lit=(flags&0x30)!=0;
        float shininess=source.getFloat("Shininess");
        for(Object value:fields) {
            JSONObject field=(JSONObject)value;
            if(field.getInt("size")!=16)continue;
            String name=field.getString("name"),key=CONSTANTS.get(name);
            int offset=field.getInt("offset");
            if(key!=null){
                JSONArray colour=source.getJSONArray(key);
                for(int channel=0;channel<4;channel++)buffer.putFloat(offset+channel*4,colour.getFloat(channel));
            }else if(name.equals("g_shininess")){
                buffer.putFloat(offset,lit?Math.max(shininess,1):0);
            }else if(name.equals("g_material_state_shininess")){
                // Future Tone uses a cube-blend coefficient and a separate exponent. Eyes use a dedicated coefficient.
                float coefficient=source.getString("ShaderName").equals("EYEBALL")?10:Math.max((shininess-16)/112,0);
                buffer.putFloat(offset,lit?coefficient:0);
            }
        }
    }
}
