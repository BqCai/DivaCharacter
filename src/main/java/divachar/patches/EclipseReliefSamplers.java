package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.render.IrisProgram",methodName="checkUnits")
public final class EclipseReliefSamplers {
    @Patch.OnEnter
    public static void enter(@Patch.This Object program){IrisEclipse.bindRelief(program);}
}
