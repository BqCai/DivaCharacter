package divachar;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import static org.lwjgl.opengl.GL43C.*;

/** Eclipse V12's HDR and display routes, checked against the installed pass contracts. */
public final class IrisEclipse {
    private static final String PACK="minecraft-Eclipse-Viewpoint-V12";
    private static final int GRASS_HIGH_VERTICES=24,GRASS_OUTPUT_COMPONENTS=48;
    private int framebuffer;
    private boolean composited;
    private final IrisHdrLayer hdr=new IrisHdrLayer();
    private final EclipseFog fog=new EclipseFog();
    private record FogFrame(Object program,int quad,Object pipeline,Object frame,IrisLegacy renderer,
                            Assets assets,Renderer material,Pose pose,int width,int height,boolean atmosphere,int shadowMode){}
    private Object currentPipeline;
    private FogFrame fogFrame;
    private IrisLegacy.Layer actorLayer;
    private int actorFog;
    private boolean fogFailed,fogLogged;
    private boolean hdrFailed,lastHdr;

    public static void bindRelief(Object program){
        try{
            Object gl=Interop.get(program,"gl");
            if(!((String)Interop.get(gl,"name")).startsWith(PACK+" "))return;
            int id=Interop.integer(gl,"id");
            // FarRelief uses these units at draw time. Supply them for the link-time sampler check.
            glProgramUniform1i(id,glGetUniformLocation(id,"uRelief"),91);
            glProgramUniform1i(id,glGetUniformLocation(id,"uReliefWater"),92);
            glProgramUniform1i(id,glGetUniformLocation(id,"uReliefWalls"),93);
            glProgramUniform1i(id,glGetUniformLocation(id,"uReliefFlow"),124);
        }catch(ReflectiveOperationException ignored){}
    }

    public static Map<String,String> limitGrass(Object pack,Map<String,String> options){
        try{
            if(!PACK.equals(Interop.get(pack,"id"))||!"true".equals(options.get("SHADER_GRASS_SETTING"))||
                !"2".equals(options.getOrDefault("GRASS_QUALITY","2")))return options;
            int limit=Math.min(glGetInteger(GL_MAX_GEOMETRY_OUTPUT_VERTICES),
                glGetInteger(GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS)/GRASS_OUTPUT_COMPONENTS);
            if(limit>=GRASS_HIGH_VERTICES)return options;
            Map<String,String> adjusted=new HashMap<>(options);
            int quality=limit>=18?1:0;
            adjusted.put("GRASS_QUALITY",Integer.toString(quality));
            System.out.println("[DivaCharacter] Eclipse shader grass geometry limited to quality "+quality+" for this GPU");
            return adjusted;
        }catch(ReflectiveOperationException ignored){return options;}
    }

    private boolean select(Object pipeline)throws ReflectiveOperationException{
        if(!PACK.equals(Interop.get(Interop.get(pipeline,"pack"),"id")))return false;
        if(currentPipeline!=pipeline){
            currentPipeline=pipeline;
            hdrFailed=fogFailed=fogLogged=false;
            finish();
        }
        return true;
    }

    void prepareFog(Object irisFrame,Object pipeline,Object pass,Object frame,IrisLegacy renderer,
                    Assets assets,Renderer material,Pose pose)throws Exception{
        if(!Tuning.irisHdr||(!Tuning.irisFog&&!Tuning.irisSceneLight)||!select(pipeline)||fogFailed||hdrFailed)return;
        Object program=Interop.get(pass,"program");
        if(!List.of(0,13).equals(Interop.get(program,"drawBuffers"))||!fog.supported(program))return;
        Map<?,?> options=(Map<?,?>)Interop.get(Interop.get(pipeline,"configured"),"values");
        if("true".equals(options.get("TAA_UPSCALING")))return;
        Object targets=Interop.get(pipeline,"targets");
        int width=Interop.integer(targets,"width"),height=Interop.integer(targets,"height");
        if(width<=0||height<=0)return;
        int quad=Interop.integer(irisFrame,"quadVao");
        boolean atmosphere=Tuning.irisFog&&!"false".equals(options.get("TOGGLE_VL_FOG"));
        int shadowMode=(Boolean)Interop.get(pipeline,"shadowOn")&&Tuning.irisSceneLight
            ?("true".equals(options.get("DISTORT_SHADOWMAP"))?2:1):0;
        fogFrame=new FogFrame(program,quad,pipeline,frame,renderer,assets,material,pose,width,height,atmosphere,shadowMode);
    }

    boolean draw(Object pipeline,Object pass,Object frame,IrisLegacy renderer,Assets assets,Renderer material,Pose pose)throws Exception{
        String name=(String)Interop.get(pass,"name");
        if(!name.equals("composite6")&&!name.equals("composite11"))return false;
        Object program=Interop.get(pass,"program");
        if(!select(pipeline)||
            !((Map<?,?>)Interop.get(program,"units")).containsKey("colortex5"))return false;
        Object targets=Interop.get(pipeline,"targets");
        int width=Interop.integer(targets,"width"),height=Interop.integer(targets,"height");
        if(name.equals("composite6")){
            if(!Tuning.irisHdr||hdrFailed||!List.of(5).equals(Interop.get(program,"drawBuffers")))return false;
            try{
                int scene=target(targets,5,width,height),fog=target(targets,7,width,height);
                int depth=Interop.integer(targets,"depthOpaque");
                if(scene==0||fog==0||depth==0)return false;
                boolean reuse=actorLayer!=null&&fogFrame.width()==width&&fogFrame.height()==height;
                var layer=reuse?actorLayer:renderer.renderLayer(pipeline,frame,assets,material,pose,width,height);
                hdr.resolve(width,height,scene,fog,depth,layer,reuse?actorFog:0);
            }catch(Exception|LinkageError error){
                hdrFailed=true;hdr.reset();
                System.out.println("[DivaCharacter] Eclipse HDR input unavailable; keeping display grading: "+error);
            }
            return false;
        }
        if(!List.of(7).equals(Interop.get(program,"drawBuffers")))return false;
        if(hdr.used()){
            composited=true;lastHdr=true;hdr.reset();return true;
        }
        hdr.reset();lastHdr=false;
        int colour=target(targets,7,width,height);
        if(colour==0)return false;

        try(GlState ignored=new GlState()){
            if(framebuffer==0)framebuffer=glGenFramebuffers();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,framebuffer);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,colour,0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete Eclipse display target");
            renderer.draw(pipeline,frame,assets,material,pose,framebuffer,new int[]{0,0,width,height});
            composited=true;
            return true;
        }
    }

    private static int target(Object targets,int slot,int width,int height)throws ReflectiveOperationException{
        Object buffer=((Object[])Interop.get(targets,"buffers"))[slot];
        if(buffer==null||width!=Interop.integer(buffer,"width")||height!=Interop.integer(buffer,"height"))return 0;
        return ((int[])Interop.get(buffer,"texture"))[Interop.integer(buffer,"read")];
    }

    boolean hdrUsed(){return lastHdr;}

    void bindFor(Object program){
        if(fogFrame!=null&&fogFrame.program()==program){
            try{
                FogFrame input=fogFrame;
                actorLayer=input.renderer().renderLayer(input.pipeline(),input.frame(),input.assets(),input.material(),input.pose(),
                    input.width(),input.height(),surface->{
                        var maps=fog.draw(program,input.quad(),surface,input.width(),input.height(),input.atmosphere(),input.shadowMode());
                        actorFog=maps.fog();
                        if(maps.lighting()!=0&&!fogLogged){
                            fogLogged=true;
                            System.out.println("[DivaCharacter] Eclipse character atmosphere="+input.atmosphere()+
                                " scene lighting="+Tuning.irisSceneLight+" shadow mode="+input.shadowMode());
                        }
                        return Tuning.irisSceneLight?maps.lighting():0;
                    });
            }catch(Exception|LinkageError error){failFog(error);}
        }
        try{hdr.bindFor(program);}
        catch(Exception|LinkageError error){
            hdrFailed=true;hdr.reset();
            System.out.println("[DivaCharacter] Eclipse HDR sampler override unavailable: "+error);
        }
    }

    void failFog(Throwable error){
        fogFailed=true;
        fogFrame=null;
        actorLayer=null;
        actorFog=0;
        System.out.println("[DivaCharacter] Eclipse character fog unavailable; retaining HDR composition: "+error);
    }

    boolean finish(){
        boolean drawn=composited;
        composited=false;
        fogFrame=null;actorLayer=null;actorFog=0;
        hdr.reset();
        return drawn;
    }
}
