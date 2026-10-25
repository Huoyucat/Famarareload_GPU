package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.kernel.Camera;
import com.wenkrang.famara.render.kernel.CaptureFrustum;
import com.wenkrang.famara.render.scene.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.*;

/** Chunk enumeration only discovers handles; all mutable living state is read on that entity's owner. */
public final class LivingEntityCapture implements ChunkCaptureService.Extras,AutoCloseable {
    @FunctionalInterface interface OwnerScheduler {void execute(LivingEntity entity,Runnable capture,Runnable retired);}
    private final ChunkCaptureService.Extras legacy;
    private final ActorAssets.Batch batch;
    private final OwnerScheduler scheduler;
    private final UUID world,photographer;
    private final CaptureFrustum frustum;
    private int frustumCulled;
    private final Set<UUID> seen=new HashSet<>();
    private final Set<String> unsupported=new TreeSet<>();
    private final List<CompletableFuture<FrozenActor>> captures=new ArrayList<>();
    private boolean sealed,closed,transferred,cancelled;
    private CompletionStage<List<ActorGeometry>> result;
    public LivingEntityCapture(Plugin plugin,ChunkCaptureService.Extras legacy,ActorAssets.Batch batch,UUID world,UUID photographer,Camera camera,double range){
        this(legacy,batch,world,photographer,camera,range,(entity,capture,retired)->{if(entity.getScheduler().run(plugin,ignored->capture.run(),retired)==null)retired.run();});
    }
    LivingEntityCapture(ChunkCaptureService.Extras legacy,ActorAssets.Batch batch,UUID world,UUID photographer,Camera camera,double range,OwnerScheduler scheduler){this.legacy=legacy;this.batch=batch;this.world=world;this.photographer=photographer;this.frustum=new CaptureFrustum(camera,range);this.scheduler=scheduler;}
    @Override public List<LegacyEntity> capture(Chunk chunk){
        List<LegacyEntity> furniture=legacy.capture(chunk);
        for(Entity handle:chunk.getEntities())if(handle instanceof LivingEntity living){
            CompletableFuture<FrozenActor> future;
            synchronized(this){if(closed || cancelled || sealed)throw new CancellationException("Actor capture closed");UUID id=handle.getUniqueId();if(id.equals(photographer))continue;String type=handle.getType().name();if(!VanillaMobModels.supports(type)){unsupported.add(type);continue;}if(!seen.add(id))continue;
                if(captures.size()>=512)throw new IllegalStateException("照片发现的生物/玩家超过512个采集限额");future=new CompletableFuture<>();captures.add(future);}
            try{scheduler.execute(living,()->{try{synchronized(this){if(closed || cancelled){future.complete(null);return;}}future.complete(freeze(living));}catch(Throwable error){future.completeExceptionally(error);}},()->future.complete(null));}catch(RuntimeException error){future.completeExceptionally(error);}
        }
        return furniture;
    }
    private FrozenActor freeze(LivingEntity entity){
        if(!entity.isValid() || entity.isDead() || entity.isInvisible() || !entity.getWorld().getUID().equals(world))return null;
        if(entity instanceof Player player && player.getGameMode()==GameMode.SPECTATOR)return null;
        // The chunk list is only a horizontal broad phase: whole chunks contain actors
        // behind the camera, below it and beside the actual photograph. Cull on the
        // entity owner BEFORE reading profiles, appearance or equipment.
        double size=scale(entity);
        var bounds=entity.getBoundingBox();
        double margin=modelMargin(entity.getType().name(),size);
        if(!frustum.mayIntersect(bounds.getMinX()-margin,bounds.getMinY()-margin,bounds.getMinZ()-margin,
                bounds.getMaxX()+margin,bounds.getMaxY()+margin,bounds.getMaxZ()+margin)){
            synchronized(this){frustumCulled++;}return null;
        }
        var at=entity.getLocation();Vec3 position=new Vec3(at.getX(),at.getY(),at.getZ());
        String skin="",variant="";boolean slim=false,sheared=false;int tint=0xffffff;
        if(entity instanceof Player player){var textures=player.getPlayerProfile().getTextures();var url=textures.getSkin();skin=url==null?"":url.toString();slim=textures.getSkinModel()==org.bukkit.profile.PlayerTextures.SkinModel.SLIM;}
        if(entity.getType().name().equals("MANNEQUIN")){var resolved=ActorAppearanceCapture.profileSkin(ActorAppearanceCapture.optional(entity,"getProfile"));skin=resolved.url();slim=resolved.slim();}
        Object shear=ActorAppearanceCapture.optional(entity,"isSheared");if(shear instanceof Boolean state)sheared=state;
        if(entity instanceof Sheep sheep){sheared=sheep.isSheared();tint=sheep.getColor().getColor().asRGB();}
        // Variant names are copied as strings, keeping Bukkit and registry objects out of worker data.
        variant=variant(entity);
        boolean baby=entity instanceof Ageable ageable && !ageable.isAdult();
        ActorAppearance appearance=ActorAppearanceCapture.capture(entity);FrozenEquipment equipment=EquipmentCapture.capture(entity,appearance);
        double swing=Math.min(.6,entity.getVelocity().length()*2);
        return new FrozenActor(entity.getUniqueId(),entity.getType().name(),position,entity.getBodyYaw(),at.getYaw(),at.getPitch(),entity.getPose().name(),baby,size,swing,variant,tint,skin,slim,sheared,at.getBlock().getLightFromSky(),at.getBlock().getLightFromBlocks(),appearance,equipment);
    }
    // Collision boxes omit held items, tails and wings. Keep a conservative model
    // margin so an actor crossing the frame edge is never rejected by its feet.
    static double modelMargin(String type,double scale){return scale*switch(type){case "ENDER_DRAGON"->16;case "GHAST","HAPPY_GHAST"->8;case "GIANT"->6;default->2;};}
    private static String variant(LivingEntity entity){
        Class<?> type=entity instanceof Cow?Cow.class:entity instanceof Pig?Pig.class:entity instanceof Chicken?Chicken.class:null;
        if(type==null)return "";
        try{Object value=type.getMethod("getVariant").invoke(entity);return ((org.bukkit.Keyed)value).getKey().getKey().toUpperCase(Locale.ROOT);}catch(NoSuchMethodException ignored){return "";}catch(ReflectiveOperationException error){throw new IllegalStateException("Actor variant API failed",error);}
    }
    private static double scale(LivingEntity entity){
        try{var attribute=(org.bukkit.attribute.Attribute)org.bukkit.attribute.Attribute.class.getField("SCALE").get(null);var value=entity.getAttribute(attribute);return value==null?1:value.getValue();}catch(NoSuchFieldException ignored){return 1;}catch(ReflectiveOperationException error){throw new IllegalStateException("Actor scale API failed",error);}
    }
    @Override public CompletionStage<List<ActorGeometry>> finishActors(){return finishActors(null);}
    @Override public synchronized CompletionStage<List<ActorGeometry>> finishActors(com.wenkrang.famara.render.kernel.ConservativeOcclusion visibility){if(closed || cancelled)throw new CancellationException("Actor capture closed");if(result!=null)return result;sealed=true;List<CompletableFuture<FrozenActor>> pending=List.copyOf(captures);result=CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenCompose(ignored->{synchronized(this){if(closed || cancelled)throw new CancellationException("Actor capture closed");}return batch.bake(pending.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList(),visibility);});return result;}
    @Override public synchronized AutoCloseable transferActorResources(){if(closed || transferred || result==null || !result.toCompletableFuture().isDone())throw new IllegalStateException("Actor resources not ready");transferred=true;return batch;}
    @Override public synchronized Map<String,String> actorMetadata(){
        var actors=result!=null && result.toCompletableFuture().isDone() && !result.toCompletableFuture().isCompletedExceptionally()?result.toCompletableFuture().join().stream().map(ActorGeometry::actor).toList():captures.stream().filter(f->f.isDone() && !f.isCompletedExceptionally()).map(CompletableFuture::join).filter(Objects::nonNull).toList();String types=String.join(",",actors.stream().map(FrozenActor::type).distinct().sorted().toList());int slots=actors.stream().mapToInt(a->a.equipment().slots().size()).sum();
        String custom=String.join(",",actors.stream().flatMap(a->a.equipment().slots().values().stream()).map(FrozenItem::itemModel).filter(k->!k.isEmpty() && !k.startsWith("minecraft:")).distinct().sorted().toList());
        String equipmentAssets=String.join(",",actors.stream().flatMap(a->a.equipment().slots().values().stream()).map(FrozenItem::equipmentAsset).filter(k->!k.isEmpty()).distinct().sorted().toList());
        return Map.ofEntries(Map.entry("unsupportedLivingTypes",bounded(String.join(",",unsupported))),Map.entry("capturedActorTypes",bounded(types)),Map.entry("capturedEquipmentSlots",Integer.toString(slots)),Map.entry("customItemModels",bounded(custom)),Map.entry("equipmentAssets",bounded(equipmentAssets)),Map.entry("actorModelVersion","26.2"),Map.entry("actorDiscoveredCount",Integer.toString(captures.size())),Map.entry("actorFrustumCulled",Integer.toString(frustumCulled)),Map.entry("actorCapturedCount",Integer.toString(actors.size())),Map.entry("actorOcclusionCulled",Integer.toString(batch.occlusionCulled())),Map.entry("actorOcclusionEarlyCulled",Integer.toString(batch.earlyCulled())));
    }
    private static String bounded(String value){return value.length()>480?value.substring(0,480)+"...":value;}
    @Override public synchronized void cancelActors(){if(!transferred){cancelled=true;captures.forEach(f->f.complete(null));batch.close();}}
    @Override public void close(){synchronized(this){if(closed)return;closed=true;captures.forEach(f->f.complete(null));captures.clear();seen.clear();if(!transferred)batch.close();}if(legacy instanceof AutoCloseable resource)try{resource.close();}catch(Exception error){throw new IllegalStateException(error);}}
}
