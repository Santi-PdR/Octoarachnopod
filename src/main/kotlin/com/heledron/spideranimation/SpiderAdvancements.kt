package com.heledron.spideranimation

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

object SpiderAdvancements {
    fun grant(player: ServerPlayer, path: String) {
        val advancement = player.server.advancements.getAdvancement(ResourceLocation(SpiderAnimationMod.MOD_ID, path)) ?: return
        player.advancements.award(advancement, "impossible")
    }
}
