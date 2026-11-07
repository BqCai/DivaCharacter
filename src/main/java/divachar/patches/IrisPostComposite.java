package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.render.IrisFrame",methodName="screen")
public final class IrisPostComposite {
    @Patch.OnEnter
    public static void enter(@Patch.This Object irisFrame,@Patch.Argument(0) Object pipeline,@Patch.Argument(1) Object pass,
                             @Patch.Argument(3) Object frame,@Patch.Argument(4) boolean finalOutput){
        if(!finalOutput)Main.beforeIrisScreen(irisFrame,pipeline,pass,frame);
    }

    @Patch.OnExit
    public static void exit(@Patch.Argument(0) Object pipeline,@Patch.Argument(1) Object pass,
                            @Patch.Argument(3) Object frame,@Patch.Argument(4) boolean finalOutput){
        if(!finalOutput)Main.afterIrisScreen(pipeline,pass,frame);
    }
}
