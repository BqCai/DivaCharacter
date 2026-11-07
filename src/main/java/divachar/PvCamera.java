package divachar;

import java.lang.Math;
import org.joml.*;

/** Scope the PV camera to one Viewpoint draw, with the same anchor and sample as the actor. */
public final class PvCamera {
    private record Projection(Matrix4f matrix,float x,float y){}
    private static final ThreadLocal<Projection> override=new ThreadLocal<>();
    private PvCamera(){}

    public static void beforeLook(Object drawer){
        Pose pose=null;
        try{
            Object frame=Interop.get(drawer,"frame"),scene=Interop.get(frame,"scene");
            pose=Main.pvPose(scene);
            if(pose==null)return;
            PvClip.Camera camera=pose.pv.sample().camera();
            Vector3f eye=pose.root.transformPosition(camera.eye(),new Vector3f());
            Vector3f dir=pose.root.transformPosition(camera.target(),new Vector3f()).sub(eye);
            if(!eye.isFinite()||!dir.isFinite()||dir.lengthSquared()<1e-6f||
                !Float.isFinite(camera.fov())||camera.fov()<.01f||camera.fov()>3.1f)
                throw new IllegalStateException("Invalid camera shot "+pose.pv.sample().shot());
            dir.normalize();
            Vector3f up=new Vector3f(0,1,0).fma(-dir.y,dir);
            if(up.lengthSquared()<1e-6f)up.set(0,0,1).fma(-dir.z,dir);
            up.normalize().rotateAxis(camera.roll(),dir.x,dir.y,dir.z);
            Matrix4f projection=(Matrix4f)Interop.get(scene,"projection");
            if(override.get()==null)override.set(new Projection(projection,projection.m00(),projection.m11()));
            Projection original=override.get();
            float aspect=original.y/original.x;
            float focal=1f/(float)Math.tan(camera.fov()*.5f);
            projection.m00(camera.horizontal()?focal:focal/aspect).m11(camera.horizontal()?focal*aspect:focal);
            copy(eye,(float[])Interop.get(drawer,"eye"));
            copy(dir,(float[])Interop.get(drawer,"dir"));
            copy(up,(float[])Interop.get(drawer,"up"));
        }catch(ReflectiveOperationException|RuntimeException error){
            restore();
            if(pose!=null){
                Main.pvCameraFailed(pose.pv.generation());
                System.out.println("[DivaCharacter] PV camera: "+error);
            }
        }
    }

    private static void copy(Vector3f vector,float[] target){
        target[0]=vector.x;target[1]=vector.y;target[2]=vector.z;
    }

    public static void restore(){
        Projection original=override.get();
        if(original==null)return;
        original.matrix.m00(original.x).m11(original.y);
        override.remove();
    }
}
