package com.wenkrang.famara.render.scene;
import java.util.*;
public record FrozenEquipment(Map<String,FrozenItem> slots,boolean leftHanded) {
    private static final Set<String> ALLOWED=Set.of("HEAD","CHEST","LEGS","FEET","MAINHAND","OFFHAND","BODY","SADDLE");
    public static final FrozenEquipment EMPTY=new FrozenEquipment(Map.of(),false);
    public FrozenEquipment{slots=Map.copyOf(slots);if(!ALLOWED.containsAll(slots.keySet()))throw new IllegalArgumentException("Unknown equipment slot");}
    public FrozenItem get(String slot){return slots.get(slot);}
}
