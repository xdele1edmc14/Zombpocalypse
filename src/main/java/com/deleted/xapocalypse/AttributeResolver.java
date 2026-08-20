package com.deleted.xapocalypse;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;

/** Registry-backed attribute references that avoid deprecated enum constants across Paper versions. */
final class AttributeResolver {

    static final Attribute MAX_HEALTH = require(
            "max_health", "generic.max_health", "GENERIC_MAX_HEALTH");
    static final Attribute ATTACK_DAMAGE = require(
            "attack_damage", "generic.attack_damage", "GENERIC_ATTACK_DAMAGE");
    static final Attribute MOVEMENT_SPEED = require(
            "movement_speed", "generic.movement_speed", "GENERIC_MOVEMENT_SPEED");
    static final Attribute KNOCKBACK_RESISTANCE = require(
            "knockback_resistance", "generic.knockback_resistance", "GENERIC_KNOCKBACK_RESISTANCE");

    private AttributeResolver() {
    }

    private static Attribute require(String modernKey, String legacyKey, String legacyField) {
        Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(modernKey));
        if (attribute != null) return attribute;

        attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(legacyKey));
        if (attribute != null) return attribute;

        // Resolve legacy enum fields reflectively as a final binary-compatibility fallback without
        // directly linking deprecated API constants.
        try {
            Object value = Attribute.class.getField(legacyField).get(null);
            if (value instanceof Attribute legacyAttribute) return legacyAttribute;
        } catch (ReflectiveOperationException ignored) {
            // The detailed error below lists every name that was attempted.
        }

        throw new IllegalStateException("Missing Minecraft attribute; tried minecraft:"
                + modernKey + ", minecraft:" + legacyKey + " and Attribute." + legacyField);
    }
}
