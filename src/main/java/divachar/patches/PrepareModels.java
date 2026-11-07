package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.render.ModelPass",methodName="prepare")
public final class PrepareModels {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0)Object scene){Main.prepareModels(scene);}
}
