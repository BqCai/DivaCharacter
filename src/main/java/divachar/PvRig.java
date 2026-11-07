package divachar;

import java.lang.Math;
import org.joml.*;

/** Resolve MOT controls onto the outfit's render skeleton in DIVA model space. */
final class PvRig {
    private final Assets assets;
    private final Matrix4f[] local,global;
    private final Quaternionf[] desired;

    private PvRig(Assets assets){
        this.assets=assets;
        local=assets.localBind.clone();
        global=new Matrix4f[local.length];
        desired=new Quaternionf[local.length];
    }

    static Matrix4f[] pose(Assets assets,PvClip.Sample sample){
        Matrix4f[] current=new PvRig(assets).solve(sample.motion(),sample.frame(),sample.performance());
        if(sample.previous()==null)return current;
        Matrix4f[] previous=new PvRig(assets).solve(sample.previous(),sample.previousFrame(),sample.performance());
        Matrix4f[] blended=new Matrix4f[current.length];
        for(int i=0;i<current.length;i++)blend(assets,i,current,previous,blended,sample.blend());
        return blended;
    }

    private static Matrix4f blend(Assets assets,int i,Matrix4f[] current,Matrix4f[] previous,Matrix4f[] result,float weight){
        if(result[i]!=null)return result[i];
        int parent=assets.parent[i];
        Matrix4f a=previous[i],b=current[i];
        if(parent>=0){
            a=new Matrix4f(previous[parent]).invert().mul(a);
            b=new Matrix4f(current[parent]).invert().mul(b);
        }
        Vector3f position=a.getTranslation(new Vector3f()).lerp(b.getTranslation(new Vector3f()),weight);
        Quaternionf rotation=rotation(a).slerp(rotation(b),weight);
        Matrix4f local=new Matrix4f().translationRotate(position.x,position.y,position.z,rotation);
        return result[i]=parent<0?local:new Matrix4f(blend(assets,parent,current,previous,result,weight)).mul(local);
    }

    private Matrix4f[] solve(PvClip.Motion motion,float frame,java.util.Map<String,PvPerformance.Value> performance){
        int pelvis=id("kl_kosi_etc_wj"),chest=id("kl_mune_b_wj"),head=id("j_kao_wj");
        if(pelvis<0||chest<0||head<0)return bindCopy();
        Vector3f hip=motion.position("n_hara_cp",frame);
        if(hip==null)return bindCopy();
        Vector3f yaw=angles(motion,"kg_hara_y",frame),lean=angles(motion,"kl_hara_xz",frame);
        Quaternionf trunk=new Quaternionf().rotateY(yaw.y).rotateXYZ(lean.x,0,lean.z);
        Vector3f pelvisAngles=angles(motion,"kl_kosi_xz",frame);
        Quaternionf waist=new Quaternionf(trunk).rotateY(angles(motion,"kl_kosi_y",frame).y)
            .rotateXYZ(pelvisAngles.x,0,pelvisAngles.z);
        for(int i=0;i<local.length;i++)if(assets.parent[i]<0){
            Vector3f translation=point(local[i]).sub(point(assets.bind[pelvis]));
            trunk.transform(translation).add(hip);
            Quaternionf rootRotation=i==pelvis?waist:trunk;
            local[i]=new Matrix4f().translationRotate(translation.x,translation.y,translation.z,
                new Quaternionf(rootRotation).mul(rotation(local[i])));
        }
        // The chest control is beyond the chest joint, so use its direction to aim the torso.
        Vector3f target=motion.position("cl_mune",frame);
        Quaternionf torso=new Quaternionf(trunk);
        if(target!=null&&target.sub(hip).lengthSquared()>1e-6f)
            torso=new Quaternionf().rotationTo(trunk.transform(new Vector3f(0,1,0)),target.normalize()).mul(trunk);
        Vector3f chestPosition=torso.transform(point(assets.bind[chest]).sub(point(assets.bind[pelvis]))).add(hip);
        Vector3f chestAngles=angles(motion,"kl_mune_b_wj",frame);
        torso.rotateXYZ(chestAngles.x,chestAngles.y,chestAngles.z);
        putWorld(chest,chestPosition,new Quaternionf(torso).mul(rotation(assets.bind[chest])));
        Vector3f look=motion.position("cl_kao",frame);
        if(look!=null){
            Vector3f headPosition=point(world(head));
            look.sub(headPosition);
            if(look.lengthSquared()>1e-6f){
                Quaternionf facing=new Quaternionf().rotationTo(torso.transform(new Vector3f(0,0,1)),look.normalize()).mul(torso);
                facing.rotateZ(angles(motion,"cl_kao",frame).y);
                putWorld(head,headPosition,facing.mul(rotation(assets.bind[head])));
            }
        }
        for(String side:new String[]{"l","r"}){
            boolean left=side.equals("l");
            String shoulder="j_kata_"+side+"_wj_cu",elbow="j_ude_"+side+"_wj",hand="kl_te_"+side+"_wj";
            String thigh="j_momo_"+side+"_wj",shin="j_sune_"+side+"_wj",foot="kl_asi_"+side+"_wj_co";
            Vector3f elbowPole=motion.position("tl_up_kata_"+side,frame);
            if(elbowPole==null||elbowPole.lengthSquared()<1e-6f)elbowPole=torso.transform(new Vector3f(left?.25f:-.25f,-.25f,-1));
            Quaternionf arm=chain(shoulder,elbow,hand,motion.position("c_kata_"+side,frame),elbowPole,torso);
            if(arm!=null){
                int wrist=id(hand);
                Vector3f a=angles(motion,hand,frame);
                putWorld(wrist,point(world(wrist)),arm.rotateXYZ(a.x,a.y,a.z));
                Retargeter.follow(assets,desired,shoulder,"n_skata_"+side+"_wj_cd_ex");
                Retargeter.blendBend(assets,desired,shoulder,elbow,"n_hiji_"+side+"_wj_ex");
            }
            Vector3f ankle=motion.position("cl_momo_"+side,frame);
            Quaternionf leg=chain(thigh,shin,foot,ankle,waist.transform(new Vector3f(0,0,1)),waist);
            if(leg!=null){
                int end=id(foot);
                Vector3f footAngles=angles(motion,foot,frame),direction=angles(motion,"cl_momo_"+side,frame);
                Quaternionf shoe=new Quaternionf().rotateY(yaw.y+direction.z)
                    .rotateX(footAngles.x).rotateZ(footAngles.y).mul(rotation(assets.bind[end]));
                putWorld(end,point(world(end)),shoe);
                Retargeter.followLeg(assets,desired,side);
            }
        }
        PvPerformance.apply(assets,local,PvPerformance.base(motion,frame),performance);
        invalidate();
        for(int i=0;i<global.length;i++)world(i);
        return global;
    }

    private Quaternionf chain(String upperName,String lowerName,String endName,Vector3f target,Vector3f pole,Quaternionf basis){
        int upper=id(upperName),lower=id(lowerName),end=id(endName);
        if(upper<0||lower<0||end<0||target==null)return null;
        Vector3f start=point(world(upper));
        Vector3f upperBind=point(assets.bind[lower]).sub(point(assets.bind[upper]));
        Vector3f lowerBind=point(assets.bind[end]).sub(point(assets.bind[lower]));
        float a=upperBind.length(),b=lowerBind.length();
        Vector3f direction=target.sub(start);
        if(direction.lengthSquared()<1e-8f||Math.min(a,b)<1e-4f)return null;
        float reach=Math.clamp(direction.length(),Math.abs(a-b)+1e-4f,a+b-1e-4f);
        direction.normalize();
        pole.fma(-pole.dot(direction),direction);
        if(pole.lengthSquared()<1e-6f){
            pole.set(0,1,0).fma(-direction.y,direction);
            if(pole.lengthSquared()<1e-6f)pole.set(1,0,0).fma(-direction.x,direction);
        }
        float along=(a*a-b*b+reach*reach)/(2*reach);
        Vector3f bend=pole.normalize((float)Math.sqrt(Math.max(0,a*a-along*along))).fma(along,direction);
        Vector3f bottom=new Vector3f(direction).mul(reach).sub(bend);
        Quaternionf upperDelta=new Quaternionf().rotationTo(basis.transform(upperBind),bend).mul(basis);
        Quaternionf lowerDelta=new Quaternionf().rotationTo(upperDelta.transform(lowerBind),bottom).mul(upperDelta);
        putWorld(upper,start,upperDelta.mul(rotation(assets.bind[upper])));
        putWorld(lower,new Vector3f(start).add(bend),new Quaternionf(lowerDelta).mul(rotation(assets.bind[lower])));
        Quaternionf finish=lowerDelta.mul(rotation(assets.bind[end]));
        putWorld(end,direction.mul(reach).add(start),finish);
        return finish;
    }

    private Matrix4f world(int i){
        if(global[i]!=null)return global[i];
        int parent=assets.parent[i];
        Matrix4f matrix=parent<0?new Matrix4f(local[i]):new Matrix4f(world(parent)).mul(local[i]);
        if(desired[i]!=null)matrix.translationRotate(matrix.m30(),matrix.m31(),matrix.m32(),desired[i]);
        return global[i]=matrix;
    }
    private void putWorld(int i,Vector3f position,Quaternionf rotation){
        Matrix4f matrix=new Matrix4f().translationRotate(position.x,position.y,position.z,rotation);
        int parent=assets.parent[i];
        local[i]=parent<0?matrix:new Matrix4f(world(parent)).invert().mul(matrix);
        desired[i]=rotation;
        invalidate();
    }
    private void invalidate(){java.util.Arrays.fill(global,null);}
    private Matrix4f[] bindCopy(){
        for(int i=0;i<global.length;i++)global[i]=new Matrix4f(assets.bind[i]);
        return global;
    }
    private int id(String name){return assets.names.indexOf(name);}
    private static Vector3f point(Matrix4f m){return m.getTranslation(new Vector3f());}
    private static Quaternionf rotation(Matrix4f m){return m.getUnnormalizedRotation(new Quaternionf()).normalize();}
    private static Vector3f angles(PvClip.Motion motion,String name,float frame){
        Vector3f angles=motion.rotation(name,frame);return angles==null?new Vector3f():angles;
    }
}
