package divachar;
import me.zed_0xff.zombie_buddy.Patch;
import divachar.Main;

@Patch(className="viewpoint.render.WorldRenderer",methodName="drawTranslucent")
public final class Forward {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0)Object scene){Main.render(scene);}
}
