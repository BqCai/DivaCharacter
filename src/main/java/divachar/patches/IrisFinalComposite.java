package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.render.IrisFrame",methodName="stage")
public final class IrisFinalComposite {
    @Patch.OnExit
    public static void exit(@Patch.Argument(0) Object pipeline,@Patch.Argument(1) Object stage,@Patch.Argument(2) Object frame){
        Main.afterIrisStage(pipeline,stage,frame);
    }
}
