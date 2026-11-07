package divachar;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.regex.Pattern;
import static org.lwjgl.opengl.GL43C.*;

/** Sample Eclipse's atmosphere and sunlight visibility at the isolated character depth. */
public final class EclipseFog {
    private static final String PROGRAM="minecraft-Eclipse-Viewpoint-V12 composite3";
    private static final String SWITCH="divaActorFog";
    private static final Pattern QUAD_SCALE=Pattern.compile(
        "gl_Position\\.xy = \\(gl_Position\\.xy\\*0\\.5\\+0\\.5\\)\\*\\(0\\.01\\+[0-9.]+\\)\\*2\\.0-1\\.0;");
    private static final String DEPTH="float z0 = texelFetch(depthtex0, texcoord,0).x;";
    private static final String COORD="ivec2 texcoord = ivec2(tc/texelSize);";
    private static final String DITHER="vec2 BN = fract(r2_sequence + bnoise);";
    private static final String WATER="if (eyeInWater){";
    private static final String AIR="GetVolumetricFog(viewPos0, WsunVec, BN, directLightColor, indirectLight_fog,";
    private static final String TRANSLUCENT="if(blendedAlpha > 0.0 || iswater){";
    private static final String DECLARATION="uniform bool "+SWITCH+";\n";
    private int fbo,texture,width,height;

    public static String shader(String name,int stage,String source){
        if(!PROGRAM.equals(name)||source==null)return source;
        if(stage==GL_VERTEX_SHADER&&QUAD_SCALE.matcher(source).find())
            return source.replace("uniform vec2 texelSize;",DECLARATION+"uniform vec2 texelSize;")
                .replaceAll(QUAD_SCALE.pattern(),"if (!divaActorFog) $0");
        if(stage!=GL_FRAGMENT_SHADER||!source.contains(DEPTH)||!source.contains(COORD)||
            !source.contains(DITHER)||!source.contains(TRANSLUCENT)||!source.contains(WATER)||!source.contains(AIR))return source;
        String edited=source.replace("uniform sampler2D depthtex0;",DECLARATION+"""
            uniform bool divaActorAtmosphere;
            uniform int divaShadowMode;
            uniform sampler2D depthtex0;
            """)
            .replace(COORD,"if (divaActorFog) tc = gl_FragCoord.xy * texelSize;\n"+COORD)
            .replace(DITHER,"vec2 BN = divaActorFog ? bnoise : fract(r2_sequence + bnoise);")
            .replace(DEPTH,DEPTH+"""

                if (divaActorFog) {
                    z0 = 1.0;
                    vec2 margin = max(vec2(0.0),0.5*(texelSize-1.0/vec2(textureSize(depthtex0,0))));
                    vec2 a = min(0.25*texelSize,margin), b = vec2(a.x,-a.y);
                    vec2 points[4] = vec2[4](tc-a,tc+a,tc-b,tc+b);
                    for (int i=0; i<4; ++i) {
                        ivec2 size = textureSize(depthtex0,0);
                        ivec2 pixel = clamp(ivec2(points[i]*vec2(size)),ivec2(0),size-1);
                        if (texelFetch(depthtex1,pixel,0).a > 0.0)
                            z0 = min(z0,texelFetch(depthtex0,pixel,0).r);
                    }
                    if (z0 >= 1.0) discard;
                }
                """)
            .replace(TRANSLUCENT,"if (!divaActorFog && (blendedAlpha > 0.0 || iswater)){");
        // Skip cloud rendering and its image writes. Only evaluate the atmosphere up to the actor.
        return edited.replace(WATER,"""
            if (divaActorFog) {
                VolumetricFog = vec4(0.0,0.0,0.0,1.0);
                if (divaActorAtmosphere && !eyeInWater) {
                    VolumetricFog = GetVolumetricFog(viewPos0,WsunVec,BN,directLightColor,indirectLight_fog,indirectLightColor_dynamic,length(viewPos0));
                    VolumetricFog.rgb = VolumetricFog.rgb * LPV_ILLUMINATION.a + LPV_ILLUMINATION.rgb;
                    VolumetricFog.a *= LPV_ILLUMINATION.a;
                    if (any(isnan(VolumetricFog)) || any(isinf(VolumetricFog))) VolumetricFog = vec4(0.0,0.0,0.0,1.0);
                    VolumetricFog.a = clamp(VolumetricFog.a,0.0,1.0);
                }
                float visibility = 1.0;
                if (divaShadowMode > 0) {
                    vec3 projected = toShadowSpaceProjected(viewPos0);
                    if (divaShadowMode == 2) projected.xy *= calcDistort(projected.xy);
                    vec3 shadowUV = projected * vec3(0.5,0.5,0.5/6.0) + 0.5;
                    if (all(greaterThan(shadowUV,vec3(0.001))) && all(lessThan(shadowUV,vec3(0.999)))) {
                        shadowUV.z -= 0.00025;
                        vec2 texel = 0.7 / vec2(textureSize(shadowtex0HW,0));
                        visibility = 0.25 * (
                            texture(shadowtex0HW,shadowUV+vec3( texel.x, texel.y,0.0)) +
                            texture(shadowtex0HW,shadowUV+vec3(-texel.x, texel.y,0.0)) +
                            texture(shadowtex0HW,shadowUV+vec3( texel.x,-texel.y,0.0)) +
                            texture(shadowtex0HW,shadowUV+vec3(-texel.x,-texel.y,0.0)));
                    }
                }
                vec3 sky = max(averageSkyCol_CloudsSSBO / 900.0,vec3(0.0));
                gl_FragData[1] = vec4(sky,visibility);
            } else
            """+WATER);
    }

    boolean supported(Object program)throws ReflectiveOperationException{
        Object gl=Interop.get(program,"gl");
        String source=(String)Interop.get(gl,"source");
        return QUAD_SCALE.matcher(source).find()&&source.contains(DEPTH)&&source.contains(AIR)&&
            glGetUniformLocation(Interop.integer(gl,"id"),SWITCH)>=0;
    }

    record Inputs(int fog,int lighting){
        static final Inputs EMPTY=new Inputs(0,0);
    }
    private int lighting;

    Inputs draw(Object program,int quad,IrisLegacy.Layer layer,int w,int h,boolean atmosphere,int shadowMode)throws ReflectiveOperationException{
        Map<?,?> units=(Map<?,?>)Interop.get(program,"units");
        if(!(units.get("depthtex0") instanceof Integer depthUnit)||depthUnit<=0||
            !(units.get("depthtex1") instanceof Integer coverageUnit)||coverageUnit<=0)return Inputs.EMPTY;
        int programId=Interop.integer(Interop.get(program,"gl"),"id");
        int location=glGetUniformLocation(programId,SWITCH);
        if(location<0)return Inputs.EMPTY;
        try(GlState ignored=new GlState()){
            glActiveTexture(GL_TEXTURE0);
            create(w,h);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);
            glViewport(0,0,w,h);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glColorMask(true,true,true,true);
            glClearBufferfv(GL_COLOR,0,new float[]{0,0,0,1});
            glClearBufferfv(GL_COLOR,1,new float[]{-1,-1,-1,1});
            int oldDepth=replace(depthUnit,layer.depth());
            int oldCoverage=replace(coverageUnit,layer.colour());
            try{
                glUseProgram(programId);
                glUniform1i(location,1);
                glUniform1i(glGetUniformLocation(programId,"divaActorAtmosphere"),atmosphere?1:0);
                glUniform1i(glGetUniformLocation(programId,"divaShadowMode"),shadowMode);
                glBindVertexArray(quad);
                glDrawArrays(GL_TRIANGLE_STRIP,0,4);
            }finally{
                glUniform1i(location,0);
                replace(depthUnit,oldDepth);
                replace(coverageUnit,oldCoverage);
            }
            return new Inputs(atmosphere?texture:0,lighting);
        }
    }

    private static int replace(int unit,int texture){
        glActiveTexture(GL_TEXTURE0+unit);
        int previous=glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D,texture);
        return previous;
    }

    private void create(int w,int h){
        if(fbo==0){fbo=glGenFramebuffers();texture=glGenTextures();lighting=glGenTextures();}
        if(w==width&&h==height)return;
        allocate(texture,w,h);
        allocate(lighting,w,h);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT1,GL_TEXTURE_2D,lighting,0);
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1});
        if(glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Incomplete Eclipse character fog target");
        width=w;height=h;
    }

    private static void allocate(int texture,int w,int h){
        glBindTexture(GL_TEXTURE_2D,texture);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA16F,w,h,0,GL_RGBA,GL_FLOAT,(ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    }
}
