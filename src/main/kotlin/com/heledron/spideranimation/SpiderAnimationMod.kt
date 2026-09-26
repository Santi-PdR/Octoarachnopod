package com.heledron.spideranimation

import com.heledron.spideranimation.entity.SpiderMob
import net.minecraft.world.item.CreativeModeTabs
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent
import net.minecraftforge.event.entity.EntityAttributeCreationEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.event.TickEvent
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

@Mod(SpiderAnimationMod.MOD_ID)
class SpiderAnimationMod {
    init {
        val bus: IEventBus = FMLJavaModLoadingContext.get().modEventBus
        ModEntities.ENTITY_TYPES.register(bus)
        ModItems.ITEMS.register(bus)
        bus.addListener(::registerAttributes)
        bus.addListener(::addCreativeItems)
        MinecraftForge.EVENT_BUS.addListener(::onServerTick)
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SpiderConfig.SPEC)
    }

    private fun registerAttributes(event: EntityAttributeCreationEvent) {
        event.put(ModEntities.SPIDER.get(), SpiderMob.createAttributes())
    }

    private fun addCreativeItems(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == CreativeModeTabs.SPAWN_EGGS) event.accept(ModItems.SPIDER_SPAWN_EGG.get())
        if (event.tabKey == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(ModItems.SPIDER_TAMER.get())
    }

    private fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.END) SpiderSpawnManager.tick(event.server)
    }

    companion object {
        const val MOD_ID = "arachnomod"
    }
}
