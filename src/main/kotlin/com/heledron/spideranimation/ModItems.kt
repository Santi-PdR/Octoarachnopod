package com.heledron.spideranimation

import net.minecraft.world.item.Item
import net.minecraft.world.item.SpawnEggItem
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModItems {
    val ITEMS: DeferredRegister<Item> =
        DeferredRegister.create(ForgeRegistries.ITEMS, SpiderAnimationMod.MOD_ID)
    val SPIDER_SPAWN_EGG: RegistryObject<SpawnEggItem> = ITEMS.register("spider_spawn_egg") {
        SpawnEggItem(ModEntities.SPIDER, 0x28252C, 0xA37A28, Item.Properties())
    }
    val SPIDER_TAMER: RegistryObject<Item> = ITEMS.register("spider_tamer") {
        Item(Item.Properties().stacksTo(1))
    }
}
