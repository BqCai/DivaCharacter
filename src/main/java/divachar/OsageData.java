package divachar;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.json.*;
import static java.lang.Math.*;

/** Original per-object physics metadata. Bone indices refer to the outfit, not the PZ rig. */
final class OsageData {
    record Hinge(float minY,float maxY,float minZ,float maxZ){
        boolean limited(){return minY>-3.14f||maxY<3.14f||minZ>-3.14f||maxZ<3.14f;}
    }
    record Node(String name,int bone,Matrix4f bind,Matrix4f link,Vector3f end,float radius,
                float weight,float inverseMass,float force,Hinge hinge,String sibling,float siblingDistance){}
    record Attachment(int bone,Vector3f offset,boolean translationOnly){}
    record Collider(int type,Attachment first,Attachment second,float radius){}
    record Link(int from,String target,int to){}
    record Chain(String name,String role,OsageAnchor anchor,List<Node> nodes,List<Collider> colliders,List<Link> links,String collisionTarget,
                 float retention,float friction,float stiffness,float wind,float rotY,float rotZ,int collisionMode){}

    static List<Chain> load(Assets assets){
        boolean bundled=assets.manifest.optJSONArray("parts")==null;
        List<Chain> chains=new ArrayList<>();
        Set<Integer> driven=new HashSet<>();
        try{
            JSONArray parts=bundled?new JSONArray(new String(Assets.bytes("physics.json"),StandardCharsets.UTF_8))
                :assets.manifest.getJSONArray("parts");
            for(Object item:parts){
                JSONObject part=(JSONObject)item;
                if(part.isNull("physicsFile"))continue;
                String file=part.getString("physicsFile");
                Properties fields=new Properties();
                try{
                    fields.load(new ByteArrayInputStream(bundled?Assets.bytes(file):assets.data(file)));
                }catch(IOException error){
                    System.out.println("[DivaCharacter] Osage parameters unavailable for "+part.optString("name")+": "+error.getMessage());
                    continue;
                }
                JSONArray blocks=part.getJSONArray("skinBlocks");
                Map<String,JSONObject> controls=new HashMap<>();
                for(Object value:blocks){
                    JSONObject block=(JSONObject)value;
                    if(block.getString("type").equals("ExpressionBlock")){
                        JSONObject control=block.getJSONObject("data");
                        controls.put(control.getString("Name"),control);
                    }
                }
                for(Object value:blocks){
                    JSONObject block=(JSONObject)value;
                    if(!block.getString("type").equals("OsageBlock"))continue;
                    JSONObject source=block.getJSONObject("data");
                    try{
                        Chain chain=read(assets,source,fields,controls.get(source.optString("ParentName")),part.getString("role"));
                        if(chain!=null&&chain.nodes.stream().noneMatch(n->driven.contains(n.bone))){
                            chains.add(chain);
                            for(Node node:chain.nodes)if(node.bone>=0)driven.add(node.bone);
                        }
                    }catch(IllegalArgumentException|JSONException error){
                        System.out.println("[DivaCharacter] Skipped Osage "+source.optString("ExternalName")+": "+error.getMessage());
                    }
                }
            }
        }catch(IOException|JSONException error){
            System.out.println("[DivaCharacter] Osage index unavailable: "+error.getMessage());
        }
        return List.copyOf(chains);
    }

    private static Chain read(Assets assets,JSONObject source,Properties fields,JSONObject control,String role){
        String name=source.getString("ExternalName"),root=name+".root.";
        JSONArray raw=source.getJSONArray("Nodes");
        if(raw.isEmpty()||raw.length()>128||!fields.containsKey(name+".node.length"))return null;
        int size=raw.length();
        int[] bones=new int[size];
        Matrix4f[] bind=new Matrix4f[size];
        float[] lengths=new float[size];
        for(int i=0;i<size;i++){
            JSONObject row=raw.getJSONObject(i);
            bones[i]=assets.names.indexOf(row.getString("Name"));
            lengths[i]=row.getFloat("Length");
            if(!Float.isFinite(lengths[i])||lengths[i]<=0)throw new IllegalArgumentException("invalid link length");
            if(bones[i]>=0)bind[i]=assets.bind[bones[i]];
            else if(i>0){
                JSONObject rot=row.optJSONObject("Rotation");
                bind[i]=new Matrix4f(bind[i-1]).translate(lengths[i-1],0,0);
                if(rot!=null)bind[i].rotateZYX(rot.getFloat("Z"),rot.getFloat("Y"),rot.getFloat("X"));
            }else return null;
        }
        List<Node> nodes=new ArrayList<>(size);
        float force=number(fields,root+"force",0),gain=number(fields,root+"force_gain",1);
        float hingeY=number(fields,root+"hinge_y",180),hingeZ=number(fields,root+"hinge_z",180);
        for(int i=0;i<size;i++){
            JSONObject row=raw.getJSONObject(i);
            Matrix4f inverse=new Matrix4f(bind[i]).invert();
            Vector3f end=i+1<size?inverse.transformPosition(bind[i+1].getTranslation(new Vector3f())):new Vector3f(lengths[i],0,0);
            if(!end.isFinite()||end.lengthSquared()<1e-8f)throw new IllegalArgumentException("invalid bind direction");
            Matrix4f link=i>0?new Matrix4f(bind[i-1]).invert().mul(bind[i]):new Matrix4f();
            String prefix=name+".node."+i+".";
            float weight=max(1,number(fields,prefix+"weight",1));
            float cancel=clamp(number(fields,prefix+"inertial_cancel",0),0,1);
            Hinge hinge=new Hinge(angle(fields,prefix+"hinge_ymin",-hingeY),angle(fields,prefix+"hinge_ymax",hingeY),
                angle(fields,prefix+"hinge_zmin",-hingeZ),angle(fields,prefix+"hinge_zmax",hingeZ));
            if(hinge.minY>hinge.maxY||hinge.minZ>hinge.maxZ)throw new IllegalArgumentException("invalid hinge interval");
            nodes.add(new Node(row.getString("Name"),bones[i],bind[i],link,end,max(0,number(fields,prefix+"coli_r",0)),
                weight,1/(weight-(weight-1)*cancel),force,hinge,row.optString("SiblingName",""),row.optFloat("SiblingMaxDistance",0)));
            force*=gain;
        }
        List<Collider> colliders=new ArrayList<>();
        for(int i=0,count=count(fields,root+"coli.length");i<count;i++){
            String prefix=root+"coli."+i+".";
            int type=(int)number(fields,prefix+"type",0);
            if(type<1||type>4)continue;
            Attachment first=attachment(assets,fields,prefix+"bone.0.");
            Attachment second=type==1?first:attachment(assets,fields,prefix+"bone.1.");
            if(first!=null&&second!=null)colliders.add(new Collider(type,first,second,max(0,number(fields,prefix+"radius",0))));
        }
        List<Link> links=new ArrayList<>();
        for(int i=0,count=count(fields,root+"boc.length");i<count;i++){
            String prefix=root+"boc."+i+".";
            String target=fields.getProperty(prefix+"ed_root","");
            if(!target.isEmpty())links.add(new Link((int)number(fields,prefix+"st_node",0),target,
                (int)number(fields,prefix+"ed_node",0)));
        }
        float resistance=clamp(number(fields,root+"air_res",.5f),0,.9f);
        return new Chain(name,role,OsageAnchor.read(assets,control,bind[0]),List.copyOf(nodes),List.copyOf(colliders),List.copyOf(links),
            fields.getProperty(root+"colli_tgt_osg",""),(float)exp(-((1-resistance)*18+2)/60),
            clamp(number(fields,root+"friction",.5f),0,1),clamp(number(fields,root+"stiffness",0),0,1),
            name.startsWith("c_opai")?0:clamp(number(fields,root+"wind_afc",.5f),0,1),
            angle(fields,root+"rot_y",0),angle(fields,root+"rot_z",0),(int)number(fields,root+"coli_type",0));
    }

    private static int count(Properties fields,String key){return (int)clamp(number(fields,key,0),0,128);}
    private static float angle(Properties fields,String key,float fallback){
        return (float)toRadians(clamp(number(fields,key,fallback),-180,180));
    }
    private static float number(Properties fields,String key,float fallback){
        float value=Float.parseFloat(fields.getProperty(key,Float.toString(fallback)));
        if(!Float.isFinite(value))throw new IllegalArgumentException("invalid "+key);
        return value;
    }

    private static Attachment attachment(Assets assets,Properties fields,String key){
        String name=fields.getProperty(key+"name","");
        int bone=assets.names.indexOf(name);
        Vector3f offset=new Vector3f(number(fields,key+"posx",0),number(fields,key+"posy",0),number(fields,key+"posz",0));
        if(bone>=0)return new Attachment(bone,offset,false);
        if(!name.equals("n_hara_cp"))return null;
        // The source root is a translation control. Its local offsets use DIVA scene axes.
        int pelvis=assets.names.indexOf("kl_kosi_etc_wj");
        return pelvis<0?null:new Attachment(pelvis,offset,true);
    }
}
