package divachar;

import java.nio.*;
import java.util.*;
import org.joml.*;
import org.json.*;
import static org.lwjgl.opengl.GL43C.*;

public final class Renderer {
    private final Assets assets;
    public static int hostLight;
    public static int hostScattering;
    public static boolean lightProxy;
    public static final Vector4f engineLight=new Vector4f(1);
    public static final Vector3f sunDirection=new Vector3f(0,1,0);
    private final Map<String,Integer> offsets=new HashMap<>();
    private final ByteBuffer skin=ByteBuffer.allocateDirect(12288).order(ByteOrder.LITTLE_ENDIAN);
    public Renderer(Assets assets){this.assets=assets;}
    static void rows(ByteBuffer b,int offset,Matrix4f matrix,int count){
        for(int r=0;r<count;r++)for(int c=0;c<4;c++)b.putFloat(offset+r*16+c*4,matrix.get(c,r));
    }
    private int offset(String shader,String block,String name){
        String key=shader+":"+block+":"+name;Integer cached=offsets.get(key);if(cached!=null)return cached;
        JSONArray fields=assets.manifest.getJSONObject("shaderLayouts").getJSONObject(shader).getJSONObject("constantBuffers").optJSONArray(block);
        if(fields!=null)for(Object item:fields){JSONObject f=(JSONObject)item;if(name.equals(f.getString("name"))){int offset=f.getInt("offset");offsets.put(key,offset);return offset;}}
        offsets.put(key,-1);
        return -1;
    }
    public int render(Matrix4f vp,Matrix4f view,Vector3f eye,Matrix4f[] palette,int height,boolean hideHead)throws Exception{
        return render(vp,view,eye,palette,height,hideHead,false);
    }
    public int render(Matrix4f vp,Matrix4f view,Vector3f eye,Matrix4f[] palette,int height,boolean hideHead,boolean shadow)throws Exception{
        return render(vp,view,eye,palette,height,hideHead,shadow,false);
    }
    public int render(Matrix4f vp,Matrix4f view,Vector3f eye,Matrix4f[] palette,int height,boolean hideHead,boolean shadow,boolean scattering)throws Exception{
        return render(vp,view,eye,palette,height,hideHead,shadow,scattering,0);
    }
    int render(Matrix4f vp,Matrix4f view,Vector3f eye,Matrix4f[] palette,int height,boolean hideHead,boolean shadow,boolean scattering,int headVariant)throws Exception{
        return render(vp,view,eye,palette,height,hideHead,shadow,scattering,false,false,headVariant);
    }
    void renderHairShadow(Matrix4f vp,Matrix4f view,Vector3f eye,Matrix4f[] palette,int size)throws Exception{
        render(vp,view,eye,palette,size,false,true,false,true,false,0);
    }
    void renderColourLayer(Matrix4f vp,Matrix4f view,Vector3f eye,Matrix4f[] palette,int height,boolean hideHead,int headVariant)throws Exception{
        render(vp,view,eye,palette,height,hideHead,false,false,false,true,headVariant);
    }
    private int render(Matrix4f vp,Matrix4f view,Vector3f eye,Matrix4f[] palette,int height,boolean hideHead,boolean shadow,boolean scattering,boolean hairOnly,boolean coverage,int headVariant)throws Exception{
        if(!assets.uploaded)assets.upload();
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);glDisable(GL_CULL_FACE);glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);
        glColorMask(!shadow,!shadow,!shadow,!shadow);
        Matrix4f dxvp=new Matrix4f(vp);
        Matrix4f viewInverse=new Matrix4f(view).invert(),joint=EyeFrame.joint(assets,palette),jointInverse=new Matrix4f(joint).invert();
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        var calibration = Tuning.materials;
        // DXBC expects [0,w] clip Z. The generated vertex epilogue converts it back.
        for(int c=0;c<4;c++)dxvp.set(c,2,(vp.get(c,2)+vp.get(c,3))*.5f);
        List<Assets.Mesh> ordered=new ArrayList<>();
        for(Assets.Mesh mesh:assets.meshes)if(Assets.visibleHead(mesh.data,headVariant))ordered.add(mesh);
        ordered.sort(Comparator.comparingInt(mesh->{Assets.Material m=(scattering?assets.scatteringMaterials:assets.materials).get(mesh.data.getInt("material"));return m==null?Integer.MAX_VALUE:m.data.getInt("captureChunk");}));
        int draws=0;
        for(Assets.Mesh mesh:ordered){
            String part=mesh.data.getString("part");if(!shadow&&hideHead&&(part.equals("head")||part.equals("hair")))continue;
            if(hairOnly&&!part.equals("hair"))continue;
            Assets.Material m=(scattering?assets.scatteringMaterials:assets.materials).get(mesh.data.getInt("material"));if(m==null)continue;
            JSONObject pipeline=m.data.getJSONObject("pipeline"),blend=pipeline.getJSONObject("blend").getJSONObject("RenderTarget").getJSONObject("0");
            boolean blended=blend.getBoolean("BlendEnable"),writesDepth=pipeline.getJSONObject("depth").getString("DepthWriteMask").endsWith("ALL");
            // Facial decals shade over the face. They must not displace its depth in the shared host G-buffer.
            if(lightProxy&&part.equals("head")&&blended&&!writesDepth)continue;
            glUseProgram(m.program);glUniform1f(glGetUniformLocation(m.program,"uViewportHeight"),height);
            glUniform1f(glGetUniformLocation(m.program,"uCharacterGain"),scattering?1:Tuning.gain);
            glUniform1i(glGetUniformLocation(m.program,"uShadowPass"),shadow?1:0);
            boolean host=!shadow&&!lightProxy&&hostLight!=0;
            glUniform1i(glGetUniformLocation(m.program,"uLightProxy"),lightProxy?1:0);
            glUniform4f(glGetUniformLocation(m.program,"uEngineLight"),engineLight.x,engineLight.y,engineLight.z,engineLight.w);
            glUniform1i(glGetUniformLocation(m.program,"uHostLighting"),host?1:0);
            glUniform1f(glGetUniformLocation(m.program,"uCorneaGain"),calibration.corneaGain());
            glUniform3f(glGetUniformLocation(m.program,"uSkinAlbedoScale"),
                calibration.skinRed(),calibration.skinGreen(),calibration.skinBlue());
            glUniform1i(glGetUniformLocation(m.program,"uHostLightMap"),31);
            glUniform1i(glGetUniformLocation(m.program,"uHostScattering"),host&&!scattering&&hostScattering!=0?1:0);
            glUniform1i(glGetUniformLocation(m.program,"uHostScatterMap"),30);
            glUniform2f(glGetUniformLocation(m.program,"uHostLightSize"),viewport[2],viewport[3]);
            if(assets.localMaterials)LocalMaterials.bind(m,vp,jointInverse,eye,scattering);
            if(host){glActiveTexture(GL_TEXTURE0+31);glBindTexture(GL_TEXTURE_2D,hostLight);glBindSampler(31,0);}
            if(host&&!scattering&&hostScattering!=0){glActiveTexture(GL_TEXTURE0+30);glBindTexture(GL_TEXTURE_2D,hostScattering);glBindSampler(30,0);}
            for(var entry:m.defaults.entrySet()){
                int bank=entry.getKey();
                if(bank==3){
                    skin.clear();
                    for(int i=0;i<mesh.palette.length;i++)rows(skin,i*48,palette[mesh.palette[i]],3);
                    glBindBuffer(GL_UNIFORM_BUFFER,m.buffers.get(bank));glBufferSubData(GL_UNIFORM_BUFFER,0,skin);glBindBufferBase(GL_UNIFORM_BUFFER,bank,m.buffers.get(bank));continue;
                }
                ByteBuffer data=m.working.get(bank);data.clear();data.put(entry.getValue().duplicate());data.flip();
                // Per-draw matrices and pose are replaced; original material constants remain intact.
                if(bank==0||bank==8){
                    // Original stage shadow maps and height fog belong to the DIVA scene.
                    data.putInt(8,data.getInt(8)&~(1<<9));data.putInt(4,data.getInt(4)&~(3<<2));
                }
                if(bank==1||bank==9){
                    String shader=m.data.getString(bank==1?"vertex":"pixel");
                    int pos=offset(shader,"Scene","g_view_position");if(pos>=0){data.putFloat(pos,eye.x);data.putFloat(pos+4,eye.y);data.putFloat(pos+8,eye.z);}
                    int v=offset(shader,"Scene","g_view");if(v>=0)rows(data,v,view,3);
                    int inverse=offset(shader,"Scene","g_view_inverse");if(inverse>=0)rows(data,inverse,viewInverse,3);
                    int size=offset(shader,"Scene","g_framebuffer_size");if(size>=0){data.putFloat(size,1f/viewport[2]);data.putFloat(size+4,1f/viewport[3]);data.putFloat(size+8,1f/viewport[2]);data.putFloat(size+12,1f/viewport[3]);}
                    if(host){
                        int direction=offset(shader,"Scene","g_light_chara_dir");if(direction>=0){data.putFloat(direction,sunDirection.x);data.putFloat(direction+4,sunDirection.y);data.putFloat(direction+8,sunDirection.z);}
                        int diffuse=offset(shader,"Scene","g_light_env_chara_diffuse");if(diffuse>=0)for(int i=0;i<3;i++)data.putFloat(diffuse+i*4,1);
                        int face=offset(shader,"Scene","g_light_face_diff");if(face>=0)for(int i=0;i<3;i++)data.putFloat(face+i*4,data.getFloat(face+i*4)*(engineLight.x+engineLight.y+engineLight.z)/3);
                    }
                }
                if(bank==2||bank==10){
                    rows(data,0,dxvp,4);rows(data,64,new Matrix4f(),3);rows(data,112,new Matrix4f(),3);rows(data,160,view,3);rows(data,208,viewInverse,3);
                    rows(data,256,joint,3);rows(data,304,jointInverse,3);
                }
                glBindBuffer(GL_UNIFORM_BUFFER,m.buffers.get(bank));glBufferSubData(GL_UNIFORM_BUFFER,0,data);glBindBufferBase(GL_UNIFORM_BUFFER,bank,m.buffers.get(bank));
            }
            JSONObject bindings=m.data.getJSONObject("textures");int unit=0;
            for(String key:bindings.keySet()){
                Assets.Texture t=assets.textures.get(bindings.getString(key));
                int textureUnit=assets.localMaterials?Integer.parseInt(key.substring(3)):unit++;
                glActiveTexture(GL_TEXTURE0+textureUnit);glBindTexture(t.target,t.id);glBindSampler(textureUnit,m.samplers.get(key));
                glUniform1i(glGetUniformLocation(m.program,key.substring(0,2)+"_t"+key.substring(3)),textureUnit);
            }
            glUniform1i(glGetUniformLocation(m.program,"uDivaOpaqueCoverage"),coverage&&!blended?1:0);
            GlState.enabled(GL_BLEND,!shadow&&!lightProxy&&blended);glBlendEquation(GL_FUNC_ADD);
            glBlendFuncSeparate(factor(blend.getString("SrcBlend")),factor(blend.getString("DestBlend")),
                coverage?GL_ONE:factor(blend.getString("SrcBlendAlpha")),
                coverage?GL_ONE_MINUS_SRC_ALPHA:factor(blend.getString("DestBlendAlpha")));
            glDepthMask(shadow||lightProxy||writesDepth);
            GlState.enabled(GL_CULL_FACE,!pipeline.getJSONObject("raster").getString("CullMode").endsWith("NONE"));glCullFace(GL_BACK);glFrontFace(palette[0].determinant()<0?GL_CW:GL_CCW);
            glBindVertexArray(mesh.vao);glDrawElements(GL_TRIANGLES,mesh.data.getInt("indices"),GL_UNSIGNED_INT,0L);
            draws++;
        }
        return draws;
    }
    private static int factor(String source){return switch(source){case "D3D11_BLEND_ZERO"->GL_ZERO;case "D3D11_BLEND_ONE"->GL_ONE;case "D3D11_BLEND_SRC_ALPHA"->GL_SRC_ALPHA;case "D3D11_BLEND_INV_SRC_ALPHA"->GL_ONE_MINUS_SRC_ALPHA;case "D3D11_BLEND_DEST_ALPHA"->GL_DST_ALPHA;case "D3D11_BLEND_INV_DEST_ALPHA"->GL_ONE_MINUS_DST_ALPHA;default->throw new IllegalArgumentException(source);};}
}
