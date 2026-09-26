package com.heledron.spideranimation

import net.minecraftforge.common.ForgeConfigSpec

object SpiderConfig {
    private val builder = ForgeConfigSpec.Builder()
    val firstSpawnMin = builder.defineInRange("spawnMinMinutes", 1.0, 0.05, 1440.0)
    val firstSpawnMax = builder.defineInRange("spawnMaxMinutes", 1.0, 0.05, 1440.0)
    val respawnAfterKill = builder.defineInRange("respawnAfterKillMinutes", 40.0, 0.05, 1440.0)
    val permadeath = builder.define("permadeath", false)
    val spawnDistanceMin = builder.defineInRange("spawnDistanceMin", 30.0, 4.0, 128.0)
    val spawnDistanceMax = builder.defineInRange("spawnDistanceMax", 34.0, 4.0, 128.0)
    val chaseDistance = builder.defineInRange("chaseDistance", 64.0, 8.0, 256.0)
    val chaseSpeed = builder.defineInRange("chaseSpeedBlocksPerSecond", 8.0, 0.5, 40.0)
    val minSize = builder.defineInRange("minSize", 0.6, 0.1, 10.0)
    val maxSize = builder.defineInRange("maxSize", 15.0, 0.5, 50.0)
    val sizeNearDistance = builder.defineInRange("sizeNearDistance", 4.0, 0.0, 64.0)
    val sizeFarDistance = builder.defineInRange("sizeFarDistance", 32.0, 1.0, 128.0)
    val netheriteHealth = builder.defineInRange("netheriteMaxHealth", 600.0, 1.0, 10000.0)
    val camoChance = builder.defineInRange("camoVariantChance", 0.25, 0.0, 1.0)
    val poisonChance = builder.defineInRange("poisonVariantChance", 0.2, 0.0, 1.0)
    val hunterChance = builder.defineInRange("hunterVariantChance", 0.15, 0.0, 1.0)
    val attackCooldown = builder.defineInRange("attackCooldownTicks", 30, 1, 1200)
    val onlyAtNight = builder.define("hostileOnlyAtNight", false)
    val SPEC: ForgeConfigSpec = builder.build()
}
