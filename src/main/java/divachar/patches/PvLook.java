package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.SceneDrawer",methodName="lookAlong")
public final class PvLook {
    @Patch.OnEnter
    public static void enter(@Patch.This Object drawer){PvCamera.beforeLook(drawer);}
}
