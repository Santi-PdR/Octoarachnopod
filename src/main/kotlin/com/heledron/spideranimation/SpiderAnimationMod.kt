package com.heledron.spideranimation

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import com.heledron.spideranimation.entity.SpiderMob
import net.minecraft.world.item.CreativeModeTabs
import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent
import net.minecraftforge.event.entity.EntityAttributeCreationEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraft.commands.Commands
import net.minecraft.world.phys.AABB
import net.minecraft.network.chat.Component
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
        MinecraftForge.EVENT_BUS.addListener(::onRegisterCommands)
        SpiderConfig.migrateConfigFile(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get())
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SpiderConfig.SPEC)
    }

    private fun registerAttributes(event: EntityAttributeCreationEvent) {
        event.put(ModEntities.SPIDER.get(), SpiderMob.createAttributes().build())
    }

    private fun addCreativeItems(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == CreativeModeTabs.SPAWN_EGGS) event.accept(ModItems.SPIDER_SPAWN_EGG.get())
        if (event.tabKey == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(ModItems.SPIDER_TAMER.get())
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("spider")
                .then(Commands.literal("newinstance").executes { context ->
                    val player = context.source.playerOrException
                    if (!player.isCreative) {
                        context.source.sendFailure(Component.literal("/spider newinstance is creative-mode only."))
                        0
                    } else {
                        spawnPersonalSpider(player, 1.0)
                        context.source.sendSuccess({ Component.literal("Spawned your personal spider. Use /spider size or /spider release.") }, false)
                        1
                    }
                }.then(Commands.argument("size", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.3, 20.0)).executes { context ->
                    val player = context.source.playerOrException
                    if (!player.isCreative) {
                        context.source.sendFailure(Component.literal("/spider newinstance is creative-mode only."))
                        0
                    } else {
                        spawnPersonalSpider(player, com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "size"))
                        context.source.sendSuccess({ Component.literal("Spawned your personal spider.") }, false)
                        1
                    }
                }))
                .then(Commands.literal("release").executes { context ->
                    val player = context.source.playerOrException
                    val ownedSpiders = personalSpiders(player)
                    ownedSpiders.forEach { it.discard() }
                    val removed = ownedSpiders.size
                    if (removed > 0) {
                        context.source.sendSuccess({ Component.literal("Spider released.") }, false)
                        1
                    } else {
                        context.source.sendFailure(Component.literal("You have no personal spider. Use /spider newinstance first."))
                        0
                    }
                })
                .then(Commands.literal("size").then(Commands.argument("size", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.3, 20.0)).executes { context ->
                    val player = context.source.playerOrException
                    val spider = personalSpiders(player).firstOrNull()
                    if (spider == null) {
                        context.source.sendFailure(Component.literal("You have no personal spider. Use /spider newinstance first."))
                        0
                    } else {
                        val size = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "size")
                        spider.resizePersonal(size)
                        context.source.sendSuccess({ Component.literal("Spider size set to $size.") }, false)
                        1
                    }
                }))
                .then(Commands.literal("chasedistance").requires { it.hasPermission(2) }
                    .then(Commands.argument("blocks", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(8.0, 256.0)).executes { context ->
                        val blocks = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "blocks")
                        SpiderConfig.chaseDistance.set(blocks)
                        SpiderConfig.SPEC.save()
                        context.source.sendSuccess({ Component.literal("Spider chase distance set to $blocks blocks (saved to config).") }, true)
                        1
                    }))
                .then(configCommand())
        )
    }

    private fun configCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        val root = Commands.literal("config").requires { it.hasPermission(2) }
        SpiderConfig.commandEntries.forEach { (path, entry) ->
            root.then(
                Commands.literal(path)
                    .then(Commands.literal("get").executes { context ->
                        context.source.sendSuccess(
                            { Component.literal("Spider config '$path' is ${entry.get()}.") },
                            false
                        )
                        1
                    })
                    .then(Commands.literal("set")
                        .then(Commands.argument("value", StringArgumentType.word()).executes { context ->
                            val raw = StringArgumentType.getString(context, "value")
                            val error = runCatching {
                                setConfigValue(entry, raw)
                                SpiderConfig.SPEC.save()
                                SpiderSpawnManager.onConfigSet(path)
                            }.exceptionOrNull()
                            if (error != null) {
                                context.source.sendFailure(Component.literal("Invalid value for '$path': ${error.message ?: raw}"))
                                0
                            } else {
                                context.source.sendSuccess(
                                    { Component.literal("Spider config '$path' set to ${entry.get()} (saved).") },
                                    true
                                )
                                1
                            }
                        }))
            )
        }
        return root
    }

    @Suppress("UNCHECKED_CAST")
    private fun setConfigValue(entry: ForgeConfigSpec.ConfigValue<*>, raw: String) {
        when (entry.get()) {
            is Boolean -> (entry as ForgeConfigSpec.ConfigValue<Boolean>).set(raw.toBooleanStrict())
            is Int -> (entry as ForgeConfigSpec.ConfigValue<Int>).set(raw.toInt())
            is Double -> (entry as ForgeConfigSpec.ConfigValue<Double>).set(raw.toDouble())
            is String -> (entry as ForgeConfigSpec.ConfigValue<String>).set(raw)
            else -> throw IllegalArgumentException("Unsupported config value type.")
        }
    }

    private fun personalSpiders(player: net.minecraft.server.level.ServerPlayer): List<SpiderMob> {
        val searchBox = player.boundingBox.inflate(128.0)
        return player.server.allLevels
            .flatMap { level -> level.getEntitiesOfClass(SpiderMob::class.java, searchBox) }
            .filter { it.personalOwner == player.uuid }
    }

    private fun spawnPersonalSpider(player: net.minecraft.server.level.ServerPlayer, size: Double) {
        personalSpiders(player).forEach { it.discard() }
        val level = player.serverLevel()
        val spider = ModEntities.SPIDER.get().create(level) ?: return
        spider.moveTo(player.x, player.y + 1.0, player.z, player.yRot, 0f)
        spider.makePersonal(player.uuid, size)
        level.addFreshEntity(spider)
    }

    private fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.END) SpiderSpawnManager.tick(event.server)
    }

    companion object {
        const val MOD_ID = "arachnomod"
    }
}
