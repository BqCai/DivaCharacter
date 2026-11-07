package divachar;

import java.util.*;
import java.util.function.ToDoubleFunction;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.json.*;
import static java.lang.Math.*;

/** Evaluate the original expression channels that drive a secondary-motion chain's attachment. */
record OsageAnchor(int parent,Matrix4f pivot,Matrix4f offset,List<OsageAnchor.Channel> channels) {
    record Channel(int index,ToDoubleFunction<Matrix4f[]> value){}

    Matrix4f transform(Matrix4f[] pose){
        Vector3f shift=new Vector3f(),rotation=new Vector3f();
        for(Channel channel:channels){
            float value=(float)channel.value.applyAsDouble(pose);
            if(channel.index<3)shift.setComponent(channel.index,value);
            else rotation.setComponent(channel.index-3,(float)toRadians(value));
        }
        return new Matrix4f(pose[parent]).mul(pivot).translate(shift)
            .rotateZYX(rotation.z,rotation.y,rotation.x).mul(offset);
    }

    static OsageAnchor read(Assets assets,JSONObject data,Matrix4f root){
        if(data==null)return null;
        int parent=assets.names.indexOf(data.optString("ParentName"));
        if(parent<0)return null;
        Vector3f position=vector(data.getJSONObject("Position")),rot=vector(data.getJSONObject("Rotation"));
        Matrix4f pivot=new Matrix4f().translation(position).rotateZYX(rot.z,rot.y,rot.x);
        Matrix4f offset=new Matrix4f(assets.bind[parent]).mul(pivot).invert().mul(root);
        List<Channel> channels=new ArrayList<>();
        for(Object entry:data.getJSONArray("Expressions")){
            String expression=entry.toString().strip();
            if(expression.isEmpty()||expression.matches("= [678] n 1"))continue;
            try{channels.add(compile(assets,expression));}
            catch(IllegalArgumentException error){
                System.out.println("[DivaCharacter] Unsupported Osage anchor expression in "+data.optString("Name")+": "+error.getMessage());
            }
        }
        return channels.isEmpty()?null:new OsageAnchor(parent,pivot,offset,List.copyOf(channels));
    }

    private static Channel compile(Assets assets,String expression){
        String[] words=expression.split("\\s+");
        if(words.length<4||words.length%2!=0||!words[0].equals("="))throw new IllegalArgumentException(expression);
        int channel=Integer.parseInt(words[1]);
        if(channel<0||channel>5)throw new IllegalArgumentException(expression);
        Deque<ToDoubleFunction<Matrix4f[]>> values=new ArrayDeque<>();
        for(int i=2;i<words.length;i+=2){
            String token=words[i+1];
            switch(words[i]){
                case "n" -> {double number=Double.parseDouble(token);values.push(pose->number);}
                case "v" -> values.push(variable(assets,token));
                case "f" -> {
                    if(values.isEmpty())throw new IllegalArgumentException(expression);
                    var value=values.pop();
                    values.push(switch(token){
                        case "neg" -> pose->-value.applyAsDouble(pose);
                        case "cos" -> pose->cos(toRadians(value.applyAsDouble(pose)));
                        case "sin" -> pose->sin(toRadians(value.applyAsDouble(pose)));
                        case "abs" -> pose->abs(value.applyAsDouble(pose));
                        default -> throw new IllegalArgumentException(token);
                    });
                }
                case "g" -> {
                    if(values.size()<2)throw new IllegalArgumentException(expression);
                    var right=values.pop();var left=values.pop();
                    values.push(switch(token){
                        case "+" -> pose->left.applyAsDouble(pose)+right.applyAsDouble(pose);
                        case "-" -> pose->left.applyAsDouble(pose)-right.applyAsDouble(pose);
                        case "*" -> pose->left.applyAsDouble(pose)*right.applyAsDouble(pose);
                        case "min" -> pose->min(left.applyAsDouble(pose),right.applyAsDouble(pose));
                        case "max" -> pose->max(left.applyAsDouble(pose),right.applyAsDouble(pose));
                        default -> throw new IllegalArgumentException(token);
                    });
                }
                default -> throw new IllegalArgumentException(words[i]);
            }
        }
        if(values.size()!=1)throw new IllegalArgumentException(expression);
        return new Channel(channel,values.pop());
    }

    private static ToDoubleFunction<Matrix4f[]> variable(Assets assets,String token){
        int dot=token.indexOf('.');
        if(dot<0)throw new IllegalArgumentException(token);
        int channel=Integer.parseInt(token.substring(0,dot));
        String name=token.substring(dot+1);
        int source=assets.names.indexOf(name);
        if(source<0&&name.matches("n_momo_a_[lr]_wj_cd_ex")){
            // Some outfits omit this control. Reconstruct its axes from the opposite-side-independent
            // bundled bind reference and drive it with the matching animated thigh.
            int thigh=assets.names.indexOf("j_momo_"+name.charAt(9)+"_wj");
            int pelvis=assets.names.indexOf("kl_kosi_etc_wj");
            Matrix4f reference=referenceBind(name,assets.localMaterials?assets.referenceBones:Reference.bones);
            if(thigh>=0&&pelvis>=0&&reference!=null)
                return variable(assets,channel,thigh,pelvis,reference);
        }
        if(source<0)throw new IllegalArgumentException("missing bone "+name);
        return variable(assets,channel,source,assets.parent[source],assets.bind[source]);
    }

    private static ToDoubleFunction<Matrix4f[]> variable(Assets assets,int channel,int bone,int parent,Matrix4f reference){
        if(channel<0||channel>5)throw new IllegalArgumentException("invalid source channel "+channel);
        Matrix4f inverseBind=assets.inverseBind[bone],inverseReference=new Matrix4f(reference).invert();
        Matrix4f parentBind=parent<0?new Matrix4f():assets.bind[parent];
        return pose->{
            Matrix4f delta=new Matrix4f(inverseReference).mul(parentBind);
            if(parent>=0)delta.mul(new Matrix4f(pose[parent]).invert());
            delta.mul(pose[bone]).mul(inverseBind).mul(reference);
            if(channel<3)return delta.getTranslation(new Vector3f()).get(channel);
            return toDegrees(delta.getEulerAnglesZYX(new Vector3f()).get(channel-3));
        };
    }

    private static Matrix4f referenceBind(String name,JSONArray bones){
        for(Object item:bones){
            JSONObject bone=(JSONObject)item;
            if(!name.equals(bone.getString("name")))continue;
            JSONArray rows=bone.getJSONArray("inverse_bind");
            float[] matrix=new float[16];
            for(int i=0;i<matrix.length;i++)matrix[i]=rows.getJSONArray(i/4).getFloat(i%4);
            return new Matrix4f().set(matrix).invert();
        }
        return null;
    }

    private static final class Reference{
        static final JSONArray bones=read();
        private static JSONArray read(){
            try{return new JSONObject(Assets.string("manifest.json")).getJSONArray("bones");}
            catch(java.io.IOException error){return new JSONArray();}
        }
    }

    private static Vector3f vector(JSONObject value){
        return new Vector3f(value.getFloat("X"),value.getFloat("Y"),value.getFloat("Z"));
    }
}
