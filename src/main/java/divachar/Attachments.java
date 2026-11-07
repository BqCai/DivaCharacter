package divachar;

import java.util.*;
import org.joml.*;
import zombie.core.skinnedmodel.model.ModelInstance;

/** Move held props between anatomical wrist frames without scaling the prop mesh. */
public final class Attachments {
    public static final int LEFT=0,RIGHT=1;
    public static int side(String bone){
        if("Bip01_L_Hand".equals(bone)||"Bip01_Prop2".equals(bone))return LEFT;
        if("Bip01_R_Hand".equals(bone)||"Bip01_Prop1".equals(bone))return RIGHT;
        return -1;
    }
    public static int side(ModelInstance instance){
        Set<ModelInstance> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        while(instance!=null&&visited.add(instance)){
            int side=side(instance.parentBoneName);if(side>=0)return side;
            instance=instance.parent;
        }
        return -1;
    }
    private static Matrix4f frame(Matrix4f root,Matrix4f wrist,Matrix4f finger,Matrix4f thumb){
        if(wrist==null||finger==null||thumb==null)return null;
        Vector3f origin=root.transformPosition(wrist.getTranslation(new Vector3f()));
        Vector3f along=root.transformPosition(finger.getTranslation(new Vector3f())).sub(origin);
        Vector3f across=root.transformPosition(thumb.getTranslation(new Vector3f())).sub(origin);
        if(along.lengthSquared()<1e-10f)return null;along.normalize();across.fma(-across.dot(along),along);
        if(across.lengthSquared()<1e-10f)return null;across.normalize();Vector3f normal=along.cross(across,new Vector3f()).normalize();
        return new Matrix4f().setColumn(0,new Vector4f(along,0)).setColumn(1,new Vector4f(across,0)).setColumn(2,new Vector4f(normal,0)).setTranslation(origin);
    }
    public static Matrix4f[] deltas(Assets assets,Matrix4f[] divaGlobal,Matrix4f divaRoot,Map<String,Matrix4f> host,Matrix4f hostRoot){
        Matrix4f[] result=new Matrix4f[2];
        for(int side=0;side<2;side++){
            String source=side==LEFT?"l":"r",prefix="Bip01_"+(side==LEFT?"L":"R")+"_";
            int hand=assets.names.indexOf("kl_te_"+source+"_wj"),finger=assets.names.indexOf("nl_naka_"+source+"_wj"),thumb=assets.names.indexOf("nl_oya_"+source+"_wj");
            if(hand<0||finger<0||thumb<0)continue;
            Matrix4f from=frame(hostRoot,host.get(prefix+"Hand"),host.get(prefix+"Finger1"),host.get(prefix+"Finger0"));
            Matrix4f to=frame(divaRoot,divaGlobal[hand],divaGlobal[finger],divaGlobal[thumb]);
            if(from!=null&&to!=null)result[side]=to.mul(from.invert());
        }
        return result;
    }
    public static Matrix4f relocate(float[] values,int draw,Matrix4f delta){
        int base=draw*68;
        Matrix4f relocated=new Matrix4f(delta).mul(new Matrix4f().set(values,base));relocated.get(values,base);
        Vector3f centre=delta.transformPosition(new Vector3f(values[base+24],values[base+25],values[base+26]));
        values[base+24]=centre.x;values[base+25]=centre.y;values[base+26]=centre.z;
        return relocated;
    }
}
