package divachar;
import me.zed_0xff.zombie_buddy.Patch;
import divachar.Main;

@Patch(className="viewpoint.models.ModelCapture",methodName="capture")
public final class Capture {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0)Object frame,@Patch.Argument(2)Object character,@Patch.Local("divaFirst")int first){first=Main.beforeCapture(frame,character);}
    @Patch.OnExit
    public static void exit(@Patch.AllArguments Object[] args,@Patch.Local("divaFirst")int first,@Patch.Return boolean result){Main.afterCapture(args,first,result);}
}
