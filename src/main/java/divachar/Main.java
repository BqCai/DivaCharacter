package divachar;

import java.util.*;
import java.nio.file.*;
import java.util.concurrent.Future;
import org.json.*;
import org.joml.*;
import me.zed_0xff.zombie_buddy.Exposer;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.iso.weather.ClimateManager;
import zombie.ZomboidFileSystem;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import static org.lwjgl.opengl.GL43C.*;

@Exposer.LuaClass(name="DivaCharacterAPI")
public final class Main {
    public static volatile boolean enabled,ready;
    private static volatile String status="Choose an outfit";
    private static volatile Avatar active;
    private static volatile Selection pending;
    private static DivaLibrary library;
    private static GameplayPerformance.Library gameplay;
    private static volatile int headPreview;
    private static volatile int faceSoftFill=100;
    private static final PvPlayer pv=new PvPlayer();
    private static final Set<Avatar> uploaded=new HashSet<>();
    private static HostLighting lighting;
    private static Scattering scattering;
    private static boolean scatteringSeen;
    private static IrisLegacy irisLegacy;
    private static boolean irisLegacyFailed,irisLegacySeen;
    private static IrisEclipse irisEclipse;
    private static Object irisPostFailed;
    private static Boolean irisPostHdr;
    private static final Map<Object,Captured> poses=Collections.synchronizedMap(new WeakHashMap<>());
    private record Selection(String id,Future<Assets> assets){}
    private static final class Captured {
        final Avatar avatar;
        final Pose pose;
        final int first;
        final int[] sides;
        boolean submitted,iris,legacy;
        Captured(Avatar avatar,Pose pose,int first,int[] sides){this.avatar=avatar;this.pose=pose;this.first=first;this.sides=sides;}
    }
    private static final class Avatar {
        final String id;
        final Assets assets;
        final Renderer renderer;
        final OsagePhysics physics;
        final GameplayPerformance performance;
        long physicsSession=-1;
        final IrisEntities iris=new IrisEntities();
        boolean irisFailed,irisLogged;
        int shaderRevision=Integer.MIN_VALUE;
        Avatar(String id,Assets assets){
            this.id=id;this.assets=assets;renderer=new Renderer(assets);physics=new OsagePhysics(assets);
            var motions=assets.gameplay==null?gameplay:assets.gameplay;
            performance=motions==null?null:new GameplayPerformance(motions);
        }
    }
    private static boolean failed;
    private static volatile boolean bindPose;
    private static long captures,draws;
    private static boolean heldSeen;
    private static boolean dumped;
    private static int dumpedRevision=-1;
    private static long surfaces;
    public static void main(String[] arguments){
        String version=Assets.hasReference()?"0.1.0-dev":"0.1.0-alpha.1";
        System.out.println("[DivaCharacter] "+version+" / PZ 42.21, Viewpoint 0.1.6.1");
        library=new DivaLibrary(Path.of(ZomboidFileSystem.instance.getCacheDir(),"DivaCharacter"));
        try{if(Assets.hasReference())gameplay=new GameplayPerformance.Library();}
        catch(Exception error){System.out.println("[DivaCharacter] Gameplay detail unavailable: "+error);}
        library.start();
    }
    public static void applyDefault(){applyModule("miku_default");}
    public static boolean hasBundledOutfit(){return Assets.hasReference();}
    public static void applyModule(String id){
        pv.stop();
        synchronized(poses){
            if(pending!=null)pending.assets.cancel(false);
            pending=new Selection(id,library.prepare(id));
            status="Preparing outfit "+id+"…";
        }
    }
    public static void restore(){pv.stop();synchronized(poses){
        if(pending!=null)pending.assets.cancel(false);
        pending=null;enabled=false;ready=false;headPreview=0;poses.clear();status="Original player appearance";
    }}
    public static void setGamePath(String path){pv.stop();library.setPath(path);}
    public static int getHeadPreview(){return headPreview;}
    public static void setHeadPreview(int id){if(id>=0&&id<=8)headPreview=id;}
    public static int getFaceSoftFill(){return faceSoftFill;}
    public static void setFaceSoftFill(int percent){if(percent>=0&&percent<=200)faceSoftFill=percent;}
    public static void browseGamePath(){library.browse();}
    public static String getGamePath(){return library.path();}
    public static String getCatalogStatus(){return library.message();}
    public static int getModuleCount(){return library.modules().size();}
    public static String getModuleId(int index){var modules=library.modules();return index>=0&&index<modules.size()?modules.get(index).id():"";}
    public static String getModuleName(int index){var modules=library.modules();return index>=0&&index<modules.size()?modules.get(index).name():"";}
    public static int getPvCount(){return library.pvs().size();}
    public static String getPvId(int index){var rows=library.pvs();return index>=0&&index<rows.size()?rows.get(index).id():"";}
    public static String getPvName(int index){var rows=library.pvs();return index>=0&&index<rows.size()?rows.get(index).name():"";}
    public static boolean getPvAvailable(int index){var rows=library.pvs();return index>=0&&index<rows.size()&&rows.get(index).available();}
    public static String getPvStatus(){return pv.message();}
    public static boolean isPvBusy(){return pv.busy();}
    public static void stopPv(){pv.stop();}
    public static void tickPv(){if(pv.busy())pv.tick(IsoPlayer.getInstance(),enabled&&ready&&pending==null&&thirdPerson());}
    public static void playPv(String id){
        IsoPlayer player=IsoPlayer.getInstance();
        if(player==null||player.isDead()||player.getVehicle()!=null){pv.unavailable("Stand on foot to preview a PV.");return;}
        if(!enabled||!ready||pending!=null){pv.unavailable("Apply a Miku outfit first.");return;}
        if(!thirdPerson()){pv.unavailable("Switch to Viewpoint third person first.");return;}
        pv.request(library,id,player);
    }
    private static boolean thirdPerson(){
        try{
            Class<?> view=Class.forName("viewpoint.core.View");
            return Boolean.TRUE.equals(Interop.get(view,"enabled"))&&Boolean.TRUE.equals(Interop.get(view,"thirdPerson"));
        }catch(ReflectiveOperationException error){return false;}
    }
    public static String getActiveModule(){Avatar current=active;return enabled&&current!=null?current.id:"";}
    public static boolean isPreparingModule(){return pending!=null;}
    public static boolean isEnabled(){return enabled;}
    public static String getStatus(){return status;}
    public static String debugState(){return "captures="+captures+" draws="+draws+" ready="+ready+" sourcePose="+bindPose;}
    public static void setSourcePose(boolean value){bindPose=value;}
    public static void failure(String operation,Throwable error){
        enabled=false;ready=false;status=operation+": "+error.getClass().getSimpleName()+" "+error.getMessage();
        if(!failed){System.out.println("[DivaCharacter] "+status);error.printStackTrace();failed=true;}
    }
    public static int beforeCapture(Object frame,Object character){
        if(!enabled||!ready||character!=IsoPlayer.getInstance())return -1;
        try{return Interop.integer(Interop.get(Interop.get(frame,"scene"),"models"),"count");}catch(Exception e){failure("capture compatibility",e);return -1;}
    }
    public static void afterCapture(Object[] args,int first,boolean captured){
        Avatar avatar=active;
        if(first<0||!captured||!enabled||!ready||avatar==null)return;
        try{
            Tuning.poll();
            ModelSlotRenderData slot=(ModelSlotRenderData)args[1];
            Object scene=Interop.get(args[0],"scene"),models=Interop.get(scene,"models");
            Matrix4f hostRoot=new Matrix4f((Matrix4f)Interop.get(Class.forName("viewpoint.models.ModelCapture"),"place"));
            IsoPlayer player=(IsoPlayer)args[2];
            boolean fitLegs=!slot.inVehicle&&!player.isClimbing()&&!player.isClimbingRope()&&!player.isOnFloor();
            boolean sourcePose=bindPose||Tuning.sourcePose;
            Vector3f actorPosition=new Vector3f(-slot.x,slot.z*2.4494896f,-slot.y);
            Vector3f wind=worldWind(player);
            PvPlayer.Frame preview=pv.frame();
            long session=preview==null?-1:preview.generation();
            if(avatar.physicsSession!=session){avatar.physics.reset();avatar.physicsSession=session;}
            Pose pose;
            try{
                pose=preview==null?null:new Pose(avatar.assets,hostRoot,avatar.physics,actorPosition,wind,preview,Tuning.hairPhysics);
            }catch(RuntimeException error){
                pv.renderingFailed(preview.generation());
                System.out.println("[DivaCharacter] PV pose: "+error);
                pose=null;
            }
            if(pose==null)pose=new Pose(avatar.assets,slot.animPlayer,hostRoot,sourcePose,(Boolean)args[4],fitLegs,
                avatar.physics,actorPosition,wind,GameTime.isGamePaused(),!slot.inVehicle&&Tuning.hairPhysics,
                player,avatar.performance,headPreview);
            JSONArray heldDiagnostic=new JSONArray();
            float[] modelValues=(float[])Interop.get(models,"values");int lightBase=first*68+20;pose.engineLight.set(modelValues[lightBase],modelValues[lightBase+1],modelValues[lightBase+2],modelValues[lightBase+3]);
            int[] sides=new int[java.lang.Math.min(slot.modelData.size(),Interop.integer(models,"count")-first)];
            for(int i=0;i<sides.length;i++){
                var data=slot.modelData.get(i);
                int side=pose.pv==null&&data.model.isStatic?Attachments.side(data.modelInstance):-1;
                sides[i]=side;
                if(side>=0&&pose.heldDeltas[side]!=null&&(!dumped||dumpedRevision!=Tuning.captureRevision)){
                    Matrix4f moved=new Matrix4f(pose.heldDeltas[side]).mul(new Matrix4f().set(modelValues,(first+i)*68));
                    heldDiagnostic.put(new JSONObject().put("bone",String.valueOf(data.modelInstance.parentBoneName)).put("side",side==Attachments.LEFT?"left":"right").put("matrix",new JSONArray(moved.get(new float[16]))));
                }
            }
            synchronized(poses){
                if(active!=avatar||!enabled||!ready){poses.remove(scene);return;}
                poses.put(scene,new Captured(avatar,pose,first,sides));
            }
            if(++captures==1)System.out.println("[DivaCharacter] first local pose captured; source="+args[5]+" PZ bones="+slot.animPlayer.getNumBones()+" draws="+slot.modelData.size());
            if(ready&&(!dumped||dumpedRevision!=Tuning.captureRevision)){
                dumped=true;
                dumpedRevision=Tuning.captureRevision;
                JSONObject dump=new JSONObject().put("root",new JSONArray(pose.root.get(new float[16]))).put("pzNames",slot.animPlayer.getSkinningBoneIndices()).put("sourcePoseKind",args[5].toString()).put("firstPerson",pose.firstPerson).put("held",heldDiagnostic);
                JSONObject current=new JSONObject(),bind=new JSONObject();
                for(var entry:slot.animPlayer.getSkinningBoneIndices().entrySet()){
                    current.put(entry.getKey(),new JSONArray(Pose.convert(slot.animPlayer.getModelTransformAt(entry.getValue())).get(new float[16])));
                    var offset=slot.animPlayer.getSkinningData().boneOffset.get(entry.getValue());
                    if(offset!=null)bind.put(entry.getKey(),new JSONArray(Pose.convert(offset).invert().get(new float[16])));
                }
                dump.put("current",current).put("bind",bind);
                Path file=Path.of(System.getProperty("java.io.tmpdir"),"DivaCharacter-pose.json");Files.writeString(file,dump.toString());
                System.out.println("[DivaCharacter] pose diagnostic written to "+file);
            }
        }catch(Exception e){failure("pose capture",e);}
    }

    static Pose pvPose(Object scene){
        Captured captured=poses.get(scene);
        PvPlayer.Frame playing=pv.frame();
        if(captured==null||captured.pose.pv==null||playing==null||captured.pose.pv.generation()!=playing.generation())return null;
        return captured.pose;
    }

    static void pvCameraFailed(long generation){pv.renderingFailed(generation);}

    private static Vector3f worldWind(IsoPlayer player){
        if(!player.isOutside())return new Vector3f();
        ClimateManager climate=ClimateManager.getInstance();
        float angle=climate.getWindAngleRadians(),strength=3f*climate.getWindIntensity();
        if(!Float.isFinite(angle)||!Float.isFinite(strength))return new Vector3f();
        return new Vector3f(-(float)java.lang.Math.sin(angle)*strength,0,-(float)java.lang.Math.cos(angle)*strength);
    }

    private static void updateSelection(){
        Selection request=pending;
        if(request==null||!request.assets.isDone())return;
        Assets prepared=null;
        try(GlState state=new GlState()){
            prepared=request.assets.get();
            prepared.upload();
            Avatar next=new Avatar(request.id,prepared);
            reloadShaders(next);
            synchronized(poses){
                if(pending!=request){prepared.release();return;}
                uploaded.add(next);active=next;pending=null;enabled=true;ready=true;
                String name=prepared.manifest.has("module")?prepared.manifest.getJSONObject("module").getString("name"):"Default Miku (bundled)";
                status="Active: "+name;
                System.out.println("[DivaCharacter] outfit active id="+next.id+" bones="+prepared.names.size()+" meshes="+prepared.meshes.size()+" Osage chains="+prepared.osage.size());
            }
        }catch(Exception|LinkageError error){
            if(prepared!=null)prepared.release();
            synchronized(poses){
                if(pending==request){
                    pending=null;
                    Throwable cause=error.getCause()==null?error:error.getCause();
                    status="Import failed: "+cause.getMessage();
                    System.out.println("[DivaCharacter] "+status);
                }
            }
        }
    }

    private static void releaseRetired(){
        if(uploaded.size()<2)return;
        synchronized(poses){
            Set<Avatar> retained=new HashSet<>();
            retained.add(active);
            for(Captured captured:poses.values())retained.add(captured.avatar);
            uploaded.removeIf(avatar->{
                if(retained.contains(avatar))return false;
                avatar.iris.release();avatar.assets.release();return true;
            });
        }
    }

    private static void reloadShaders(Avatar avatar){
        int revision=Tuning.shaderRevision;
        if(revision==avatar.shaderRevision)return;
        avatar.shaderRevision=revision;
        try{
            avatar.assets.reloadPrograms(Tuning.FILE.resolveSibling("divachar-shaders"));
            System.out.println("[DivaCharacter] shader reload ready for next frame: "+revision);
        }catch(Exception error){System.out.println("[DivaCharacter] kept previous shaders: "+error.getMessage());}
    }

    /** Both backends consume the same capture. Replace the host records only after the selected route is ready. */
    public static void prepareModels(Object scene){
        if((!enabled&&pending==null)||failed)return;
        try{
            Tuning.poll();
            updateSelection();
            releaseRetired();
            ready=enabled&&active!=null;
            if(!ready)return;
            Captured captured=poses.get(scene);
            if(captured==null||captured.avatar!=active||captured.submitted)return;
            Object models=Interop.get(scene,"models");
            Avatar avatar=captured.avatar;Pose pose=captured.pose;
            if(IrisEntities.requested()){
                if(avatar.irisFailed)return;
                try{
                    boolean pbr=Tuning.irisPbr;
                    captured.legacy=!pbr&&!irisLegacyFailed;
                    avatar.iris.submit(models,avatar.assets,pose.palette,pose.root,pose.engineLight,pose.firstPerson,captured.legacy,pbr,pose.headVariant);
                    captured.iris=true;
                    if(!avatar.irisLogged){
                        avatar.irisLogged=true;
                        System.out.println("[DivaCharacter] Iris entity batches submitted id="+avatar.id+" meshes="+avatar.assets.meshes.size());
                    }
                }catch(Exception|LinkageError error){
                    avatar.irisFailed=true;
                    status="Iris entity submission unavailable. Showing original player.";
                    System.out.println("[DivaCharacter] "+status+" "+error);
                    error.printStackTrace();
                    return;
                }
            }
            int[] flags=(int[])Interop.get(models,"flags");float[] values=(float[])Interop.get(models,"values");
            for(int i=0;i<captured.sides.length;i++){
                int index=captured.first+i,side=captured.sides[i];
                if(side<0){flags[index]=0;values[index*68+55]=0;}
                else if(pose.heldDeltas[side]!=null){
                    Attachments.relocate(values,index,pose.heldDeltas[side]);
                    if(!heldSeen){heldSeen=true;System.out.println("[DivaCharacter] first held prop relocated; side="+side);}
                }
            }
            captured.submitted=true;
        }catch(Exception|LinkageError error){failure("character model preparation",error);}
    }

    public static void beforeIrisScreen(Object irisFrame,Object pipeline,Object pass,Object frame){
        if(!enabled||!ready||failed||irisPostFailed==pipeline||!Tuning.irisPost||!Tuning.irisHdr||
            (!Tuning.irisFog&&!Tuning.irisSceneLight))return;
        try{
            if(!"composite3".equals(Interop.get(pass,"name")))return;
            Captured captured=poses.get(Interop.get(frame,"scene"));
            if(captured==null||!captured.submitted||!captured.legacy)return;
            if(irisLegacy==null)irisLegacy=new IrisLegacy();
            if(irisEclipse==null)irisEclipse=new IrisEclipse();
            Avatar avatar=captured.avatar;
            irisEclipse.prepareFog(irisFrame,pipeline,pass,frame,irisLegacy,avatar.assets,avatar.renderer,captured.pose);
        }catch(Exception|LinkageError error){
            if(irisEclipse!=null)irisEclipse.failFog(error);
        }
    }

    public static void afterIrisScreen(Object pipeline,Object pass,Object frame){
        if(!enabled||!ready||failed||irisPostFailed==pipeline||!Tuning.irisPost)return;
        try{
            Captured captured=poses.get(Interop.get(frame,"scene"));
            if(captured==null||!captured.submitted||!captured.legacy)return;
            if(irisLegacy==null)irisLegacy=new IrisLegacy();
            if(irisEclipse==null)irisEclipse=new IrisEclipse();
            Avatar avatar=captured.avatar;
            if(irisEclipse.draw(pipeline,pass,frame,irisLegacy,avatar.assets,avatar.renderer,captured.pose)){
                boolean hdr=irisEclipse.hdrUsed();
                if(irisPostHdr==null||irisPostHdr!=hdr){
                    irisPostHdr=hdr;
                    System.out.println("[DivaCharacter] Future Tone is feeding Eclipse "+
                        (hdr?"HDR bloom, depth of field, and display grading":"display grading"));
                }
                reloadShaders(avatar);
            }
        }catch(Exception|LinkageError error){
            irisPostFailed=pipeline;
            System.out.println("[DivaCharacter] Eclipse display grading unavailable; using final composite: "+error);
            error.printStackTrace();
        }
    }

    public static void bindIrisPost(Object program){
        if(irisEclipse!=null)irisEclipse.bindFor(program);
    }

    public static void afterIrisStage(Object pipeline,Object stage,Object frame){
        if(!(stage instanceof Enum<?> pass))return;
        if(pass.name().equals("BEGIN")&&irisEclipse!=null)irisEclipse.finish();
        if(!pass.name().equals("FINAL"))return;
        if(irisEclipse!=null&&irisEclipse.finish())return;
        if(!enabled||!ready||failed||irisLegacyFailed)return;
        try{
            Captured captured=poses.get(Interop.get(frame,"scene"));
            if(captured==null||!captured.submitted||!captured.legacy)return;
            if(irisLegacy==null)irisLegacy=new IrisLegacy();
            Avatar avatar=captured.avatar;
            irisLegacy.draw(pipeline,frame,avatar.assets,avatar.renderer,captured.pose);
            if(!irisLegacySeen){
                irisLegacySeen=true;
                System.out.println("[DivaCharacter] Future Tone composite active after Iris final colour pass");
            }
            reloadShaders(avatar);
        }catch(Exception|LinkageError error){
            irisLegacyFailed=true;
            status="Future Tone composite unavailable. Using Iris base materials.";
            System.out.println("[DivaCharacter] "+status+" "+error);
            error.printStackTrace();
        }
    }

    /** ModelPass.gbuffer exit precedes far surfaces and the host indirect-light pass. */
    public static void geometry(Object frame){
        if(!enabled||!ready||failed)return;
        try{
            Object scene=Interop.get(frame,"scene");Captured captured=poses.get(scene);
            if(captured==null||!captured.submitted)return;
            Pose pose=captured.pose;Renderer renderer=captured.avatar.renderer;
            Object targets=Interop.get(Class.forName("viewpoint.render.WorldRenderer"),"targets");
            HostLighting.writeSurface(Interop.integer(targets,"gbufferFbo"),Interop.integer(frame,"width"),Interop.integer(frame,"height"),renderer,pose.palette,pose.engineLight,
                (Matrix4f)Interop.get(frame,"viewProjection"),(Matrix4f)Interop.get(frame,"view"),
                new Vector3f(Interop.number(frame,"eyeX"),Interop.number(frame,"eyeY"),Interop.number(frame,"eyeZ")),pose.firstPerson,pose.headVariant);
            if(++surfaces==1)System.out.println("[DivaCharacter] character depth and normals written before host occlusion/bounce");
        }catch(Exception|LinkageError e){failure("host character surface",e);}
    }
    public static void render(Object scene){
        if(!enabled||active==null||failed)return;
        Captured captured=poses.get(scene);
        if(captured==null||!captured.submitted)return;
        try(GlState state=new GlState(true)){
            Tuning.poll();
            if((Boolean)Interop.get(Class.forName("viewpoint.render.WorldRenderer"),"irisDrawn"))return;
            Avatar avatar=captured.avatar;Assets assets=avatar.assets;Renderer renderer=avatar.renderer;Pose pose=captured.pose;
            Object frame=Interop.get(Class.forName("viewpoint.render.WorldRenderer"),"frame");
            Matrix4f vp=(Matrix4f)Interop.get(frame,"viewProjection"),view=(Matrix4f)Interop.get(frame,"view");
            Vector3f eye=new Vector3f(Interop.number(frame,"eyeX"),Interop.number(frame,"eyeY"),Interop.number(frame,"eyeZ"));
            Object targets=Interop.get(Class.forName("viewpoint.render.WorldRenderer"),"targets");
            if(lighting==null)lighting=new HostLighting();
            Renderer.hostLight=lighting.draw(frame,targets,assets,renderer,pose,vp,view,eye);
            Renderer.hostScattering=0;
            if(Tuning.scattering&&assets.manifest.has("scattering")){
                if(scattering==null)scattering=new Scattering(assets);
                Renderer.hostScattering=scattering.draw(Interop.integer(targets,"gbufferFbo"),Interop.integer(frame,"width"),Interop.integer(frame,"height"),renderer,vp,view,eye,pose.palette,pose.firstPerson,pose.headVariant);
                if(!scatteringSeen){scatteringSeen=true;System.out.println("[DivaCharacter] character scattering prepass and RGB filter active");}
            }
            glBindFramebuffer(GL_FRAMEBUFFER,Interop.integer(targets,"hdrFbo"));glViewport(0,0,Interop.integer(frame,"width"),Interop.integer(frame,"height"));
            renderer.render(vp,view,eye,pose.palette,Interop.integer(frame,"height"),pose.firstPerson,false,false,pose.headVariant);
            if(++draws==1)System.out.println("[DivaCharacter] first custom frame drawn; eye="+eye+" source root="+pose.root.getTranslation(new Vector3f()));
            reloadShaders(avatar);
        }catch(Exception|LinkageError e){failure("Viewpoint rendering",e);}
    }
    public static void shadow(Object scene,Matrix4f lightProjection){
        if(!enabled||!ready||failed)return;
        Captured captured=poses.get(scene);if(captured==null||!captured.submitted||captured.iris)return;
        Pose pose=captured.pose;Renderer renderer=captured.avatar.renderer;
        try(GlState state=new GlState()){
            Object frame=Interop.get(Class.forName("viewpoint.render.WorldRenderer"),"frame");
            Vector3f eye=new Vector3f(Interop.number(frame,"eyeX"),Interop.number(frame,"eyeY"),Interop.number(frame,"eyeZ"));
            renderer.render(lightProjection,(Matrix4f)Interop.get(frame,"view"),eye,pose.palette,Interop.integer(frame,"height"),false,true,false,pose.headVariant);
        }catch(Exception|LinkageError e){failure("character shadow rendering",e);}
    }
}
