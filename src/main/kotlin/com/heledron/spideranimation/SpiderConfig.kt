package com.heledron.spideranimation

import net.minecraftforge.common.ForgeConfigSpec
import com.electronwill.nightconfig.core.file.CommentedFileConfig
import java.nio.file.Files
import java.nio.file.Path

object SpiderConfig {
    private val builder = ForgeConfigSpec.Builder()
    private val configVersion = builder
        .comment("Internal config-format version - do not edit.")
        .defineInRange("configVersion", 5, 1, 5)
    val firstSpawnMin = builder.defineInRange("spawnMinMinutes", 1.0, 0.05, 1440.0)
    val peacefulExitSpawnMinutes = builder.defineInRange("peacefulExitSpawnMinutes", 1.0, 0.05, 1440.0)
    val firstSpawnMax = builder.defineInRange("spawnMaxMinutes", 1.0, 0.05, 1440.0)
    val respawnAfterKill = builder.defineInRange("respawnAfterKillMinutes", 40.0, 0.05, 1440.0)
    val permadeath = builder.define("permadeath", false)
    val relocateDistance = builder.defineInRange("relocateDistanceBlocks", 192.0, 0.0, 4096.0)
    val spawnAngleAttempts = builder.defineInRange("spawnAngleAttempts", 24, 4, 1024)
    val spawnCloseFallbackDistance = builder.defineInRange("spawnCloseFallbackDistance", 6.0, 0.0, 128.0)
    val spawnMaxVerticalSearch = builder.defineInRange("spawnMaxVerticalSearch", 48, 4, 384)
    val spawnDistanceMin = builder.defineInRange("spawnDistanceMin", 30.0, 4.0, 128.0)
    val spawnDistanceMax = builder.defineInRange("spawnDistanceMax", 34.0, 4.0, 128.0)
    val chaseDistance = builder.defineInRange("chaseDistance", 64.0, 8.0, 256.0)
    val chaseSpeed = builder.defineInRange("chaseSpeedBlocksPerSecond", 8.0, 0.5, 40.0)
    val minSize = builder.defineInRange("minSize", 0.6, 0.1, 10.0)
    val maxSize = builder.defineInRange("maxSize", 15.0, 0.5, 50.0)
    val sizeNearDistance = builder.defineInRange("sizeNearDistance", 4.0, 0.0, 64.0)
    val sizeFarDistance = builder.defineInRange("sizeFarDistance", 32.0, 1.0, 128.0)
    val netheriteHealth = builder.defineInRange("netheriteMaxHealth", 350.0, 1.0, 1000000.0)
    val camoChance = builder.defineInRange("camoVariantChance", 0.25, 0.0, 1.0)
    val poisonChance = builder.defineInRange("poisonVariantChance", 0.2, 0.0, 1.0)
    val hunterChance = builder.defineInRange("hunterVariantChance", 0.15, 0.0, 1.0)
    val attackCooldown = builder.defineInRange("attackCooldownTicks", 20, 1, 400)
    val chaseExitMultiplier = builder.defineInRange("chaseExitDistanceMultiplier", 1.25, 1.0, 3.0)
    val alertReactionTicks = builder.defineInRange("alertReactionTicks", 10, 0, 200)
    val chasePathfinding = builder.define("chasePathfinding", true)
    val speedGrowthFactor = builder.defineInRange("speedGrowthFactor", 8.0, 1.0, 32.0)
    val legStepSpeed = builder.defineInRange("legStepSpeed", 1.1, 0.1, 5.0)
    val enableWandering = builder.define("enableWandering", true)
    val wanderSpeedFactor = builder.defineInRange("wanderSpeedFactor", 0.35, 0.05, 1.0)
    val wanderRadius = builder.defineInRange("wanderRadius", 24.0, 4.0, 128.0)
    val wanderMinIntervalSeconds = builder.defineInRange("wanderMinIntervalSeconds", 3.0, 0.5, 120.0)
    val wanderMaxIntervalSeconds = builder.defineInRange("wanderMaxIntervalSeconds", 9.0, 0.5, 300.0)
    val wanderPauseChance = builder.defineInRange("wanderPauseChance", 0.25, 0.0, 1.0)
    val groomingChance = builder.defineInRange("groomingChance", 0.03, 0.0, 1.0)
    val squeezeSize = builder.defineInRange("squeezeSize", 0.25, 0.1, 1.0)
    val growPercentPerTick = builder.defineInRange("growPercentPerTick", 12.0, 0.5, 100.0)
    val shrinkPercentPerTick = builder.defineInRange("shrinkPercentPerTick", 25.0, 0.5, 100.0)
    val riddenSize = builder.defineInRange("riddenSize", 2.0, 0.3, 20.0)
    val growInWater = builder.define("growInWater", true)
    val netheriteArmor = builder.defineInRange("netheriteArmor", 20.0, 0.0, 30.0)
    val netheriteArmorToughness = builder.defineInRange("netheriteArmorToughness", 12.0, 0.0, 20.0)
    val netheriteKnockbackResistance = builder.defineInRange("netheriteKnockbackResistance", 0.4, 0.0, 1.0)
    val netheriteLegs = builder.defineInRange("netheriteLegs", 8, 1, 16)
    val camoHealth = builder.defineInRange("camoMaxHealth", 600.0, 1.0, 1000000.0)
    val poisonHealth = builder.defineInRange("poisonMaxHealth", 500.0, 1.0, 1000000.0)
    val poisonSize = builder.defineInRange("poisonSize", 1.0, 0.3, 5.0)
    val hunterHealth = builder.defineInRange("hunterMaxHealth", 400.0, 1.0, 1000000.0)
    val hunterSize = builder.defineInRange("hunterSize", 1.1, 0.3, 5.0)
    val hunterSpeedMultiplier = builder.defineInRange("hunterSpeedMultiplier", 1.6, 1.0, 8.0)
    val hunterBlindnessRange = builder.defineInRange("hunterBlindnessRange", 16.0, 0.0, 128.0)
    val hunterBlindnessSeconds = builder.defineInRange("hunterBlindnessSeconds", 30.0, 1.0, 3600.0)
    val netheriteAttackDamageHearts = builder.defineInRange("netheriteAttackDamageHearts", 6.0, 0.0, 100.0)
    val camoAttackDamageHearts = builder.defineInRange("camoAttackDamageHearts", 5.0, 0.0, 100.0)
    val hunterAttackDamageHearts = builder.defineInRange("hunterAttackDamageHearts", 4.0, 0.0, 100.0)
    val poisonAttackDamageHearts = builder.defineInRange("poisonAttackDamageHearts", 3.0, 0.0, 100.0)
    val poisonEffectSeconds = builder.defineInRange("poisonEffectSeconds", 30.0, 0.0, 3600.0)
    val netheriteDropChance = builder.defineInRange("netheriteDropChance", 0.5, 0.0, 1.0)
    val enragedHealth = builder.defineInRange("enragedMaxHealth", 700.0, 1.0, 1000000.0)
    val enragedSpeedMultiplier = builder.defineInRange("enragedSpeedMultiplier", 1.5, 1.0, 8.0)
    val enragedAttackDamageHearts = builder.defineInRange("enragedAttackDamageHearts", 10.0, 0.0, 100.0)
    val variantStepSound = builder.define("variantStepSound", "minecraft:block.moss.step")
    val variantStepVolume = builder.defineInRange("variantStepVolume", 0.3, 0.0, 10.0)
    val variantLandSound = builder.define("variantLandSound", "minecraft:block.moss.fall")
    val variantLandVolume = builder.defineInRange("variantLandVolume", 1.0, 0.0, 10.0)
    val hunterLightBlindnessOnlyAtNight = builder.define("hostileOnlyAtNight", false)
    val onlyAtNight = hunterLightBlindnessOnlyAtNight
    val commandEntries: Map<String, ForgeConfigSpec.ConfigValue<*>> = linkedMapOf(
        "spawnMinMinutes" to firstSpawnMin,
        "peacefulExitSpawnMinutes" to peacefulExitSpawnMinutes,
        "spawnMaxMinutes" to firstSpawnMax,
        "respawnAfterKillMinutes" to respawnAfterKill,
        "permadeath" to permadeath,
        "relocateDistanceBlocks" to relocateDistance,
        "spawnAngleAttempts" to spawnAngleAttempts,
        "spawnCloseFallbackDistance" to spawnCloseFallbackDistance,
        "spawnMaxVerticalSearch" to spawnMaxVerticalSearch,
        "spawnDistanceMin" to spawnDistanceMin,
        "spawnDistanceMax" to spawnDistanceMax,
        "chaseDistance" to chaseDistance,
        "chaseSpeedBlocksPerSecond" to chaseSpeed,
        "minSize" to minSize,
        "maxSize" to maxSize,
        "sizeNearDistance" to sizeNearDistance,
        "sizeFarDistance" to sizeFarDistance,
        "netheriteMaxHealth" to netheriteHealth,
        "camoVariantChance" to camoChance,
        "poisonVariantChance" to poisonChance,
        "hunterVariantChance" to hunterChance,
        "attackCooldownTicks" to attackCooldown,
        "chaseExitDistanceMultiplier" to chaseExitMultiplier,
        "alertReactionTicks" to alertReactionTicks,
        "chasePathfinding" to chasePathfinding,
        "speedGrowthFactor" to speedGrowthFactor,
        "legStepSpeed" to legStepSpeed,
        "enableWandering" to enableWandering,
        "wanderSpeedFactor" to wanderSpeedFactor,
        "wanderRadius" to wanderRadius,
        "wanderMinIntervalSeconds" to wanderMinIntervalSeconds,
        "wanderMaxIntervalSeconds" to wanderMaxIntervalSeconds,
        "wanderPauseChance" to wanderPauseChance,
        "groomingChance" to groomingChance,
        "squeezeSize" to squeezeSize,
        "growPercentPerTick" to growPercentPerTick,
        "shrinkPercentPerTick" to shrinkPercentPerTick,
        "riddenSize" to riddenSize,
        "growInWater" to growInWater,
        "netheriteArmor" to netheriteArmor,
        "netheriteArmorToughness" to netheriteArmorToughness,
        "netheriteKnockbackResistance" to netheriteKnockbackResistance,
        "netheriteLegs" to netheriteLegs,
        "camoMaxHealth" to camoHealth,
        "poisonMaxHealth" to poisonHealth,
        "poisonSize" to poisonSize,
        "hunterMaxHealth" to hunterHealth,
        "hunterSize" to hunterSize,
        "hunterSpeedMultiplier" to hunterSpeedMultiplier,
        "hunterBlindnessRange" to hunterBlindnessRange,
        "hunterBlindnessSeconds" to hunterBlindnessSeconds,
        "netheriteAttackDamageHearts" to netheriteAttackDamageHearts,
        "camoAttackDamageHearts" to camoAttackDamageHearts,
        "hunterAttackDamageHearts" to hunterAttackDamageHearts,
        "poisonAttackDamageHearts" to poisonAttackDamageHearts,
        "poisonEffectSeconds" to poisonEffectSeconds,
        "netheriteDropChance" to netheriteDropChance,
        "enragedMaxHealth" to enragedHealth,
        "enragedSpeedMultiplier" to enragedSpeedMultiplier,
        "enragedAttackDamageHearts" to enragedAttackDamageHearts,
        "variantStepSound" to variantStepSound,
        "variantStepVolume" to variantStepVolume,
        "variantLandSound" to variantLandSound,
        "variantLandVolume" to variantLandVolume,
        "hostileOnlyAtNight" to hunterLightBlindnessOnlyAtNight
    )
    fun migrateConfigFile(configDir: Path) {
        val file = configDir.resolve("arachnomod-common.toml")
        if (!Files.exists(file)) return

        CommentedFileConfig.builder(file).preserveInsertionOrder().build().use { config ->
                config.load()
                val hasContent = config.get<Any>("spawnMinMinutes") != null
                val fileVersion = (config.get<Number>("configVersion")?.toInt())
                    ?: if (hasContent) 1 else 5

                fun migrateDefault(path: String, oldValue: Number, newValue: Number) {
                    val current = config.get<Any>(path)
                    val matches = when (oldValue) {
                        is Double -> (current as? Number)?.toDouble() == oldValue
                        is Int -> (current as? Number)?.toInt() == oldValue
                        else -> current == oldValue
                    }
                    if (matches) config.set<Any>(path, newValue)
                }

                if (fileVersion < 2) {
                    migrateDefault("spawnMinMinutes", 5.0, 1.0)
                    migrateDefault("spawnMaxMinutes", 30.0, 1.0)
                    migrateDefault("spawnAngleAttempts", 12, 24)
                }
                if (fileVersion < 3) migrateDefault("maxHealth", 1000.0, 600.0)
                if (fileVersion < 4) {
                    val oldHealth = (config.get<Number>("maxHealth"))?.toDouble()
                    if (oldHealth != null && oldHealth != 600.0) {
                        config.set<Any>("netheriteMaxHealth", oldHealth)
                        config.set<Any>("camoMaxHealth", oldHealth)
                    }
                    config.remove<Any>("maxHealth")
                }
                if (fileVersion < 5) {
                    val oldDamage = (config.get<Number>("attackDamageHearts"))?.toDouble()
                    if (oldDamage != null && oldDamage != 6.0) {
                        config.set<Any>("netheriteAttackDamageHearts", oldDamage)
                        config.set<Any>("camoAttackDamageHearts", oldDamage)
                        config.set<Any>("hunterAttackDamageHearts", oldDamage)
                    }
                    config.remove<Any>("attackDamageHearts")
                }
                config.set<Any>("configVersion", 5)
                config.save()
        }
    }

    val SPEC: ForgeConfigSpec = builder.build()
}
