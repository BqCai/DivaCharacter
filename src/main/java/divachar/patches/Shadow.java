package divachar;
import me.zed_0xff.zombie_buddy.Patch;
import org.joml.Matrix4f;

@Patch(className="viewpoint.render.ModelPass",methodName="shadow")
public final class Shadow {
    @Patch.OnExit
    public static void exit(@Patch.AllArguments Object[] arguments){
        // All public shadow routes converge on the five-argument implementation.
        if(arguments.length==5)Main.shadow(arguments[0],(Matrix4f)arguments[1]);
    }
}
