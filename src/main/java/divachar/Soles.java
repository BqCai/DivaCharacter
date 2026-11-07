package divachar;

import java.nio.*;
import java.util.*;
import org.joml.*;
import java.lang.Math;

/** Calibrate contact using weighted boot vertices, not the unrelated toe-marker heights. */
public final class Soles {
    private record Sample(float x,float y,float z,int[] bones,float[] weights){}
    private final List<Sample> samples=new ArrayList<>();
    private final float bindFloor;
    public Soles(Assets assets){
        float ankle=0;
        for(String side:new String[]{"l","r"}){
            int bone=assets.names.indexOf("kl_asi_"+side+"_wj_co");
            if(bone>=0)ankle=Math.max(ankle,assets.bind[bone].m31());
        }
        String footwear=assets.meshes.stream().anyMatch(mesh->mesh.data.getString("name").equals("tights"))?"tights":"boots";
        boolean hasFootwear=assets.meshes.stream().anyMatch(mesh->mesh.data.getString("name").equals(footwear));
        for(Assets.Mesh mesh:assets.meshes){
            if(!mesh.data.getString("part").equals("body")||hasFootwear&&!mesh.data.getString("name").equals(footwear))continue;
            FloatBuffer values=ByteBuffer.wrap(mesh.vertices).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            for(int v=0;v<mesh.vertices.length/128;v++){
                int base=v*32;float y=values.get(base+1);if(y>ankle+.02f)continue;
                int[] bones=new int[4];float[] weights=new float[4];
                for(int i=0;i<4;i++){
                    int local=(int)values.get(base+28+i)/3;
                    weights[i]=values.get(base+24+i);bones[i]=weights[i]>0&&local>=0?mesh.palette[local]:-1;
                }
                samples.add(new Sample(values.get(base),y,values.get(base+2),bones,weights));
            }
        }
        if(samples.isEmpty())throw new IllegalStateException("No source boot samples found");
        float floor=Float.POSITIVE_INFINITY;
        for(Sample sample:samples)floor=Math.min(floor,sample.y);
        bindFloor=floor;
    }
    public int count(){return samples.size();}
    float bindFloor(){return bindFloor;}
    public float neutralTiltError(Matrix4f[] skin){
        float floor=Float.POSITIVE_INFINITY;
        for(Sample sample:samples)floor=Math.min(floor,sample.y);
        float[] minimum={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},maximum={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(Sample sample:samples){
            if(sample.y>floor+.003f)continue;float y=0;
            for(int i=0;i<4;i++)if(sample.bones[i]>=0&&sample.weights[i]>0){Matrix4f m=skin[sample.bones[i]];y+=sample.weights[i]*(m.m01()*sample.x+m.m11()*sample.y+m.m21()*sample.z+m.m31());}
            int side=sample.x<0?0:1;float delta=y-sample.y;minimum[side]=Math.min(minimum[side],delta);maximum[side]=Math.max(maximum[side],delta);
        }
        return Math.max(maximum[0]-minimum[0],maximum[1]-minimum[1]);
    }
    public float minimum(Matrix4f[] skin){
        float[] feet=heights(skin);
        return Math.min(feet[0],feet[1]);
    }
    public float[] heights(Matrix4f[] skin){
        float[] minimum={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY};
        for(Sample sample:samples){
            float y=0;
            for(int i=0;i<4;i++){
                if(sample.bones[i]<0||sample.weights[i]<=0)continue;
                Matrix4f m=skin[sample.bones[i]];y+=sample.weights[i]*(m.m01()*sample.x+m.m11()*sample.y+m.m21()*sample.z+m.m31());
            }
            int side=sample.x>=0?0:1;
            minimum[side]=Math.min(minimum[side],y);
        }
        return minimum;
    }
    public static float target(Map<String,Matrix4f> current,Map<String,Matrix4f> bind,float scale){
        float y=Math.min(targetHeight(current,bind,scale,"L"),targetHeight(current,bind,scale,"R"));
        return Float.isFinite(y)?y:0;
    }
    public static float targetHeight(Map<String,Matrix4f> current,Map<String,Matrix4f> bind,float scale,String side){
        String name="Bip01_"+side+"_Foot";
        Matrix4f c=current.get(name),b=bind.get(name);
        if(c==null||b==null)return Float.POSITIVE_INFINITY;
        Vector3f local=new Matrix3f(b).invert().transform(new Vector3f(0,-b.m31(),0));
        return c.transformPosition(local,new Vector3f()).y/scale;
    }
    public float pelvisOffset(Map<String,Matrix4f> current,Map<String,Matrix4f> bind,float scale){
        Matrix4f animated=current.get("Bip01_Pelvis"),neutral=bind.get("Bip01_Pelvis");
        if(animated==null||neutral==null)return Float.NaN;
        return (animated.m31()-neutral.m31())/scale-bindFloor;
    }
}
