#version 430 core

in vec3 worldPosition;
in vec3 worldNormal;
in vec4 worldTangent;
in vec4 vertexColour;
in vec2 texCoord;

layout(location=0) out vec4 target0;
layout(location=1) out vec4 surfaceNormal;
layout(location=2) out vec4 surfaceLight;

layout(binding=0) uniform sampler2D ps_t0;
layout(binding=1) uniform sampler2D ps_t1;
layout(binding=2) uniform sampler2D ps_t2;
layout(binding=3) uniform sampler2D ps_t3;
layout(binding=5) uniform samplerCube ps_t5;
layout(binding=9) uniform samplerCube ps_t9;
layout(binding=10) uniform samplerCube ps_t10;
layout(binding=11) uniform samplerCube ps_t11;
layout(binding=12) uniform samplerCube ps_t12;
layout(binding=13) uniform samplerCube ps_t13;
uniform sampler2D uHostLightMap,uHostScatterMap;
uniform vec2 uHostLightSize;
uniform mat4 uInverseHead;
uniform vec3 uEye,uSun,uSkinAlbedoScale,uSourceKey;
uniform vec4 uDiffuse,uSpecular,uEmission,uEngineLight;
uniform float uShininess,uCharacterGain,uCorneaGain,uFaceFillStrength;
uniform int uMaps,uFamily,uAlpha,uAnisotropy;
uniform bool uAlphaTexture,uAlphaMaterial,uShadowPass,uLightProxy,uHostLighting,uHostScattering,uScatterPass,uFaceMaterial;

bool mapped(int binding) {
    return (uMaps & (1 << binding)) != 0;
}

mat3 lightFrame(vec3 direction) {
    vec3 axis=normalize(direction);
    vec3 up=abs(axis.y)<.999?vec3(0,1,0):vec3(0,0,1);
    vec3 across=normalize(cross(up,axis));
    return mat3(across,cross(axis,across),axis);
}

vec3 probeDirection(vec3 direction) {
    return lightFrame(uSourceKey)*transpose(lightFrame(uSun))*direction;
}

vec3 specularProbe(vec3 direction,float visibility) {
    vec3 point=probeDirection(direction);
    vec3 narrow=textureLod(ps_t10,point,0.0).rgb;
    vec3 broad=textureLod(ps_t11,point,1.0).rgb;
    vec3 narrowShade=textureLod(ps_t12,point,0.0).rgb;
    vec3 broadShade=textureLod(ps_t13,point,1.0).rgb;
    float gloss=clamp((uShininess-16.0)/112.0,0.0,1.0);
    return mix(mix(broadShade,narrowShade,gloss),mix(broad,narrow,gloss),visibility);
}

vec3 shadingNormal(vec3 n,vec3 t) {
    if(!mapped(2)) return n;
    vec2 xy=texture(ps_t2,texCoord).rg*2.0-1.0;
    vec3 b=normalize(cross(n,t))*worldTangent.w;
    // DIVA's Dot bump encoding describes slopes around the original surface normal.
    vec2 slope=1.5*xy+vec2(2.0*xy.y*xy.y*xy.y);
    return normalize(n+t*slope.x+b*slope.y);
}

void main() {
    vec4 colour=texture(ps_t0,texCoord);
    float alpha=(uAlphaTexture?colour.a:1.0)*(uAlphaMaterial?uDiffuse.a:1.0);
    if((uAlpha==1 && alpha<0.5) || (uAlpha==2 && alpha<0.005)) discard;
    if(uShadowPass || uLightProxy) {
        if(uAlpha==2 && alpha<0.5) discard;
        if(uLightProxy) {
            target0=vec4(1.0,1.0,1.0,31.0/255.0);
            surfaceNormal=vec4(normalize(worldNormal),uEngineLight.a>=1.0?1.0:0.0);
            surfaceLight=uEngineLight;
        }
        return;
    }

    bool skin=uFamily==1;
    bool hair=uFamily==2;
    bool eye=uFamily==3;
    vec3 n=normalize(worldNormal);
    vec3 projectedTangent=worldTangent.xyz-n*dot(n,worldTangent.xyz);
    vec3 t=length(projectedTangent)>1e-4?normalize(projectedTangent):normalize(cross(n,abs(n.y)<.9?vec3(0,1,0):vec3(1,0,0)));
    n=shadingNormal(n,t);
    vec3 b=normalize(cross(n,t))*worldTangent.w;
    vec3 strand=uAnisotropy==3 || (uAnisotropy==0 && mapped(1))?b:t;
    vec3 l=normalize(uSun);
    vec3 v=normalize(uEye-worldPosition);
    float direct=max(dot(n,l),0.0);
    vec2 screen=gl_FragCoord.xy/uHostLightSize;
    vec3 light=uHostLighting?max(texture(uHostLightMap,screen).rgb,vec3(0.0)):vec3(.42+.70*direct);
    if(uScatterPass) {
        target0=vec4(light,1.0);
        return;
    }
    // The source character shaders add a broad, view-facing light before the material response.
    float facing=pow(max(dot(normalize(worldNormal),v),0.0),.4);
    float keyFacing=clamp(.5+.5*dot(l,-v),0.0,1.0);
    vec3 fill=max(uEngineLight.rgb,vec3(0.0))*facing*(.15+.18*keyFacing);
    float peak=max(light.r,max(light.g,light.b));
    light/=1.0+.45*max(peak-.78,0.0);
    vec3 receivedLight=light;
    if(skin && uHostScattering) {
        vec4 scatter=texture(uHostScatterMap,screen);
        float coverage=smoothstep(.7,.98,scatter.a);
        vec3 transport=(scatter.rgb-light)*vec3(1.15,.85,.90);
        light=max(light+.6*coverage*transport,vec3(0.0));
    }
    if(hair) light*=.75+.25*pow(dot(strand,v),2.0);
    light+=fill;
    // EYEBALL shades iris pigmentation independently of the material's half-strength surface diffuse.
    // Match the source and the development shader's UNORM material math in both host routes.
    vec3 base=colour.rgb*(eye?vec3(1.0):uDiffuse.rgb)*vertexColour.rgb;
    if(skin) base*=uSkinAlbedoScale;
    if(hair && mapped(9)) {
        vec3 direction=probeDirection(n);
        float narrow=dot(textureLod(ps_t9,direction,0.0).rgb,vec3(.2126,.7152,.0722));
        float broad=dot(textureLod(ps_t9,direction,1.0).rgb,vec3(.2126,.7152,.0722));
        light*=mix(1.0,clamp((narrow+.05)/(broad+.05),.66,1.40),.65);
    }
    if(hair && mapped(1)) {
        float rim=pow(max(0.0,1.0-pow(dot(n,v),2.0)),8.0);
        light+=light*texture(ps_t1,texCoord).rgb*rim*1.7;
    }

    vec4 gloss=mapped(3)?texture(ps_t3,texCoord):vec4(0.0);
    vec3 h=normalize(l+v);
    float lobe=pow(max(dot(n,h),0.0),max(uShininess*.68,5.0));
    // Specular RGB and reflection alpha are authored weights, sampled directly.
    vec3 highlights=gloss.rgb*uSpecular.rgb*lobe*light*(1.15+direct);
    if(hair) {
        float alongLight=clamp(dot(strand,l),-.999,.999);
        float alongView=clamp(dot(strand,v),-.999,.999);
        float across=sqrt((1.0-alongLight*alongLight)*(1.0-alongView*alongView));
        float primary=pow(max(across-alongLight*alongView,0.0),max(uShininess,1.0));
        float secondary=pow(max(across+alongLight*alongView,0.0),max(uShininess,1.0));
        float front=clamp(.3+.7*dot(n,l),0.0,1.0);
        float back=clamp(.3-.7*dot(n,l),0.0,1.0);
        highlights=gloss.rgb*uSpecular.rgb*light*(3.6*front*primary+.75*back*secondary);
    }
    if(skin) highlights+=vec3(.028,.018,.013)*light*lobe;
    if(mapped(10) && mapped(11)) {
        float strength=dot(receivedLight,vec3(.2126,.7152,.0722));
        vec3 reflected=specularProbe(reflect(-v,n),clamp(strength,0.0,1.0))*strength;
        float grazing=pow(1.0-max(dot(n,v),0.0),5.0);
        if(uFamily==4 || uFamily==0) highlights=reflected*gloss.rgb*uSpecular.rgb*(.18+.59*grazing);
        if(skin) highlights=reflected*.015;
        if(eye) highlights+=reflected*uSpecular.a;
    }
    if(mapped(5)) {
        vec3 reflected=reflect(-v,n);
        if(eye) reflected=mat3(uInverseHead)*reflected;
        vec3 environment=texture(ps_t5,reflected).rgb;
        float fresnel=pow(1.0-max(dot(n,v),0.0),5.0);
        float strength=eye?(.14+.7*gloss.a)*uCorneaGain:
            gloss.a*uSpecular.a*(1.0+5.9*fresnel);
        highlights+=environment*strength*light;
    }
    if(eye) highlights+=vec3(1.0,.97,.94)*gloss.a*light*(.54*uCorneaGain);
    vec3 emitted=colour.rgb*max(uEmission.rgb,vec3(0.0));
    vec3 diffuseLight=light;
    if(uFamily==4 || uFamily==0) diffuseLight*=.96;
    if(skin) {
        float shade=1.0-smoothstep(.15,.67,dot(light,vec3(.2126,.7152,.0722)));
        diffuseLight*=mix(vec3(1.20,1.04,1.115),vec3(1.38,1.19,1.045),shade);
    }
    if(uFaceMaterial) diffuseLight+=uFaceFillStrength*sqrt(max(receivedLight,vec3(0.0)))*facing;
    vec3 shaded=base*diffuseLight+highlights+emitted;
    target0=vec4(shaded*uCharacterGain,uAlpha==2?alpha:1.0);
}
