package com.heledron.spideranimation

import com.heledron.spideranimation.entity.SpiderMob
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object SpiderSpawnManager {
    private const val DATA_NAME = "arachnomod_hunt"

    private fun rollFirstSpawnTicks(): Int {
        val min = SpiderConfig.firstSpawnMin.get()
        val max = maxOf(min, SpiderConfig.firstSpawnMax.get())
        return (Random.nextDouble(min, max) * 1200.0).toInt().coerceAtLeast(1)
    }

    private fun killRespawnTicks(): Int =
        (SpiderConfig.respawnAfterKill.get() * 1200.0).toInt().coerceAtLeast(1)

    fun onConfigSet(path: String) {
        val server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() ?: return
        val data = HuntData.get(server.overworld())
        val duration = when (data.scheduleKind) {
            "FIRST_SPAWN" -> if (path == "spawnMinMinutes" || path == "spawnMaxMinutes") rollFirstSpawnTicks() else return
            "KILL_COOLDOWN" -> if (path == "respawnAfterKillMinutes") killRespawnTicks() else return
            "PEACEFUL_EXIT" -> if (path == "peacefulExitSpawnMinutes") {
                (SpiderConfig.peacefulExitSpawnMinutes.get() * 1200.0).toInt().coerceAtLeast(1)
            } else return
            else -> return
        }
        data.remainingTicks = (duration - data.scheduleElapsed).coerceAtLeast(1)
        data.setDirty()
    }

    fun tick(server: MinecraftServer) {
        val overworld = server.overworld()
        val data = HuntData.get(overworld)
        if (overworld.difficulty == Difficulty.PEACEFUL) {
            if (!data.wasPeaceful) { data.wasPeaceful = true; data.setDirty() }
            return
        }
        if (data.wasPeaceful) {
            data.wasPeaceful = false
            val survivingSpider = data.spiderId?.let { uuid ->
                server.allLevels.asSequence().mapNotNull { it.getEntity(uuid) as? SpiderMob }.firstOrNull { it.isAlive }
            }
            if (survivingSpider != null) {
                data.setDirty()
                return
            }
            data.spiderId = null
            data.remainingTicks = (SpiderConfig.peacefulExitSpawnMinutes.get() * 1200.0).toInt().coerceAtLeast(1)
            data.scheduleKind = "PEACEFUL_EXIT"
            data.scheduleElapsed = 0
            data.setDirty()
            return
        }
        val active = data.spiderId?.let { uuid ->
            server.allLevels.asSequence().mapNotNull { it.getEntity(uuid) as? SpiderMob }.firstOrNull { it.isAlive }
        }
        if (active != null) {
            val players = server.playerList.players.filter { it.isAlive }
            val activeLevel = active.level() as? ServerLevel
            if (activeLevel != null && players.isNotEmpty()) {
                val localPlayers = activeLevel.players().filter { it.isAlive }
                if (localPlayers.isEmpty()) {
                    data.abandonedTicks++
                    if (data.abandonedTicks >= 100) {
                        data.abandonedTicks = 0
                        if (relocate(active, data, players.randomOrNull()!!)) return
                    }
                } else {
                    data.abandonedTicks = 0
                    val limit = SpiderConfig.relocateDistance.get()
                    if (limit > 0.0 && localPlayers.minOf { it.distanceToSqr(active) } > limit * limit) {
                        data.strandedTicks++
                        if (data.strandedTicks >= 200) {
                            data.strandedTicks = 0
                            if (relocate(active, data, players.randomOrNull()!!)) return
                        }
                    } else {
                        data.strandedTicks = 0
                    }
                }
            } else {
                data.abandonedTicks = 0
                data.strandedTicks = 0
            }
            return
        }
        // The stored entity can be temporarily absent because its chunk is unloaded.
        // Only SpiderMob.die clears this ID, so never spawn a duplicate from a missing lookup.
        if (data.spiderId != null) return
        if (server.playerList.players.none { it.isAlive }) return
        if (!data.initialized) {
            data.remainingTicks = rollFirstSpawnTicks()
            data.scheduleKind = "FIRST_SPAWN"
            data.scheduleElapsed = 0
            data.initialized = true
            data.setDirty()
        }
        if (data.remainingTicks > 0) {
            data.remainingTicks--
            data.scheduleElapsed++
            if (data.remainingTicks % 20 == 0) data.setDirty()
            return
        }
        if (data.everSpawned && SpiderConfig.permadeath.get()) return
        val player = server.playerList.players.filter { it.isAlive }.randomOrNull() ?: return
        val spider = spawnNear(player)
        if (spider != null) {
            data.spiderId = spider.uuid
            data.everSpawned = true
            data.remainingTicks = 0
            data.scheduleKind = "NONE"
            data.scheduleElapsed = 0
        } else {
            data.remainingTicks = 200
            data.scheduleKind = "RETRY"
            data.scheduleElapsed = 0
        }
        data.setDirty()
    }

    private fun relocate(oldSpider: SpiderMob, data: HuntData, player: ServerPlayer): Boolean {
        val replacement = spawnNear(player) ?: return false
        oldSpider.discard()
        data.spiderId = replacement.uuid
        data.abandonedTicks = 0
        data.strandedTicks = 0
        data.setDirty()
        return true
    }

    private fun spawnNear(player: ServerPlayer): SpiderMob? {
        val level = player.serverLevel()
        val minDistance = SpiderConfig.spawnDistanceMin.get()
        val maxDistance = maxOf(minDistance, SpiderConfig.spawnDistanceMax.get())
        val angleAttempts = SpiderConfig.spawnAngleAttempts.get()
        val chosenDistance = minDistance + Random.nextDouble() * (maxDistance - minDistance)
        val candidateRadii = mutableListOf(chosenDistance)
        var offset = 2.0
        while (chosenDistance + offset <= maxDistance || chosenDistance - offset >= minDistance) {
            if (chosenDistance + offset <= maxDistance) candidateRadii += chosenDistance + offset
            if (chosenDistance - offset >= minDistance) candidateRadii += chosenDistance - offset
            offset += 2.0
        }

        fun spawnAt(distance: Double): SpiderMob? {
            val phase = Random.nextDouble(0.0, Math.PI * 2.0)
            repeat(angleAttempts) { index ->
                val angle = phase + Math.PI * 2.0 * index / angleAttempts
                val candidateX = player.x + cos(angle) * distance
                val candidateZ = player.z + sin(angle) * distance
                val safeY = com.heledron.spideranimation.entity.SafeGroundFinder.groundYAt(level, candidateX, candidateZ, player.y)
                    ?: return@repeat
                val pos = BlockPos.containing(candidateX, safeY, candidateZ)
                val spider = ModEntities.SPIDER.get().create(level) ?: return@repeat
                spider.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null, null)
                spider.moveTo(candidateX, safeY, candidateZ, Random.nextFloat() * 360f, 0f)
                spider.naturalEncounter = true
                spider.chooseVariant()
                if (level.addFreshEntity(spider)) return spider
            }
            return null
        }

        for (distance in candidateRadii) spawnAt(distance)?.let { return it }
        var outerDistance = maxDistance + 2.0
        repeat(16) {
            spawnAt(outerDistance)?.let { return it }
            outerDistance += 2.0
        }

        val closeFallback = SpiderConfig.spawnCloseFallbackDistance.get()
        if (closeFallback > 0.0 && closeFallback < minDistance) {
            var fallbackDistance = minDistance - 2.0
            while (fallbackDistance >= closeFallback) {
                spawnAt(fallbackDistance)?.let { return it }
                fallbackDistance -= 2.0
            }
        }
        return null
    }
    fun killed(server: MinecraftServer) {
        val data = HuntData.get(server.overworld())
        data.spiderId = null
        data.everSpawned = true
        data.remainingTicks = if (SpiderConfig.permadeath.get()) 0 else killRespawnTicks()
        data.scheduleKind = if (SpiderConfig.permadeath.get()) "NONE" else "KILL_COOLDOWN"
        data.scheduleElapsed = 0
        data.setDirty()
    }

    private class HuntData : SavedData() {
        var spiderId: java.util.UUID? = null
        var everSpawned = false
        var remainingTicks = 0
        var scheduleKind = "NONE"
        var scheduleElapsed = 0
        var abandonedTicks = 0
        var strandedTicks = 0
        var wasPeaceful = false
        var initialized = false

        override fun save(tag: CompoundTag): CompoundTag {
            tag.putBoolean("everSpawned", everSpawned)
            tag.putInt("remainingTicks", remainingTicks)
            tag.putString("scheduleKind", scheduleKind)
            tag.putInt("scheduleElapsed", scheduleElapsed)
            tag.putInt("abandonedTicks", abandonedTicks)
            tag.putInt("strandedTicks", strandedTicks)
            tag.putBoolean("wasPeaceful", wasPeaceful)
            tag.putBoolean("initialized", initialized)
            spiderId?.let { tag.putUUID("spiderId", it) }
            return tag
        }

        companion object {
            fun load(tag: CompoundTag) = HuntData().also {
                it.everSpawned = tag.getBoolean("everSpawned")
                it.remainingTicks = tag.getInt("remainingTicks")
                it.scheduleKind = tag.getString("scheduleKind").ifEmpty { "NONE" }
                it.scheduleElapsed = tag.getInt("scheduleElapsed").coerceAtLeast(0)
                it.abandonedTicks = tag.getInt("abandonedTicks").coerceAtLeast(0)
                it.strandedTicks = tag.getInt("strandedTicks").coerceAtLeast(0)
                it.wasPeaceful = tag.getBoolean("wasPeaceful")
                it.initialized = tag.getBoolean("initialized")
                if (tag.hasUUID("spiderId")) it.spiderId = tag.getUUID("spiderId")
            }

            fun get(level: ServerLevel): HuntData =
                level.dataStorage.computeIfAbsent(::load, ::HuntData, DATA_NAME)
        }
    }
}
