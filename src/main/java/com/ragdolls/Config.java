package com.ragdolls;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side configuration. All ragdoll logic runs on the client, so the spec is registered as a
 * {@code CLIENT} config. Values are read lazily and fall back to defaults if the config has not
 * finished loading yet (e.g. a death on the very first tick).
 */
public final class Config {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue LIFETIME_SECONDS;
    public static final ModConfigSpec.DoubleValue FADE_SECONDS;
    public static final ModConfigSpec.DoubleValue KNOCKBACK_MULTIPLIER;
    public static final ModConfigSpec.IntValue MAX_RAGDOLLS;
    public static final ModConfigSpec.DoubleValue MAX_RENDER_DISTANCE;
    public static final ModConfigSpec.BooleanValue BURN_IN_LAVA;
    public static final ModConfigSpec.BooleanValue FLOAT_IN_WATER;
    public static final ModConfigSpec.BooleanValue ENABLE_GORE;
    public static final ModConfigSpec.BooleanValue ENABLE_LIMBS;
    public static final ModConfigSpec.DoubleValue LIMB_FLOPPINESS;
    public static final ModConfigSpec.BooleanValue USE_ENTITY_COLLISION;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");
        LIFETIME_SECONDS = b
                .comment("How long a corpse stays before it fades out, in seconds.")
                .defineInRange("lifetimeSeconds", 12.0, 0.5, 600.0);
        FADE_SECONDS = b
                .comment("Duration of the smooth transparent fade-out before a corpse is removed, in seconds.")
                .defineInRange("fadeSeconds", 1.5, 0.1, 30.0);
        KNOCKBACK_MULTIPLIER = b
                .comment("Multiplier for how far corpses are thrown by the killing blow (1.0 = realistic).")
                .defineInRange("knockbackMultiplier", 1.0, 0.0, 5.0);
        b.pop();

        b.push("performance");
        MAX_RAGDOLLS = b
                .comment("Maximum number of corpses at once. When exceeded, the oldest fades out and",
                        "is removed. Lower this if you see frame drops during mass deaths (mob farms).")
                .defineInRange("maxActiveRagdolls", 12, 1, 1024);
        MAX_RENDER_DISTANCE = b
                .comment("Do not render corpses farther than this many blocks (0 = no limit).")
                .defineInRange("maxRenderDistance", 64.0, 0.0, 512.0);
        b.pop();

        b.push("interactions");
        BURN_IN_LAVA = b
                .comment("Corpses catch fire and quickly burn away in lava.")
                .define("burnInLava", true);
        FLOAT_IN_WATER = b
                .comment("Corpses float and drift with the current in water.")
                .define("floatInWater", true);
        ENABLE_GORE = b
                .comment("Let you hit corpses: they get knocked around; a hard blow tears off limbs,",
                        "and a strong hit to the chest gibs the corpse in a burst of blood.")
                .define("enableGore", true);
        b.pop();

        b.push("limbs");
        ENABLE_LIMBS = b
                .comment("Procedural floppy limbs: arms/legs/head dangle and lag like a rag doll.",
                        "Only affects entities using vanilla model parts (most mobs); others stay rigid.")
                .define("enableLimbs", true);
        LIMB_FLOPPINESS = b
                .comment("How loose the limbs are. 0 = stiff, 1 = natural, higher = floppier.")
                .defineInRange("limbFloppiness", 1.0, 0.0, 3.0);
        b.pop();

        b.push("compat");
        USE_ENTITY_COLLISION = b
                .comment("Collide corpses through a real (invisible, silent, inert) helper entity so",
                        "they rest on / are carried by physics blocks and contraptions (Create",
                        "Aeronautics / Sable, Valkyrien Skies) instead of falling through. On by",
                        "default; turn off for a slightly lighter, vanilla-only collision path.")
                .define("useEntityCollision", true);
        b.pop();

        SPEC = b.build();
    }

    private Config() {}

    public static int lifetimeTicks() {
        double seconds = SPEC.isLoaded() ? LIFETIME_SECONDS.get() : 12.0;
        return Math.max(1, (int) Math.round(seconds * 20.0));
    }

    public static int fadeTicks() {
        double seconds = SPEC.isLoaded() ? FADE_SECONDS.get() : 1.5;
        return Math.max(0, (int) Math.round(seconds * 20.0));
    }

    public static int maxRagdolls() {
        return SPEC.isLoaded() ? MAX_RAGDOLLS.get() : 15;
    }

    public static double maxRenderDistance() {
        return SPEC.isLoaded() ? MAX_RENDER_DISTANCE.get() : 64.0;
    }

    public static boolean burnInLava() {
        return !SPEC.isLoaded() || BURN_IN_LAVA.get();
    }

    public static boolean floatInWater() {
        return !SPEC.isLoaded() || FLOAT_IN_WATER.get();
    }

    public static boolean enableGore() {
        return !SPEC.isLoaded() || ENABLE_GORE.get();
    }

    public static double knockbackMultiplier() {
        return SPEC.isLoaded() ? KNOCKBACK_MULTIPLIER.get() : 1.0;
    }

    public static boolean enableLimbs() {
        return !SPEC.isLoaded() || ENABLE_LIMBS.get();
    }

    public static double limbFloppiness() {
        return SPEC.isLoaded() ? LIMB_FLOPPINESS.get() : 1.0;
    }

    public static boolean useEntityCollision() {
        return !SPEC.isLoaded() || USE_ENTITY_COLLISION.get();
    }
}
