package divachar;

import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.render.ModelBatches",methodName="alike")
public final class IrisBatchBoundary {
    @Patch.OnExit
    public static void exit(@Patch.This Object batches,@Patch.Argument(0) int first,@Patch.Argument(1) int second,
                            @Patch.Return(readOnly=false) boolean same){
        if(same)same=IrisMaterialBindings.canBatch(batches,first,second);
    }
}
