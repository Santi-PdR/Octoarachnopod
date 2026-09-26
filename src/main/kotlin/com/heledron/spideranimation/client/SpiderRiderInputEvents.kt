package com.heledron.spideranimation.client

import com.heledron.spideranimation.SpiderAnimationMod
import com.heledron.spideranimation.SpiderNetwork
import com.heledron.spideranimation.entity.SpiderMob
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(
    modid = SpiderAnimationMod.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = [Dist.CLIENT]
)
object SpiderRiderInputEvents {
    @JvmStatic
    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (player.vehicle !is SpiderMob) return

        val options = minecraft.options
        val forward = (if (options.keyUp.isDown) 1 else 0) - (if (options.keyDown.isDown) 1 else 0)
        val strafe = (if (options.keyLeft.isDown) 1 else 0) - (if (options.keyRight.isDown) 1 else 0)
        SpiderNetwork.sendRiderInput(forward.toFloat(), strafe.toFloat())
    }
}
