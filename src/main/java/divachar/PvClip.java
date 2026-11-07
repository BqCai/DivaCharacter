package divachar;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.joml.Vector3f;

/** An indexed set of original curves and DSC events extracted by the local importer. */
final class PvClip {
    private static final float TICKS_PER_SECOND=100_000f;
    private record Event(int time,int start,int index,float speed,int blend){}
    private record HeadChange(int time,int id){}
    private record Key(float frame,float value,float in,float out){}
    private record Curve(int type,Key[] keys){
        float at(float frame){
            if(keys.length==0)return 0;
            int low=0,high=keys.length-1;
            if(frame<=keys[low].frame)return keys[low].value;
            if(frame>=keys[high].frame)return keys[high].value;
            while(high-low>1){
                int middle=(low+high)>>>1;
                if(frame<keys[middle].frame)high=middle;else low=middle;
            }
            Key a=keys[low],b=keys[high];
            if(type==4)return a.value;
            float span=b.frame-a.frame,t=(frame-a.frame)/span;
            if(type!=3)return a.value+(b.value-a.value)*t;
            float smooth=t*t*(3-2*t);
            return a.value+(b.value-a.value)*smooth
                +span*t*(1-t)*(a.out*(1-t)-b.in*t);
        }
    }
    private record Track(Curve[] position,Curve[] rotation){}
    record Motion(int frames,Map<String,Track> tracks){
        Vector3f position(String name,float frame){
            Track track=tracks.get(name);return track==null?null:vector(track.position,frame);
        }
        Vector3f rotation(String name,float frame){
            Track track=tracks.get(name);return track==null?null:vector(track.rotation,frame);
        }
        private static Vector3f vector(Curve[] curves,float frame){
            if(curves[0].keys.length+curves[1].keys.length+curves[2].keys.length==0)return null;
            return new Vector3f(curves[0].at(frame),curves[1].at(frame),curves[2].at(frame));
        }
    }
    private record Shot(float fps,float begin,boolean horizontal,Curve[] curves){
        Camera camera(float seconds){
            float frame=begin+seconds*fps;
            return new Camera(new Vector3f(curves[0].at(frame),curves[1].at(frame),curves[2].at(frame)),
                new Vector3f(curves[3].at(frame),curves[4].at(frame),curves[5].at(frame)),
                curves[6].at(frame),curves[7].at(frame),horizontal);
        }
    }
    record Camera(Vector3f eye,Vector3f target,float roll,float fov,boolean horizontal){}
    record Sample(Motion motion,float frame,Motion previous,float previousFrame,float blend,Camera camera,int shot,
                  Map<String,PvPerformance.Value> performance,int headVariant){}
    final Path song;
    final int music,end;
    private final Map<Integer,Motion> motions;
    private final Map<Integer,Shot> shots;
    private final Event[] moves,cuts;
    private final HeadChange[] heads;
    private final PvPerformance performance;

    PvClip(Path directory)throws IOException{
        song=directory.resolve("song.ogg");
        if(Files.size(song)<4)throw new IOException("Missing PV audio.");
        Path file=directory.resolve("preview.bin");
        if(Files.size(file)>256L*1024*1024)throw new IOException("PV cache is too large.");
        ByteBuffer data=ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        try{
            int version=data.getInt();
            if(version!=0x31505644&&version!=0x32505644&&version!=0x33505644)throw new IOException("Unknown PV cache version.");
            music=data.getInt();end=data.getInt();
            if(music<0||end<=music)throw new IOException("Invalid PV duration.");
            motions=new HashMap<>();
            int count=count(data,1024);
            for(int i=0;i<count;i++)motions.put(data.getInt(),readMotion(data));
            moves=events(data,motions,true);
            shots=new HashMap<>();
            count=count(data,1024);
            for(int i=0;i<count;i++){
                int id=data.getInt();float fps=number(data),begin=number(data);
                boolean horizontal=data.get()!=0;
                if(fps<=0||fps>240)throw new IOException("Invalid PV camera rate.");
                shots.put(id,new Shot(fps,begin,horizontal,curves(data,8)));
            }
            cuts=events(data,shots,false);
            if(version!=0x31505644){
                Map<Integer,Motion> common=new HashMap<>();
                count=count(data,1024);
                for(int i=0;i<count;i++)common.put(data.getInt(),readMotion(data));
                List<PvPerformance.Cue> cues=new ArrayList<>();
                count=count(data,100_000);
                for(int i=0;i<count;i++){
                    int time=data.getInt(),layer=count(data,5),id=data.getInt(),ms=count(data,1_000_000);
                    float amount=number(data);
                    if(time<0||id!=-1&&!common.containsKey(id)||amount<0||amount>1)
                        throw new IOException("Invalid PV expression cue.");
                    cues.add(new PvPerformance.Cue(time,layer,common.get(id),ms*100,amount));
                }
                performance=new PvPerformance(cues);
            }else performance=new PvPerformance(List.of());
            heads=new HeadChange[version==0x33505644?count(data,100_000):0];
            int previous=-1;
            for(int i=0;i<heads.length;i++){
                int time=data.getInt(),id=count(data,8);
                if(time<0||time<previous)throw new IOException("Unordered PV head changes.");
                heads[i]=new HeadChange(time,id);previous=time;
            }
            if(data.hasRemaining())throw new IOException("Unexpected PV cache data.");
        }catch(BufferUnderflowException error){throw new IOException("Incomplete PV cache.",error);}
    }

    Sample sample(int time){
        int move=find(moves,time);
        Event event=moves[move],cut=cuts[find(cuts,time)];
        Motion motion=motions.get(event.index);
        Motion previous=null;
        float previousFrame=0,blend=1;
        if(move>0&&event.blend>0){
            blend=Math.clamp((time-event.time)/(100f*event.blend),0f,1f);
            if(blend<1){
                Event before=moves[move-1];previous=motions.get(before.index);
                previousFrame=frame(previous,before,time);
            }
        }
        return new Sample(motion,frame(motion,event,time),previous,previousFrame,blend,
            shots.get(cut.index).camera(Math.max(0,time-cut.start)/TICKS_PER_SECOND),cut.index,performance.at(time),headAt(time));
    }

    private int headAt(int time){
        int low=0,high=heads.length;
        while(low<high){
            int middle=(low+high)>>>1;
            if(time<heads[middle].time)high=middle;else low=middle+1;
        }
        return low==0?0:heads[low-1].id;
    }

    static Motion readMotion(ByteBuffer data)throws IOException{
        int frames=count(data,1_000_000),tracks=count(data,4096);
        var bindings=new HashMap<String,Track>();
        for(int i=0;i<tracks;i++){
            byte[] name=new byte[count(data,256)];data.get(name);
            bindings.put(new String(name,StandardCharsets.UTF_8),new Track(curves(data,3),curves(data,3)));
        }
        return new Motion(frames,Map.copyOf(bindings));
    }

    private static float frame(Motion motion,Event event,int time){
        return Math.clamp((time-event.start)*60f/TICKS_PER_SECOND*event.speed,0f,Math.max(0,motion.frames-1));
    }

    private static int find(Event[] events,int time){
        int low=0,high=events.length;
        while(low+1<high){
            int middle=(low+high)>>>1;
            if(time<events[middle].time)high=middle;else low=middle;
        }
        return low;
    }

    private static Event[] events(ByteBuffer data,Map<Integer,?> resources,boolean motion)throws IOException{
        Event[] entries=new Event[count(data,100_000)];
        if(entries.length==0)throw new IOException("PV has an empty timeline.");
        int previous=-1;
        for(int i=0;i<entries.length;i++){
            int time=data.getInt(),start=data.getInt(),id=data.getInt();
            float speed=motion?number(data):1;int blend=motion?data.getInt():0;
            if(time<previous||start<0||start>time||!resources.containsKey(id))throw new IOException("Invalid PV event.");
            entries[i]=new Event(time,start,id,speed,blend);previous=time;
        }
        return entries;
    }

    private static Curve[] curves(ByteBuffer data,int axes)throws IOException{
        Curve[] result=new Curve[axes];
        for(int i=0;i<axes;i++){
            int type=count(data,4),length=count(data,1_000_000);
            if(length>data.remaining()/16)throw new IOException("Incomplete PV curve.");
            Key[] keys=new Key[length];
            float previous=-Float.MAX_VALUE;
            for(int j=0;j<length;j++){
                float frame=number(data),value=number(data),in=number(data),out=number(data);
                if(frame<previous)throw new IOException("Unordered PV curve.");
                keys[j]=new Key(frame,value,in,out);previous=frame;
            }
            result[i]=new Curve(type,keys);
        }
        return result;
    }

    private static int count(ByteBuffer data,int max)throws IOException{
        int count=data.getInt();
        if(count<0||count>max)throw new IOException("Invalid PV cache count "+count);
        return count;
    }

    private static float number(ByteBuffer data)throws IOException{
        float value=data.getFloat();
        if(!Float.isFinite(value))throw new IOException("Non-finite PV curve value.");
        return value;
    }
}
