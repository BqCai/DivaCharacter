package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.SceneDrawer",methodName="drawFrame")
public final class PvProjection {
    @Patch.OnExit(onThrowable=Throwable.class)
    public static void exit(){PvCamera.restore();}
}
