package divachar;

import java.io.IOException;
import java.nio.*;
import java.util.*;
import org.joml.Matrix4f;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;

/** Source-authored finger and face poses, selected using the local PZ gameplay state. */
final class GameplayPerformance {
    private enum Hand { RELAXED, OPEN, GRIP, FLASHLIGHT }

    static final class Library {
        private final Map<Hand,Map<String,PvPerformance.Value>> left=new EnumMap<>(Hand.class),right=new EnumMap<>(Hand.class);
        private final Map<String,PvPerformance.Value> blink,breath,strain;

        Library() throws IOException {
            this(Assets.bytes("gameplay.bin"));
        }

        Library(byte[] contents) throws IOException {
            ByteBuffer data=ByteBuffer.wrap(contents).order(ByteOrder.LITTLE_ENDIAN);
            for(Hand kind:Hand.values()){
                var motion=PvClip.readMotion(data);
                left.put(kind,PvPerformance.preset(motion,PvPerformance.LEFT));
                right.put(kind,PvPerformance.preset(motion,PvPerformance.RIGHT));
            }
            blink=PvPerformance.preset(PvClip.readMotion(data),PvPerformance.LIDS);
            breath=PvPerformance.preset(PvClip.readMotion(data),PvPerformance.MOUTH);
            strain=PvPerformance.preset(PvClip.readMotion(data),PvPerformance.FACE);
            if(data.hasRemaining())throw new IOException("Unexpected gameplay motion data.");
        }
    }

    private static final class HandBlend {
        private Hand kind;
        private Map<String,PvPerformance.Value> from;
        private float elapsed;

        Map<String,PvPerformance.Value> sample(Hand next,Map<Hand,Map<String,PvPerformance.Value>> poses,float dt){
            if(kind==null){kind=next;from=poses.get(next);elapsed=1;}
            else if(next!=kind){
                from=PvPerformance.blend(from,poses.get(kind),smooth(elapsed));
                kind=next;elapsed=0;
            }
            elapsed=Math.min(1,elapsed+dt/.12f);
            return PvPerformance.blend(from,poses.get(kind),smooth(elapsed));
        }
    }

    private final Library library;
    private final HandBlend left=new HandBlend(),right=new HandBlend();
    private final SplittableRandom random=new SplittableRandom();
    private long last;
    private float blinkIn=2.4f,exertion,effortPhase,strain;

    GameplayPerformance(Library library){this.library=library;}

    Map<String,PvPerformance.Value> sample(IsoPlayer player,long now,boolean paused,boolean showFace){
        var primary=player.getPrimaryHandItem();
        var secondary=player.getSecondaryHandItem();
        if(primary!=null&&primary.isTwoHandWeapon()&&secondary==null&&
            (primary.isRequiresEquippedBothHands()||player.isAiming()||player.isAttacking()))secondary=primary;
        boolean open=player.isShoving()||player.isClimbing()||player.isClimbingRope();
        boolean exerting=player.isRunning()||player.isSprinting();
        float tension=player.hasHitReaction()?.7f:player.isAttacking()||player.isShoving()?.36f:0;
        return sample(hand(secondary,open),hand(primary,open),now,paused,showFace,exerting,tension);
    }

    private static Hand hand(InventoryItem item,boolean open){
        if(open)return Hand.OPEN;
        if(item==null)return Hand.RELAXED;
        return item.getLightStrength()>0?Hand.FLASHLIGHT:Hand.GRIP;
    }

    private Map<String,PvPerformance.Value> sample(Hand leftHand,Hand rightHand,long now,boolean paused,
                                                    boolean showFace,boolean exerting,float tension){
        float dt=paused||last==0?0:Math.clamp((now-last)*1e-9f,0,.05f);
        last=now;
        Map<String,PvPerformance.Value> pose=new HashMap<>(left.sample(leftHand,library.left,dt));
        pose.putAll(right.sample(rightHand,library.right,dt));
        blinkIn-=dt;
        if(blinkIn<-.16f)blinkIn=(float)random.nextDouble(2.5,5.5);
        exertion=approach(exertion,exerting?1:0,dt,5);
        strain=approach(strain,tension,dt,tension>strain?22:8);
        effortPhase=(effortPhase+dt*7)%((float)Math.TAU);
        if(showFace){
            float breath=exertion*(.17f+.09f*(float)Math.sin(effortPhase));
            PvPerformance.overlay(pose,library.breath,breath);
            PvPerformance.overlay(pose,library.strain,strain);
            if(blinkIn<0){
                float age=-blinkIn;
                PvPerformance.overlay(pose,library.blink,smooth(Math.clamp(Math.min(age/.055f,(.16f-age)/.105f),0,1)));
            }
        }
        return pose;
    }

    private static float smooth(float t){return t*t*(3-2*t);}
    private static float approach(float from,float to,float dt,float rate){
        return to+(from-to)*(float)Math.exp(-dt*rate);
    }

    static void apply(Assets assets,Matrix4f[] global,Matrix4f[] skin,Map<String,PvPerformance.Value> detail){
        Matrix4f[] local=assets.localBind.clone(),updated=new Matrix4f[global.length];
        PvPerformance.apply(assets,local,Map.of(),detail);
        boolean[] visited=new boolean[global.length];
        for(int i=0;i<global.length;i++)update(assets,i,local,global,updated,visited);
        for(int i=0;i<global.length;i++)if(updated[i]!=null)skin[i].set(updated[i]).mul(assets.inverseBind[i]);
    }

    private static void update(Assets assets,int bone,Matrix4f[] local,Matrix4f[] global,Matrix4f[] updated,boolean[] visited){
        if(visited[bone])return;
        int parent=assets.parent[bone];
        if(parent>=0)update(assets,parent,local,global,updated,visited);
        if(local[bone]!=assets.localBind[bone]){
            updated[bone]=parent<0?local[bone]:new Matrix4f(updated[parent]==null?global[parent]:updated[parent]).mul(local[bone]);
        }else if(parent>=0&&updated[parent]!=null){
            updated[bone]=new Matrix4f(global[parent]).invert().mul(global[bone]).mulLocal(updated[parent]);
        }
        visited[bone]=true;
    }
}
