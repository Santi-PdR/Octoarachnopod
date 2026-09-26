package com.heledron.spideranimation.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Ground queries used by natural patrol movement. Mirrors the reference mod's
 * dry, two-block-clearance spawn/patrol test.
 */
object SafeGroundFinder {
    fun findSafeY(level: ServerLevel, x: Double, z: Double, maxSearch: Int = 48): Double? {
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        if (!level.hasChunk(blockX shr 4, blockZ shr 4)) return null

        val topY = minOf(
            level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ),
            level.maxBuildHeight - 1
        )
        val bottomY = maxOf(topY - maxSearch, level.minBuildHeight)
        val groundPos = BlockPos.MutableBlockPos()
        val feetPos = BlockPos.MutableBlockPos()
        val headPos = BlockPos.MutableBlockPos()
        for (groundY in topY downTo bottomY) {
            groundPos.set(blockX, groundY, blockZ)
            if (!isDrySolidGround(level, groundPos)) continue
            feetPos.set(blockX, groundY + 1, blockZ)
            headPos.set(blockX, groundY + 2, blockZ)
            if (isPassable(level, feetPos) && isPassable(level, headPos)) {
                return (groundY + 1).toDouble()
            }
        }
        return null
    }

    private fun isDrySolidGround(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.getFluidState(pos).isEmpty) return false
        val state: BlockState = level.getBlockState(pos)
        return !state.isAir && state.isFaceSturdy(level, pos, Direction.UP)
    }

    private fun isPassable(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.getFluidState(pos).isEmpty) return false
        val state = level.getBlockState(pos)
        return state.isAir || state.getCollisionShape(level, pos).isEmpty
    }
}
