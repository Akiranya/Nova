package xyz.xenondevs.nova.util

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.block.ShulkerBox
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.PistonHead
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.integration.customitems.CustomItemServiceManager
import xyz.xenondevs.nova.material.BlockNovaMaterial
import xyz.xenondevs.nova.tileentity.network.fluid.FluidType
import xyz.xenondevs.nova.util.reflection.ReflectionRegistry
import xyz.xenondevs.nova.world.block.BlockBreaking
import xyz.xenondevs.nova.world.block.BlockManager
import xyz.xenondevs.nova.world.block.context.BlockBreakContext
import xyz.xenondevs.nova.world.block.context.BlockPlaceContext
import xyz.xenondevs.nova.world.pos
import xyz.xenondevs.particle.ParticleEffect
import java.util.*
import kotlin.random.Random
import net.minecraft.world.item.context.BlockPlaceContext as MojangBlockPlaceContext

val Block.below: Block
    get() = location.subtract(0.0, 1.0, 0.0).block

val Block.hardness: Double
    get() = BlockManager.getBlock(pos)?.material?.hardness ?: type.hardness.toDouble()

val Block.breakTexture: Material
    get() = BlockManager.getBlock(pos)?.material?.breakParticles ?: type

fun Block.hasSameTypeBelow(): Boolean {
    return type == below.type
}

fun Block.remove(ctx: BlockBreakContext, playEffects: Boolean = true) {
    if (CustomItemServiceManager.removeBlock(this, playEffects))
        return
    if (BlockManager.removeBlock(ctx, playEffects))
        return
    
    if (playEffects) playBreakEffects()
    getMainHalf().type = Material.AIR
}

fun Block.getAllDrops(ctx: BlockBreakContext): List<ItemStack> {
    val tool = ctx.item
    CustomItemServiceManager.getDrops(this, tool)?.let { return it }
    
    val novaBlockState = BlockManager.getBlock(pos)
    if (novaBlockState != null)
        return novaBlockState.material.novaBlock.getDrops(novaBlockState, ctx)
    
    val drops = ArrayList<ItemStack>()
    val state = state
    if (state is Chest) {
        drops += state.blockInventory.contents.filterNotNull()
        state.blockInventory.clear()
    } else if (state is Container && state !is ShulkerBox) {
        drops += state.inventory.contents.filterNotNull()
        state.inventory.clear()
    }
    
    val block = getMainHalf()
    drops += block.getDrops(tool)
    
    return drops.filterNot { it.type.isAir }
}

private fun Block.getMainHalf(): Block {
    val data = blockData
    if (data is Bisected) {
        if (data.half == Bisected.Half.TOP) {
            return location.subtract(0.0, 1.0, 0.0).block
        }
    } else if (data is Bed) {
        if (data.part == Bed.Part.FOOT) {
            return location.advance(data.facing).block
        }
    } else if (data is PistonHead) {
        return location.advance(data.facing.oppositeFace).block
    }
    
    return this
}

fun Block.playBreakEffects() {
    showBreakParticles()
    playBreakSound()
}

fun Block.showBreakParticles() {
    type.showBreakParticles(this.location)
}

fun Material.showBreakParticles(location: Location) {
    particleBuilder(ParticleEffect.BLOCK_CRACK, location.add(0.5, 0.5, 0.5)) {
        texture(this@showBreakParticles)
        offset(0.2, 0.2, 0.2)
        amount(50)
    }.display()
}

fun Block.playBreakSound() {
    val breakSound = blockData.soundGroup.breakSound
    world.playSound(location, breakSound, 1f, Random.nextDouble(0.8, 0.95).toFloat())
}

fun Block.setBreakStage(entityId: Int, state: Int) {
    val novaBlockState = BlockManager.getBlock(pos)
    if (novaBlockState != null) {
        BlockBreaking.setBreakStage(pos, entityId, state)
    } else {
        sendDestructionPacket(entityId, state)
    }
}

fun Block.sendDestructionPacket(entityId: Int, stage: Int) {
    val packet = ClientboundBlockDestructionPacket(entityId, location.blockPos, stage)
    
    chunk.getSurroundingChunks(1, true)
        .flatMap { it.entities.toList() }
        .filterIsInstance<Player>()
        .forEach { it.send(packet) }
}

fun Block.place(ctx: BlockPlaceContext, playEffects: Boolean = true): Boolean {
    val item = ctx.item
    val novaMaterial = item.novaMaterial
    if (novaMaterial is BlockNovaMaterial) {
        BlockManager.placeBlock(novaMaterial, ctx, playEffects)
        return true
    }
    
    if (CustomItemServiceManager.placeItem(item, ctx.pos.location, playEffects))
        return true
    
    if (item.type.isBlock) {
        val fakePlayer = EntityUtils.createFakePlayer(ctx.sourceLocation ?: location, UUID.randomUUID(), "")
        return placeVanilla(fakePlayer, item)
    }
    
    return false
}

fun Block.placeVanilla(player: ServerPlayer, itemStack: ItemStack, playEffects: Boolean = true): Boolean {
    val location = location
    val nmsStack = itemStack.nmsStack
    val blockItem = nmsStack.item as BlockItem
    val result = blockItem.place(MojangBlockPlaceContext(UseOnContext(
        world.serverLevel,
        player,
        InteractionHand.MAIN_HAND,
        nmsStack,
        BlockHitResult(
            Vec3(location.x, location.y, location.z),
            Direction.UP,
            BlockPos(location.blockX, location.blockY, location.blockZ),
            false
        )
    )))
    
    if (result.consumesAction()) {
        setBlockEntityDataFromItemStack(itemStack)
        if (playEffects) itemStack.type.playPlaceSoundEffect(location)
        return true
    }
    return false
}

fun Block.setBlockEntityDataFromItemStack(itemStack: ItemStack) {
    val itemTag = CompoundTag()
    ReflectionRegistry.CB_CRAFT_META_APPLY_TO_METHOD.invoke(itemStack.itemMeta, itemTag)
    
    val tileEntityTag = itemTag.getCompound("BlockEntityTag")?.let { if (it.isEmpty) itemTag else it }
    if (tileEntityTag != null) {
        val world = this.world.serverLevel
        world.getBlockEntity(BlockPos(x, y, z), true)?.load(tileEntityTag)
    }
}

fun Block.isSourceFluid(): Boolean {
    return blockData is Levelled && (blockData as Levelled).level == 0
}

val Block.sourceFluidType: FluidType?
    get() {
        val blockData = blockData
        if (blockData is Levelled && blockData.level == 0) {
            return when (type) {
                Material.WATER, Material.BUBBLE_COLUMN -> FluidType.WATER
                Material.LAVA -> FluidType.LAVA
                else -> null
            }
        }
        
        return null
    }
