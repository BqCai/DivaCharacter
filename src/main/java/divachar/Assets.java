package divachar;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.json.*;
import org.joml.*;
import java.lang.Math;
import static org.lwjgl.opengl.GL43C.*;

/** Local reference resources are packaged inside the mod jar. No source game files are modified. */
public final class Assets {
    public final JSONObject manifest;
    public final List<Material> materials = new ArrayList<>();
    public final List<Material> scatteringMaterials = new ArrayList<>();
    public final List<Mesh> meshes = new ArrayList<>();
    public final Map<String, Texture> textures = new LinkedHashMap<>();
    public final List<String> names = new ArrayList<>();
    public final Matrix4f[] inverseBind, bind, localBind;
    public final int[] parent;
    public final Soles soles;
    final List<OsageData.Chain> osage;
    final GameplayPerformance.Library gameplay;
    final boolean localMaterials;
    final JSONArray referenceBones;
    public boolean uploaded;
    private final Path importedRoot,sharedRoot;
    private final Set<Integer> programs=new HashSet<>();

    public static byte[] bytes(String name) throws IOException {
        try (InputStream stream=Assets.class.getResourceAsStream("/divachar/"+name)) {
            if(stream==null) throw new FileNotFoundException(name);
            return stream.readAllBytes();
        }
    }
    public static ByteBuffer direct(byte[] data) {
        ByteBuffer result=ByteBuffer.allocateDirect(data.length).order(ByteOrder.LITTLE_ENDIAN);
        return result.put(data).flip();
    }
    public static String string(String name) throws IOException {
        return new String(bytes(name),StandardCharsets.UTF_8);
    }
    static boolean hasReference(){return Assets.class.getResource("/divachar/manifest.json")!=null;}
    public Assets() throws IOException {
        this(null);
    }
    Assets(Path importedRoot) throws IOException {
        this(importedRoot,null,null);
    }
    Assets(Path importedRoot,Path sharedRoot,JSONArray referenceBones) throws IOException {
        this.importedRoot=importedRoot;
        this.sharedRoot=sharedRoot;
        localMaterials=sharedRoot!=null;
        manifest=importedRoot==null?new JSONObject(string("manifest.json")):
            ImportMaterials.manifest(new JSONObject(Files.readString(importedRoot.resolve("module.json"))),localMaterials);
        ImportMaterials.appendFaces(manifest,new JSONObject(localMaterials?Files.readString(sharedRoot.resolve("faces.json")):string("faces.json")),localMaterials);
        gameplay=localMaterials?new GameplayPerformance.Library(Files.readAllBytes(sharedRoot.resolve("gameplay.bin"))):null;
        JSONArray bones=manifest.getJSONArray("bones");
        this.referenceBones=referenceBones==null?bones:referenceBones;
        int count=bones.length();
        inverseBind=new Matrix4f[count]; bind=new Matrix4f[count];localBind=new Matrix4f[count];parent=new int[count];
        for(int i=0;i<count;i++) names.add(bones.getJSONObject(i).getString("name"));
        for(int i=0;i<count;i++) {
            JSONObject bone=bones.getJSONObject(i);
            parent[i]=names.indexOf(bone.optString("runtimeParent",bone.optString("parent","")));
            float[] f=new float[16]; JSONArray rows=bone.getJSONArray("inverse_bind");
            for(int r=0;r<4;r++)for(int c=0;c<4;c++)f[r*4+c]=rows.getJSONArray(r).getFloat(c);
            inverseBind[i]=new Matrix4f().set(f);
            bind[i]=new Matrix4f(inverseBind[i]).invert();
        }
        for(int i=0;i<count;i++) localBind[i]=parent[i]<0?new Matrix4f(bind[i]):new Matrix4f(bind[parent[i]]).invert().mul(bind[i]);
        JSONObject tex=manifest.getJSONObject("textures");
        for(String id:tex.keySet())textures.put(id,new Texture(tex.getJSONObject(id),this));
        for(Object m:manifest.getJSONArray("materials")){
            JSONObject data=(JSONObject)m;materials.add(new Material(data));
            scatteringMaterials.add(data.has("scattering")?new Material(data.getJSONObject("scattering")):null);
        }
        for(Object m:manifest.getJSONArray("meshes"))meshes.add(new Mesh((JSONObject)m));
        soles=new Soles(this);
        osage=OsageData.load(this);
    }
    static boolean visibleHead(JSONObject mesh,int variant){
        return !"head".equals(mesh.getString("part"))||mesh.optInt("headVariant",0)==variant;
    }
    byte[] data(String name)throws IOException {
        if(name.startsWith("import/"))return localData(importedRoot,"import",name);
        if(localMaterials&&name.startsWith("faces/"))return localData(sharedRoot,"faces",name);
        return bytes(name);
    }
    private static byte[] localData(Path root,String folder,String name)throws IOException{
        Path path=root.resolve(name).normalize();
        if(!path.startsWith(root.resolve(folder)))throw new IOException("Invalid import resource path");
        return Files.readAllBytes(path);
    }
    public void upload() throws IOException {
        if(uploaded)return;
        Map<String,Integer> linked=new HashMap<>();
        try {
            for(Texture texture:textures.values())texture.upload();
            textures.put("white",new Texture(255)); textures.put("black",new Texture(0));
            for(Material material:materials) material.upload(linked);
            for(Material material:scatteringMaterials)if(material!=null)material.upload(linked);
            for(Mesh mesh:meshes) mesh.upload();
            uploaded=true;
        } finally { programs.addAll(linked.values()); }
    }
    public static int program(String vertex,String pixel) {
        int vs = shader(GL_VERTEX_SHADER, vertex);
        int ps = 0, program = 0;
        try {
            ps = shader(GL_FRAGMENT_SHADER, pixel);
            program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, ps);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("GLSL link: " + glGetProgramInfoLog(program));
            }
            return program;
        } catch (RuntimeException error) {
            if (program != 0) glDeleteProgram(program);
            throw error;
        } finally {
            glDeleteShader(vs);
            if (ps != 0) glDeleteShader(ps);
        }
    }

    private static int materialProgram(String vertex,String pixel){
        // Keep the capture's material Alpha available to the built-in passes. An isolated
        // character layer uses coverage instead, including for existing shader overrides.
        String entry=pixel.replaceFirst("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)", "void divaMaterialMain()");
        return program(vertex,entry+"\nuniform bool uDivaOpaqueCoverage;\n"
            +"void main(){divaMaterialMain();if(uDivaOpaqueCoverage)target0.a=1.0;}\n");
    }
    private static int shader(int type,String source) {
        int s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);
        if(glGetShaderi(s,GL_COMPILE_STATUS)==0){
            String log = glGetShaderInfoLog(s);
            glDeleteShader(s);
            throw new IllegalStateException("GLSL compile: " + log);
        }
        return s;
    }

    /** Link every replacement before switching any draw to the new programs. */
    public void reloadPrograms(Path overrides) throws IOException {
        List<Material> draws = new ArrayList<>(materials);
        for (Material material : scatteringMaterials) {
            if (material != null) draws.add(material);
        }
        Map<String, Integer> replacements = new HashMap<>();
        try {
            for (Material material : draws) {
                if (replacements.containsKey(material.shaderPair())) continue;
                String vertex = material.data.getString("vertex") + ".vert";
                String fragment = material.data.getString("pixel") + ".frag";
                replacements.put(material.shaderPair(), materialProgram(
                    shaderSource(overrides, vertex), shaderSource(overrides, fragment)));
            }
        } catch (IOException | RuntimeException error) {
            replacements.values().forEach(id -> glDeleteProgram(id));
            throw error;
        }
        Set<Integer> oldPrograms = new HashSet<>();
        for (Material material : draws) {
            oldPrograms.add(material.program);
            material.program = replacements.get(material.shaderPair());
        }
        oldPrograms.forEach(id -> glDeleteProgram(id));
        programs.clear();
        programs.addAll(replacements.values());
    }

    void release() {
        for(int id:programs)glDeleteProgram(id);
        programs.clear();
        for(Texture texture:textures.values())if(texture.id!=0){glDeleteTextures(texture.id);texture.id=0;}
        textures.remove("white");textures.remove("black");
        for(Material material:materials)material.release();
        for(Material material:scatteringMaterials)if(material!=null)material.release();
        for(Mesh mesh:meshes){glDeleteVertexArrays(mesh.vao);glDeleteBuffers(mesh.vbo);glDeleteBuffers(mesh.ibo);mesh.vao=mesh.vbo=mesh.ibo=0;}
        uploaded=false;
    }

    private static String shaderSource(Path overrides, String name) throws IOException {
        Path path = overrides.resolve(name);
        return Files.exists(path) ? Files.readString(path) : string("shaders/" + name);
    }

    public final class Material {
        public final JSONObject data,source;
        public int program;
        public final Map<Integer,ByteBuffer> defaults=new TreeMap<>();
        public final Map<Integer,ByteBuffer> working=new TreeMap<>();
        public final Map<Integer,Integer> buffers=new TreeMap<>();
        public final Map<String,Integer> samplers=new HashMap<>();
        public Material(JSONObject data)throws IOException {
            this.data=data;source=data.getJSONObject("source");
            JSONObject cbs=data.getJSONObject("buffers");
            for(String key:cbs.keySet()) {
                int bank=Integer.parseInt(key.substring(3))+(key.startsWith("ps")?8:0);
                defaults.put(bank,direct(bytes(cbs.getString(key))));
                if(data.optBoolean("imported")&&(bank==2||bank==10))
                    ImportMaterials.updateBatch(manifest,data,data.getString(bank==2?"vertex":"pixel"),defaults.get(bank));
                working.put(bank,ByteBuffer.allocateDirect(defaults.get(bank).capacity()).order(ByteOrder.LITTLE_ENDIAN));
            }
            if(localMaterials)defaults.put(3,ByteBuffer.allocateDirect(12288).order(ByteOrder.LITTLE_ENDIAN));
        }
        void release(){buffers.values().forEach(id->glDeleteBuffers(id));buffers.clear();samplers.values().forEach(id->glDeleteSamplers(id));samplers.clear();program=0;}
        private String shaderPair() {
            return data.getString("vertex") + ":" + data.getString("pixel");
        }
        public void upload(Map<String,Integer> programs)throws IOException {
            String vs=data.getString("vertex"),ps=data.getString("pixel"),pair=shaderPair();
            if(!programs.containsKey(pair))programs.put(pair,materialProgram(string("shaders/"+vs+".vert"),string("shaders/"+ps+".frag")));
            program=programs.get(pair);
            for(var e:defaults.entrySet()) {
                int id=glGenBuffers();glBindBuffer(GL_UNIFORM_BUFFER,id);glBufferData(GL_UNIFORM_BUFFER,e.getValue(),GL_DYNAMIC_DRAW);buffers.put(e.getKey(),id);
            }
            JSONObject bindings=data.getJSONObject("textures");
            for(String key:bindings.keySet()){
                int slot=Integer.parseInt(key.substring(3)),s=glGenSamplers();
                JSONObject sourceSlot=null;
                String type=switch(slot){case 0->"Color";case 2->"Normal";case 3->"Specular";case 5->"EnvironmentCube";default->"";};
                // ColorL1 is the second colour layer, where present.
                if(slot==1)type="ColorL1";
                for(Object item:source.getJSONArray("MaterialTextures"))if(type.equals(((JSONObject)item).optString("Type")))sourceSlot=(JSONObject)item;
                glSamplerParameteri(s,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);glSamplerParameteri(s,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
                glSamplerParameteri(s,GL_TEXTURE_WRAP_S,sourceSlot!=null&&sourceSlot.optBoolean("RepeatU")?GL_REPEAT:GL_CLAMP_TO_EDGE);
                glSamplerParameteri(s,GL_TEXTURE_WRAP_T,sourceSlot!=null&&sourceSlot.optBoolean("RepeatV")?GL_REPEAT:GL_CLAMP_TO_EDGE);
                glSamplerParameteri(s,GL_TEXTURE_WRAP_R,GL_CLAMP_TO_EDGE);
                JSONObject captured=data.getJSONObject("samplers").optJSONObject(key);
                if(captured!=null){
                    glSamplerParameteri(s,GL_TEXTURE_WRAP_S,address(captured.getString("AddressU")));
                    glSamplerParameteri(s,GL_TEXTURE_WRAP_T,address(captured.getString("AddressV")));
                    glSamplerParameteri(s,GL_TEXTURE_WRAP_R,address(captured.getString("AddressW")));
                    glSamplerParameterf(s,GL_TEXTURE_LOD_BIAS,captured.getFloat("MipLODBias"));
                    if(captured.getString("Filter").contains("ANISOTROPIC"))glSamplerParameterf(s,0x84FE,captured.getFloat("MaxAnisotropy"));
                }
                samplers.put(key,s);
            }
        }
        private static int address(String source){return switch(source){case "D3D11_TEXTURE_ADDRESS_WRAP"->GL_REPEAT;case "D3D11_TEXTURE_ADDRESS_MIRROR"->GL_MIRRORED_REPEAT;case "D3D11_TEXTURE_ADDRESS_BORDER"->GL_CLAMP_TO_BORDER;default->GL_CLAMP_TO_EDGE;};}
    }
    public final class Mesh {
        public final JSONObject data;
        public final int[] palette;
        public final byte[] vertices,indices;
        public int vao,vbo,ibo;
        public Mesh(JSONObject data)throws IOException {
            this.data=data;JSONArray p=data.getJSONArray("palette");palette=new int[p.length()];
            for(int i=0;i<p.length();i++)palette[i]=p.getInt(i);
            vertices=Assets.this.data(data.getString("vertexFile"));indices=Assets.this.data(data.getString("indexFile"));
        }
        public void upload(){
            vao=glGenVertexArrays();vbo=glGenBuffers();ibo=glGenBuffers();glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,direct(vertices),GL_STATIC_DRAW);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ibo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,direct(indices),GL_STATIC_DRAW);
            for(int i=0;i<8;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,4,GL_FLOAT,false,128,i*16L);}
        }
    }
    public static final class Texture {
        public final JSONObject data;
        public final List<byte[]> contents=new ArrayList<>();
        public int id,target;
        Texture(JSONObject data,Assets assets)throws IOException {
            this.data=data;
            for(Object path:data.getJSONArray("data")) contents.add(assets.data((String)path));
        }
        Texture(int value){
            data=null;target=GL_TEXTURE_2D;id=glGenTextures();glBindTexture(target,id);
            glTexImage2D(target,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,direct(new byte[]{(byte)value,(byte)value,(byte)value,(byte)255}));
            glTexParameteri(target,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(target,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        }
        void upload(){
            int faces=data.getInt("faces"),levels=data.getInt("levels"),w=data.getInt("width"),h=data.getInt("height");
            target=faces==6?GL_TEXTURE_CUBE_MAP:GL_TEXTURE_2D;id=glGenTextures();glBindTexture(target,id);
            String format=data.getString("format");
            int internal=switch(format){
                case "DXGI_FORMAT_BC1_UNORM"->0x83F1;
                case "DXGI_FORMAT_BC2_UNORM"->0x83F2;
                case "DXGI_FORMAT_BC3_UNORM"->0x83F3;
                case "DXGI_FORMAT_BC5_UNORM"->GL_COMPRESSED_RG_RGTC2;
                case "DXGI_FORMAT_R16G16B16A16_FLOAT"->GL_RGBA16F;
                case "DXGI_FORMAT_R8G8B8A8_UNORM"->GL_RGBA8;
                case "DXGI_FORMAT_R32_FLOAT"->GL_R32F;
                default->throw new IllegalArgumentException("Unsupported GPU texture: "+format);
            };
            for(int face=0;face<faces;face++)for(int mip=0;mip<levels;mip++){
                int t=faces==6?GL_TEXTURE_CUBE_MAP_POSITIVE_X+face:target;
                ByteBuffer buffer=direct(contents.get(face*levels+mip));
                if(format.contains("_BC"))glCompressedTexImage2D(t,mip,internal,Math.max(1,w>>mip),Math.max(1,h>>mip),0,buffer);
                else glTexImage2D(t,mip,internal,Math.max(1,w>>mip),Math.max(1,h>>mip),0,internal==GL_R32F?GL_RED:GL_RGBA,internal==GL_RGBA16F?GL_HALF_FLOAT:(internal==GL_R32F?GL_FLOAT:GL_UNSIGNED_BYTE),buffer);
            }
            glTexParameteri(target,GL_TEXTURE_BASE_LEVEL,0);glTexParameteri(target,GL_TEXTURE_MAX_LEVEL,levels-1);
            glTexParameteri(target,GL_TEXTURE_MIN_FILTER,levels>1?GL_LINEAR_MIPMAP_LINEAR:GL_LINEAR);
            glTexParameteri(target,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(target,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(target,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(target,GL_TEXTURE_WRAP_R,GL_CLAMP_TO_EDGE);
        }
    }
}
