package divachar;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.fonts.AngelCodeFont;
import zombie.ui.UIElement;

@Patch(className="zombie.ui.UIElement",methodName="render")
public final class CatalogFontScope {
    @Patch.OnEnter
    public static void enter(@Patch.This UIElement element,@Patch.Local("divaPreviousFont") AngelCodeFont previous){
        previous=CatalogFont.enter(element);
    }
    @Patch.OnExit(onThrowable=Throwable.class)
    public static void exit(@Patch.Local("divaPreviousFont") AngelCodeFont previous){
        CatalogFont.exit(previous);
    }
}
