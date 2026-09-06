package com.deleted.xapocalypse;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.LlamaSpit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpecialZombieBehaviorTest {
    private xApocalypseUtils utils;

    @BeforeEach
    void setUp() throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getStringList(anyString())).thenReturn(List.of());
        when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(config.getLong(anyString(), anyLong())).thenAnswer(i -> i.getArgument(1));
        utils = new xApocalypseUtils(plugin);

        setField("minerBreakables", EnumSet.of(Material.OAK_PLANKS));
        setField("minerBreakDelayMs", 0L);
    }

    @Test
    void minerBreaksDiagonalObstacleRegardlessOfFractionalPosition() throws Exception {
        World world = mock(World.class);
        Zombie miner = minerAt(world, 0.1, 64, 0.1);
        LivingEntity target = targetAt(world, 10.1, 64, 10.1);
        Block obstacle = breakableBlock(world, 1, 64, 1);
        when(miner.getTarget()).thenReturn(target);
        stubAirEverywhereExcept(world, obstacle, 1, 64, 1);

        tick("tickMinerAI", miner);

        verify(obstacle).breakNaturally();
    }

    @Test
    void minerBreaksBlockSupportingNearbyElevatedTarget() throws Exception {
        World world = mock(World.class);
        Zombie miner = minerAt(world, 1.5, 64, 0.5);
        LivingEntity target = targetAt(world, 0.5, 67, 0.5);
        Block support = breakableBlock(world, 0, 66, 0);
        when(miner.getTarget()).thenReturn(target);
        stubAirEverywhereExcept(world, support, 0, 66, 0);

        tick("tickMinerAI", miner);

        verify(support).breakNaturally();
    }

    @Test
    void minerDoesNotRemotelyBreakSupportFarAboveIt() throws Exception {
        World world = mock(World.class);
        Zombie miner = minerAt(world, 1.5, 64, 0.5);
        LivingEntity target = targetAt(world, 0.5, 74, 0.5);
        Block support = breakableBlock(world, 0, 73, 0);
        when(miner.getTarget()).thenReturn(target);
        stubAirEverywhereExcept(world, support, 0, 73, 0);

        tick("tickMinerAI", miner);

        verify(support, never()).breakNaturally();
    }

    @Test
    void spitterFiresAfterChasingTargetInsideOldFourBlockDeadZone() throws Exception {
        World world = mock(World.class);
        Zombie spitter = mock(Zombie.class);
        LivingEntity target = targetAt(world, 3.9, 64, 0);
        LlamaSpit projectile = mock(LlamaSpit.class);
        PersistentDataContainer spitterData = mock(PersistentDataContainer.class);
        PersistentDataContainer projectileData = mock(PersistentDataContainer.class);

        when(spitter.getTarget()).thenReturn(target);
        when(spitter.getWorld()).thenReturn(world);
        when(spitter.getLocation()).thenAnswer(i -> new Location(world, 0, 64, 0));
        when(spitter.getEyeLocation()).thenAnswer(i -> new Location(world, 0, 65.62, 0));
        when(spitter.getPersistentDataContainer()).thenReturn(spitterData);
        when(spitter.hasLineOfSight(target)).thenReturn(true);
        when(spitter.launchProjectile(eq(LlamaSpit.class), any(Vector.class))).thenReturn(projectile);
        when(projectile.getPersistentDataContainer()).thenReturn(projectileData);

        tick("tickSpitterAI", spitter);

        verify(spitter).launchProjectile(eq(LlamaSpit.class), any(Vector.class));
    }

    @Test
    void spitterStillRetreatsWhenTargetIsTooCloseToFire() throws Exception {
        World world = mock(World.class);
        Zombie spitter = mock(Zombie.class);
        LivingEntity target = targetAt(world, 1.0, 64, 0);
        when(spitter.getTarget()).thenReturn(target);
        when(spitter.getWorld()).thenReturn(world);
        when(spitter.getLocation()).thenAnswer(i -> new Location(world, 0, 64, 0));
        when(spitter.getVelocity()).thenReturn(new Vector());
        when(spitter.hasLineOfSight(target)).thenReturn(true);

        tick("tickSpitterAI", spitter);

        verify(spitter).setVelocity(any(Vector.class));
    }

    @Test
    void acidHitDealsImmediateDamageInAdditionToPoison() throws Exception {
        LivingEntity victim = mock(LivingEntity.class);
        Zombie attacker = mock(Zombie.class);
        World world = mock(World.class);
        when(victim.getWorld()).thenReturn(world);
        when(victim.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        setField("spitterPoisonDurationTicks", 0);

        try {
            Method method = xApocalypseUtils.class.getDeclaredMethod("handleAcidHit", Entity.class, Entity.class);
            method.invoke(utils, victim, attacker);
        } catch (NoSuchMethodException missingAttributedHandler) {
            org.junit.jupiter.api.Assertions.fail("Acid hit handling must accept the attacking Spitter");
        } catch (InvocationTargetException exception) {
            if (!(exception.getCause() instanceof LinkageError)) throw exception;
        } catch (LinkageError ignored) {
            // Paper's visual-effect registries are unavailable without a running server.
        }

        verify(victim).damage(2.0, attacker);
    }

    private Zombie minerAt(World world, double x, double y, double z) {
        Zombie miner = mock(Zombie.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(miner.getWorld()).thenReturn(world);
        when(miner.getLocation()).thenAnswer(i -> new Location(world, x, y, z));
        when(miner.getEyeLocation()).thenAnswer(i -> new Location(world, x, y + 1.62, z));
        when(miner.getPersistentDataContainer()).thenReturn(data);
        return miner;
    }

    private LivingEntity targetAt(World world, double x, double y, double z) {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenAnswer(i -> new Location(world, x, y, z));
        when(target.getEyeLocation()).thenAnswer(i -> new Location(world, x, y + 1.62, z));
        return target;
    }

    private Block breakableBlock(World world, int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.OAK_PLANKS);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(new Location(world, x, y, z));
        when(block.getBlockData()).thenReturn(mock(BlockData.class));
        return block;
    }

    private void stubAirEverywhereExcept(World world, Block exception, int x, int y, int z) {
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(any(Location.class))).thenAnswer(i -> {
            Location location = i.getArgument(0);
            return location.getBlockX() == x && location.getBlockY() == y && location.getBlockZ() == z
                    ? exception : air;
        });
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(i ->
                i.getArgument(0, Integer.class) == x
                        && i.getArgument(1, Integer.class) == y
                        && i.getArgument(2, Integer.class) == z ? exception : air);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = xApocalypseUtils.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(utils, value);
    }

    private void tick(String name, Zombie zombie) throws Exception {
        Method method = xApocalypseUtils.class.getDeclaredMethod(name, Zombie.class);
        method.setAccessible(true);
        try {
            method.invoke(utils, zombie);
        } catch (InvocationTargetException exception) {
            // Paper's registry-backed Sound constants require a running server. The observable
            // block/projectile action happens first, which is the behavior these unit tests cover.
            if (!(exception.getCause() instanceof LinkageError)) throw exception;
        }
    }
}
