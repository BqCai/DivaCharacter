package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.platform.GlProgram",methodName="foreign")
public final class EclipseShaderSource {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) String name,@Patch.Argument(1) int stage,
                             @Patch.Argument(value=2,readOnly=false) String source){
        source=EclipseFog.shader(name,stage,source);
    }
}
