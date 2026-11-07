package divachar;

import java.nio.file.*;
import org.json.JSONObject;

/** Development calibration data can be edited without changing the approved Java jar. */
public final class Tuning {
    public static volatile float scale=.58f,gain=1f;
    public static volatile boolean sourcePose,scattering=true,hairShadow=true,hairPhysics=true,irisPbr,irisPost=true,irisHdr=true,irisFog=true;
    public static volatile float irisHdrGain=.4f;
    public static volatile boolean irisSceneLight=true;
    public static volatile boolean hairShoulderGuard=true;
    public static volatile int captureRevision;
    public static volatile int shaderRevision;
    public record MaterialCalibration(float corneaGain, float skinRed, float skinGreen, float skinBlue) {}
    public record PhysicsCalibration(float hairSettleDelay,float hairSettleDamping,float hairSettleDeadband,
                                     float skirtClearance,float garmentLiftAllowance,float jacketOpening,
                                     float tieDropStrength,float tieCenterStrength) {}
    public static volatile MaterialCalibration materials = new MaterialCalibration(1f, 1f, 1f, 1f);
    private static final PhysicsCalibration DEFAULT_PHYSICS=new PhysicsCalibration(.12f,.78f,.55f,0,8f,.95f,.07f,36f);
    public static volatile PhysicsCalibration physics=DEFAULT_PHYSICS;
    private static long checked,modified=-1;
    public static final Path FILE=locate();
    private static Path locate(){
        try{return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent().getParent().resolve("divachar-tuning.json");}
        catch(Exception e){return Path.of(System.getProperty("java.io.tmpdir"),"divachar-tuning.json");}
    }
    public static synchronized void poll(){
        long now=System.nanoTime();if(now-checked<500_000_000L)return;checked=now;
        try{
            if(!Files.exists(FILE))return;
            long stamp=Files.getLastModifiedTime(FILE).toMillis();if(stamp==modified)return;
            JSONObject values=new JSONObject(Files.readString(FILE));
            float newScale=values.optFloat("scale",scale),newGain=values.optFloat("gain",gain);
            float hdrGain=bounded(values,"irisHdrGain",irisHdrGain,.05f,2f);
            if(!Float.isFinite(newScale)||newScale<.1f||newScale>2f||!Float.isFinite(newGain)||newGain<0||newGain>8)throw new IllegalArgumentException("Invalid calibration range");
            var old = materials;
            var next = new MaterialCalibration(
                bounded(values, "corneaGain", old.corneaGain(), 0f, 4f),
                bounded(values, "skinRed", old.skinRed(), .5f, 1.5f),
                bounded(values, "skinGreen", old.skinGreen(), .5f, 1.5f),
                bounded(values, "skinBlue", old.skinBlue(), .5f, 1.5f));
            JSONObject parameters=values.optJSONObject("physics");
            if(values.has("physics")&&parameters==null)throw new IllegalArgumentException("physics must be an object");
            var nextPhysics=parameters==null?DEFAULT_PHYSICS:new PhysicsCalibration(
                bounded(parameters,"hairSettleDelay",DEFAULT_PHYSICS.hairSettleDelay(),0f,1.5f),
                bounded(parameters,"hairSettleDamping",DEFAULT_PHYSICS.hairSettleDamping(),0f,.95f),
                bounded(parameters,"hairSettleDeadband",DEFAULT_PHYSICS.hairSettleDeadband(),0f,4f),
                bounded(parameters,"skirtClearance",DEFAULT_PHYSICS.skirtClearance(),0f,.06f),
                bounded(parameters,"garmentLiftAllowance",DEFAULT_PHYSICS.garmentLiftAllowance(),5f,60f),
                bounded(parameters,"jacketOpening",DEFAULT_PHYSICS.jacketOpening(),0f,1f),
                bounded(parameters,"tieDropStrength",DEFAULT_PHYSICS.tieDropStrength(),0f,.2f),
                bounded(parameters,"tieCenterStrength",DEFAULT_PHYSICS.tieCenterStrength(),0f,100f));
            scale=newScale;gain=newGain;sourcePose=values.optBoolean("sourcePose",sourcePose);modified=stamp;
            materials = next;
            if(!nextPhysics.equals(physics))System.out.println("[DivaCharacter] physics tuning updated: "+nextPhysics);
            physics=nextPhysics;
            scattering=values.optBoolean("scattering",scattering);
            hairShadow=values.optBoolean("hairShadow",hairShadow);
            hairPhysics=values.optBoolean("hairPhysics",hairPhysics);
            hairShoulderGuard=values.optBoolean("hairShoulderGuard",hairShoulderGuard);
            irisPbr=values.optBoolean("irisPbr",irisPbr);
            irisPost=values.optBoolean("irisPost",irisPost);
            irisHdr=values.optBoolean("irisHdr",irisHdr);
            irisFog=values.optBoolean("irisFog",irisFog);
            irisSceneLight=values.optBoolean("irisSceneLight",irisSceneLight);
            irisHdrGain=hdrGain;
            captureRevision=values.optInt("capture",captureRevision);
            shaderRevision = values.optInt("shaderReload", shaderRevision);
            System.out.println("[DivaCharacter] calibration: scale="+scale+" gain="+gain+" sourcePose="+sourcePose+" scattering="+scattering+" hairShadow="+hairShadow+" "+next);
        }catch(Exception e){System.out.println("[DivaCharacter] calibration file left unchanged: "+e.getMessage());}
    }
    private static float bounded(JSONObject values, String name, float previous, float min, float max) {
        float value = values.has(name) ? values.getFloat(name) : previous;
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }
}
