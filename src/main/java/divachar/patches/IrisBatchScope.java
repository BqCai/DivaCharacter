package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.render.ModelBatches",methodName="issue")
public final class IrisBatchScope {
    @Patch.OnEnter
    public static void enter(){IrisMaterialBindings.begin();}

    @Patch.OnExit(onThrowable=Throwable.class)
    public static void exit(){IrisMaterialBindings.end();}
}
