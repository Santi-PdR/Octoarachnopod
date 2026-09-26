package com.heledron.spideranimation.entity

import com.heledron.spideranimation.ModItems
import com.heledron.spideranimation.SpiderConfig
import com.heledron.spideranimation.SpiderSpawnManager
import com.heledron.spideranimation.SpiderAdvancements
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
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.entity.Display.BlockDisplay
import net.minecraft.world.entity.EntityType.BLOCK_DISPLAY
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
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

    var variant: Variant = Variant.NETHERITE
        private set
    var tamed = false
        private set
    var enraged = false
        private set
    var naturalEncounter = false
    private var attackTimer = 0
    private var alertTimer = 0
    private var phase = 0.0
    private var currentScale = 1.0
    private var wanderTicks = 0
    private var wanderAngle = 0.0
    private val parts = mutableListOf<BlockDisplay>()
    private val bossEvent = ServerBossEvent(
        Component.literal("Netherite Octoarachnopod"),
        BossEvent.BossBarColor.PURPLE,
        BossEvent.BossBarOverlay.PROGRESS
    )

    init {
        setNoGravity(true)
        noPhysics = true
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

    override fun tick() {
        super.tick()
        if (level().isClientSide) return
        val serverLevel = level() as? ServerLevel ?: return
        ensureModel(serverLevel)
        if (tickCount % 20 == 0) grantEncounterAdvancements(serverLevel)
        attackTimer = (attackTimer - 1).coerceAtLeast(0)

        val rider = firstPassenger as? Player
        if (tamed && rider != null) {
            setYRot(rider.yRot)
            val forward = rider.zza
            val strafe = rider.xxa
            val yaw = Math.toRadians(rider.yRot.toDouble())
            val speed = if (rider.isSprinting) 0.42 else 0.24
            setPos(x + (-sin(yaw) * forward + cos(yaw) * strafe) * speed, y, z + (cos(yaw) * forward + sin(yaw) * strafe) * speed)
            setTarget(null)
        } else if (!tamed) {
            hunt(serverLevel)
        }

        phase += if (deltaMovement.horizontalDistance() > 0.015) 0.45 else 0.08
        updateModel()
        bossEvent.progress = (health / maxHealth).coerceIn(0f, 1f)
    }

    private fun hunt(serverLevel: ServerLevel) {
        val night = serverLevel.dayTime % 24000L in 13000L..23000L
        val target = serverLevel.players()
            .filter { it.isAlive && (!SpiderConfig.onlyAtNight.get() || night) }
            .minByOrNull { it.distanceToSqr(this) }
        if (target == null || distanceTo(target) > SpiderConfig.chaseDistance.get() * SpiderConfig.chaseExitMultiplier.get()) {
            setTarget(null)
            alertTimer = 0
            currentScale += (1.0 - currentScale) * 0.03
            wander(serverLevel)
            return
        }

        setTarget(target)
        val distance = distanceTo(target)
        val near = SpiderConfig.sizeNearDistance.get()
        val far = max(near + 0.01, SpiderConfig.sizeFarDistance.get())
        val desiredScale = SpiderConfig.minSize.get() +
            (SpiderConfig.maxSize.get() - SpiderConfig.minSize.get()) *
            ((distance - near) / (far - near)).coerceIn(0.0, 1.0)
        val adjustment = if (desiredScale > currentScale) SpiderConfig.growPercentPerTick.get() / 100.0 else SpiderConfig.shrinkPercentPerTick.get() / 100.0\n        currentScale += (desiredScale - currentScale) * adjustment

        val dx = target.x - x
        val dz = target.z - z
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        setYRot(yaw)
        yRotO = yaw
        if (alertTimer == 0) alertTimer = SpiderConfig.alertReactionTicks.get()
        if (alertTimer > 0) {
            alertTimer--
            return
        }

        val scaleSpeed = 1.0 + ((currentScale - 1.0) / max(1.0, SpiderConfig.maxSize.get() - 1.0)) * 7.0
        val variantSpeed = if (variant == Variant.HUNTER) SpiderConfig.hunterSpeedMultiplier.get() else 1.0\n        val enragedSpeed = if (enraged) SpiderConfig.enragedSpeedMultiplier.get() else 1.0\n        val speed = SpiderConfig.chaseSpeed.get() / 20.0 * scaleSpeed * variantSpeed * enragedSpeed
        val direction = Vec3(dx, 0.0, dz).normalize()
        setPos(x + direction.x * speed, y, z + direction.z * speed)

        if (distance <= 3.5 && attackTimer == 0) {
            val damage = when (variant) {
                Variant.NETHERITE -> if (enraged) 24f else 12f
                Variant.POISON -> 8f
                Variant.HUNTER -> 10f
                Variant.CAMO -> 8f
            }
            if (target.hurt(damageSources().mobAttack(this), damage)) {
                if (variant == Variant.POISON) target.addEffect(MobEffectInstance(MobEffects.POISON, (SpiderConfig.poisonEffectSeconds.get() * 20).toInt(), 1))
                if (variant == Variant.HUNTER) target.addEffect(MobEffectInstance(MobEffects.BLINDNESS, (SpiderConfig.hunterBlindnessSeconds.get() * 20).toInt(), 0))
                attackTimer = SpiderConfig.attackCooldown.get()
            }
        }
    }

    private fun wander(@Suppress("UNUSED_PARAMETER") serverLevel: ServerLevel) {
        if (random.nextInt(100) == 0) wanderAngle = random.nextDouble() * Math.PI * 2.0
        val speed = 0.04
        setYRot(Math.toDegrees(wanderAngle).toFloat())
        if (++wanderTicks % 120 == 0) wanderAngle += (random.nextDouble() - 0.5) * 1.5
        setPos(x - sin(wanderAngle) * speed, y, z + cos(wanderAngle) * speed)
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

    private fun ensureModel(level: ServerLevel) {
        if (parts.isNotEmpty()) return
        repeat(19) { index ->
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

    private fun stateFor(index: Int): BlockState = when (variant) {
        Variant.CAMO -> if (index < 3) Blocks.MOSS_BLOCK.defaultBlockState() else Blocks.MOSSY_COBBLESTONE.defaultBlockState()
        Variant.POISON -> if (index < 3) Blocks.SCULK.defaultBlockState() else Blocks.DEEPSLATE_BRICKS.defaultBlockState()
        Variant.HUNTER -> Blocks.BLACKSTONE.defaultBlockState()
        Variant.NETHERITE -> if (index < 3) Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState() else Blocks.POLISHED_BLACKSTONE.defaultBlockState()
    }

    private fun updateModel() {
        if (parts.size != 19) return
        val scale = currentScale.toFloat()
        val yaw = Math.toRadians(yRot.toDouble())
        val forward = Vec3(-sin(yaw), 0.0, cos(yaw))
        val center = Vec3(x, y + 0.8 * currentScale + sin(phase) * 0.08 * currentScale, z)
        box(parts[0], center, Vector3f(1.2f * scale, 0.58f * scale, 1.4f * scale))
        box(parts[1], center.add(forward.scale(-0.62 * currentScale)), Vector3f(0.85f * scale, 0.5f * scale, 0.9f * scale))
        box(parts[2], center.add(forward.scale(0.62 * currentScale)), Vector3f(0.62f * scale, 0.48f * scale, 0.65f * scale))
        for (i in 0 until 8) {
            val side = if (i % 2 == 0) -1.0 else 1.0
            val row = (i / 2 - 1) * 0.32 * currentScale
            val stride = sin(phase + i * 1.5) * 0.25 * currentScale
            val hip = center.add(forward.scale(row)).add(0.0, -0.16 * currentScale, side * 0.38 * currentScale)
            val knee = center.add(forward.scale(row + (if (i < 4) 0.48 else -0.35) * currentScale))
                .add(0.12 * currentScale, -0.3 * currentScale, side * 0.92 * currentScale)
            val foot = knee.add(0.0, -0.48 * currentScale + max(0.0, sin(phase + i * 1.5)) * 0.22 * currentScale, side * 0.72 * currentScale)
                .add(forward.scale(stride))
            segment(parts[3 + i * 2], hip, knee, 0.15f * scale)
            segment(parts[4 + i * 2], knee, foot, 0.12f * scale)
        }
    }

    private fun box(display: BlockDisplay, center: Vec3, scale: Vector3f) {
        display.setPos(center.x, center.y, center.z)
        display.setTransformation(Transformation(Vector3f(-0.5f, -0.5f, -0.5f), Quaternionf(), scale, Quaternionf()))
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
