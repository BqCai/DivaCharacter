package divachar;
import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.render.ModelPass",methodName="gbuffer")
public final class Geometry {
    @Patch.OnExit
    public static void exit(@Patch.Argument(0)Object frame){Main.geometry(frame);}
}
