package com.heledron.spideranimation.entity

import com.heledron.spideranimation.ModItems
import com.heledron.spideranimation.SpiderConfig
import com.heledron.spideranimation.SpiderSpawnManager
import com.heledron.spideranimation.SpiderAdvancements
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.world.BossEvent
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.SpawnGroupData
import java.util.UUID
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.entity.Display.BlockDisplay
import net.minecraft.world.entity.EntityType.BLOCK_DISPLAY
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.HitResult
import com.mojang.math.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class SpiderMob(type: EntityType<out SpiderMob>, level: Level) : Monster(type, level) {
    enum class Variant { NETHERITE, CAMO, POISON, HUNTER }
    private enum class AiMode { WANDER, ALERT, CHASE }
    private data class LegLayout(val rootZ: Double, val side: Double, val restX: Double, val restZ: Double, val reach: Double)

    var variant: Variant = Variant.NETHERITE
        private set
    var tamed = false
        private set
    var enraged = false
        private set
    var naturalEncounter = false
    var personalOwner: UUID? = null
        private set
    var personalSize = 1.0
        private set
    private var attackTimer = 0
    private var aiMode = AiMode.WANDER
    private var alertTimer = 0
    private var phase = 0.0
    private var currentScale = 1.0
    private var wanderTicks = 0
    private var wanderAngle = 0.0
    private val parts = mutableListOf<BlockDisplay>()
    private val footPositions = mutableListOf<Vec3?>()
    private val footStepStarts = mutableListOf<Vec3?>()
    private val footStepTargets = mutableListOf<Vec3?>()
    private val footStepProgress = mutableListOf<Double>()
    private val bossEvent = ServerBossEvent(
        Component.literal("Netherite Octoarachnopod"),
        BossEvent.BossBarColor.PURPLE,
        BossEvent.BossBarOverlay.PROGRESS
    )

    init {
        bossEvent.isVisible = false
        setNoGravity(true)
        setInvisible(true)
    }

    override fun registerGoals() = Unit
    override fun removeWhenFarAway(distance: Double): Boolean = false

    override fun startSeenByPlayer(player: ServerPlayer) {
        super.startSeenByPlayer(player)
        bossEvent.addPlayer(player)
    }

    override fun stopSeenByPlayer(player: ServerPlayer) {
        super.stopSeenByPlayer(player)
        bossEvent.removePlayer(player)
    }

    override fun finalizeSpawn(
        level: ServerLevelAccessor,
        difficulty: DifficultyInstance,
        spawnType: MobSpawnType,
        spawnGroupData: SpawnGroupData?,
        tag: net.minecraft.nbt.CompoundTag?
    ): SpawnGroupData? {
        if (spawnType == MobSpawnType.SPAWN_EGG) chooseVariant()
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData, tag)
    }

    fun chooseVariant() {
        variant = when {
            random.nextDouble() < SpiderConfig.hunterChance.get() -> Variant.HUNTER
            random.nextDouble() < SpiderConfig.poisonChance.get() -> Variant.POISON
            random.nextDouble() < SpiderConfig.camoChance.get() -> Variant.CAMO
            else -> Variant.NETHERITE
        }
        applyVariantStats()
    }

    private fun applyVariantStats(healToMax: Boolean = true) {
        val variantHealth = when (variant) {
            Variant.NETHERITE -> SpiderConfig.netheriteHealth.get()
            Variant.CAMO -> SpiderConfig.camoHealth.get()
            Variant.POISON -> SpiderConfig.poisonHealth.get()
            Variant.HUNTER -> SpiderConfig.hunterHealth.get()
        }
        getAttribute(Attributes.MAX_HEALTH)?.baseValue = variantHealth
        getAttribute(Attributes.ARMOR)?.baseValue = if (variant == Variant.NETHERITE) SpiderConfig.netheriteArmor.get() else 0.0
        getAttribute(Attributes.ARMOR_TOUGHNESS)?.baseValue = if (variant == Variant.NETHERITE) SpiderConfig.netheriteArmorToughness.get() else 0.0
        getAttribute(Attributes.KNOCKBACK_RESISTANCE)?.baseValue = if (variant == Variant.NETHERITE) SpiderConfig.netheriteKnockbackResistance.get() else 0.0
        health = if (healToMax) maxHealth else min(health, maxHealth)
        if (variant == Variant.POISON) currentScale = SpiderConfig.poisonSize.get()
        if (variant == Variant.HUNTER) currentScale = SpiderConfig.hunterSize.get()
    }

    fun makePersonal(owner: UUID, size: Double) {
        personalOwner = owner
        tamed = true
        personalSize = size.coerceIn(0.3, 20.0)
        currentScale = personalSize
        setTarget(null)
    }

    fun resizePersonal(size: Double) {
        personalSize = size.coerceIn(0.3, 20.0)
        currentScale = personalSize
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (stack.item == net.minecraft.world.item.Items.NETHERITE_INGOT &&
            variant == Variant.NETHERITE && !enraged && level() is ServerLevel
        ) {
            if (!level().isClientSide) {
                enraged = true
                val attr = getAttribute(Attributes.MAX_HEALTH)
                val oldMax = attr?.baseValue ?: maxHealth.toDouble()
                val newMax = max(oldMax, SpiderConfig.enragedHealth.get())
                attr?.baseValue = newMax
                health = min(maxHealth, health + (newMax - oldMax).toFloat())
                bossEvent.isVisible = true
                bossEvent.name = Component.literal("Enraged Netherite Octoarachnopod")
                bossEvent.color = BossEvent.BossBarColor.RED
                bossEvent.isVisible = true
                if (player is ServerPlayer) SpiderAdvancements.grant(player, "enrage")
                if (!player.abilities.instabuild) stack.shrink(1)
            }
            return InteractionResult.sidedSuccess(level().isClientSide)
        }

        if (stack.item == ModItems.SPIDER_TAMER.get()) {
            if (!level().isClientSide && !tamed) {
                tamed = true
                setTarget(null)
                (level() as ServerLevel).sendParticles(
                    net.minecraft.core.particles.ParticleTypes.HEART, x, y + 1.5, z,
                    9, 1.2, 1.2, 1.2, 0.02
                )
            }
            return InteractionResult.sidedSuccess(level().isClientSide)
        }

        if (tamed && stack.isEmpty && passengers.isEmpty()) {
            if (!level().isClientSide) player.startRiding(this)
            return InteractionResult.sidedSuccess(level().isClientSide)
        }
        return super.mobInteract(player, hand)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putString("Variant", variant.name)
        tag.putBoolean("Tamed", tamed)
        tag.putBoolean("Enraged", enraged)
        tag.putBoolean("NaturalEncounter", naturalEncounter)
        tag.putDouble("PersonalSize", personalSize)
        personalOwner?.let { tag.putUUID("PersonalOwner", it) }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        variant = runCatching { Variant.valueOf(tag.getString("Variant")) }.getOrDefault(Variant.NETHERITE)
        tamed = tag.getBoolean("Tamed")
        enraged = tag.getBoolean("Enraged")
        naturalEncounter = tag.getBoolean("NaturalEncounter")
        personalSize = tag.getDouble("PersonalSize").takeIf { it > 0.0 } ?: 1.0
        personalOwner = if (tag.hasUUID("PersonalOwner")) tag.getUUID("PersonalOwner") else null
        applyVariantStats(healToMax = false)
        if (enraged && variant == Variant.NETHERITE) {
            getAttribute(Attributes.MAX_HEALTH)?.baseValue = SpiderConfig.enragedHealth.get()
            bossEvent.isVisible = true
        }
    }

    override fun tick() {
        super.tick()
        if (level().isClientSide) return
        val serverLevel = level() as? ServerLevel ?: return
        ensureModel(serverLevel)
        if (tickCount % 20 == 0) grantEncounterAdvancements(serverLevel)
        attackTimer = (attackTimer - 1).coerceAtLeast(0)

        val rider = firstPassenger as? Player
        if (tamed && rider != null) {
            navigation.stop()
            setYRot(rider.yRot)
            val forward = rider.zza
            val strafe = rider.xxa
            val yaw = Math.toRadians(rider.yRot.toDouble())
            val speed = if (rider.isSprinting) 0.42 else 0.24
            move(MoverType.SELF, Vec3((-sin(yaw) * forward + cos(yaw) * strafe) * speed, 0.0, (cos(yaw) * forward + sin(yaw) * strafe) * speed))
            currentScale += (SpiderConfig.riddenSize.get() - currentScale) * 0.2
            setTarget(null)
        } else if (tamed && personalOwner != null) {
            navigation.stop()
            val owner = serverLevel.getPlayerByUUID(personalOwner!!)
            if (owner != null && distanceTo(owner) > 2.0) {
                val dx = owner.x - x
                val dz = owner.z - z
                val direction = Vec3(dx, 0.0, dz).normalize()
                val speed = if (distanceTo(owner) > 8.0) 0.28 else 0.16
                setYRot(Math.toDegrees(atan2(-dx, dz)).toFloat())
                move(MoverType.SELF, direction.scale(speed))
            }
            currentScale += (personalSize - currentScale) * 0.2
            setTarget(null)
        } else if (!tamed) {
            hunt(serverLevel)
        }

        if (deltaMovement.horizontalDistance() > 0.015) phase += 0.45
        updateModel(serverLevel)
        bossEvent.progress = (health / maxHealth).coerceIn(0f, 1f)
        if (variant == Variant.HUNTER && SpiderConfig.hunterBlindnessRange.get() > 0 && tickCount % 20 == 0) {
            serverLevel.players().filter { it.distanceTo(this) <= SpiderConfig.hunterBlindnessRange.get() }.forEach {
                it.addEffect(MobEffectInstance(MobEffects.BLINDNESS, (SpiderConfig.hunterBlindnessSeconds.get() * 20).toInt(), 0, true, false))
            }
        }
    }

    private fun hunt(serverLevel: ServerLevel) {
        val night = serverLevel.dayTime % 24000L in 13000L..23000L
        val target = serverLevel.players()
            .filter { it.isAlive && (!SpiderConfig.onlyAtNight.get() || night) }
            .minByOrNull { it.distanceToSqr(this) }
        val distance = if (target == null) Double.MAX_VALUE else distanceTo(target).toDouble()
        val detectionDistance = SpiderConfig.chaseDistance.get()
        val allowedDistance = if (aiMode == AiMode.WANDER) {
            detectionDistance
        } else {
            detectionDistance * SpiderConfig.chaseExitMultiplier.get()
        }
        if (target == null || distance > allowedDistance) {
            navigation.stop()
            setTarget(null)
            aiMode = AiMode.WANDER
            alertTimer = 0
            val idleScale = when (variant) {
                Variant.POISON -> SpiderConfig.poisonSize.get()
                Variant.HUNTER -> SpiderConfig.hunterSize.get()
                else -> 1.0
            }
            currentScale += (idleScale - currentScale) * 0.03
            wander(serverLevel)
            return
        }

        setTarget(target)
        if (aiMode == AiMode.WANDER) {
            aiMode = AiMode.ALERT
            alertTimer = SpiderConfig.alertReactionTicks.get()
        }
        if (aiMode == AiMode.ALERT) {
            navigation.stop()
            if (alertTimer > 0) {
                alertTimer--
                return
            }
            aiMode = AiMode.CHASE
        }
        val near = SpiderConfig.sizeNearDistance.get()
        val far = max(near + 0.01, SpiderConfig.sizeFarDistance.get())
        val desiredScale = when (variant) {
            Variant.POISON -> SpiderConfig.poisonSize.get()
            Variant.HUNTER -> SpiderConfig.hunterSize.get()
            else -> SpiderConfig.minSize.get() +
                (SpiderConfig.maxSize.get() - SpiderConfig.minSize.get()) *
                ((distance - near) / (far - near)).coerceIn(0.0, 1.0)
        }
        val adjustment = if (desiredScale > currentScale) SpiderConfig.growPercentPerTick.get() / 100.0 else SpiderConfig.shrinkPercentPerTick.get() / 100.0
        currentScale += (desiredScale - currentScale) * adjustment

        val dx = target.x - x
        val dz = target.z - z
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        setYRot(yaw)
        yRotO = yaw
        if (variant == Variant.HUNTER && distance <= 6.0 && target.hasLineOfSight(this)) {
            val toSpider = Vec3(x - target.x, y + 1.0 - target.eyeY, z - target.z).normalize()
            if (target.lookAngle.dot(toSpider) > 0.7) {
                navigation.stop()
                return
            }
        }

        val scaleSpeed = 1.0 + ((currentScale - 1.0) / max(1.0, SpiderConfig.maxSize.get() - 1.0)) * (SpiderConfig.speedGrowthFactor.get() - 1.0)
        val variantSpeed = if (variant == Variant.HUNTER) SpiderConfig.hunterSpeedMultiplier.get() else 1.0
        val enragedSpeed = if (enraged) SpiderConfig.enragedSpeedMultiplier.get() else 1.0
        val speed = SpiderConfig.chaseSpeed.get() / 20.0 * scaleSpeed * variantSpeed * enragedSpeed
        if (SpiderConfig.chasePathfinding.get()) {
            if (navigation.isDone || tickCount % 10 == 0) {
                val baseSpeed = getAttributeValue(Attributes.MOVEMENT_SPEED).coerceAtLeast(0.01)
                navigation.moveTo(target, speed / baseSpeed)
            }
        } else {
            navigation.stop()
            val direction = Vec3(dx, 0.0, dz).normalize()
            move(MoverType.SELF, direction.scale(speed))
        }

        if (distance <= 3.5 && attackTimer == 0) {
            val damage = when (variant) {
                Variant.NETHERITE -> (if (enraged) SpiderConfig.enragedAttackDamageHearts.get() else SpiderConfig.netheriteAttackDamageHearts.get()).toFloat() * 2f
                Variant.POISON -> SpiderConfig.poisonAttackDamageHearts.get().toFloat() * 2f
                Variant.HUNTER -> SpiderConfig.hunterAttackDamageHearts.get().toFloat() * 2f
                Variant.CAMO -> SpiderConfig.camoAttackDamageHearts.get().toFloat() * 2f
            }
            if (target.hurt(damageSources().mobAttack(this), damage)) {
                if (variant == Variant.POISON) target.addEffect(MobEffectInstance(MobEffects.POISON, (SpiderConfig.poisonEffectSeconds.get() * 20).toInt(), 1))
                if (variant == Variant.HUNTER) target.addEffect(MobEffectInstance(MobEffects.BLINDNESS, (SpiderConfig.hunterBlindnessSeconds.get() * 20).toInt(), 0))
                attackTimer = SpiderConfig.attackCooldown.get()
            }
        }
    }

    private fun wander(@Suppress("UNUSED_PARAMETER") serverLevel: ServerLevel) {
        if (!SpiderConfig.enableWandering.get()) return
        if (random.nextDouble() < SpiderConfig.wanderPauseChance.get() / 20.0) return
        if (random.nextInt(100) == 0) wanderAngle = random.nextDouble() * Math.PI * 2.0
        val speed = SpiderConfig.chaseSpeed.get() / 20.0 * SpiderConfig.wanderSpeedFactor.get()
        setYRot(Math.toDegrees(wanderAngle).toFloat())
        if (++wanderTicks % 120 == 0) wanderAngle += (random.nextDouble() - 0.5) * 1.5
        move(MoverType.SELF, Vec3(-sin(wanderAngle) * speed, 0.0, cos(wanderAngle) * speed))
    }

    private fun grantEncounterAdvancements(level: ServerLevel) {
        level.players().filter { it.distanceToSqr(x, y, z) <= 576.0 }.forEach { player ->
            SpiderAdvancements.grant(player, "encounter")
            when (variant) {
                Variant.CAMO -> SpiderAdvancements.grant(player, "encounter_camo")
                Variant.POISON -> SpiderAdvancements.grant(player, "encounter_poison")
                Variant.HUNTER -> SpiderAdvancements.grant(player, "encounter_hunter")
                Variant.NETHERITE -> Unit
            }
        }
    }

    private fun legLayouts(): List<LegLayout> {
        val count = if (variant == Variant.NETHERITE) SpiderConfig.netheriteLegs.get() else 8
        val pairs = count / 2
        val standardPairs = listOf(
            doubleArrayOf(0.1, 1.0, 1.6, 1.1),
            doubleArrayOf(0.0, 1.3, 0.4, 1.0),
            doubleArrayOf(-0.1, 1.3, -0.9, 1.1),
            doubleArrayOf(-0.2, 1.1, -2.5, 1.6)
        )
        val layouts = mutableListOf<LegLayout>()
        repeat(pairs) { index ->
            val sample = if (pairs <= 1) 1.5 else index.toDouble() * 3.0 / (pairs - 1)
            val lower = sample.toInt().coerceAtMost(2)
            val blend = sample - lower
            val first = standardPairs[lower]
            val second = standardPairs[lower + 1]
            fun value(column: Int) = first[column] + (second[column] - first[column]) * blend
            val rootZ: Double
            val restX: Double
            val restZ: Double
            val reach: Double
            if (variant == Variant.NETHERITE) {
                val q = if (pairs <= 1) 0.5 else index.toDouble() / (pairs - 1)
                rootZ = 0.1 - 0.3 * q
                restX = 1.0 + 0.3 * sin(Math.PI * q)
                restZ = 1.6 - 4.1 * q
                reach = 1.05 + 0.55 * q
            } else {
                rootZ = value(0)
                restX = value(1)
                restZ = value(2)
                reach = value(3)
            }
            layouts += LegLayout(rootZ, -1.0, restX, restZ, reach)
            layouts += LegLayout(rootZ, 1.0, restX, restZ, reach)
        }
        if (count % 2 == 1) layouts += LegLayout(0.15, 0.0, 0.0, 1.8, 1.1)
        return layouts
    }

    private fun ensureModel(level: ServerLevel) {
        val requiredParts = legLayouts().size * 3
        if (parts.size == requiredParts) return
        parts.toList().forEach { if (it.isAlive) it.discard() }
        parts.clear()
        repeat(requiredParts) { index ->
            val display = BLOCK_DISPLAY.create(level) ?: return@repeat
            display.setBlockState(stateFor(index))
            display.setNoGravity(true)
            display.noPhysics = true
            display.setInvulnerable(true)
            display.addTag("arachnomod_part")
            display.persistentData.putUUID("arachnomod_owner", uuid)
            if (level.addFreshEntity(display)) parts += display
        }
    }

    private fun stateFor(@Suppress("UNUSED_PARAMETER") index: Int): BlockState = when (variant) {
        Variant.CAMO -> Blocks.MOSS_BLOCK.defaultBlockState()
        Variant.POISON -> Blocks.WARPED_WART_BLOCK.defaultBlockState()
        Variant.HUNTER -> Blocks.BLACK_CONCRETE.defaultBlockState()
        Variant.NETHERITE -> Blocks.NETHERITE_BLOCK.defaultBlockState()
    }

    private fun updateModel(level: ServerLevel) {
        val layouts = legLayouts()
        if (parts.size != layouts.size * 3) return
        if (footPositions.size != layouts.size) {
            footPositions.clear()
            footStepStarts.clear()
            footStepTargets.clear()
            footStepProgress.clear()
            repeat(layouts.size) {
                footPositions += null
                footStepStarts += null
                footStepTargets += null
                footStepProgress += 0.0
            }
        }
        val scale = currentScale
        val yaw = Math.toRadians(yRot.toDouble())
        val forward = Vec3(-sin(yaw), 0.0, cos(yaw))
        val right = Vec3(forward.z, 0.0, -forward.x)
        val anchor = Vec3(x, y + 1.1 * scale, z)
        layouts.forEachIndexed { index, leg ->
            val pairPhase = phase + (index / 2) * Math.PI
            val stride = sin(pairPhase) * 0.25 * scale
            val lift = max(0.0, sin(pairPhase)) * 0.2 * scale
            val root = anchor.add(forward.scale(leg.rootZ * scale))
                .add(right.scale(leg.side * 0.18 * scale))
            val plannedFoot = Vec3(x, y + 0.04 * scale + lift, z)
                .add(right.scale(leg.side * leg.restX * scale))
                .add(forward.scale((leg.restZ + stride) * scale))
            var planted = footPositions[index]
            if (planted == null) {
                planted = probeGround(level, plannedFoot, scale)
                footPositions[index] = planted
            }
            if (footStepTargets[index] == null &&
                planted.subtract(plannedFoot).horizontalDistance() > 0.6 * scale
            ) {
                footStepStarts[index] = planted
                footStepTargets[index] = probeGround(level, plannedFoot, scale)
                footStepProgress[index] = 0.0
            }
            val destination = footStepTargets[index]
            val foot = if (destination != null) {
                val progress = (footStepProgress[index] + (SpiderConfig.legStepSpeed.get() / 5.0).coerceIn(0.05, 1.0)).coerceAtMost(1.0)
                footStepProgress[index] = progress
                val start = footStepStarts[index] ?: planted
                val stepping = start.lerp(destination, progress)
                    .add(0.0, sin(progress * Math.PI) * 0.25 * scale, 0.0)
                if (progress >= 1.0) {
                    footPositions[index] = destination
                    footStepStarts[index] = null
                    footStepTargets[index] = null
                    stepping
                } else {
                    stepping
                }
            } else {
                planted
            }
            val firstJoint = root.lerp(foot, 0.28)
                .add(right.scale(leg.side * leg.reach * 0.42 * scale))
                .add(0.0, -0.12 * scale, 0.0)
            val secondJoint = root.lerp(foot, 0.64)
                .add(right.scale(leg.side * leg.reach * 0.55 * scale))
                .add(0.0, -0.28 * scale, 0.0)
            val points = listOf(root, firstJoint, secondJoint, foot)
            for (segmentIndex in 0 until 3) {
                val taper = 0.28125 + (0.09375 - 0.28125) * segmentIndex / 2.0
                segment(
                    parts[index * 3 + segmentIndex],
                    points[segmentIndex],
                    points[segmentIndex + 1],
                    (taper * scale).toFloat()
                )
            }
        }
    }

    private fun probeGround(level: ServerLevel, planned: Vec3, scale: Double): Vec3 {
        val hit = level.clip(
            ClipContext(
                planned.add(0.0, 1.5 * scale, 0.0),
                planned.add(0.0, -1.25 * scale, 0.0),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this
            )
        )
        return if (hit.type == HitResult.Type.BLOCK) hit.location.add(0.0, 0.025 * scale, 0.0) else planned
    }

    private fun segment(display: BlockDisplay, start: Vec3, end: Vec3, width: Float) {
        val delta = end.subtract(start)
        val length = delta.length().toFloat().coerceAtLeast(0.01f)
        val direction = delta.normalize()
        val rotation = Quaternionf().rotationTo(
            Vector3f(0f, 1f, 0f),
            Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
        )
        display.setPos((start.x + end.x) / 2.0, (start.y + end.y) / 2.0, (start.z + end.z) / 2.0)
        display.setTransformation(Transformation(Vector3f(-0.5f, -0.5f, -0.5f), rotation, Vector3f(width, length, width), Quaternionf()))
    }

    override fun die(source: DamageSource) {
        if (!level().isClientSide) {
            val serverLevel = level() as ServerLevel
            val drop = if (enraged) net.minecraft.world.item.Items.NETHERITE_BLOCK else net.minecraft.world.item.Items.NETHERITE_INGOT
            if (enraged || random.nextDouble() < SpiderConfig.netheriteDropChance.get()) {
                serverLevel.addFreshEntity(net.minecraft.world.entity.item.ItemEntity(serverLevel, x, y + 0.25, z, net.minecraft.world.item.ItemStack(drop)))
            }
            (lastHurtByPlayer as? ServerPlayer)?.let { killer ->
                SpiderAdvancements.grant(killer, "slay")
                if (enraged) SpiderAdvancements.grant(killer, "slay_boss")
            }
            if (naturalEncounter) SpiderSpawnManager.killed(serverLevel.server)
        }
        cleanup()
        super.die(source)
    }

    private fun cleanup() {
        parts.forEach { if (it.isAlive) it.discard() }
        parts.clear()
        bossEvent.removeAllPlayers()
    }

    companion object {
        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 600.0)
            .add(Attributes.ARMOR, 16.0)
            .add(Attributes.MOVEMENT_SPEED, 0.35)
            .add(Attributes.ATTACK_DAMAGE, 12.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, SpiderConfig.netheriteKnockbackResistance.get())
    }
}
