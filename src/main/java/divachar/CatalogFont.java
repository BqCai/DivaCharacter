package divachar;

import java.io.FileNotFoundException;
import se.krka.kahlua.vm.KahluaTable;
import zombie.core.fonts.AngelCodeFont;
import zombie.ui.TextManager;
import zombie.ui.UIElement;
import zombie.ui.UIFont;

/** The game uses its locale's small font for both combo text and the native search editor. */
public final class CatalogFont {
    private static final int SMALL=UIFont.Small.ordinal();
    private static AngelCodeFont japanese,symbols;
    private static boolean ready,unavailable;

    public static AngelCodeFont enter(UIElement element){
        var fonts=TextManager.instance.enumToFont;
        AngelCodeFont previous=fonts[SMALL];
        if(unavailable||previous==null||previous==japanese)return null;
        KahluaTable table=element.getTable();
        if(table==null)return null;
        // The stock combo popup is shared with other menus. Follow its current owner.
        if(!marked(table)&&!(table.rawget("parentCombo") instanceof KahluaTable combo&&marked(combo)))return null;
        if(!load())return null;
        fonts[SMALL]=japanese;
        return previous;
    }

    public static void exit(AngelCodeFont previous){
        if(previous!=null)TextManager.instance.enumToFont[SMALL]=previous;
    }

    private static boolean marked(KahluaTable table){
        return Boolean.TRUE.equals(table.rawget("divaCatalogFont"));
    }

    private static boolean load(){
        if(ready)return true;
        try{
            if(japanese==null){
                japanese=new AngelCodeFont("media/fonts/zomboidSmallJapanese.fnt",(String)null);
                symbols=new AngelCodeFont("media/fonts/divaCatalogSymbols.fnt",(String)null);
            }
            if(japanese.isLoading()||symbols.isLoading())return false;
            for(var glyph:symbols.chars)
                if(glyph!=null&&japanese.chars[glyph.id]==null)japanese.chars[glyph.id]=glyph;
            ready=true;
            return true;
        }catch(FileNotFoundException|RuntimeException error){
            unavailable=true;
            System.out.println("[DivaCharacter] Catalog font unavailable: "+error);
            return false;
        }
    }
}
