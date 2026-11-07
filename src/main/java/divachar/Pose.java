package divachar;

import java.util.*;
import org.joml.*;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.characters.IsoPlayer;

/** Retarget on the capture thread; the render thread receives copied matrices only. */
public final class Pose {
    public final Matrix4f[] palette;
    public final Matrix4f root;
    public final Matrix4f[] heldDeltas;
    public final boolean firstPerson;
    final int headVariant;
    final PvPlayer.Frame pv;
    public final Vector4f engineLight=new Vector4f(1);
    public final long time=System.nanoTime();
    Pose(Assets assets,AnimationPlayer animation,Matrix4f hostRoot,boolean sourcePose,boolean firstPerson,boolean fitLegs,
         OsagePhysics physics,Vector3f actorPosition,Vector3f wind,boolean paused,boolean simulatePhysics,
         IsoPlayer player,GameplayPerformance performance,int headVariant){
        float scale=Tuning.scale;
        this.pv=null;
        this.headVariant=sourcePose?0:headVariant;
        this.root=Basis.divaToWorld(hostRoot,scale);this.firstPerson=firstPerson;
        int n=assets.names.size();palette=new Matrix4f[n];
        Map<String,Matrix4f> current=new HashMap<>(),bind=new HashMap<>();
        var data=animation.getSkinningData();
        for(var entry:animation.getSkinningBoneIndices().entrySet()){
            int index=entry.getValue();
            current.put(entry.getKey(),convert(animation.getModelTransformAt(index)));
            if(data.boneOffset!=null&&index<data.boneOffset.size()&&data.boneOffset.get(index)!=null)bind.put(entry.getKey(),convert(data.boneOffset.get(index)).invert());
        }
        float pelvisOffset=fitLegs&&!sourcePose?assets.soles.pelvisOffset(current,bind,scale):Float.NaN;
        Matrix4f[] global=sourcePose?assets.bind:Retargeter.global(assets,current,bind,scale,pelvisOffset);
        Matrix4f[] skin=Retargeter.skinMatrices(assets,global);
        float correction=Float.isFinite(pelvisOffset)?pelvisOffset:Soles.target(current,bind,scale)-assets.soles.minimum(skin);
        if(!Float.isFinite(correction)||java.lang.Math.abs(correction)>4)throw new IllegalStateException("Invalid foot contact correction: "+correction);
        this.root.translate(0,correction,0);
        if(sourcePose||!simulatePhysics)physics.reset();
        else {
            physics.apply(global,this.root,actorPosition,correction,time,paused,wind);
            for(int bone:physics.affected)skin[bone].set(global[bone]).mul(assets.inverseBind[bone]);
        }
        heldDeltas=Attachments.deltas(assets,global,this.root,current,hostRoot);
        if(!sourcePose&&performance!=null)
            GameplayPerformance.apply(assets,global,skin,performance.sample(player,time,paused,!firstPerson&&headVariant==0));
        for(int i=0;i<n;i++)palette[i]=new Matrix4f(this.root).mul(skin[i]);
    }
    Pose(Assets assets,Matrix4f hostRoot,OsagePhysics physics,Vector3f actorPosition,Vector3f wind,PvPlayer.Frame pv,boolean simulatePhysics){
        this.pv=pv;firstPerson=false;
        headVariant=pv.sample().headVariant();
        root=Basis.divaToWorld(hostRoot,Tuning.scale);
        Matrix4f[] global=PvRig.pose(assets,pv.sample());
        float floor=assets.soles.bindFloor();
        root.translate(0,-floor,0);
        if(simulatePhysics)physics.apply(global,root,actorPosition,-floor,time,false,wind);else physics.reset();
        palette=Retargeter.skinMatrices(assets,global);
        for(Matrix4f matrix:palette)matrix.mulLocal(root);
        heldDeltas=new Matrix4f[2];
    }
    public static Matrix4f convert(org.lwjgl.util.vector.Matrix4f m){
        return new Matrix4f().set(new float[]{m.m00,m.m01,m.m02,m.m03,m.m10,m.m11,m.m12,m.m13,m.m20,m.m21,m.m22,m.m23,m.m30,m.m31,m.m32,m.m33}).transpose();
    }
}
