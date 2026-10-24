package com.wenkrang.famara.render.scene;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AllActorCatalogTest {
    @Test void registryCoversEveryLivingTypeExportedFromActual262Api()throws Exception {
        var catalog=ActorModelCatalog.load();JsonObject raw;try(var in=getClass().getResourceAsStream("/render-actors/models-26.2.json")){raw=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in),StandardCharsets.UTF_8)).getAsJsonObject();}
        Set<String> expected=new TreeSet<>();for(var name:raw.getAsJsonArray("livingTypes"))expected.add(name.getAsString());assertEquals(93,expected.size());assertEquals(expected,VanillaActorRegistry.types());assertTrue(catalog.retainedBytes()<8L*1024*1024);
        for(String type:expected){var a=LivingActorTest.actor(type,0,0);assertNotNull(catalog.require(catalog.model(a)),type);assertNotNull(VanillaActorRegistry.texture(type),type);}
    }
    @Test void humanoidHeadAndCrouchingTransformsMoveRealGeometry()throws Exception {
        var catalog=ActorModelCatalog.load();var a=LivingActorTest.actor("PLAYER",0,0);var image=LivingActorTest.texture();var key=com.wenkrang.famara.render.asset.ResourceKey.parse("test:skin");var standing=new ActorModelBuilder(a,1,false);catalog.emit(standing,a,catalog.model(a),key,image,0xffffff,0);
        var crouching=new FrozenActor(a.id(),a.type(),a.position(),a.bodyYaw(),a.headYaw()+35,20,"CROUCHING",false,1,0,"",0xffffff,"",false,false,15,0);var bent=new ActorModelBuilder(crouching,1,false);catalog.emit(bent,crouching,catalog.model(crouching),key,image,0xffffff,0);
        assertEquals(standing.faceCount(),bent.faceCount());assertTrue(standing.faceCount()>=72);assertNotEquals(standing.build().model(),bent.build().model());assertTrue(bent.build().model().max().y()<standing.build().model().max().y());
    }
    @Test void equipmentAndSpeciesSnapshotsCannotBeMutatedAfterCapture(){
        var slots=new HashMap<String,FrozenItem>();slots.put("MAINHAND",FrozenItem.vanilla("minecraft:diamond_sword"));var equipment=new FrozenEquipment(slots,false);slots.clear();assertEquals("minecraft:diamond_sword",equipment.get("MAINHAND").material());assertThrows(UnsupportedOperationException.class,()->equipment.slots().clear());
        var traits=new HashMap<String,String>();traits.put("variant","snow");var appearance=new ActorAppearance(traits,Map.of());traits.put("variant","red");assertEquals("snow",appearance.value("variant",""));assertThrows(IllegalArgumentException.class,()->new FrozenEquipment(Map.of("UNKNOWN",FrozenItem.vanilla("minecraft:stone")),false));
    }
}
