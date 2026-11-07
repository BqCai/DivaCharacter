package divachar;

import java.util.*;
import java.util.regex.Pattern;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Region-limited DSC poses, evaluated against the same clock as the PV motion and audio. */
final class PvPerformance {
    static final int FACE=0,MOUTH=1,EYES=2,LIDS=3,LEFT=4,RIGHT=5;
    private static final Pattern FINGER=Pattern.compile("nl_(?:oya|hito|naka|kusu|ko)(?:_[bc])?_[lr]_wj");
    private static final Value NEUTRAL=new Value(new Vector3f(),new Quaternionf(),0);
    record Cue(int time,int layer,PvClip.Motion motion,int duration,float amount){}
    record Value(Vector3f position,Quaternionf rotation,float weight){
        Value blend(Value to,float factor){
            if(factor<=0)return this;
            if(factor>=1)return to;
            float coverage=weight+(to.weight-weight)*factor;
            if(coverage<=0)return NEUTRAL;
            float mix=to.weight*factor/coverage;
            return new Value(new Vector3f(position).lerp(to.position,mix),new Quaternionf(rotation).slerp(to.rotation,mix),coverage);
        }
    }
    private record Transition(int time,int duration,Map<String,Value> from,Map<String,Value> to){
        Map<String,Value> at(int position){
            if(duration==0||position-time>=duration)return to;
            float blend=Math.clamp((position-time)/(float)duration,0,1);
            return PvPerformance.blend(from,to,blend);
        }
    }
    private final List<List<Transition>> layers=new ArrayList<>(6);

    PvPerformance(List<Cue> cues){
        for(int i=0;i<6;i++)layers.add(new ArrayList<>());
        for(Cue cue:cues.stream().sorted(Comparator.comparingInt(Cue::time)).toList()){
            var layer=layers.get(cue.layer);
            var from=layer.isEmpty()?Map.<String,Value>of():layer.getLast().at(cue.time);
            Map<String,Value> to=cue.motion==null?Map.of():pose(cue.motion,
                Math.max(0,cue.motion.frames()-1)*cue.amount,cue.layer,true);
            layer.add(new Transition(cue.time,cue.duration,from,to));
        }
    }

    Map<String,Value> at(int time){
        Map<String,Value> result=new HashMap<>();
        for(var layer:layers){
            int low=0,high=layer.size();
            while(low<high){
                int middle=(low+high)>>>1;
                if(layer.get(middle).time<=time)low=middle+1;
                else high=middle;
            }
            if(low>0)layer.get(low-1).at(time).forEach((name,value)->{
                if(value.weight>0)result.merge(name,value,(base,top)->base.blend(
                    new Value(top.position,top.rotation,1),top.weight));
            });
        }
        return result;
    }

    static Map<String,Value> base(PvClip.Motion motion,float frame){return pose(motion,frame,-1,false);}
    static Map<String,Value> preset(PvClip.Motion motion,int layer){
        return Map.copyOf(pose(motion,Math.max(0,motion.frames()-1),layer,false));
    }

    static Map<String,Value> blend(Map<String,Value> from,Map<String,Value> to,float mix){
        if(mix<=0)return from;
        if(mix>=1)return to;
        Map<String,Value> result=new HashMap<>();
        from.forEach((bone,value)->result.put(bone,value.blend(to.getOrDefault(bone,NEUTRAL),mix)));
        to.forEach((bone,value)->result.putIfAbsent(bone,NEUTRAL.blend(value,mix)));
        return result;
    }

    static void overlay(Map<String,Value> target,Map<String,Value> layer,float amount){
        if(amount<=0)return;
        layer.forEach((name,value)->target.compute(name,
            (bone,base)->(base==null?NEUTRAL:base).blend(value,amount)));
    }

    private static Map<String,Value> pose(PvClip.Motion motion,float frame,int layer,boolean resetMissing){
        Map<String,Value> pose=new HashMap<>();
        for(String name:motion.tracks().keySet()){
            boolean finger=FINGER.matcher(name).matches();
            boolean mouth=name.equals("kl_ago_wj")||name.startsWith("tl_kuti_")||name.startsWith("tl_tooth_");
            boolean lid=name.startsWith("tl_mabu_")||name.startsWith("tl_eyelid_");
            boolean face=mouth||lid||name.startsWith("tl_mayu_")||name.startsWith("tl_hoho_");
            boolean matches=switch(layer){
                case FACE -> face;
                case MOUTH -> mouth;
                case LIDS -> lid;
                case LEFT -> finger&&name.endsWith("_l_wj");
                case RIGHT -> finger&&name.endsWith("_r_wj");
                default -> layer<0&&(face||finger);
            };
            if(!matches)continue;
            Vector3f offset=finger?null:motion.position(name,frame);
            Vector3f angles=motion.rotation(name,frame);
            if(resetMissing||offset!=null||angles!=null)pose.put(name,value(offset,angles));
        }
        if(layer==EYES||layer==FACE||layer<0)for(String side:List.of("l","r")){
            String bone="n_eye_"+side+"_wj_ex";
            Vector3f gaze=motion.rotation("kl_eye_"+side,frame);
            if(gaze!=null)gaze.set(-gaze.x,-.5f*gaze.y,0);
            else gaze=motion.rotation(bone,frame);
            if(gaze!=null||resetMissing)pose.put(bone,value(null,gaze));
        }
        return pose;
    }

    private static Value value(Vector3f offset,Vector3f angles){
        return new Value(offset==null?new Vector3f():offset,angles==null?new Quaternionf():
            new Quaternionf().rotateZYX(angles.z,angles.y,angles.x),1);
    }

    static void apply(Assets assets,Matrix4f[] local,Map<String,Value> base,Map<String,Value> overlay){
        Map<String,Value> resolved=new HashMap<>(base);
        overlay.forEach((name,top)->resolved.put(name,
            base.getOrDefault(name,NEUTRAL).blend(new Value(top.position,top.rotation,1),top.weight)));
        resolved.forEach((name,value)->{
            int bone=assets.names.indexOf(name);
            if(bone<0)return;
            Vector3f offset=new Vector3f(value.position).mul(value.weight);
            int parent=assets.parent[bone];
            if(parent>=0)assets.inverseBind[parent].transformDirection(offset);
            Vector3f position=local[bone].getTranslation(new Vector3f()).add(offset);
            Quaternionf rotation=new Quaternionf().slerp(value.rotation,value.weight);
            local[bone]=new Matrix4f(local[bone]).setTranslation(position).rotate(rotation);
        });
    }
}
