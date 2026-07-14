package com.deleted.xapocalypse;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;

import java.util.Objects;

/** Registry-backed attribute references that avoid deprecated enum constants on newer 1.21 builds. */
final class AttributeResolver {

    static final Attribute MAX_HEALTH = require("generic.max_health");
    static final Attribute ATTACK_DAMAGE = require("generic.attack_damage");
    static final Attribute MOVEMENT_SPEED = require("generic.movement_speed");
    static final Attribute KNOCKBACK_RESISTANCE = require("generic.knockback_resistance");

    private AttributeResolver() {
    }

    private static Attribute require(String key) {
        return Objects.requireNonNull(
                Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key)),
                "Missing Minecraft attribute: " + key);
    }
}
