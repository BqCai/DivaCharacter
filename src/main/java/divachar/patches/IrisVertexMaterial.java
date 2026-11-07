package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.iris.IrisAssembler",methodName="vertex")
public final class IrisVertexMaterial {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly=false) String shader){shader=IrisMaterialBindings.vertex(shader);}
}
