package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.render.IrisBindings",methodName="bind")
public final class IrisPostInputs {
    @Patch.OnExit
    public static void exit(@Patch.Argument(0) Object program){Main.bindIrisPost(program);}
}
