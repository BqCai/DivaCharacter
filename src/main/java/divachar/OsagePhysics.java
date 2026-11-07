package divachar;

import java.util.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import static java.lang.Math.*;

/** Fixed-step Osage chains in world space. The skin palette receives their solved bone transforms. */
final class OsagePhysics {
    private static final double STEP=1.0/60;
    private static final float STEP_TURN_LIMIT=(float)cos(toRadians(8));
    private static final float GRAVITY=8.5f;
    private enum Pass { INITIALIZE, STEP, DRAW }
    private record Particle(Strand strand,int index){}
    private record Shape(int type,Vector3f start,Vector3f axis,float radius){}
    private record BackGuide(Vector3f shoulder,Vector3f forward,float pitchRoom){}
    private record Frame(Matrix4f[] pose,Matrix4f world,Matrix4f inverse,Matrix4f[] deltas,
                         float scale,float floor,Vector3f wind,float thighLift,BackGuide back,Tuning.PhysicsCalibration tuning){}

    private final List<Strand> strands=new ArrayList<>();
    private final int[] drivers;
    private final int[] hips=new int[2],knees=new int[2];
    private final int chest,head;
    private final Matrix4f chestInverse,headInverse;
    private final Vector3f bindShoulder;
    final int[] affected;
    private final Vector3f origin=new Vector3f(),lastPosition=new Vector3f();
    private long lastTime;
    private float lastYaw;
    private double remainder;
    private boolean initialized;

    OsagePhysics(Assets assets){
        int pelvis=assets.names.indexOf("kl_kosi_etc_wj");
        chest=assets.names.indexOf("kl_mune_b_wj");
        head=assets.names.indexOf("j_kao_wj");
        chestInverse=chest<0?null:assets.inverseBind[chest];
        headInverse=head<0?null:assets.inverseBind[head];
        int left=assets.names.indexOf("j_kata_l_wj_cu"),right=assets.names.indexOf("j_kata_r_wj_cu");
        bindShoulder=chest<0||left<0||right<0?null:assets.bind[left].getTranslation(new Vector3f())
            .lerp(assets.bind[right].getTranslation(new Vector3f()),.5f);
        for(int i=0;i<2;i++){
            String side=i==0?"l":"r";
            hips[i]=assets.names.indexOf("j_momo_"+side+"_wj");
            knees[i]=assets.names.indexOf("j_sune_"+side+"_wj");
        }
        Map<Integer,Strand> owners=new HashMap<>();
        Map<String,Strand> named=new HashMap<>();
        Map<String,Particle> nodes=new HashMap<>();
        Map<OsageData.Chain,Float> splitPanels=splitPanels(assets,pelvis);
        for(var chain:assets.osage){
            int parent=assets.parent[chain.nodes().getFirst().bone()];
            var strand=new Strand(chain,parent,parent==pelvis&&pelvis>=0,
                collarChain(assets,chain,parent,chest),splitPanels.getOrDefault(chain,0f));
            named.putIfAbsent(chain.name(),strand);
            for(int i=0;i<chain.nodes().size();i++){
                var node=chain.nodes().get(i);
                if(node.bone()>=0)owners.put(node.bone(),strand);
                nodes.putIfAbsent(node.name(),new Particle(strand,i));
            }
            strands.add(strand);
        }
        drivers=new int[assets.names.size()];Arrays.fill(drivers,-1);
        for(int i=0;i<drivers.length;i++)
            for(int bone=i,depth=0;bone>=0&&depth<drivers.length;bone=assets.parent[bone],depth++)
                if(owners.containsKey(bone)){drivers[i]=bone;break;}
        for(Strand strand:strands){
            strand.parentDriver=strand.parent<0?-1:drivers[strand.parent];
            strand.dependency=owners.get(strand.parentDriver);
            strand.target=named.get(strand.chain.collisionTarget());
            for(int i=0;i<strand.points.length;i++)strand.siblings[i]=nodes.get(strand.chain.nodes().get(i).sibling());
            for(var link:strand.chain.links()){
                Strand other=named.get(link.target());
                if(other!=null&&link.from()>=0&&link.from()<strand.points.length&&link.to()>=0&&link.to()<other.points.length)
                    strand.crossLinks.get(link.from()).add(new Particle(other,link.to()));
            }
        }
        // A branch must use its parent's solved transform during the same physics step.
        List<Strand> remaining=new ArrayList<>(strands);
        strands.clear();
        while(!remaining.isEmpty()){
            int before=remaining.size();
            for(var iterator=remaining.iterator();iterator.hasNext();){
                Strand strand=iterator.next();
                if(strand.dependency==null||strands.contains(strand.dependency)){
                    strand.setBackGuide(bindShoulder,left,right);
                    strands.add(strand);iterator.remove();
                }
            }
            if(remaining.size()==before)throw new IllegalArgumentException("Cyclic Osage branch hierarchy");
        }
        affected=java.util.stream.IntStream.range(0,drivers.length).filter(i->drivers[i]>=0).toArray();
    }

    private static Map<OsageData.Chain,Float> splitPanels(Assets assets,int pelvis){
        if(pelvis<0)return Map.of();
        Vector3f waist=assets.bind[pelvis].getTranslation(new Vector3f());
        Map<OsageData.Chain,Vector3f> innerEdges=new LinkedHashMap<>();
        for(var chain:assets.osage){
            var first=chain.nodes().getFirst();
            if(!chain.role().equals("body")||assets.parent[first.bone()]!=pelvis||chain.nodes().size()<2||chain.links().isEmpty())continue;
            Vector3f root=first.bind().getTranslation(new Vector3f()).sub(waist);
            float side=signum(root.x);
            Vector3f direction=first.bind().transformDirection(first.end(),new Vector3f()).normalize();
            if(abs(root.x)>.0005f&&abs(root.x)<.026f&&root.y>-.19f&&root.y<.03f&&root.z>.035f&&
                direction.x*side>.19f&&direction.y<-.30f)innerEdges.put(chain,root);
        }
        Map<OsageData.Chain,Float> result=new HashMap<>();
        for(var left:innerEdges.entrySet()){
            Vector3f l=left.getValue();if(l.x<=0)continue;
            for(var right:innerEdges.entrySet()){
                Vector3f r=right.getValue();if(r.x>=0)continue;
                if(abs(l.x+r.x)>.009f||abs(l.y-r.y)>.013f||abs(l.z-r.z)>.013f)continue;
                if(left.getKey().links().stream().anyMatch(link->link.target().equals(right.getKey().name()))||
                    right.getKey().links().stream().anyMatch(link->link.target().equals(left.getKey().name())))continue;
                result.put(left.getKey(),1f);result.put(right.getKey(),-1f);
            }
        }
        return result;
    }

    private static boolean collarChain(Assets assets,OsageData.Chain chain,int parent,int chest){
        if(chest<0||parent!=chest||!chain.role().equals("body")||chain.nodes().size()<3||!chain.links().isEmpty()||
            chain.colliders().stream().noneMatch(c->c.type()==3&&c.first().bone()==chest))return false;
        var first=chain.nodes().getFirst();
        Vector3f offset=first.bind().getTranslation(new Vector3f()).sub(assets.bind[chest].getTranslation(new Vector3f()));
        return abs(offset.x)<.04f&&offset.y>.035f&&first.bind().transformDirection(first.end(),new Vector3f()).y<0;
    }

    synchronized void reset(){initialized=false;remainder=0;}

    synchronized void apply(Matrix4f[] pose,Matrix4f root,Vector3f position,float correction,long time,boolean paused,Vector3f wind){
        if(strands.isEmpty())return;
        double elapsed=(time-lastTime)*1e-9;
        lastTime=time;
        if(initialized&&position.distanceSquared(lastPosition)>1.5f)reset();
        if(!initialized)origin.set(position);
        lastPosition.set(position);
        // Viewpoint's render origin can move with the camera. Retain its basis and anchor to PZ world position.
        Vector3f translation=new Vector3f(position).sub(origin);
        float floor=translation.y;
        translation.add(root.transformDirection(new Vector3f(0,correction,0)));
        Matrix4f world=new Matrix4f(root).setTranslation(translation);
        float scale=world.transformDirection(new Vector3f(1,0,0)).length();
        float thighLift=0;
        for(int i=0;i<2;i++)if(hips[i]>=0&&knees[i]>=0){
            Vector3f axis=world.transformDirection(pose[knees[i]].getTranslation(new Vector3f())
                .sub(pose[hips[i]].getTranslation(new Vector3f())));
            if(axis.lengthSquared()>1e-8f)thighLift=max(thighLift,clamp((axis.normalize().y+.99f)/.065f,0,1));
        }
        thighLift=thighLift*thighLift*(3-2*thighLift);
        BackGuide back=null;
        if(bindShoulder!=null&&Tuning.hairShoulderGuard){
            Matrix4f torso=new Matrix4f(pose[chest]).mul(chestInverse);
            Matrix4f body=new Matrix4f(world).mul(torso);
            Vector3f forward=body.transformDirection(new Vector3f(0,0,1));
            forward.y=0;
            if(forward.lengthSquared()>1e-6f){
                float pitchRoom=0;
                if(headInverse!=null){
                    Vector3f headUp=new Matrix4f(pose[head]).mul(headInverse).transformDirection(new Vector3f(0,1,0)).normalize();
                    float pitch=clamp((.83f-headUp.dot(torso.transformDirection(new Vector3f(0,1,0)).normalize()))/.49f,0,1);
                    pitchRoom=.20f*scale*pitch*pitch*(3-2*pitch);
                }
                back=new BackGuide(body.transformPosition(bindShoulder,new Vector3f()),forward.normalize(),pitchRoom);
            }
        }
        Frame frame=new Frame(pose,world,new Matrix4f(world).invert(),new Matrix4f[pose.length],scale,floor,wind,thighLift,back,Tuning.physics);
        for(Strand strand:strands)strand.animatedAnchor=strand.chain.anchor()==null
            ?pose[strand.chain.nodes().getFirst().bone()]:strand.chain.anchor().transform(pose);
        float yaw=(float)atan2(world.m20(),world.m22());
        boolean initializing=!initialized;
        if(initializing){
            solve(frame,Pass.INITIALIZE,0,0);
            initialized=true;
        }else if(!paused&&elapsed>0&&elapsed<=.25){
            float turn=(float)IEEEremainder(yaw-lastYaw,2*PI);
            float snap=turn-clamp(turn,-.27f,.27f);
            remainder+=min(elapsed,5*STEP);
            while(remainder>=STEP){
                solve(frame,Pass.STEP,0,snap);
                snap=0;
                remainder-=STEP;
            }
        }
        lastYaw=yaw;
        float blend=paused?0:(float)(1-exp(-12*clamp(elapsed,0,5*STEP)));
        if(!initializing)solve(frame,Pass.DRAW,blend,0);
        for(int bone:affected){
            Matrix4f delta=frame.deltas[drivers[bone]];
            if(delta!=null)pose[bone]=new Matrix4f(delta).mul(pose[bone]);
        }
    }

    private void solve(Frame frame,Pass pass,float blend,float snap){
        Arrays.fill(frame.deltas,null);
        for(Strand strand:strands)strand.solve(frame,pass,blend,snap);
    }

    private static final class Strand {
        final OsageData.Chain chain;
        final int parent;
        final Vector3f[] points,previous,filtered,worldFiltered;
        final float[] restTilt,panelOutward;
        float[] backLimits;
        final Vector3f panelSide;
        final Particle[] siblings;
        final List<List<Particle>> crossLinks=new ArrayList<>();
        final Vector3f lastAnchor=new Vector3f();
        final Vector3f lastDirection=new Vector3f();
        int quietSteps;
        float worldBlend;
        Strand dependency,target;
        int parentDriver;
        Matrix4f animatedAnchor;
        final boolean garmentSpans,necktie,skirt,hair;

        Strand(OsageData.Chain chain,int parent,boolean waist,boolean collar,float side){
            this.chain=chain;this.parent=parent;
            garmentSpans=chain.collisionMode()!=0||!chain.links().isEmpty();
            necktie=collar;
            skirt=waist&&chain.role().equals("body")&&chain.collisionMode()!=0;
            hair=chain.role().equals("hair");
            int size=chain.nodes().size();
            points=vectors(size);previous=vectors(size);filtered=vectors(size);worldFiltered=vectors(size);
            restTilt=new float[size];
            panelOutward=new float[size];
            // The two inner jacket chains border an intentional opening above the skirt.
            panelSide=side==0?null:new Matrix4f(chain.nodes().getFirst().bind()).invert()
                .transformDirection(new Vector3f(side,0,0)).normalize();
            siblings=new Particle[size];
            for(int i=0;i<size;i++){
                crossLinks.add(new ArrayList<>());
                var node=chain.nodes().get(i);
                Vector3f direction=node.bind().transformDirection(node.end(),new Vector3f()).normalize();
                restTilt[i]=(float)toDegrees(acos(clamp(-direction.y,-1,1)));
                panelOutward[i]=max(0,direction.x*side);
            }
        }

        private static Vector3f[] vectors(int size){
            Vector3f[] result=new Vector3f[size];Arrays.setAll(result,i->new Vector3f());return result;
        }

        void setBackGuide(Vector3f shoulder,int left,int right){
            if(!hair||shoulder==null)return;
            var nodes=chain.nodes();
            Vector3f start=nodes.getFirst().bind().getTranslation(new Vector3f());
            Vector3f end=nodes.getLast().bind().transformPosition(nodes.getLast().end(),new Vector3f());
            boolean shoulderColliders=chain.colliders().stream().anyMatch(shape->shape.first().bone()==left||shape.first().bone()==right);
            boolean hangsBehind=shoulderColliders&&start.y>shoulder.y&&start.z<shoulder.z&&end.y<shoulder.y-.14f;
            if(!hangsBehind&&(dependency==null||dependency.backLimits==null))return;
            backLimits=new float[nodes.size()];
            for(int i=0;i<nodes.size();i++){
                var node=nodes.get(i);
                Vector3f endpoint=node.bind().transformPosition(node.end(),new Vector3f());
                backLimits[i]=endpoint.y<shoulder.y-.26f?Float.POSITIVE_INFINITY:max(-.025f,endpoint.z-shoulder.z+.015f);
            }
        }

        private void stayBehindShoulder(Frame frame,int index,Vector3f joint,Vector3f direction,float length){
            if(backLimits==null||frame.back==null)return;
            BackGuide guide=frame.back;
            Vector3f fromShoulder=new Vector3f(joint).sub(guide.shoulder);
            float height=fromShoulder.y+length*direction.y;
            // Leave room for the anchor above the shoulder and for each branch's original silhouette.
            float maxDepth=backLimits[index]*frame.scale+guide.pitchRoom+.55f*max(0,height);
            float forwardLimit=(maxDepth-fromShoulder.dot(guide.forward))/length;
            if(direction.dot(guide.forward)>forwardLimit)
                limitTurn(direction,new Vector3f(guide.forward).negate(),clamp(-forwardLimit,-1,.50f));
        }

        void solve(Frame frame,Pass pass,float blend,float snap){
            Matrix4f basis=new Matrix4f(animatedAnchor);
            if(parentDriver>=0&&frame.deltas[parentDriver]!=null)basis.mulLocal(frame.deltas[parentDriver]);
            basis.mulLocal(frame.world);
            Vector3f seamNormal=panelSide==null?null:frame.world.transformDirection(
                frame.pose[chain.nodes().getFirst().bone()].transformDirection(panelSide,new Vector3f())).normalize();
            Matrix4f inverseAnchor=new Matrix4f(basis).invert(), anchorBasis=new Matrix4f(basis);
            Vector3f anchor=basis.getTranslation(new Vector3f());
            Vector3f oldJoint=new Vector3f(lastAnchor);
            if(pass==Pass.STEP&&abs(snap)>1e-4f){
                for(int i=0;i<points.length;i++){
                    points[i].sub(lastAnchor).rotateY(snap).add(anchor);
                    previous[i].sub(lastAnchor).rotateY(snap).add(anchor);
                }
                oldJoint.set(anchor);
            }
            if(pass!=Pass.DRAW)lastAnchor.set(anchor);
            Vector3f joint=new Vector3f(anchor);
            float activeGarment=garmentSpans?frame.thighLift:0;
            boolean drawContacts=activeGarment>0||necktie;
            List<Shape> colliders=pass==Pass.STEP||pass==Pass.DRAW&&drawContacts?colliders(frame):List.of();
            Vector3f forceDirection=basis.transformDirection(new Vector3f(1,0,0)
                .rotateZ(chain.rotZ()).rotateY(chain.rotY())).normalize();
            if(pass==Pass.INITIALIZE){lastDirection.set(forceDirection);quietSteps=0;worldBlend=0;}
            if(pass==Pass.STEP){
                boolean quiet=anchor.distanceSquared(oldJoint)<2.5e-5f*frame.scale*frame.scale&&
                    forceDirection.dot(lastDirection)>.9990f&&abs(snap)<1e-4f;
                quietSteps=quiet?min(quietSteps+1,120):0;
                lastDirection.set(forceDirection);
            }
            if(pass==Pass.DRAW&&hair)worldBlend+=(settle(frame)-worldBlend)*blend;
            float deadbandCos=(float)cos(toRadians(frame.tuning.hairSettleDeadband()));
            for(int i=0;i<points.length;i++){
                var node=chain.nodes().get(i);
                if(i>0)basis.mul(node.link()).setTranslation(joint);
                Vector3f neutral=basis.transformDirection(node.end(),new Vector3f());
                float length=neutral.length();
                neutral.div(length);
                Vector3f direction;
                if(pass==Pass.INITIALIZE){
                    direction=new Vector3f(neutral);
                    points[i].set(joint).fma(length,direction);
                    previous[i].set(points[i]);inverseAnchor.transformDirection(direction,filtered[i]).normalize();
                    worldFiltered[i].set(direction);
                }else if(pass==Pass.STEP){
                    direction=step(i,frame,basis,joint,oldJoint,neutral,length,forceDirection,colliders);
                }else{
                    Vector3f from=i==0?lastAnchor:points[i-1];
                    direction=unit(new Vector3f(points[i]).sub(from),neutral);
                    Vector3f local=inverseAnchor.transformDirection(direction,new Vector3f()).normalize();
                    filtered[i].lerp(local,blend);
                    Vector3f anchored=unit(anchorBasis.transformDirection(filtered[i],new Vector3f()),neutral);
                    if(hair){
                        if(worldBlend<.01f)worldFiltered[i].set(anchored);
                        else if(worldFiltered[i].dot(direction)<deadbandCos)
                            worldFiltered[i].lerp(direction,blend*.5f).normalize();
                        direction=unit(anchored.lerp(worldFiltered[i],worldBlend),neutral);
                    }else direction=anchored;
                    if(drawContacts){
                        Vector3f end=new Vector3f(joint).fma(length,direction);
                        direction=contacts(i,frame,basis,joint,neutral,length,null,end,direction,colliders,true);
                    }
                    stayBehindShoulder(frame,i,joint,direction,length);
                }
                if(pass!=Pass.INITIALIZE&&seamNormal!=null&&frame.tuning.jacketOpening()>0){
                    limitTurn(direction,seamNormal,panelOutward[i]*frame.tuning.jacketOpening());
                    if(pass==Pass.STEP)points[i].set(joint).fma(length,direction);
                }
                Quaternionf swing=new Quaternionf().rotationTo(neutral,direction);
                basis.mulLocal(new Matrix4f().rotation(swing)).setTranslation(joint);
                if(node.bone()>=0)
                    frame.deltas[node.bone()]=new Matrix4f(frame.inverse).mul(basis).mul(new Matrix4f(frame.pose[node.bone()]).invert());
                joint.fma(length,direction);
            }
        }

        private Vector3f step(int i,Frame frame,Matrix4f basis,Vector3f joint,Vector3f oldJoint,
                              Vector3f neutral,float length,Vector3f force,List<Shape> colliders){
            var node=chain.nodes().get(i);
            Vector3f point=points[i];
            Vector3f oldDirection=unit(new Vector3f(point).sub(oldJoint),neutral);
            float settling=hair?settle(frame):0;
            float retention=chain.retention()*(1-frame.tuning.hairSettleDamping()*settling);
            Vector3f next=new Vector3f(point).fma(retention,new Vector3f(point).sub(previous[i]));
            float dtMass=(float)(STEP*STEP)*node.inverseMass();
            next.y-=GRAVITY*(necktie?1.25f:1)*node.weight()*frame.scale*dtMass;
            if(necktie){
                Vector3f side=frame.world.transformDirection(new Vector3f(1,0,0)).normalize();
                float lateral=new Vector3f(next).sub(lastAnchor).dot(side);
                next.fma(-frame.tuning.tieCenterStrength()*lateral*dtMass,side);
                // The collar and upper tie follow the chest. The loose lower links settle toward gravity.
                if(i>=2)next.lerp(new Vector3f(joint).add(0,-length,0),frame.tuning.tieDropStrength());
            }
            next.fma(85*node.force()*frame.scale*dtMass,force).fma(chain.wind()*frame.scale*dtMass,frame.wind);
            if(chain.stiffness()>0)next.lerp(new Vector3f(joint).fma(length,neutral),chain.stiffness()*.10f);
            Vector3f direction=constrain(next,joint,basis,node,neutral,length,oldDirection);
            Vector3f unconstrained=new Vector3f(next);
            direction=contacts(i,frame,basis,joint,neutral,length,oldDirection,next,direction,colliders,false);
            if(backLimits!=null&&frame.back!=null){
                stayBehindShoulder(frame,i,joint,direction,length);
                next.set(joint).fma(length,direction);
            }
            oldJoint.set(point);
            previous[i].set(point);point.set(next);
            if(next.distanceSquared(unconstrained)>1e-10f)previous[i].lerp(point,chain.friction());
            return direction;
        }

        private Vector3f contacts(int i,Frame frame,Matrix4f basis,Vector3f joint,Vector3f neutral,float length,
                                  Vector3f oldDirection,Vector3f next,Vector3f direction,List<Shape> colliders,boolean drawing){
            var node=chain.nodes().get(i);
            // Clearance also covers the skin between adjacent pleats, which follows blended bone weights.
            float activeGarment=garmentSpans?frame.thighLift:0;
            float maxTilt=restTilt[i]+frame.tuning.garmentLiftAllowance();
            float down=activeGarment>0&&restTilt[i]<65?(float)cos(toRadians(min(maxTilt,68f))):0;
            if(down>0)limitLift(next,joint,direction,length,down);
            float clearance=.008f*activeGarment;
            if(skirt)clearance+=frame.tuning.skirtClearance()*activeGarment;
            float radius=(node.radius()+clearance)*frame.scale;
            float limit=(necktie?.035f:.012f+.033f*activeGarment)*frame.scale;
            for(int pass=0;pass<(activeGarment>0||necktie?5:3);pass++){
                boolean pushed=false;
                if(next.y<frame.floor+radius){next.y=frame.floor+radius;pushed=true;}
                for(Shape shape:colliders){
                    pushed|=separate(next,shape,radius,limit);
                    if(activeGarment>0)
                        pushed|=separateSpan(next,joint,shape,radius,limit,true);
                    else if(chain.collisionMode()!=0&&(chain.collisionMode()!=1||i>0))
                        pushed|=separateSpan(next,joint,shape,radius,limit,activeGarment>0);
                    if(!drawing)for(Particle link:crossLinks.get(i))
                        pushed|=separateSpan(next,link.strand.points[link.index],shape,radius,limit,activeGarment>0);
                }
                if(!drawing&&target!=null&&target!=this)for(int j=0;j<target.points.length;j++){
                    float other=target.chain.nodes().get(j).radius()*frame.scale;
                    pushed|=separateBall(next,target.points[j],radius+other,.01f*frame.scale);
                }
                Particle sibling=siblings[i];
                if(!drawing&&sibling!=null&&node.siblingDistance()>0){
                    var other=sibling.strand.chain.nodes().get(sibling.index);
                    float bindDistance=node.bind().transformPosition(node.end(),new Vector3f())
                        .distance(other.bind().transformPosition(other.end(),new Vector3f()));
                    Vector3f peer=sibling.strand.points[sibling.index];
                    float maxDistance=max(bindDistance,node.siblingDistance())*frame.scale;
                    float distance=next.distance(peer);
                    if(distance>maxDistance){
                        next.lerp(peer,(distance-maxDistance)/distance);pushed=true;
                    }
                }
                if(!pushed)break;
                direction=constrain(next,joint,basis,node,neutral,length,oldDirection);
                if(down>0)limitLift(next,joint,direction,length,down);
            }
            return direction;
        }

        private float settle(Frame frame){
            return clamp((quietSteps-round(frame.tuning.hairSettleDelay()*60))/25f,0,1);
        }

        private List<Shape> colliders(Frame frame){
            List<Shape> result=new ArrayList<>(chain.colliders().size());
            for(var collider:chain.colliders()){
                var a=collider.first();var b=collider.second();
                Matrix4f first=colliderMatrix(frame,a);
                Vector3f start=first.transformPosition(a.offset(),new Vector3f());
                Vector3f axis=collider.type()==3?first.transformDirection(b.offset(),new Vector3f()).normalize()
                    :colliderMatrix(frame,b).transformPosition(b.offset(),new Vector3f()).sub(start);
                if(axis.isFinite())result.add(new Shape(collider.type(),start,axis,collider.radius()*frame.scale));
            }
            return result;
        }

        private static Matrix4f colliderMatrix(Frame frame,OsageData.Attachment attachment){
            Matrix4f joint=frame.pose[attachment.bone()];
            return attachment.translationOnly()?new Matrix4f(frame.world).translate(joint.getTranslation(new Vector3f()))
                :new Matrix4f(frame.world).mul(joint);
        }
    }

    private static Vector3f constrain(Vector3f point,Vector3f joint,Matrix4f basis,OsageData.Node node,Vector3f neutral,
                                      float length,Vector3f oldDirection){
        Vector3f direction=unit(new Vector3f(point).sub(joint),neutral);
        if(oldDirection!=null)limitTurn(direction,oldDirection,STEP_TURN_LIMIT);
        var h=node.hinge();
        if(h.limited()){
            Quaternionf bindAxis=new Quaternionf().rotationTo(new Vector3f(1,0,0),new Vector3f(node.end()).normalize());
            Vector3f local=new Matrix4f(basis).invert().transformDirection(direction,new Vector3f());
            bindAxis.transformInverse(local);
            float y=(float)atan2(-local.z,local.x),z=(float)atan2(local.y,hypot(local.x,local.z));
            float cy=clamp(y,h.minY(),h.maxY()),cz=clamp(z,h.minZ(),h.maxZ());
            if(cy!=y||cz!=z){
                local.set(1,0,0).rotateZ(cz).rotateY(cy);
                basis.transformDirection(bindAxis.transform(local),direction).normalize();
            }
        }
        point.set(joint).fma(length,direction);
        return direction;
    }

    private static Vector3f unit(Vector3f value,Vector3f fallback){
        return value.lengthSquared()>1e-10f?value.normalize():value.set(fallback);
    }

    private static void limitTurn(Vector3f direction,Vector3f axis,float limit){
        float dot=direction.dot(axis);
        if(dot<limit){
            direction.fma(-dot,axis);
            if(direction.lengthSquared()<1e-10f)direction.set(axis);
            else direction.normalize((float)sqrt(1-limit*limit)).fma(limit,axis);
        }
    }

    private static void limitLift(Vector3f end,Vector3f start,Vector3f direction,float length,float down){
        if(direction.y<=-down)return;
        float horizontal=(float)hypot(direction.x,direction.z);
        if(horizontal<1e-6f){direction.set(0,-1,0);}
        else{
            float scale=(float)sqrt(1-down*down)/horizontal;
            direction.set(direction.x*scale,-down,direction.z*scale);
        }
        end.set(start).fma(length,direction);
    }

    private static boolean separateSpan(Vector3f end,Vector3f start,Shape shape,float radius,float limit,boolean moving){
        boolean hit=false;
        int samples=moving?2:1;
        for(int i=1;i<=samples;i++){
            float t=i/(float)(samples+1);
            Vector3f sample=new Vector3f(start).lerp(end,t),before=new Vector3f(sample);
            if(separate(sample,shape,radius,limit)){end.add(sample.sub(before));hit=true;}
        }
        return hit;
    }

    private static boolean separate(Vector3f point,Shape shape,float radius,float limit){
        if(shape.type==3){
            float depth=radius-new Vector3f(point).sub(shape.start).dot(shape.axis);
            if(depth<=0)return false;
            point.fma(min(depth,limit),shape.axis);return true;
        }
        Vector3f closest=new Vector3f(shape.start);
        float span=shape.axis.lengthSquared();
        if(shape.type==4&&span>1e-10f)
            return separateEllipsoid(point,shape,radius+shape.radius,limit);
        if(span>1e-10f)closest.fma(clamp(new Vector3f(point).sub(closest).dot(shape.axis)/span,0,1),shape.axis);
        return separateBall(point,closest,radius+shape.radius,limit);
    }

    private static boolean separateEllipsoid(Vector3f point,Shape shape,float width,float limit){
        // The source endpoints are foci. The collision radius is the minor semiaxis.
        float length=shape.axis.length();
        Vector3f axis=new Vector3f(shape.axis).div(length);
        Vector3f center=new Vector3f(shape.start).fma(.5f,shape.axis);
        Vector3f radial=new Vector3f(point).sub(center);
        float axial=radial.dot(axis),majorSquared=length*length*.25f+width*width;
        float normalized=axial*axial/majorSquared+max(0,radial.lengthSquared()-axial*axial)/(width*width);
        if(!(normalized<1))return false;
        if(normalized<1e-8f){
            radial.set(0,1,0).fma(-axis.y,axis);
            if(radial.lengthSquared()<1e-8f)radial.set(1,0,0).fma(-axis.x,axis);
            point.add(radial.normalize(min(width,limit)));
        }else{
            float distance=radial.length()*((float)(1/sqrt(normalized))-1);
            point.add(radial.normalize(min(distance,limit)));
        }
        return true;
    }

    private static boolean separateBall(Vector3f point,Vector3f center,float radius,float limit){
        Vector3f away=new Vector3f(point).sub(center);
        float distance=away.length();
        if(distance>=radius||distance<1e-6f)return false;
        point.fma(min(radius-distance,limit)/distance,away);return true;
    }

}
