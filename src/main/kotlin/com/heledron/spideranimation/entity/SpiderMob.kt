package com.heledron.spideranimation.entity

import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.level.Level

class SpiderMob(type: EntityType<out SpiderMob>, level: Level) : Monster(type, level) {
    override fun registerGoals() = Unit

    companion object {
        fun createAttributes(): AttributeSupplier.Builder = Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 600.0)
            .add(Attributes.ARMOR, 16.0)
            .add(Attributes.MOVEMENT_SPEED, 0.35)
            .add(Attributes.ATTACK_DAMAGE, 12.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.8)
    }
}
