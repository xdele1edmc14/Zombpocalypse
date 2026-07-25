package com.deleted.xapocalypse;

import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobListFilterTest {

    @Test
    void matchesEnumAndNamespacedMobNamesCaseInsensitively() {
        assertTrue(xApocalypseListener.isInMobList(EntityType.ZOMBIE, List.of("ZOMBIE")));
        assertTrue(xApocalypseListener.isInMobList(EntityType.ZOMBIE, List.of("zombie")));
        assertTrue(xApocalypseListener.isInMobList(EntityType.ZOMBIE, List.of(" minecraft:zombie ")));
        assertFalse(xApocalypseListener.isInMobList(EntityType.ZOMBIE, List.of("SKELETON")));
    }

    @Test
    void filtersNaturalAndChunkGenerationSpawnsOnly() {
        assertTrue(xApocalypseListener.isMobListSpawnReason(CreatureSpawnEvent.SpawnReason.NATURAL));
        assertTrue(xApocalypseListener.isMobListSpawnReason(CreatureSpawnEvent.SpawnReason.CHUNK_GEN));
        assertFalse(xApocalypseListener.isMobListSpawnReason(CreatureSpawnEvent.SpawnReason.CUSTOM));
        assertFalse(xApocalypseListener.isMobListSpawnReason(CreatureSpawnEvent.SpawnReason.SPAWNER));
        assertFalse(xApocalypseListener.isMobListSpawnReason(CreatureSpawnEvent.SpawnReason.COMMAND));
    }
}
