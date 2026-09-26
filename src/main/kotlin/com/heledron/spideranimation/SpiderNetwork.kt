package com.heledron.spideranimation

import com.heledron.spideranimation.entity.SpiderMob
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel
import java.util.function.Supplier

object SpiderNetwork {
    private const val PROTOCOL = "1"
    private val channel: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation(SpiderAnimationMod.MOD_ID, "main"),
        { PROTOCOL },
        { version -> version == PROTOCOL },
        { version -> version == PROTOCOL }
    )

    fun register() {
        channel.registerMessage(
            0,
            RiderInputMessage::class.java,
            { message, buffer -> message.encode(buffer) },
            { buffer -> RiderInputMessage.decode(buffer) },
            { message, context -> message.handle(context) }
        )
    }

    fun sendRiderInput(forward: Float, strafe: Float) {
        channel.sendToServer(RiderInputMessage(forward, strafe))
    }

    data class RiderInputMessage(val forward: Float, val strafe: Float) {
        fun encode(buffer: FriendlyByteBuf) {
            buffer.writeFloat(forward.coerceIn(-1f, 1f))
            buffer.writeFloat(strafe.coerceIn(-1f, 1f))
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                val spider = player.vehicle as? SpiderMob ?: return@enqueueWork
                spider.setRiderInput(player.uuid, forward, strafe)
            }
            context.packetHandled = true
        }

        companion object {
            fun decode(buffer: FriendlyByteBuf) = RiderInputMessage(
                buffer.readFloat().coerceIn(-1f, 1f),
                buffer.readFloat().coerceIn(-1f, 1f)
            )
        }
    }
}
