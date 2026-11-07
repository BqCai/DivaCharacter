package divachar;

import java.util.*;
import java.lang.Math;
import org.joml.*;

/** Preserve DIVA segment lengths while following the anatomical directions of the PZ pose. */
public final class Retargeter {
    private static final Map<String,String> mapping=new HashMap<>();
    private static final List<String[]> segments=new ArrayList<>();
    static{
        mapping.put("kl_kosi_etc_wj","Bip01_Pelvis");mapping.put("kl_mune_b_wj","Bip01_Spine1");mapping.put("j_kao_wj","Bip01_Head");
        for(String side:new String[]{"l","r"}){
            String p="Bip01_"+(side.equals("l")?"L":"R")+"_";
            mapping.put("kl_waki_"+side+"_wj",p+"Clavicle");mapping.put("j_kata_"+side+"_wj_cu",p+"UpperArm");
            mapping.put("j_ude_"+side+"_wj",p+"Forearm");mapping.put("kl_te_"+side+"_wj",p+"Hand");
            mapping.put("j_momo_"+side+"_wj",p+"Thigh");mapping.put("j_sune_"+side+"_wj",p+"Calf");
            mapping.put("kl_asi_"+side+"_wj_co",p+"Foot");
            segments.add(new String[]{"kl_waki_"+side+"_wj","j_kata_"+side+"_wj_cu",p+"Clavicle",p+"UpperArm"});
            segments.add(new String[]{"j_kata_"+side+"_wj_cu","j_ude_"+side+"_wj",p+"UpperArm",p+"Forearm"});
            segments.add(new String[]{"j_ude_"+side+"_wj","kl_te_"+side+"_wj",p+"Forearm",p+"Hand"});
            segments.add(new String[]{"j_momo_"+side+"_wj","j_sune_"+side+"_wj",p+"Thigh",p+"Calf"});
            segments.add(new String[]{"j_sune_"+side+"_wj","kl_asi_"+side+"_wj_co",p+"Calf",p+"Foot"});
        }
    }
    public static Matrix4f[] global(Assets assets,Map<String,Matrix4f> current,Map<String,Matrix4f> bind){
        return global(assets,current,bind,1,Float.NaN);
    }
    public static Matrix4f[] global(Assets assets,Map<String,Matrix4f> current,Map<String,Matrix4f> bind,float scale,float pelvisOffset){
        Map<String,Matrix4f> convertedCurrent=new HashMap<>(),convertedBind=new HashMap<>();
        current.forEach((name,matrix)->convertedCurrent.put(name,Basis.pzToDiva(matrix)));
        bind.forEach((name,matrix)->convertedBind.put(name,Basis.pzToDiva(matrix)));
        return divaGlobal(assets,convertedCurrent,convertedBind,scale,pelvisOffset);
    }
    private static Matrix4f[] divaGlobal(Assets assets,Map<String,Matrix4f> current,Map<String,Matrix4f> bind,float scale,float pelvisOffset){
        int count=assets.names.size();Quaternionf[] desired=new Quaternionf[count];
        for(int i=0;i<count;i++){
            String pz=mapping.get(assets.names.get(i));Matrix4f c=current.get(pz),b=bind.get(pz);
            if(c==null||b==null)continue;
            desired[i]=c.getUnnormalizedRotation(new Quaternionf()).mul(b.getUnnormalizedRotation(new Quaternionf()).invert()).mul(assets.bind[i].getUnnormalizedRotation(new Quaternionf()));
        }
        for(String[] segment:segments){
            int from=assets.names.indexOf(segment[0]),to=assets.names.indexOf(segment[1]);
            Matrix4f c=current.get(segment[2]),end=current.get(segment[3]);
            if(from<0||to<0||c==null||end==null)continue;
            Vector3f source=assets.bind[to].getTranslation(new Vector3f()).sub(assets.bind[from].getTranslation(new Vector3f()));
            Vector3f target=end.getTranslation(new Vector3f()).sub(c.getTranslation(new Vector3f()));
            if(source.lengthSquared()<1e-8f||target.lengthSquared()<1e-8f)continue;
            Quaternionf rotation=desired[from];if(rotation==null)rotation=assets.bind[from].getUnnormalizedRotation(new Quaternionf());
            // Start from the calibrated rotation, then correct swing while preserving its twist.
            Vector3f local=assets.bind[from].getUnnormalizedRotation(new Quaternionf()).invert().transform(source.normalize());
            Vector3f oriented=rotation.transform(local,new Vector3f()).normalize();
            desired[from]=new Quaternionf().rotationTo(oriented,target.normalize()).mul(rotation);
        }
        // The garment and upper-arm skin are weighted primarily to these source
        // corrective chains, rather than only to the visible motion joints.
        for(String side:new String[]{"l","r"}){
            follow(assets,desired,"j_kata_"+side+"_wj_cu","n_skata_"+side+"_wj_cd_ex");
            blendBend(assets,desired,"j_kata_"+side+"_wj_cu","j_ude_"+side+"_wj","n_hiji_"+side+"_wj_ex");
            followLeg(assets,desired,side);
        }
        for(String side:new String[]{"l","r"}){
            String p="Bip01_"+(side.equals("l")?"L":"R")+"_";
            int hand=assets.names.indexOf("kl_te_"+side+"_wj"),finger=assets.names.indexOf("nl_naka_"+side+"_wj"),thumb=assets.names.indexOf("nl_oya_"+side+"_wj");
            Matrix4f h=current.get(p+"Hand"),f=current.get(p+"Finger1"),t=current.get(p+"Finger0");
            if(hand<0||finger<0||thumb<0||h==null||f==null||t==null)continue;
            Quaternionf source=palm(assets.bind[hand],assets.bind[finger],assets.bind[thumb]),target=palm(h,f,t);
            if(source!=null&&target!=null)desired[hand]=target.mul(source.invert()).mul(assets.bind[hand].getUnnormalizedRotation(new Quaternionf()));
        }
        Matrix4f[] result=evaluate(assets,desired);
        if(!Float.isFinite(pelvisOffset))return result;
        float[] boots=assets.soles.heights(skinMatrices(assets,result));
        fitLeg(assets,desired,result,"l",Soles.targetHeight(current,bind,scale,"L")-pelvisOffset-boots[0]);
        fitLeg(assets,desired,result,"r",Soles.targetHeight(current,bind,scale,"R")-pelvisOffset-boots[1]);
        return evaluate(assets,desired);
    }
    static Matrix4f[] skinMatrices(Assets assets,Matrix4f[] global){
        Matrix4f[] skin=new Matrix4f[global.length];
        for(int i=0;i<skin.length;i++)skin[i]=new Matrix4f(global[i]).mul(assets.inverseBind[i]);
        return skin;
    }
    private static Matrix4f[] evaluate(Assets assets,Quaternionf[] desired){
        int count=desired.length;
        Matrix4f[] result=new Matrix4f[count];boolean[] visiting=new boolean[count];
        for(int i=0;i<count;i++)evaluate(i,assets,result,desired,visiting);
        return result;
    }
    static void followLeg(Assets assets,Quaternionf[] desired,String side){
        // The thigh helper uses a separate pelvis-parented chain with its own bind axes.
        follow(assets,desired,"j_momo_"+side+"_wj","n_momo_a_"+side+"_wj_cd_ex");
        blendBend(assets,desired,"j_momo_"+side+"_wj","j_sune_"+side+"_wj","n_hiza_"+side+"_wj_ex");
    }
    private static void fitLeg(Assets assets,Quaternionf[] desired,Matrix4f[] pose,String side,float heightDelta){
        if(!Float.isFinite(heightDelta)||Math.abs(heightDelta)<1e-4f)return;
        int thigh=assets.names.indexOf("j_momo_"+side+"_wj"),calf=assets.names.indexOf("j_sune_"+side+"_wj"),foot=assets.names.indexOf("kl_asi_"+side+"_wj_co");
        if(thigh<0||calf<0||foot<0)return;
        Vector3f hip=pose[thigh].getTranslation(new Vector3f()),knee=pose[calf].getTranslation(new Vector3f()),ankle=pose[foot].getTranslation(new Vector3f());
        Vector3f upper=new Vector3f(knee).sub(hip),lower=new Vector3f(ankle).sub(knee);
        float a=upper.length(),b=lower.length();
        Vector3f direction=new Vector3f(ankle).add(0,heightDelta,0).sub(hip);
        float reach=direction.length();
        if(reach<1e-5f||Math.min(a,b)<1e-5f)return;
        direction.div(reach);
        float distance=Math.clamp(reach,Math.abs(a-b)+1e-5f,a+b-1e-5f);
        float along=(a*a-b*b+distance*distance)/(2*distance);
        Vector3f bend=new Vector3f(upper).fma(-upper.dot(direction),direction);
        if(bend.lengthSquared()<1e-8f)bend.set(0,0,1).fma(-direction.z,direction);
        if(bend.lengthSquared()<1e-8f)bend.set(1,0,0).fma(-direction.x,direction);
        Vector3f fittedUpper=bend.normalize((float)Math.sqrt(Math.max(0,a*a-along*along))).fma(along,direction);
        Vector3f fittedLower=new Vector3f(direction).mul(distance).sub(fittedUpper);
        desired[thigh]=new Quaternionf().rotationTo(upper,fittedUpper).mul(pose[thigh].getUnnormalizedRotation(new Quaternionf()));
        desired[calf]=new Quaternionf().rotationTo(lower,fittedLower).mul(pose[calf].getUnnormalizedRotation(new Quaternionf()));
        desired[foot]=pose[foot].getUnnormalizedRotation(new Quaternionf());
        followLeg(assets,desired,side);
    }
    private static Quaternionf delta(Assets assets,Quaternionf[] desired,int driver){
        return new Quaternionf(desired[driver]).mul(assets.bind[driver].getUnnormalizedRotation(new Quaternionf()).invert()).normalize();
    }
    static void follow(Assets assets,Quaternionf[] desired,String driverName,String helperName){
        int driver=assets.names.indexOf(driverName),helper=assets.names.indexOf(helperName);
        if(driver<0||helper<0||desired[driver]==null)return;
        desired[helper]=delta(assets,desired,driver).mul(assets.bind[helper].getUnnormalizedRotation(new Quaternionf())).normalize();
    }
    static void blendBend(Assets assets,Quaternionf[] desired,String upperName,String lowerName,String helperName){
        int upper=assets.names.indexOf(upperName),lower=assets.names.indexOf(lowerName),helper=assets.names.indexOf(helperName);
        if(upper<0||lower<0||helper<0||desired[upper]==null||desired[lower]==null)return;
        desired[helper]=delta(assets,desired,upper).slerp(delta(assets,desired,lower),.5f).mul(assets.bind[helper].getUnnormalizedRotation(new Quaternionf())).normalize();
    }
    private static Quaternionf palm(Matrix4f hand,Matrix4f finger,Matrix4f thumb){
        Vector3f origin=hand.getTranslation(new Vector3f());
        Vector3f forward=finger.getTranslation(new Vector3f()).sub(origin),side=thumb.getTranslation(new Vector3f()).sub(origin);
        if(forward.lengthSquared()<1e-10f)return null;forward.normalize();side.fma(-side.dot(forward),forward);
        if(side.lengthSquared()<1e-10f)return null;side.normalize();Vector3f normal=forward.cross(side,new Vector3f()).normalize();
        Matrix3f basis=new Matrix3f().setColumn(0,forward).setColumn(1,side).setColumn(2,normal);
        return new Quaternionf().setFromNormalized(basis);
    }
    private static void evaluate(int i,Assets assets,Matrix4f[] global,Quaternionf[] desired,boolean[] visiting){
        if(global[i]!=null)return;
        if(visiting[i])throw new IllegalStateException("Cyclic DIVA parent: "+assets.names.get(i));visiting[i]=true;
        int parent=assets.parent[i];if(parent>=0)evaluate(parent,assets,global,desired,visiting);
        Matrix4f result=parent<0?new Matrix4f(assets.localBind[i]):new Matrix4f(global[parent]).mul(assets.localBind[i]);
        if(desired[i]!=null){Vector3f pos=result.getTranslation(new Vector3f());result.translationRotate(pos.x,pos.y,pos.z,desired[i]);}
        global[i]=result;visiting[i]=false;
    }
}
