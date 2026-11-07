package divachar;

import java.lang.reflect.*;
import java.util.concurrent.*;

/** Private Viewpoint access is isolated here and fails closed on incompatible versions. */
public final class Interop {
    private static final ConcurrentMap<String,Field> fields=new ConcurrentHashMap<>();
    public static Object get(Object object,String name)throws ReflectiveOperationException{
        Class<?> type=object instanceof Class<?> c?c:object.getClass();
        String key=type.getName()+"#"+name;
        Field f=fields.get(key);
        if(f==null){
            Class<?> current=type;
            while(current!=null){try{f=current.getDeclaredField(name);break;}catch(NoSuchFieldException e){current=current.getSuperclass();}}
            if(f==null)throw new NoSuchFieldException(key);f.setAccessible(true);fields.put(key,f);
        }
        return f.get(object instanceof Class<?>?null:object);
    }
    public static float number(Object object,String name)throws ReflectiveOperationException{return ((Number)get(object,name)).floatValue();}
    public static int integer(Object object,String name)throws ReflectiveOperationException{return ((Number)get(object,name)).intValue();}
    public static void set(Object object,String name,Object value)throws ReflectiveOperationException{
        get(object,name);Class<?> type=object instanceof Class<?> c?c:object.getClass();
        fields.get(type.getName()+"#"+name).set(object instanceof Class<?>?null:object,value);
    }
}
