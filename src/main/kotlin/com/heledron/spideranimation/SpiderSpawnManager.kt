package com.heledron.spideranimation

import com.heledron.spideranimation.entity.SpiderMob
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Difficulty\nimport net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object SpiderSpawnManager {
    private const val DATA_NAME = "arachnomod_hunt"

    fun tick(server: MinecraftServer) {
        val overworld = server.overworld()
        val data = HuntData.get(overworld)
        if (overworld.difficulty == Difficulty.PEACEFUL) {\n            if (!data.wasPeaceful) { data.wasPeaceful = true; data.setDirty() }\n            return\n        }\n        if (data.wasPeaceful) {\n            data.wasPeaceful = false\n            data.spiderId = null\n            data.remainingTicks = (SpiderConfig.peacefulExitSpawnMinutes.get() * 1200.0).toInt()\n            data.setDirty()\n            return\n        }\n        val active = data.spiderId?.let { uuid ->
            server.allLevels.asSequence().mapNotNull { it.getEntity(uuid) as? SpiderMob }.firstOrNull { it.isAlive }
        }
        if (active != null) return
        if (data.spiderId != null) {
            data.spiderId = null
            if (data.everSpawned && !SpiderConfig.permadeath.get()) {
                data.remainingTicks = (SpiderConfig.respawnAfterKill.get() * 1200.0).toInt()
            }
            data.setDirty()
        }
        if (!data.initialized) {\n            val min = SpiderConfig.firstSpawnMin.get()\n            val max = maxOf(min, SpiderConfig.firstSpawnMax.get())\n            data.remainingTicks = (Random.nextDouble(min, max) * 1200.0).toInt()\n            data.initialized = true\n            data.setDirty()\n        }\n        if (data.remainingTicks > 0) {
            data.remainingTicks--
            if (data.remainingTicks % 20 == 0) data.setDirty()
            return
        }
        if (data.everSpawned && SpiderConfig.permadeath.get()) return
        val player = overworld.players().filter { it.isAlive }.randomOrNull() ?: return
        val minDistance = SpiderConfig.spawnDistanceMin.get()
        val maxDistance = maxOf(minDistance, SpiderConfig.spawnDistanceMax.get())
        repeat(SpiderConfig.spawnAngleAttempts.get()) {
            val angle = Random.nextDouble(0.0, Math.PI * 2.0)
            val distance = Random.nextDouble(minDistance, maxDistance)
            val x = player.x + cos(angle) * distance
            val z = player.z + sin(angle) * distance
            val y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt())
            val pos = BlockPos(x.toInt(), y, z.toInt())
            if (!overworld.getBlockState(pos).isAir || !overworld.getFluidState(pos).isEmpty ||
                !overworld.getBlockState(pos.below()).blocksMotion()
            ) return@repeat
            val spider = ModEntities.SPIDER.get().create(overworld) ?: return@repeat
            spider.finalizeSpawn(overworld, overworld.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null, null)
            spider.moveTo(x, y.toDouble(), z, Random.nextFloat() * 360f, 0f)
            spider.naturalEncounter = true
            spider.chooseVariant()
            if (overworld.addFreshEntity(spider)) {
                data.spiderId = spider.uuid
                data.everSpawned = true
                data.setDirty()
                return
            }
        }
        if (!data.everSpawned) data.remainingTicks = 20
        data.setDirty()
    }

    fun killed(server: MinecraftServer) {
        val data = HuntData.get(server.overworld())
        data.spiderId = null
        data.everSpawned = true
        data.remainingTicks = if (SpiderConfig.permadeath.get()) 0 else (SpiderConfig.respawnAfterKill.get() * 1200.0).toInt()
        data.setDirty()
    }

    private class HuntData : SavedData() {
        var spiderId: java.util.UUID? = null
        var everSpawned = false
        var remainingTicks = 0\n        var wasPeaceful = false\n        var initialized = false

        override fun save(tag: CompoundTag): CompoundTag {
            tag.putBoolean("everSpawned", everSpawned)
            tag.putInt("remainingTicks", remainingTicks)\n            tag.putBoolean("wasPeaceful", wasPeaceful)\n            tag.putBoolean("initialized", initialized)
            spiderId?.let { tag.putUUID("spiderId", it) }
            return tag
        }

        companion object {
            fun load(tag: CompoundTag) = HuntData().also {
                it.everSpawned = tag.getBoolean("everSpawned")
                it.remainingTicks = tag.getInt("remainingTicks")\n                it.wasPeaceful = tag.getBoolean("wasPeaceful")\n                it.initialized = tag.getBoolean("initialized")
                if (tag.hasUUID("spiderId")) it.spiderId = tag.getUUID("spiderId")
            }

            fun get(level: ServerLevel): HuntData =
                level.dataStorage.computeIfAbsent(::load, ::HuntData, DATA_NAME)
        }
    }
}
