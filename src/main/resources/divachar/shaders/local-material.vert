#version 430 core

layout(location=0) in vec4 position;
layout(location=1) in vec4 normal;
layout(location=2) in vec4 colour;
layout(location=3) in vec4 uv;
layout(location=5) in vec4 tangent;
layout(location=6) in vec4 weight;
layout(location=7) in vec4 joint;

layout(std140, binding=3) uniform Skinning {
    vec4 rows[768];
};
uniform mat4 uProjection;

out vec3 worldPosition;
out vec3 worldNormal;
out vec4 worldTangent;
out vec4 vertexColour;
out vec2 texCoord;

vec3 transform(int row, vec4 value) {
    return vec3(dot(rows[row],value),dot(rows[row+1],value),dot(rows[row+2],value));
}

void main() {
    vec3 p=vec3(0),n=vec3(0),t=vec3(0);
    for(int i=0;i<4;i++) {
        if(weight[i]<=0.0 || joint[i]<0.0) continue;
        int row=int(joint[i]);
        p+=weight[i]*transform(row,vec4(position.xyz,1.0));
        n+=weight[i]*transform(row,vec4(normal.xyz,0.0));
        t+=weight[i]*transform(row,vec4(tangent.xyz,0.0));
    }
    worldPosition=p;
    worldNormal=n;
    worldTangent=vec4(t,tangent.w);
    vertexColour=colour;
    texCoord=uv.xy;
    gl_Position=uProjection*vec4(p,1.0);
}
