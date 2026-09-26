package com.heledron.spideranimation

import com.heledron.spideranimation.entity.SpiderMob
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModEntities {
    val ENTITY_TYPES: DeferredRegister<EntityType<*>> =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SpiderAnimationMod.MOD_ID)
    val SPIDER: RegistryObject<EntityType<SpiderMob>> = ENTITY_TYPES.register("spider") {
        EntityType.Builder.of(::SpiderMob, MobCategory.MONSTER)
            .sized(2.0F, 2.0F)
            .clientTrackingRange(16)
            .build("arachnomod:spider")
    }
}
