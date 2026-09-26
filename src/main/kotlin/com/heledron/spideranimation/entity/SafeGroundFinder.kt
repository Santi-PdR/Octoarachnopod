package com.heledron.spideranimation.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.tags.BlockTags
import com.heledron.spideranimation.SpiderConfig
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Ground queries used by natural patrol movement. Mirrors the reference mod's
 * dry, two-block-clearance spawn/patrol test.
 */
object SafeGroundFinder {
    data class Opening(
        val x: Double,
        val y: Double,
        val z: Double,
        val height: Int,
        val alongX: Boolean,
        val lo: Double,
        val hi: Double
    )
    fun findSafeY(level: ServerLevel, x: Double, z: Double, maxSearch: Int = SpiderConfig.spawnMaxVerticalSearch.get()): Double? {
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

    fun groundYAt(level: ServerLevel, x: Double, z: Double, referenceY: Double): Double? =
        if (level.dimensionType().hasCeiling()) findSafeYNear(level, x, z, referenceY) else findSafeY(level, x, z)

    fun findSafeYNear(level: ServerLevel, x: Double, z: Double, referenceY: Double, maxSearch: Int = SpiderConfig.spawnMaxVerticalSearch.get()): Double? {
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        if (!level.hasChunk(blockX shr 4, blockZ shr 4)) return null
        val referenceBlockY = kotlin.math.floor(referenceY).toInt()
        val topY = minOf(referenceBlockY + 16, level.maxBuildHeight - 1)
        val bottomY = maxOf(referenceBlockY - maxSearch, level.minBuildHeight)
        val groundPos = BlockPos.MutableBlockPos()
        val feetPos = BlockPos.MutableBlockPos()
        val headPos = BlockPos.MutableBlockPos()
        for (groundY in topY downTo bottomY) {
            groundPos.set(blockX, groundY, blockZ)
            if (!isDrySolidGround(level, groundPos)) continue
            feetPos.set(blockX, groundY + 1, blockZ)
            headPos.set(blockX, groundY + 2, blockZ)
            if (isPassable(level, feetPos) && isPassable(level, headPos)) return (groundY + 1).toDouble()
        }
        return null
    }

    fun findFloorBelow(level: ServerLevel, x: Double, y: Double, z: Double, maxDepth: Int = 96): Double? {
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        val startY = minOf(kotlin.math.floor(y).toInt(), level.maxBuildHeight - 1)
        val bottomY = maxOf(startY - maxDepth, level.minBuildHeight)
        val pos = BlockPos.MutableBlockPos()
        for (groundY in startY downTo bottomY) {
            pos.set(blockX, groundY, blockZ)
            val state = level.getBlockState(pos)
            if (!state.isAir && !state.getCollisionShape(level, pos).isEmpty) {
                return (groundY + 1).toDouble()
            }
        }
        return null
    }

    fun waterDepthAbove(level: ServerLevel, x: Double, floorY: Double, z: Double, maxDepth: Int = 64): Double {
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        var y = kotlin.math.floor(floorY).toInt()
        val topY = minOf(y + maxDepth, level.maxBuildHeight - 1)
        val pos = BlockPos.MutableBlockPos()
        var depth = 0
        while (y <= topY) {
            pos.set(blockX, y, blockZ)
            if (!level.getFluidState(pos).`is`(FluidTags.WATER)) break
            depth++
            y++
        }
        return depth.toDouble()
    }

    fun roomAt(level: ServerLevel, x: Double, groundY: Double, z: Double): Double? {
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        if (!level.hasChunk(blockX shr 4, blockZ shr 4)) return null
        val startY = kotlin.math.floor(groundY + 0.5).toInt()
        val pos = BlockPos.MutableBlockPos()
        var floorTop = Int.MIN_VALUE
        for (offset in 0 downTo -4) {
            pos.set(blockX, startY + offset, blockZ)
            if (isBodyBlocking(level, pos)) {
                floorTop = pos.y + 1
                break
            }
        }
        if (floorTop == Int.MIN_VALUE || kotlin.math.abs(groundY - floorTop) > 1.5) return null
        val walledX = isBodyBlocking(level, BlockPos(blockX + 1, floorTop, blockZ)) && isBodyBlocking(level, BlockPos(blockX - 1, floorTop, blockZ))
        val walledZ = isBodyBlocking(level, BlockPos(blockX, floorTop, blockZ + 1)) && isBodyBlocking(level, BlockPos(blockX, floorTop, blockZ - 1))
        if (!walledX && !walledZ) return null
        var headroom = 4
        for (dy in 0..3) {
            if (isBodyBlocking(level, BlockPos(blockX, floorTop + dy, blockZ))) {
                headroom = dy
                break
            }
        }
        return if (headroom >= 1 && headroom < 4) headroom.toDouble() else null
    }

    private fun isBodyBlocking(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return !state.isAir && !state.`is`(BlockTags.DOORS) && !state.`is`(BlockTags.TRAPDOORS) &&
            !state.`is`(BlockTags.FENCE_GATES) && !state.getCollisionShape(level, pos).isEmpty
    }

    fun openingHeight(level: ServerLevel, x: Double, groundY: Double, z: Double): Int {
        val blockX = kotlin.math.floor(x).toInt()
        val blockZ = kotlin.math.floor(z).toInt()
        val feetY = kotlin.math.floor(groundY).toInt()
        val floorPos = BlockPos(blockX, feetY - 1, blockZ)
        if (!level.getBlockState(floorPos).isFaceSturdy(level, floorPos, Direction.UP)) return 0
        val feetPos = BlockPos(blockX, feetY, blockZ)
        if (!isDoorway(level, feetPos)) return 0
        return if (isDoorway(level, BlockPos(blockX, feetY + 1, blockZ))) 2 else 1
    }

    fun collectOpenings(
        level: ServerLevel, hitX: Double, hitZ: Double, groundY: Double,
        targetX: Double, targetZ: Double, radius: Int = 7, minHeight: Int = 1, limit: Int = 12
    ): List<Opening> {
        val baseX = kotlin.math.floor(hitX).toInt()
        val baseZ = kotlin.math.floor(hitZ).toInt()
        val candidates = mutableListOf<Pair<Double, Opening>>()
        for (dx in -radius..radius) for (dz in -radius..radius) {
            val blockX = baseX + dx
            val blockZ = baseZ + dz
            if (!level.hasChunk(blockX shr 4, blockZ shr 4)) continue
            val candidateX = blockX + 0.5
            val candidateZ = blockZ + 0.5
            for (verticalOffset in -1..1) {
                val candidateY = groundY + verticalOffset
                val height = openingHeight(level, candidateX, candidateY, candidateZ)
                if (height < minHeight) continue
                val feetY = kotlin.math.floor(candidateY).toInt()
                val axis = pinchAxis(level, blockX, feetY, blockZ)
                if (axis == 0) continue
                val alongX = axis == 2
                val (lo, hi) = passageExtent(level, candidateX, candidateY, candidateZ, height, alongX)
                val opening = Opening(candidateX, candidateY, candidateZ, height, alongX, lo, hi)
                val distance = (targetX - candidateX) * (targetX - candidateX) + (targetZ - candidateZ) * (targetZ - candidateZ)
                candidates += distance to opening
                break
            }
        }
        return candidates.sortedBy { it.first }.distinctBy { Triple(it.second.x, it.second.y, it.second.z) }
            .take(limit).map { it.second }
    }

    private fun passageExtent(level: ServerLevel, x: Double, y: Double, z: Double, height: Int, alongX: Boolean): Pair<Double, Double> {
        val origin = if (alongX) x else z
        var lo = origin
        var hi = origin
        val expectedAxis = if (alongX) 2 else 1
        for (direction in intArrayOf(-1, 1)) {
            for (step in 1..32) {
                val candidateX = if (alongX) x + direction * step else x
                val candidateZ = if (alongX) z else z + direction * step
                if (openingHeight(level, candidateX, y, candidateZ) < height) break
                val axis = pinchAxis(level, kotlin.math.floor(candidateX).toInt(), kotlin.math.floor(y).toInt(), kotlin.math.floor(candidateZ).toInt())
                if (axis != expectedAxis) break
                val along = if (alongX) candidateX else candidateZ
                if (direction < 0) lo = along else hi = along
            }
        }
        return lo to hi
    }

    private fun pinchAxis(level: ServerLevel, x: Int, feetY: Int, z: Int): Int {
        val blockedX = isBodyBlocking(level, BlockPos(x + 1, feetY, z)) && isBodyBlocking(level, BlockPos(x - 1, feetY, z))
        if (blockedX) return 1
        val blockedZ = isBodyBlocking(level, BlockPos(x, feetY, z + 1)) && isBodyBlocking(level, BlockPos(x, feetY, z - 1))
        return if (blockedZ) 2 else 0
    }

    private fun isDoorway(level: ServerLevel, pos: BlockPos): Boolean {
        if (isPassable(level, pos)) return true
        if (!level.getFluidState(pos).isEmpty) return false
        val state = level.getBlockState(pos)
        return state.`is`(BlockTags.DOORS) || state.`is`(BlockTags.TRAPDOORS) || state.`is`(BlockTags.FENCE_GATES)
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
