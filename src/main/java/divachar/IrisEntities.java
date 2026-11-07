package divachar;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import zombie.asset.AssetPath;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.model.VertexBufferObject.VertexFormat;
import zombie.core.skinnedmodel.model.VertexBufferObject.VertexType;
import zombie.core.textures.Texture;
import static org.lwjgl.opengl.GL43C.*;

/** Submit DIVA skin palettes and materials through Viewpoint's Iris model batches. */
final class IrisEntities {
    private static final int COLOUR=1,SHADOW=2,SOLID=128,DIVA_TANGENT=2048,PLACED=2,VERTEX_BYTES=72;
    private record Part(Assets.Mesh source,ModelMesh mesh,Albedo texture,int buffer,boolean head,boolean doubleSided){}
    private final List<Part> parts=new ArrayList<>();
    private final Set<Integer> materialTextures=new HashSet<>();
    private final float[] rows=new float[256*12];
    private boolean packedMaterials;

    private static final class Host {
        static final Class<?> DRAWS=type("viewpoint.render.ModelDraws");
        static final Method ACTIVE=method(type("viewpoint.platform.IrisPacks"),"active");
        static final Method PREPARE=method(type("viewpoint.render.IrisMode"),"prepare");
        static final Method PIPELINE=method(type("viewpoint.render.IrisMode"),"pipeline");
        static final Method ADD=method(DRAWS,"add",ModelMesh.class,ModelInstance.class,Texture.class,Matrix4f.class,int.class,int.class,int.class,int.class);
        static final Method PALETTE=method(DRAWS,"palette",float[].class,int.class);
        static final Method MATERIAL=method(DRAWS,"material",int.class,float.class,float.class,float.class,float.class);
        static final Method LIGHT=method(DRAWS,"light",int.class,float.class,float.class,float.class,float.class);
        static final Method STAND=method(DRAWS,"stand",int.class,int.class,float.class,float.class,float.class);
        static final Method BOUNDS=method(DRAWS,"bounds",int.class,float.class,float.class,float.class,float.class);

        private static Class<?> type(String name){
            try{return Class.forName(name);}catch(ClassNotFoundException e){throw new IllegalStateException(name,e);}
        }
        private static Method method(Class<?> type,String name,Class<?>... params){
            try{Method method=type.getDeclaredMethod(name,params);method.setAccessible(true);return method;}
            catch(NoSuchMethodException e){throw new IllegalStateException(name,e);}
        }
    }

    static boolean requested()throws ReflectiveOperationException{
        return Host.ACTIVE.invoke(null)!=null&&(Boolean)Host.PREPARE.invoke(null)&&Host.PIPELINE.invoke(null)!=null;
    }

    void submit(Object draws,Assets assets,Matrix4f[] pose,Matrix4f root,org.joml.Vector4f light,boolean firstPerson,
                boolean shadowOnly,boolean pbr)throws ReflectiveOperationException{
        submit(draws,assets,pose,root,light,firstPerson,shadowOnly,pbr,0);
    }

    void submit(Object draws,Assets assets,Matrix4f[] pose,Matrix4f root,org.joml.Vector4f light,boolean firstPerson,
                boolean shadowOnly,boolean pbr,int headVariant)throws ReflectiveOperationException{
        if(packedMaterials!=pbr)release();
        if(parts.isEmpty())upload(assets,pbr);
        int first=Interop.integer(draws,"count"),bones=Interop.integer(draws,"bones");
        Matrix4f identity=new Matrix4f();
        Vector3f centre=root.transformPosition(new Vector3f(0,.83f,0));
        boolean reflected=root.determinant()<0;
        try{
            for(Part part:parts){
                if(!Assets.visibleHead(part.source.data,headVariant))continue;
                int size=part.source.palette.length;
                for(int i=0;i<size;i++){
                    Matrix4f matrix=pose[part.source.palette[i]];
                    for(int r=0;r<3;r++)for(int c=0;c<4;c++)rows[i*12+r*4+c]=matrix.get(c,r);
                }
                int palette=(Integer)Host.PALETTE.invoke(draws,rows,size);
                // Match Viewpoint's whole-character policy for close third-person cameras.
                int flags=(pbr?DIVA_TANGENT:0)|SOLID|SHADOW|(shadowOnly||firstPerson&&part.head?0:COLOUR);
                int cull=part.doubleSided?0:reflected?GL_FRONT:GL_BACK;
                int index=(Integer)Host.ADD.invoke(draws,part.mesh,null,part.texture,identity,palette,palette,cull,flags);
                Host.STAND.invoke(draws,index,PLACED,0f,0f,0f);
                Host.MATERIAL.invoke(draws,index,1f,1f,1f,0f);
                Host.LIGHT.invoke(draws,index,light.x,light.y,light.z,light.w);
                Host.BOUNDS.invoke(draws,index,centre.x,centre.y,centre.z,1.6f);
            }
        }catch(ReflectiveOperationException|RuntimeException error){
            Interop.set(draws,"count",first);
            Interop.set(draws,"bones",bones);
            throw error;
        }
    }

    private void upload(Assets assets,boolean pbr)throws ReflectiveOperationException{
        Map<Assets.Material,Albedo> textures=new IdentityHashMap<>();
        VertexFormat format=new VertexFormat(6);
        format.setElement(0,VertexType.VertexArray,12);
        format.setElement(1,VertexType.NormalArray,12);
        format.setElement(2,VertexType.BlendWeightArray,16);
        format.setElement(3,VertexType.BlendIndexArray,16);
        format.setElement(4,VertexType.TextureCoordArray,8);
        format.setElement(5,VertexType.TextureCoordArray,8);
        format.calculate();
        try(GlState state=new GlState();IrisMaterialMaps converter=pbr?new IrisMaterialMaps(materialTextures):null){
            for(Assets.Mesh source:assets.meshes){
                Assets.Material material=assets.materials.get(source.data.getInt("material"));
                Assets.Texture texture=assets.textures.get(material.data.getJSONObject("textures").optString("ps:0","white"));
                Albedo albedo=textures.computeIfAbsent(material,key->new Albedo(texture,converter==null?null:converter.convert(assets,key)));
                ModelMesh mesh=new ModelMesh(new AssetPath("divachar/"+parts.size()),null,new ModelMesh.MeshAssetParams());
                mesh.vb=new VertexBufferObject();
                VertexBufferObject.Vbo handle=new VertexBufferObject.Vbo();
                handle.eboId=source.ibo;
                handle.numElements=source.indices.length/4;
                handle.vertexStride=VERTEX_BYTES;
                Interop.set(mesh.vb,"vertexFormat",format);
                Interop.set(mesh.vb,"handle",handle);
                ByteBuffer vertices=vertices(source);
                String role=source.data.getString("part");
                boolean doubleSided=material.data.getJSONObject("pipeline").getJSONObject("raster").getString("CullMode").endsWith("NONE");
                handle.vboId=glGenBuffers();
                parts.add(new Part(source,mesh,albedo,handle.vboId,role.equals("head")||role.equals("hair"),doubleSided));
                glBindBuffer(GL_ARRAY_BUFFER,handle.vboId);
                glBufferData(GL_ARRAY_BUFFER,vertices,GL_STATIC_DRAW);
            }
            packedMaterials=pbr;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError error){release();throw error;}
    }

    static ByteBuffer vertices(Assets.Mesh mesh){
        ByteBuffer source=ByteBuffer.wrap(mesh.vertices).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer result=ByteBuffer.allocateDirect(mesh.vertices.length/128*VERTEX_BYTES).order(ByteOrder.nativeOrder());
        for(int offset=0;offset<source.capacity();offset+=128){
            for(int axis=0;axis<3;axis++)result.putFloat(source.getFloat(offset+axis*4));
            for(int axis=0;axis<3;axis++)result.putFloat(source.getFloat(offset+16+axis*4));
            for(int joint=0;joint<4;joint++)result.putFloat(source.getFloat(offset+96+joint*4));
            // DIVA stores palette row offsets. Viewpoint expects joint indices.
            for(int joint=0;joint<4;joint++)result.putFloat(Math.max(0,source.getFloat(offset+112+joint*4)/3));
            result.putFloat(source.getFloat(offset+48)).putFloat(source.getFloat(offset+52));
            float x=source.getFloat(offset+80),y=source.getFloat(offset+84),z=source.getFloat(offset+88);
            float length=(float)Math.sqrt(x*x+y*y+z*z);
            float azimuth=1.5f+(float)(Math.atan2(y,x)/(2*Math.PI));
            result.putFloat(Math.copySign(azimuth,source.getFloat(offset+92))).putFloat(length>0?z/length:0);
        }
        return result.flip();
    }

    void release(){
        for(Part part:parts)glDeleteBuffers(part.buffer);
        for(int texture:materialTextures)glDeleteTextures(texture);
        parts.clear();materialTextures.clear();
    }

    static final class Albedo extends Texture {
        private final Assets.Texture source;
        private final IrisMaterialMaps.Maps maps;
        Albedo(Assets.Texture source,IrisMaterialMaps.Maps maps){this.source=source;this.maps=maps;}
        @Override public boolean isReady(){return source.id!=0;}
        @Override public int getID(){return source.id;}
        @Override public void bind(){glBindTexture(GL_TEXTURE_2D,source.id);if(maps!=null)IrisMaterialBindings.bind(maps);}
    }
}
