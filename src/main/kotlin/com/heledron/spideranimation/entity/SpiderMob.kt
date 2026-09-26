package com.heledron.spideranimation.entity

import com.heledron.spideranimation.ModItems
import com.heledron.spideranimation.SpiderConfig
import com.heledron.spideranimation.SpiderSpawnManager
import com.heledron.spideranimation.SpiderAdvancements
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
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
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.Pose
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
import kotlin.math.sqrt

class SpiderMob(type: EntityType<out SpiderMob>, level: Level) : Monster(type, level) {
    enum class Variant { NETHERITE, CAMO, POISON, HUNTER }
    private enum class AiMode { WANDER, ALERT, CHASE }
    private data class LegLayout(val rootZ: Double, val side: Double, val restX: Double, val restZ: Double, val segmentLength: Double)

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
    private var trophyRolled = false
    private var blindnessCooldown = 0
    private var lungeTimer = 0
    private var lungeDirection = Vec3.ZERO
    private var aiMode = AiMode.WANDER
    private var alertTimer = 0
    private var phase = 0.0
    private var currentScale = 1.0
    private var supportNormal = Vec3(0.0, 1.0, 0.0)
    private var supportHeightOffset = 0.0
    private var wanderTicks = 0
    private var wanderAngle = 0.0
    private var chaseSteerSign = 1
    private var patrolAnchor: Vec3? = null
    private var wanderGoal: BlockPos? = null
    private var wanderGoalExpiresAt = 0
    private var committedOpening: SafeGroundFinder.Opening? = null
    private var committedOpeningUntil = 0
    private var openingRescanAt = 0
    private var stationaryTicks = 0
    private var groomingTimer = 0
    private var groomingOriginalLeft: Vec3? = null
    private var groomingOriginalRight: Vec3? = null
    private var groomingOriginalLeftGrounded = false
    private var groomingOriginalRightGrounded = false
    private val parts = mutableListOf<BlockDisplay>()
    private var bodyDisplay: BlockDisplay? = null
    private var riderInputForward = 0.0
    private var riderInputStrafe = 0.0
    private var riderInputReceivedAt = Long.MIN_VALUE
    private var wasOnGround = true
    private val footPositions = mutableListOf<Vec3?>()
    private val footGrounded = mutableListOf<Boolean>()
    private val footStepStarts = mutableListOf<Vec3?>()
    private val footStepTargets = mutableListOf<Vec3?>()
    private val footStepTargetGrounded = mutableListOf<Boolean>()
    private val footStepProgress = mutableListOf<Double>()
    private val footStepStartedAt = mutableListOf<Int>()
    private val footStepStoppedAt = mutableListOf<Int>()
    private val legBlockStates = mutableListOf<BlockState?>()
    private val bossEvent = ServerBossEvent(
        Component.literal("Netherite Octoarachnopod"),
        BossEvent.BossBarColor.PURPLE,
        BossEvent.BossBarOverlay.PROGRESS
    )

    init {
        bossEvent.isVisible = false
        setNoGravity(true)
        setInvisible(true)
        applyVariantStats()
    }

    override fun registerGoals() = Unit
    override fun removeWhenFarAway(distance: Double): Boolean = false
    override fun shouldBeSaved(): Boolean = false
    override fun fireImmune(): Boolean = variant == Variant.NETHERITE

    override fun getPassengersRidingOffset(): Double = 0.25 * currentScale + 0.2

    override fun getDimensions(pose: Pose): EntityDimensions {
        val scale = currentScale.coerceAtLeast(0.01).toFloat()
        return EntityDimensions.scalable(2.0f * scale, 2.0f * scale)
    }

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
        refreshDimensions()
    }

    fun makePersonal(owner: UUID, size: Double) {
        personalOwner = owner
        tamed = true
        personalSize = size.coerceIn(0.3, 20.0)
        currentScale = personalSize
        refreshDimensions()
        setTarget(null)
    }

    fun resizePersonal(size: Double) {
        personalSize = size.coerceIn(0.3, 20.0)
        currentScale = personalSize
        refreshDimensions()
    }

    fun setRiderInput(playerId: UUID, forward: Float, strafe: Float) {
        if (!tamed || firstPassenger?.uuid != playerId) return
        riderInputForward = forward.coerceIn(-1f, 1f).toDouble()
        riderInputStrafe = strafe.coerceIn(-1f, 1f).toDouble()
        riderInputReceivedAt = tickCount.toLong()
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (stack.item == net.minecraft.world.item.Items.NETHERITE_INGOT &&
            variant == Variant.NETHERITE && !tamed && !enraged && level() is ServerLevel
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
                val serverLevel = level() as ServerLevel
                serverLevel.playSound(
                    null, x, y, z, net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_THUNDER,
                    net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.4f
                )
                serverLevel.sendParticles(
                    net.minecraft.core.particles.DustParticleOptions.REDSTONE, x, y, z,
                    60, 1.2 * currentScale, 0.8 * currentScale, 1.2 * currentScale, 0.1
                )
                serverLevel.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, x, y, z,
                    40, 1.2 * currentScale, 0.8 * currentScale, 1.2 * currentScale, 0.3
                )
                if (player is ServerPlayer) SpiderAdvancements.grant(player, "enrage")
                if (!player.abilities.instabuild) stack.shrink(1)
            }
            return InteractionResult.sidedSuccess(level().isClientSide)
        }

        if (stack.item == ModItems.SPIDER_TAMER.get()) {
            if (!level().isClientSide && !tamed) {
                tamed = true
                setTarget(null)
                val serverLevel = level() as ServerLevel
                serverLevel.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.HEART, x, y + 1.5, z,
                    9, 1.2, 1.2, 1.2, 0.02
                )
                serverLevel.playSound(
                    null, x, y, z, net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP,
                    net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.2f
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
        if (onGround() && !wasOnGround) playLandingSound(serverLevel)
        wasOnGround = onGround()
        ensureModel(serverLevel)
        if (tickCount % 20 == 0) grantEncounterAdvancements(serverLevel)
        attackTimer = (attackTimer - 1).coerceAtLeast(0)
        blindnessCooldown = (blindnessCooldown - 1).coerceAtLeast(0)
        if (lungeTimer > 0) lungeTimer--
        val movementSpeedSquared = deltaMovement.lengthSqr()
        if (!SpiderConfig.enableWandering.get() && movementSpeedSquared < 0.001) {
            stationaryTicks++
            if (groomingTimer <= 0 && footStepTargets.getOrNull(0) == null &&
                footStepTargets.getOrNull(1) == null && footGrounded.getOrNull(0) == true &&
                footGrounded.getOrNull(1) == true && stationaryTicks > 60 &&
                random.nextDouble() < SpiderConfig.groomingChance.get() / 20.0
            ) {
                groomingOriginalLeft = footPositions.getOrNull(0)
                groomingOriginalRight = footPositions.getOrNull(1)
                if (groomingOriginalLeft != null && groomingOriginalRight != null) {
                    groomingOriginalLeftGrounded = footGrounded[0]
                    groomingOriginalRightGrounded = footGrounded[1]
                    footGrounded[0] = false
                    footGrounded[1] = false
                    groomingTimer = 100
                }
            }
        } else {
            stationaryTicks = 0
            if (SpiderConfig.enableWandering.get() || movementSpeedSquared > 0.01) {
                if (groomingTimer > 0) {
                    footGrounded[0] = groomingOriginalLeftGrounded
                    footGrounded[1] = groomingOriginalRightGrounded
                }
                groomingTimer = 0
                groomingOriginalLeft = null
                groomingOriginalRight = null
            }
        }
        if (groomingTimer > 0) {
            groomingTimer--
            if (groomingTimer == 0) {
                footGrounded[0] = groomingOriginalLeftGrounded
                footGrounded[1] = groomingOriginalRightGrounded
                groomingOriginalLeft = null
                groomingOriginalRight = null
            }
        }
        val scaleBeforeUpdate = currentScale

        val rider = firstPassenger as? Player
        if (tamed && rider != null) {
            navigation.stop()
            setYRot(rider.yRot)
            val inputFresh = tickCount.toLong() - riderInputReceivedAt <= 4L
            val forward = if (inputFresh) riderInputForward else 0.0
            val strafe = if (inputFresh) riderInputStrafe else 0.0
            val yaw = Math.toRadians(rider.yRot.toDouble())
            val input = Vec3(-sin(yaw) * forward + cos(yaw) * strafe, 0.0, cos(yaw) * forward + sin(yaw) * strafe)
            val movement = if (input.lengthSqr() > 1.0e-6) {
                input.normalize().scale(0.24 * scaleToSpeedFactor(SpiderConfig.riddenSize.get()))
            } else {
                Vec3.ZERO
            }
            move(MoverType.SELF, movement)
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

        if (kotlin.math.abs(currentScale - scaleBeforeUpdate) > 1.0e-4) refreshDimensions()
        if (deltaMovement.horizontalDistance() > 0.015) phase += 0.45
        updateModel(serverLevel)
        bossEvent.progress = (health / maxHealth).coerceIn(0f, 1f)
        if (variant == Variant.HUNTER && !tamed && aiMode == AiMode.CHASE &&
            SpiderConfig.hunterBlindnessRange.get() > 0.0 && blindnessCooldown == 0
        ) {
            val duration = (SpiderConfig.hunterBlindnessSeconds.get() * 20.0).toInt().coerceAtLeast(1)
            val affected = serverLevel.players().filter {
                it.isAlive && it.distanceToSqr(this) <= SpiderConfig.hunterBlindnessRange.get() * SpiderConfig.hunterBlindnessRange.get()
            }
            affected.forEach { it.addEffect(MobEffectInstance(MobEffects.BLINDNESS, duration, 0), this) }
            if (affected.isNotEmpty()) blindnessCooldown = 40
        }
    }

    private fun hunt(serverLevel: ServerLevel) {
        val brightOutside = serverLevel.getSkyDarken() < 4
        val target = serverLevel.players()
            .filter { it.isAlive && (!SpiderConfig.onlyAtNight.get() || !brightOutside) }
            .minByOrNull { it.distanceToSqr(this) }
        val distance = if (target == null) Double.MAX_VALUE else distanceTo(target).toDouble()
        val horizontalDistance = if (target == null) Double.MAX_VALUE else {
            val dx = target.x - x
            val dz = target.z - z
            sqrt(dx * dx + dz * dz)
        }
        val detectionDistance = SpiderConfig.chaseDistance.get()
        val allowedDistance = if (aiMode == AiMode.WANDER) {
            detectionDistance
        } else {
            detectionDistance * SpiderConfig.chaseExitMultiplier.get()
        }
        if (target == null || distance > allowedDistance) {
            if (aiMode != AiMode.WANDER) {
                patrolAnchor = Vec3(x, y, z)
                wanderGoal = null
                committedOpening = null
                openingRescanAt = 0
                navigation.stop()
            }
            setTarget(null)
            aiMode = AiMode.WANDER
            alertTimer = 0
            val idleScale = when (variant) {
                Variant.POISON -> SpiderConfig.poisonSize.get()
                Variant.HUNTER -> SpiderConfig.hunterSize.get()
                Variant.NETHERITE, Variant.CAMO -> max(
                    SpiderConfig.maxSize.get(),
                    if (SpiderConfig.growInWater.get()) waterGrowthScale(serverLevel) ?: 0.0 else 0.0
                )
            }
            approachScale(idleScale)
            wander(serverLevel)
            return
        }

        setTarget(target)
        if (aiMode == AiMode.WANDER) {
            patrolAnchor = null
            wanderGoal = null
            committedOpening = null
            openingRescanAt = 0
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
        val waterScale = if (variant != Variant.HUNTER && SpiderConfig.growInWater.get()) {
            waterGrowthScale(serverLevel)
        } else null
        val distanceScale = SpiderConfig.minSize.get() +
            (SpiderConfig.maxSize.get() - SpiderConfig.minSize.get()) *
            ((horizontalDistance - near) / (far - near)).coerceIn(0.0, 1.0)
        var desiredScale = when (variant) {
            Variant.POISON -> SpiderConfig.poisonSize.get()
            Variant.HUNTER -> SpiderConfig.hunterSize.get()
            Variant.NETHERITE, Variant.CAMO -> max(distanceScale, waterScale ?: 0.0)
        }
        if (variant != Variant.HUNTER && horizontalDistance <= 14.0 + 6.0 * currentScale) {
            val room = SafeGroundFinder.roomAt(serverLevel, target.x, target.y, target.z)
            if (room != null) {
                val fitScale = max(0.12, (room - 0.2) / (1.1 + 0.3))
                val passageScale = when (room.toInt()) {
                    1 -> SpiderConfig.squeezeSize.get()
                    2 -> 0.6
                    else -> fitScale
                }
                desiredScale = min(desiredScale, min(fitScale, passageScale))
            }
        }
        if (variant != Variant.HUNTER) {
            passageScaleCap()?.let { desiredScale = min(desiredScale, it) }
        }
        approachScale(desiredScale)

        val dx = target.x - x
        val dz = target.z - z
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        setYRot(yaw)
        yRotO = yaw
        if (variant == Variant.HUNTER) {
            val playerLooksFromFarAway = horizontalDistance > 6.0 && serverLevel.players().any(::isLookingAt)
            val cannotReachPlayerHeight = kotlin.math.abs((y - 1.1 * currentScale) - target.y) > 2.0 &&
                horizontalDistance < 8.0
            if (playerLooksFromFarAway || cannotReachPlayerHeight) {
                navigation.stop()
                return
            }
        }

        val scaleSpeed = scaleToSpeedFactor(currentScale)
        val variantSpeed = if (variant == Variant.HUNTER) {
            SpiderConfig.hunterSpeedMultiplier.get()
        } else {
            scaleSpeed
        }
        val enragedSpeed = if (enraged) SpiderConfig.enragedSpeedMultiplier.get() else 1.0
        val speed = SpiderConfig.chaseSpeed.get() / 20.0 * variantSpeed * enragedSpeed
        if (variant == Variant.POISON && lungeTimer in 1..7) {
            navigation.stop()
            if (lungeTimer == 7) {
                val impulse = 0.9 * currentScale.coerceIn(0.6, 2.5)
                move(MoverType.SELF, lungeDirection.scale(impulse).add(0.0, 0.3 * impulse, 0.0))
            } else {
                move(MoverType.SELF, Vec3(0.0, -0.05 * currentScale, 0.0))
            }
        } else if (SpiderConfig.chasePathfinding.get()) {
            if (navigation.isDone || tickCount % 10 == 0) {
                val baseSpeed = getAttributeValue(Attributes.MOVEMENT_SPEED).coerceAtLeast(0.01)
                val passageWaypoint = findPassageWaypoint(serverLevel, target)
                val steerWaypoint = if (passageWaypoint == null) findSteerWaypoint(serverLevel, target) else null
                val destination = passageWaypoint ?: steerWaypoint ?: target.position()
                navigation.moveTo(destination.x, destination.y, destination.z, speed / baseSpeed)
            }
        } else {
            navigation.stop()
            val direction = Vec3(dx, 0.0, dz).normalize()
            move(MoverType.SELF, direction.scale(speed))
        }

        val attackReach = 3.5 * min(currentScale, 2.0)
        if (variant == Variant.POISON) {
            if (lungeTimer == 0 && groomingTimer == 0 && attackTimer == 0 && distance <= attackReach * 2.2) {
                lungeDirection = Vec3(dx, 0.0, dz).normalize()
                if (lungeDirection.lengthSqr() > 1.0e-8) {
                    lungeTimer = 14
                    for (legIndex in 0..1) {
                        if (legIndex < footStepTargets.size) {
                            footStepStarts[legIndex] = null
                            footStepTargets[legIndex] = null
                            footStepTargetGrounded[legIndex] = false
                            footGrounded[legIndex] = false
                        }
                    }
                    attackTimer = SpiderConfig.attackCooldown.get()
                }
            } else if (lungeTimer in 1..6 && target.distanceToSqr(this) <= attackReach * attackReach) {
                val damage = SpiderConfig.poisonAttackDamageHearts.get().toFloat() * 2f
                target.hurt(damageSources().mobAttack(this), damage)
                target.addEffect(MobEffectInstance(MobEffects.POISON, (SpiderConfig.poisonEffectSeconds.get() * 20).toInt(), 1))
                lungeTimer = 0
            }
        } else if (target.distanceToSqr(this) <= attackReach * attackReach && attackTimer == 0) {
            val damage = when (variant) {
                Variant.NETHERITE -> (if (enraged) SpiderConfig.enragedAttackDamageHearts.get() else SpiderConfig.netheriteAttackDamageHearts.get()).toFloat() * 2f
                Variant.HUNTER -> SpiderConfig.hunterAttackDamageHearts.get().toFloat() * 2f
                Variant.CAMO -> SpiderConfig.camoAttackDamageHearts.get().toFloat() * 2f
                Variant.POISON -> 0f
            }
            target.hurt(damageSources().mobAttack(this), damage)
            attackTimer = SpiderConfig.attackCooldown.get()
        }
    }

    private fun passageScaleCap(): Double? {
        val opening = committedOpening ?: return null
        val along = if (opening.alongX) x else z
        val lane = if (opening.alongX) z else x
        val lo = opening.lo
        val hi = opening.hi
        val enterAtLo = along <= (lo + hi) * 0.5
        val entry = if (enterAtLo) lo else hi
        val distanceToEntry = kotlin.math.abs(along - entry) +
            kotlin.math.abs(lane - if (opening.alongX) opening.z else opening.x)
        if (distanceToEntry >= 7.0) return null
        if (opening.height == 1) {
            return if (distanceToEntry < 4.5) SpiderConfig.squeezeSize.get() else 0.3
        }
        return if (opening.height == 2) 0.6 else null
    }

    private fun findPassageWaypoint(level: ServerLevel, target: Player): Vec3? {
        var opening = committedOpening
        if (opening != null && tickCount >= committedOpeningUntil) {
            committedOpening = null
            opening = null
        }
        if (opening == null) {
            if (tickCount < openingRescanAt) return null
            openingRescanAt = tickCount + 20
            val start = Vec3(x, y + currentScale, z)
            val end = Vec3(target.x, target.y + currentScale, target.z)
            val hit = level.clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
            if (hit.type == HitResult.Type.BLOCK) {
                val groundY = SafeGroundFinder.groundYAt(level, hit.location.x, hit.location.z, y)
                if (groundY != null) {
                    val minimumOpeningHeight = if (variant == Variant.HUNTER) 2 else 1
                    opening = SafeGroundFinder.collectOpenings(
                        level, hit.location.x, hit.location.z, groundY, target.x, target.z,
                        minHeight = minimumOpeningHeight, limit = 256
                    ).firstOrNull { canReachOpening(level, it, minimumOpeningHeight) }
                    if (opening != null) {
                        committedOpening = opening
                        committedOpeningUntil = tickCount + 80
                    }
                }
            }
        }
        opening ?: return null

        val along = if (opening.alongX) x else z
        val lane = if (opening.alongX) z else x
        val openingLane = if (opening.alongX) opening.z else opening.x
        val enterAtLo = along <= (opening.lo + opening.hi) * 0.5
        val entry = if (enterAtLo) opening.lo else opening.hi
        val exit = if (enterAtLo) opening.hi else opening.lo
        val direction = if (enterAtLo) 1.0 else -1.0
        val laneOffset = kotlin.math.abs(lane - openingLane)
        val insideSpan = along > opening.lo - 1.5 && along < opening.hi + 1.5
        if (if (enterAtLo) along > opening.hi + 2.0 else along < opening.lo - 2.0) {
            committedOpening = null
            openingRescanAt = tickCount + 20
            return null
        }
        val waypointAlong = if (laneOffset >= 0.75 && !insideSpan) {
            entry - direction * 1.6
        } else {
            exit + direction * 1.6
        }
        return if (opening.alongX) Vec3(waypointAlong, opening.y, opening.z)
        else Vec3(opening.x, opening.y, waypointAlong)
    }

    private fun canReachOpening(level: ServerLevel, opening: SafeGroundFinder.Opening, minimumHeight: Int): Boolean {
        if (opening.height < minimumHeight) return false
        val offset = currentScale * 0.5
        val hit = level.clip(
            ClipContext(
                Vec3(x, y + offset, z),
                Vec3(opening.x, opening.y + offset, opening.z),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this
            )
        )
        if (hit.type != HitResult.Type.BLOCK) return true
        val dx = hit.location.x - opening.x
        val dz = hit.location.z - opening.z
        if (dx * dx + dz * dz > 2.25) return false
        return SafeGroundFinder.openingHeight(level, hit.location.x, opening.y, hit.location.z) >= minimumHeight
    }

    private fun findSteerWaypoint(level: ServerLevel, target: Player): Vec3? {
        val bodyOffset = currentScale * 0.5
        val directHit = level.clip(
            ClipContext(
                Vec3(x, y + bodyOffset, z),
                Vec3(target.x, target.y + bodyOffset, target.z),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this
            )
        )
        if (directHit.type != HitResult.Type.BLOCK) return null

        val dx = target.x - x
        val dz = target.z - z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < 1.5) return null
        val forwardX = dx / distance
        val forwardZ = dz / distance
        val angles = doubleArrayOf(0.611, 1.222, 1.833)
        for (angle in angles) {
            for (sign in intArrayOf(chaseSteerSign, -chaseSteerSign)) {
                val signedAngle = angle * sign
                val directionX = forwardX * cos(signedAngle) - forwardZ * sin(signedAngle)
                val directionZ = forwardX * sin(signedAngle) + forwardZ * cos(signedAngle)
                var previous = Vec3(x, y + bodyOffset, z)
                var endY = y
                var safe = true
                for (step in 1..4) {
                    val nextX = x + directionX * step
                    val nextZ = z + directionZ * step
                    val groundY = SafeGroundFinder.findSafeYNear(
                        level, nextX, nextZ, y, maxSearch = 4
                    )
                    if (groundY == null || kotlin.math.abs(groundY - y) > 2.0) {
                        safe = false
                        break
                    }
                    val nextBounds = boundingBox.move(nextX - x, groundY - y, nextZ - z)
                    if (!level.noCollision(this, nextBounds)) {
                        safe = false
                        break
                    }
                    val next = Vec3(nextX, groundY + bodyOffset, nextZ)
                    val hit = level.clip(
                        ClipContext(previous, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)
                    )
                    if (hit.type == HitResult.Type.BLOCK) {
                        safe = false
                        break
                    }
                    previous = next
                    endY = groundY
                }
                if (safe) {
                    chaseSteerSign = sign
                    return Vec3(x + directionX * 4.0, endY, z + directionZ * 4.0)
                }
            }
        }
        return null
    }

    private fun scaleToSpeedFactor(scale: Double): Double {
        val minSize = SpiderConfig.minSize.get()
        val maxSize = SpiderConfig.maxSize.get()
        val normalized = if (maxSize > minSize) {
            ((scale - minSize) / (maxSize - minSize)).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        return 1.0 + normalized * (SpiderConfig.speedGrowthFactor.get() - 1.0)
    }

    private fun approachScale(targetScale: Double) {
        val linearStep = currentScale + (targetScale - currentScale) * 0.3
        val minimum = currentScale / (1.0 + SpiderConfig.shrinkPercentPerTick.get() / 100.0)
        val maximum = currentScale * (1.0 + SpiderConfig.growPercentPerTick.get() / 100.0)
        currentScale = linearStep.coerceIn(minimum, maximum)
    }

    private fun isLookingAt(player: Player): Boolean {
        val dx = x - player.x
        val dy = y - (player.y + player.eyeHeight)
        val dz = z - player.z
        val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1.0e-6) return true
        val look = player.lookAngle
        return (look.x * dx + look.y * dy + look.z * dz) / length > 0.7
    }

    private fun waterGrowthScale(serverLevel: ServerLevel): Double? {
        val floorY = SafeGroundFinder.findFloorBelow(serverLevel, x, y, z, 16) ?: return null
        val waterDepth = SafeGroundFinder.waterDepthAbove(serverLevel, x, floorY, z, 16)
        return if (waterDepth <= 1.0) null else (waterDepth + 0.5) / 1.1
    }

    private fun wander(serverLevel: ServerLevel) {
        if (!SpiderConfig.enableWandering.get()) {
            navigation.stop()
            wanderGoal = null
            return
        }

        val anchor = patrolAnchor ?: Vec3(x, y, z).also { patrolAnchor = it }
        val currentGoal = wanderGoal
        if (currentGoal != null) {
            val reached = distanceToSqr(
                currentGoal.x + 0.5,
                currentGoal.y.toDouble(),
                currentGoal.z + 0.5
            ) < 2.25
            if (reached || tickCount >= wanderGoalExpiresAt || navigation.isDone) {
                navigation.stop()
                wanderGoal = null
            } else {
                return
            }
        }

        if (tickCount < wanderGoalExpiresAt) return
        val minInterval = (SpiderConfig.wanderMinIntervalSeconds.get() * 20.0).toInt()
        val maxInterval = max(minInterval, (SpiderConfig.wanderMaxIntervalSeconds.get() * 20.0).toInt())
        wanderGoalExpiresAt = tickCount + minInterval + random.nextInt((maxInterval - minInterval + 1).coerceAtLeast(1))
        if (random.nextDouble() < SpiderConfig.wanderPauseChance.get()) return

        val radius = SpiderConfig.wanderRadius.get()
        repeat(8) {
            val angle = random.nextDouble() * Math.PI * 2.0
            val distance = radius * random.nextDouble()
            val targetX = anchor.x + cos(angle) * distance
            val targetZ = anchor.z + sin(angle) * distance
            val targetY = SafeGroundFinder.groundYAt(serverLevel, targetX, targetZ, anchor.y) ?: return@repeat
            val maxStepHeight = (1.1 * currentScale * 1.5).coerceIn(3.0, 12.0)
            if (!isWanderPathSafe(serverLevel, anchor, Vec3(targetX, targetY, targetZ), maxStepHeight)) return@repeat

            val target = BlockPos.containing(targetX, targetY, targetZ)
            wanderGoal = target
            val speed = SpiderConfig.wanderSpeedFactor.get() * scaleToSpeedFactor(currentScale)
            navigation.moveTo(targetX, targetY, targetZ, speed)
            setYRot(Math.toDegrees(atan2(-(targetX - x), targetZ - z)).toFloat())
            return
        }
    }

    private fun isWanderPathSafe(level: ServerLevel, start: Vec3, end: Vec3, maxStepHeight: Double): Boolean {
        val dx = end.x - start.x
        val dz = end.z - start.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < 1.0) return true

        val steps = kotlin.math.ceil(distance).toInt()
        val stepX = dx / steps
        val stepZ = dz / steps
        var pathX = start.x
        var pathZ = start.z
        var previousY = SafeGroundFinder.groundYAt(level, pathX, pathZ, start.y) ?: return false
        repeat(steps) {
            pathX += stepX
            pathZ += stepZ
            val nextY = SafeGroundFinder.groundYAt(level, pathX, pathZ, previousY) ?: return false
            if (previousY - nextY > maxStepHeight) return false
            previousY = nextY
        }
        return true
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
            val segmentLength: Double
            if (variant == Variant.NETHERITE) {
                val q = if (pairs <= 1) 0.5 else index.toDouble() / (pairs - 1)
                rootZ = 0.1 - 0.3 * q
                restX = 1.0 + 0.3 * sin(Math.PI * q)
                restZ = 1.6 - 4.1 * q
                segmentLength = 1.05 + 0.55 * q * q
            } else {
                rootZ = value(0)
                restX = value(1)
                restZ = value(2)
                segmentLength = value(3)
            }
            layouts += LegLayout(rootZ, -1.0, restX, restZ, segmentLength)
            layouts += LegLayout(rootZ, 1.0, restX, restZ, segmentLength)
        }
        if (count % 2 == 1) layouts += LegLayout(0.15, 0.0, 0.0, 1.8, 1.1)
        return layouts
    }

    private fun ensureModel(level: ServerLevel) {
        val requiredParts = legLayouts().size * 3
        if (parts.size == requiredParts && bodyDisplay?.isAlive == true) return
        parts.toList().forEach { if (it.isAlive) it.discard() }
        parts.clear()
        bodyDisplay?.let { if (it.isAlive) it.discard() }
        bodyDisplay = null
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
        val torso = BLOCK_DISPLAY.create(level) ?: return
        torso.setBlockState(Blocks.NETHERITE_BLOCK.defaultBlockState())
        torso.setNoGravity(true)
        torso.noPhysics = true
        torso.setInvulnerable(true)
        torso.addTag("arachnomod_part")
        torso.persistentData.putUUID("arachnomod_owner", uuid)
        if (level.addFreshEntity(torso)) bodyDisplay = torso
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
            footGrounded.clear()
            footStepStarts.clear()
            footStepTargets.clear()
            footStepTargetGrounded.clear()
            footStepProgress.clear()
            footStepStartedAt.clear()
            footStepStoppedAt.clear()
            repeat(layouts.size) {
                footPositions += null
                footGrounded += false
                footStepStarts += null
                footStepTargets += null
                footStepTargetGrounded += false
                footStepProgress += 0.0
                footStepStartedAt += tickCount - 1
                footStepStoppedAt += tickCount - 1
            }
        }
        if (legBlockStates.size != layouts.size) {
            legBlockStates.clear()
            repeat(layouts.size) { legBlockStates += null }
        }
        val scale = currentScale
        val yaw = Math.toRadians(yRot.toDouble())
        val forward = Vec3(-sin(yaw), 0.0, cos(yaw))
        val right = Vec3(forward.z, 0.0, -forward.x)

        // Match the original body's support-plane response: planted feet determine
        // a smoothed up axis, so the leg roots lean with uneven terrain.
        fun average(points: List<Vec3>): Vec3? =
            if (points.isEmpty()) null else points.reduce(Vec3::add).scale(1.0 / points.size)
        val supportedFeet = layouts.indices.filter {
            footGrounded[it] && footStepTargets[it] == null
        }
        val leftFeet = supportedFeet.filter { layouts[it].side < 0.0 }.mapNotNull { footPositions[it] }
        val rightFeet = supportedFeet.filter { layouts[it].side > 0.0 }.mapNotNull { footPositions[it] }
        val frontFeet = supportedFeet.filter { layouts[it].rootZ >= 0.0 }.mapNotNull { footPositions[it] }
        val backFeet = supportedFeet.filter { layouts[it].rootZ < 0.0 }.mapNotNull { footPositions[it] }
        val lateral = average(rightFeet)?.subtract(average(leftFeet) ?: Vec3.ZERO)
        val longitudinal = average(frontFeet)?.subtract(average(backFeet) ?: Vec3.ZERO)
        if (leftFeet.isNotEmpty() && rightFeet.isNotEmpty() &&
            frontFeet.isNotEmpty() && backFeet.isNotEmpty() &&
            lateral != null && longitudinal != null &&
            lateral.lengthSqr() > 1.0e-6 && longitudinal.lengthSqr() > 1.0e-6
        ) {
            var estimatedUp = longitudinal.cross(lateral).normalize()
            if (estimatedUp.y < 0.0) estimatedUp = estimatedUp.scale(-1.0)
            if (estimatedUp.y > 0.15) {
                supportNormal = supportNormal.scale(0.82).add(estimatedUp.scale(0.18)).normalize()
            }
        }
        var bodyRight = right.subtract(supportNormal.scale(right.dot(supportNormal)))
        if (bodyRight.lengthSqr() < 1.0e-6) bodyRight = right
        bodyRight = bodyRight.normalize()
        var bodyForward = bodyRight.cross(supportNormal).normalize()
        if (bodyForward.dot(forward) < 0.0) bodyForward = bodyForward.scale(-1.0)

        val supportFootHeight = average(layouts.indices.mapNotNull { footStepTargets[it] ?: footPositions[it] })?.y
        if (supportFootHeight != null) {
            val preferredOffset = supportFootHeight - y
            // The original gait corrects body height by 25% toward mean leg target height.
            supportHeightOffset += (preferredOffset - supportHeightOffset) * 0.25
        }

        val idleBreathing = !tamed && !SpiderConfig.enableWandering.get() && stationaryTicks > 0
        val breath = if (idleBreathing) sin(tickCount * 0.08) * 0.035 * scale else 0.0
        val anchor = Vec3(x, y + supportHeightOffset, z)
            .add(supportNormal.scale(1.1 * scale))
            .add(0.0, breath, 0.0)
        bodyDisplay?.takeIf { it.isAlive }?.let { torso ->
            val bodyRotation = Quaternionf().rotationTo(
                Vector3f(0f, 0f, 1f),
                Vector3f(bodyForward.x.toFloat(), bodyForward.y.toFloat(), bodyForward.z.toFloat())
            )
            torso.setPos(anchor.x, anchor.y, anchor.z)
            torso.setTransformation(
                Transformation(
                    Vector3f(-0.5f, -0.5f, -0.5f),
                    bodyRotation,
                    Vector3f((0.7 * scale).toFloat(), (0.45 * scale).toFloat(), scale.toFloat()),
                    Quaternionf()
                )
            )
        }
        layouts.forEachIndexed { index, leg ->
            val root = anchor.add(bodyForward.scale(leg.rootZ * scale))
                .add(bodyRight.scale(leg.side * 0.18 * scale))
            // The source gait places feet from each leg's rest pose and looks ahead
            // along actual motion; it does not swing feet on a global sine phase.
            val horizontalVelocity = deltaMovement.multiply(1.0, 0.0, 1.0)
            val maxWalkSpeed = (SpiderConfig.chaseSpeed.get().toDouble() / 20.0).coerceAtLeast(1.0e-6)
            val speedFraction = (deltaMovement.length() / maxWalkSpeed).coerceIn(0.0, 1.0)
            // The original interpolates the foot trigger radius from 0.25 blocks
            // at rest to 0.8 while moving, and scales lookahead by that radius.
            val triggerRadius = 0.25 + (0.8 - 0.25) * speedFraction
            val lookAhead = if (horizontalVelocity.lengthSqr() > 1.0e-6) {
                horizontalVelocity.normalize().scale(triggerRadius * 0.6 * scale)
            } else Vec3.ZERO
            val plannedFoot = Vec3(x, y + 0.04 * scale, z)
                .add(right.scale(leg.side * leg.restX * scale))
                .add(forward.scale(leg.restZ * scale))
                .add(lookAhead)
            var planted = footPositions[index]
            if (planted == null) {
                val initialProbe = probeGround(level, plannedFoot, scale)
                planted = initialProbe.first
                footPositions[index] = planted
                footGrounded[index] = initialProbe.second
            }
            if (footStepTargets[index] == null &&
                (!footGrounded[index] || planted.subtract(plannedFoot).horizontalDistance() > triggerRadius * scale) &&
                canStartLegStep(index, layouts, planted, plannedFoot, scale, triggerRadius, footGrounded[index])
            ) {
                footStepStarts[index] = planted
                val targetProbe = probeGround(level, plannedFoot, scale)
                footStepTargets[index] = targetProbe.first
                footStepTargetGrounded[index] = targetProbe.second
                footGrounded[index] = false
                footStepProgress[index] = 0.0
                footStepStartedAt[index] = tickCount
            }
            val destination = footStepTargets[index]
            val foot = if (lungeTimer > 0 && index < 2) {
                val sideDirection = if (index == 0) 1.0 else -1.0
                val rightOfLunge = Vec3(
                    -lungeDirection.z,
                    0.0,
                    lungeDirection.x
                )
                val lungeTarget = Vec3(x, y, z)
                    .add(lungeDirection.scale(0.9 * scale))
                    .add(0.0, 0.8 * scale, 0.0)
                    .add(rightOfLunge.scale(0.35 * sideDirection * scale))
                val raisedFoot = planted.lerp(lungeTarget, 0.35)
                footPositions[index] = raisedFoot
                footGrounded[index] = false
                raisedFoot
            } else if (groomingTimer > 0 && index < 2) {
                footGrounded[index] = false
                val progress = 1.0 - groomingTimer / 100.0
                val edge = ((progress / 0.2).coerceAtMost(1.0) * ((1.0 - progress) / 0.2).coerceAtMost(1.0)).coerceIn(0.0, 1.0)
                val smooth = edge * edge * (3.0 - 2.0 * edge)
                val bodyPosition = Vec3(x, y, z)
                val mouth = bodyPosition.add(forward.scale(0.5 * scale)).add(supportNormal.scale(-0.05 * scale))
                val sideSign = if (index == 0) -1.0 else 1.0
                val rub = when {
                    progress < 0.2 || progress > 0.8 -> 0.0
                    progress < 0.3 -> (progress - 0.2) / 0.1
                    progress > 0.7 -> (0.8 - progress) / 0.1
                    else -> 1.0
                }
                val target = mouth.add(right.scale(0.15 * sideSign * scale))
                    .add(supportNormal.scale(sin(progress * Math.PI * 6.0) * 0.08 * scale * rub))
                    .add(forward.scale(cos(progress * Math.PI * 6.0) * 0.04 * scale * rub))
                val original = if (index == 0) groomingOriginalLeft else groomingOriginalRight
                val arc = supportNormal.scale(sin(smooth * Math.PI) * 0.4 * scale)
                (original ?: planted).lerp(target, smooth).add(arc)
            } else if (destination != null) {
                // Match Leg.updateMovement from the original: travel at the
                // configured world-units-per-tick speed, lift while traversing,
                // then settle vertically onto the scanned ground target.
                val position = planted ?: footStepStarts[index] ?: destination
                val speed = SpiderConfig.legStepSpeed.get().coerceAtLeast(0.01)
                val offset = destination.subtract(position)
                val horizontalOffset = Vec3(offset.x, 0.0, offset.z)
                val horizontalDistance = horizontalOffset.length()
                val horizontalStep = if (horizontalDistance <= speed) {
                    Vec3(destination.x, position.y, destination.z)
                } else {
                    position.add(horizontalOffset.scale(speed / horizontalDistance))
                }
                val lift = if (horizontalDistance > 0.35 * scale) 0.35 * scale else 0.0
                val targetY = destination.y + lift
                val nextY = position.y + (targetY - position.y).coerceIn(-speed, speed)
                val stepping = Vec3(horizontalStep.x, nextY, horizontalStep.z)
                if (horizontalDistance <= speed && kotlin.math.abs(nextY - destination.y) <= speed) {
                    if (footStepTargetGrounded[index]) playFootstepSound(level, destination)
                    footPositions[index] = destination
                    footGrounded[index] = footStepTargetGrounded[index]
                    footStepStarts[index] = null
                    footStepTargets[index] = null
                    footStepProgress[index] = 0.0
                    footStepStoppedAt[index] = tickCount
                    destination
                } else {
                    footPositions[index] = stepping
                    stepping
                }
            } else {
                planted
            }
            val points = solveLeg(root, foot, right.scale(leg.side), bodyForward, leg.segmentLength * scale)
            if (variant == Variant.CAMO) {
                camoBlockUnder(level, foot)?.let { groundBlock ->
                    if (legBlockStates[index] != groundBlock) {
                        for (segmentIndex in 0 until 3) {
                            parts[index * 3 + segmentIndex].setBlockState(groundBlock)
                        }
                        legBlockStates[index] = groundBlock
                    }
                }
            }
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

    private fun canStartLegStep(
        index: Int,
        layouts: List<LegLayout>,
        planted: Vec3?,
        planned: Vec3,
        scale: Double,
        triggerRadius: Double,
        grounded: Boolean
    ): Boolean {
        if (!grounded) return true
        val crossPair = listOf(
            index - 2,
            index + 2,
            if (index % 2 == 0) index + 1 else index - 1
        ).filter { it in layouts.indices }
        val samePair = if (index % 2 == 0) {
            listOf(index - 1, index + 3)
        } else {
            listOf(index - 3, index + 1)
        }.filter { it in layouts.indices }

        // WALK's source gait keeps adjacent support feet planted and spaces
        // starts of diagonal partners by the configured one-tick cooldowns.
        if (crossPair.any { footStepTargets[it] != null }) return false
        if (crossPair.any {
                footGrounded[it] && footStepTargets[it] == null && tickCount - footStepStoppedAt[it] < 1
            }
        ) return false
        if (samePair.any {
                footStepTargets[it] != null && tickCount - footStepStartedAt[it] < 1
            }
        ) return false

        val wantsToMove = planted == null || planted.subtract(planned).horizontalDistance() > triggerRadius * scale
        val alreadyAtTarget = planted != null && planted.distanceToSqr(planned) < 0.01
        val otherSupport = footPositions.indices.any {
            it != index && footGrounded[it] && footPositions[it] != null && footStepTargets[it] == null
        }
        return wantsToMove && !alreadyAtTarget && (onGround() || otherSupport)
    }

    private fun solveLeg(root: Vec3, foot: Vec3, side: Vec3, bodyForward: Vec3, segmentLength: Double): List<Vec3> {
        val fallback = if (side.lengthSqr() > 1.0e-8) side.normalize() else Vec3(0.0, 0.0, 1.0)
        val forward = Vector3f(0f, 0f, 1f)
        val pivot = Quaternionf().rotationTo(
            forward,
            Vector3f(bodyForward.x.toFloat(), bodyForward.y.toFloat(), bodyForward.z.toFloat())
        )
        val legDirection = foot.subtract(root)
        val legOrientation = Quaternionf().rotationTo(
            forward,
            Vector3f(legDirection.x.toFloat(), legDirection.y.toFloat(), legDirection.z.toFloat())
        )
        val relativeRotation = Quaternionf(pivot).difference(legOrientation).getEulerAnglesYXZ(Vector3f())
        val straightening = Quaternionf(pivot).rotateYXZ(
            relativeRotation.y,
            relativeRotation.x + Math.toRadians(-80.0).toFloat(),
            0f
        )
        val initialDirection = Vector3f(forward).rotate(straightening)
        val segmentOffset = Vec3(
            initialDirection.x.toDouble(),
            initialDirection.y.toDouble(),
            initialDirection.z.toDouble()
        ).scale(segmentLength)
        val points = Array(4) { root }
        for (index in 1..3) {
            points[index] = points[index - 1].add(segmentOffset)
        }

        // Match the original KinematicChain FABRIK pass: pin the foot first,
        // pull the joints toward it, then pin the root and extend back outward.
        repeat(20) {
            points[3] = foot
            for (index in 2 downTo 1) {
                val offset = points[index].subtract(points[index + 1])
                val direction = if (offset.lengthSqr() > 1.0e-8) offset.normalize() else fallback
                points[index] = points[index + 1].add(direction.scale(segmentLength))
            }

            val firstOffset = root.subtract(points[1])
            val firstDirection = if (firstOffset.lengthSqr() > 1.0e-8) firstOffset.normalize() else fallback
            points[1] = root.subtract(firstDirection.scale(segmentLength))
            for (index in 2..3) {
                val offset = points[index].subtract(points[index - 1])
                val direction = if (offset.lengthSqr() > 1.0e-8) offset.normalize() else fallback
                points[index] = points[index - 1].add(direction.scale(segmentLength))
            }

            if (points[3].distanceToSqr(foot) < 0.01) return points.toList()
        }
        return points.toList()
    }

    private fun camoBlockUnder(level: ServerLevel, position: Vec3, maxDepth: Int = 3): BlockState? {
        val x = kotlin.math.floor(position.x).toInt()
        val z = kotlin.math.floor(position.z).toInt()
        val startY = kotlin.math.floor(position.y + 0.01).toInt()
        for (depth in 0..maxDepth) {
            val blockPos = BlockPos(x, startY - depth, z)
            val state = level.getBlockState(blockPos)
            if (!state.isAir && !state.getCollisionShape(level, blockPos).isEmpty) return state
        }
        return null
    }

    private fun playFootstepSound(level: ServerLevel, position: Vec3) {
        val configuredVolume = SpiderConfig.variantStepVolume.get().toFloat()
        val (sound, volume, pitch) = when (variant) {
            Variant.NETHERITE -> Triple(SoundEvents.NETHERITE_BLOCK_STEP, 0.3f, 1.0f)
            Variant.POISON -> Triple(SoundEvents.WART_BLOCK_STEP, configuredVolume, 0.9f)
            Variant.CAMO -> {
                val surface = camoBlockUnder(level, position)
                if (surface != null) {
                    val soundType = surface.soundType
                    Triple(soundType.stepSound, soundType.volume * configuredVolume, soundType.pitch)
                } else {
                    Triple(resolveSound(SpiderConfig.variantStepSound.get(), SoundEvents.NETHERITE_BLOCK_STEP), configuredVolume, 1.0f)
                }
            }
            Variant.HUNTER -> return
        }
        playSoundAt(level, position, sound, volume, pitch)
    }

    private fun playLandingSound(level: ServerLevel) {
        val configuredVolume = SpiderConfig.variantLandVolume.get().toFloat()
        val position = Vec3(x, y, z)
        val (sound, volume, pitch) = when (variant) {
            Variant.NETHERITE -> Triple(SoundEvents.NETHERITE_BLOCK_FALL, 1.0f, 0.8f)
            Variant.POISON -> Triple(SoundEvents.WART_BLOCK_FALL, configuredVolume, 0.8f)
            Variant.CAMO -> {
                val surface = camoBlockUnder(level, position, 6)
                if (surface != null) {
                    val soundType = surface.soundType
                    Triple(soundType.fallSound, soundType.volume * configuredVolume, soundType.pitch)
                } else {
                    Triple(resolveSound(SpiderConfig.variantLandSound.get(), SoundEvents.NETHERITE_BLOCK_FALL), configuredVolume, 0.8f)
                }
            }
            Variant.HUNTER -> Triple(
                resolveSound(SpiderConfig.variantLandSound.get(), SoundEvents.NETHERITE_BLOCK_FALL),
                configuredVolume,
                0.8f
            )
        }
        playSoundAt(level, position, sound, volume, pitch)
    }

    private fun resolveSound(id: String, fallback: SoundEvent): SoundEvent {
        val location = ResourceLocation.tryParse(id) ?: return fallback
        return BuiltInRegistries.SOUND_EVENT.getOptional(location).orElse(fallback)
    }

    private fun playSoundAt(level: ServerLevel, position: Vec3, sound: SoundEvent, volume: Float, pitch: Float) {
        level.playSound(
            null,
            BlockPos.containing(position.x, position.y, position.z),
            sound,
            SoundSource.HOSTILE,
            volume,
            pitch
        )
    }

    private fun probeGround(level: ServerLevel, planned: Vec3, scale: Double): Pair<Vec3, Boolean> {
        fun raycast(x: Double, z: Double): Vec3? {
            val hit = level.clip(
                ClipContext(
                    Vec3(x, planned.y + 1.76 * scale, z),
                    Vec3(x, planned.y - 2.09 * scale, z),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    this
                )
            )
            return if (hit.type == HitResult.Type.BLOCK) hit.location.add(0.0, 0.025 * scale, 0.0) else null
        }

        val main = raycast(planned.x, planned.z)
        if (main != null && main.y in (planned.y - 0.24 * scale)..(planned.y + 1.5 * scale)) return main to true

        // The original scans the center and neighboring block edges when the
        // direct ray finds no usable surface, helping legs land on stairs and ledges.
        val margin = 0.125 * scale
        val minX = kotlin.math.floor(planned.x) - margin
        val maxX = kotlin.math.ceil(planned.x) + margin
        val minZ = kotlin.math.floor(planned.z) - margin
        val maxZ = kotlin.math.ceil(planned.z) + margin
        val xs = listOf(minX, planned.x, maxX)
        val zs = listOf(minZ, planned.z, maxZ)
        val candidates = buildList {
            for (x in xs) for (z in zs) raycast(x, z)?.let(::add)
            if (main != null) add(main)
        }.filter { candidate ->
            candidate.y >= planned.y - 1.6 * scale &&
                candidate.subtract(planned).horizontalDistance() <= 1.2 * scale
        }

        val yaw = Math.toRadians(yRot.toDouble())
        val lookAhead = planned.add(-sin(yaw) * scale, 0.0, cos(yaw) * scale)
        val aheadPos = BlockPos.containing(lookAhead.x, lookAhead.y, lookAhead.z)
        val obstructed = !level.getBlockState(aheadPos).getCollisionShape(level, aheadPos).isEmpty
        val preferred = if (obstructed) lookAhead.add(0.0, 0.5 * scale, 0.0) else lookAhead
        val ground = candidates.minByOrNull { it.distanceToSqr(preferred) }
        return if (ground != null) ground to true else planned to false
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

    override fun remove(reason: Entity.RemovalReason) {
        if (!level().isClientSide) {
            if (reason == Entity.RemovalReason.KILLED || health <= 0.0f) {
                rollTrophy(level() as ServerLevel)
            } else if (!reason.shouldDestroy()) {
                SpiderSpawnManager.onUnexpectedRemoval(this, reason)
            }
            cleanup()
        }
        super.remove(reason)
    }

    override fun die(source: DamageSource) {
        if (!level().isClientSide) rollTrophy(level() as ServerLevel)
        cleanup()
        super.die(source)
    }

    private fun rollTrophy(serverLevel: ServerLevel) {
        if (trophyRolled) return
        trophyRolled = true
        val drop = if (enraged) net.minecraft.world.item.Items.NETHERITE_BLOCK else net.minecraft.world.item.Items.NETHERITE_INGOT
        if (enraged || random.nextDouble() < SpiderConfig.netheriteDropChance.get()) {
            val dropY = SafeGroundFinder.findFloorBelow(serverLevel, x, y, z, 16) ?: y
            val trophy = net.minecraft.world.entity.item.ItemEntity(
                serverLevel, x, dropY + 0.25, z, net.minecraft.world.item.ItemStack(drop)
            )
            trophy.setDefaultPickUpDelay()
            serverLevel.addFreshEntity(trophy)
        }
        (lastHurtByPlayer as? ServerPlayer)?.let { killer ->
            SpiderAdvancements.grant(killer, "slay")
            if (enraged) SpiderAdvancements.grant(killer, "slay_boss")
        }
        SpiderSpawnManager.killed(serverLevel.server)
    }

    internal fun trackedDisplayIds(): List<java.util.UUID> =
        buildList {
            parts.filter { it.isAlive }.mapTo(this) { it.uuid }
            bodyDisplay?.takeIf { it.isAlive }?.let { add(it.uuid) }
        }

    private fun cleanup() {
        parts.forEach { if (it.isAlive) it.discard() }
        parts.clear()
        bodyDisplay?.let { if (it.isAlive) it.discard() }
        bodyDisplay = null
        bossEvent.removeAllPlayers()
    }

    companion object {
        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 600.0)
            .add(Attributes.ARMOR, 0.0)
            .add(Attributes.ARMOR_TOUGHNESS, 0.0)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.ATTACK_DAMAGE, 12.0)
            .add(Attributes.FOLLOW_RANGE, 64.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.0)
    }
}
