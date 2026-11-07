package divachar;

import org.joml.*;

/** PZ and DIVA both use +Y up and +X for the left limb, but their forward Z signs differ. */
public final class Basis {
    public static Matrix4f pzToDiva(Matrix4f pose){
        // Coordinate conversion, not an extra skeletal joint rotation: C * pose * C.
        return new Matrix4f().scaling(1,1,-1).mul(pose).scale(1,1,-1);
    }
    public static Matrix4f divaToWorld(Matrix4f pzActor,float scale){
        return new Matrix4f(pzActor).scale(scale,scale,-scale);
    }
}
