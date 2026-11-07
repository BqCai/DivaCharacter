package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.render.ModelPass",methodName="bind")
public final class IrisBatchTexture {
    @Patch.OnEnter
    public static void enter(){IrisMaterialBindings.restore();}
}
