package divachar;

import java.util.concurrent.Future;
import fmod.javafmod;
import fmod.javafmodJNI;
import fmod.fmod.FMODManager;
import zombie.SoundManager;
import zombie.characters.IsoPlayer;
import zombie.core.Core;

/** Owns the preview audio on the game thread and publishes an immutable timeline sample. */
final class PvPlayer {
    record Frame(long generation,PvClip.Sample sample){}
    private record Request(DivaLibrary.Pv row,IsoPlayer player,Future<PvClip> data){}
    private Request pending;
    private IsoPlayer player;
    private PvClip clip;
    private long generation,started,sound,channel,lastAudio,lastAudioAt;
    private long musicVca;
    private float savedBgm,hostMusic;
    private int time;
    private float x,y,z;
    private volatile Frame frame;
    private volatile long failedGeneration=-1;
    private volatile String message="Choose a PV from the original game.";

    Frame frame(){return frame;}
    String message(){return message;}
    boolean busy(){return pending!=null||clip!=null;}

    void request(DivaLibrary library,String id,IsoPlayer actor){
        stop();
        DivaLibrary.Pv row=library.pvs().stream().filter(pv->pv.id().equals(id)&&pv.available()).findFirst().orElse(null);
        if(row==null){message="This PV has no supported solo Miku 3D preview.";return;}
        pending=new Request(row,actor,library.preparePv(id));
        message="Preparing PV "+id+" · "+row.name();
    }

    void unavailable(String reason){message=reason;}
    void renderingFailed(long which){failedGeneration=which;}

    void tick(IsoPlayer actor,boolean avatarReady){
        try{
            if(pending!=null){
                if(actor!=pending.player||actor.isDead()||!avatarReady){stop();return;}
                if(!pending.data.isDone())return;
                Request request=pending;pending=null;
                clip=request.data.get();player=actor;
                x=actor.getX();y=actor.getY();z=actor.getZ();
                time=0;lastAudio=0;started=System.nanoTime();
                lastAudioAt=started;
                generation++;
                muteBgm();
                message="Playing PV "+request.row.id()+" · "+request.row.name();
                System.out.println("[DivaCharacter] "+message);
            }
            if(clip==null)return;
            if(failedGeneration==generation){
                stop();message="PV preview stopped because the camera or rig could not be drawn.";return;
            }
            if(actor!=player||actor.isDead()||actor.getVehicle()!=null||!avatarReady||
                Math.abs(actor.getX()-x)>.3f||Math.abs(actor.getY()-y)>.3f||Math.abs(actor.getZ()-z)>.1f){
                stop();return;
            }
            updateBgm();
            long now=System.nanoTime();
            if(channel==0){
                time=(int)((now-started)/10_000);
                if(time>=clip.music){
                    openAudio();
                    lastAudioAt=now;
                    time=clip.music;
                }
            }else{
                if(!javafmod.FMOD_Channel_IsPlaying(channel)){stop();return;}
                long position=javafmod.FMOD_Channel_GetPosition(channel,FMODManager.FMOD_TIMEUNIT_MS);
                if(position>lastAudio){lastAudio=position;lastAudioAt=now;}
                long estimate=lastAudio+Math.min(25,(now-lastAudioAt)/1_000_000);
                time=Math.max(time,clip.music+(int)(estimate*100));
            }
            if(time>=clip.end){stop();return;}
            frame=new Frame(generation,clip.sample(time));
        }catch(Exception|LinkageError error){
            stop();
            Throwable cause=error.getCause()==null?error:error.getCause();
            message="PV failed: "+cause.getMessage();
            System.out.println("[DivaCharacter] "+message);
        }
    }

    private void muteBgm(){
        long vca=javafmodJNI.FMOD_Studio_System_GetVCA("vca:/Settings_Music");
        if(vca<=0)return;
        savedBgm=javafmodJNI.FMOD_Studio_VCA_GetVolume(vca);
        hostMusic=SoundManager.instance.getMusicVolume();
        if(javafmodJNI.FMOD_Studio_VCA_SetVolume(vca,0)==0){
            musicVca=vca;
            System.out.println("[DivaCharacter] PZ music muted for PV");
        }
    }

    private void updateBgm(){
        if(musicVca==0)return;
        float current=SoundManager.instance.getMusicVolume();
        if(current==hostMusic)return;
        hostMusic=current;savedBgm=current;
        javafmodJNI.FMOD_Studio_VCA_SetVolume(musicVca,0);
        if(channel>0)javafmod.FMOD_Channel_SetVolume(channel,songVolume());
    }

    private static float songVolume(){
        return Math.clamp(Core.getInstance().getOptionMusicVolume()/10f,0,1)*.7f;
    }

    private void openAudio(){
        sound=javafmod.FMOD_System_CreateSound(clip.song.toString(),
            FMODManager.FMOD_CREATESTREAM|FMODManager.FMOD_2D|FMODManager.FMOD_LOOP_OFF);
        if(sound<=0)throw new IllegalStateException("FMOD could not open the Ogg song.");
        channel=javafmod.FMOD_System_PlaySound(sound,true);
        if(channel<=0)throw new IllegalStateException("FMOD could not start the song.");
        javafmod.FMOD_Channel_SetVolume(channel,songVolume());
        if(javafmod.FMOD_Channel_SetPaused(channel,false)!=0)throw new IllegalStateException("FMOD could not unpause the song.");
        System.out.println("[DivaCharacter] PV audio stream started");
    }

    void stop(){
        frame=null;
        if(pending!=null){pending.data.cancel(true);pending=null;}
        long oldChannel=channel,oldSound=sound,oldVca=musicVca;
        channel=0;sound=0;musicVca=0;
        if(clip!=null||player!=null)message="PV preview stopped.";
        else message="Choose a PV from the original game.";
        clip=null;player=null;
        try{
            if(oldChannel>0)javafmod.FMOD_Channel_Stop(oldChannel);
            if(oldSound>0)javafmod.FMOD_Sound_Release(oldSound);
        }finally{
            if(oldVca>0){
                float current=SoundManager.instance.getMusicVolume();
                float volume=current==hostMusic?savedBgm:current;
                if(javafmodJNI.FMOD_Studio_VCA_SetVolume(oldVca,volume)==0)
                    System.out.println("[DivaCharacter] PZ music volume restored to "+volume);
            }
        }
    }
}
