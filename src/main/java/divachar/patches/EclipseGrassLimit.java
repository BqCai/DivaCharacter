package divachar;

import java.util.Map;
import me.zed_0xff.zombie_buddy.Patch;

@Patch(className="viewpoint.render.IrisPipeline",methodName="create")
public final class EclipseGrassLimit {
    @Patch.OnEnter
    public static void enter(@Patch.Argument(0) Object pack,
                             @Patch.Argument(value=1,readOnly=false) Map<String,String> options){
        options=IrisEclipse.limitGrass(pack,options);
    }
}
