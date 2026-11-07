package divachar;

import org.joml.*;

/** Analytic eye refraction uses a unit head-skin frame, independently of avatar scale. */
public final class EyeFrame {
    public static Matrix4f joint(Assets assets,Matrix4f[] palette){
        int head=assets.names.indexOf("j_kao_wj");
        if(head<0)throw new IllegalStateException("Missing eye reference head joint");
        Matrix4f skin=palette[head];Vector3f position=skin.getTranslation(new Vector3f());
        Quaternionf rotation=skin.getUnnormalizedRotation(new Quaternionf()).normalize();
        return new Matrix4f().translationRotate(position.x,position.y,position.z,rotation);
    }
}
