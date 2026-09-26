package com.heledron.spideranimation.client

import com.heledron.spideranimation.ModEntities
import com.heledron.spideranimation.SpiderAnimationMod
import com.heledron.spideranimation.entity.SpiderMob
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = SpiderAnimationMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object SpiderClientEvents {
    @JvmStatic
    @SubscribeEvent
    fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(ModEntities.SPIDER.get(), ::SpiderMobRenderer)
    }
}

private class SpiderMobRenderer(context: EntityRendererProvider.Context) : EntityRenderer<SpiderMob>(context) {
    override fun getTextureLocation(entity: SpiderMob): ResourceLocation =
        ResourceLocation("minecraft", "textures/atlas/blocks.png")
}
