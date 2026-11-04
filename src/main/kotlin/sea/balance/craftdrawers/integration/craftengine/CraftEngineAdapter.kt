package sea.balance.craftdrawers.integration.craftengine

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture
import net.momirealms.craftengine.bukkit.entity.furniture.element.ItemDisplayFurnitureElementConfig
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData
import net.momirealms.craftengine.core.util.Direction
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import org.bukkit.Chunk
import sea.balance.craftdrawers.domain.DrawerKey

class CraftEngineAdapter(
    private val namespace: String,
    itemPrefix: String,
    blockPrefix: String,
) {
    private val itemPrefix = normalizeDrawerPrefix(itemPrefix)
    private val blockPrefix = normalizeDrawerPrefix(blockPrefix)
    private val halfDrawers = mutableMapOf<DrawerKey, BukkitFurniture>()
    private val halfLayout = "wall_aligned"

    fun drawerType(block: Block): String? {
        legacyDrawerType(block)?.let { return it }
        return halfFurniture(block)?.let(::drawerType)
    }

    fun legacyDrawerType(block: Block): String? {
        if (!CraftEngineBlocks.isCustomBlock(block)) return null
        val id = CraftEngineBlocks.getCustomBlockState(block)?.owner()?.value()?.id()?.asString() ?: return null
        return drawerType(id)
    }

    private fun drawerType(id: String): String? {
        val path = id.removePrefix("$namespace:").takeIf { id.startsWith("$namespace:") } ?: return null
        return path.removePrefix(blockPrefix).takeIf { path.startsWith(blockPrefix) }
    }

    fun drawerType(furniture: BukkitFurniture): String? = drawerType(furniture.id().asString())
        ?.takeIf { "_half_" in it }

    fun facing(block: Block): String {
        val state = CraftEngineBlocks.getCustomBlockState(block)
            ?: return halfFurniture(block)?.let(::facing) ?: "north"
        val property = state.getProperty<Direction>("facing") ?: return "north"
        return state.get(property).name.lowercase()
    }

    // The unrotated model occupies local north (z = -0.5 .. 0).
    fun facing(furniture: BukkitFurniture): String = when (Math.floorMod(
        kotlin.math.round(furniture.location().yaw / 90f).toInt(), 4,
    )) {
        1 -> "east"
        2 -> "south"
        3 -> "west"
        else -> "north"
    }

    fun halfFurniture(block: Block): BukkitFurniture? {
        if (!block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) return null
        val key = block.key()
        halfDrawers[key]?.let { cached ->
            if (cached.isValid && cached.bukkitEntity().isValid && cached.location().block.key() == key) return cached
            halfDrawers.remove(key)
        }
        indexHalfDrawers(block.chunk)
        return halfDrawers[key]
    }

    fun indexHalfDrawers(chunk: Chunk) {
        chunk.entities.forEach { entity ->
            val furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity) ?: return@forEach
            if (furniture.isValid && drawerType(furniture) != null) halfDrawers[furniture.location().block.key()] = furniture
        }
    }

    fun forgetChunk(chunk: Chunk) {
        halfDrawers.keys.removeIf {
            it.worldId == chunk.world.uid && (it.x shr 4) == chunk.x && (it.z shr 4) == chunk.z
        }
    }

    fun forgetHalfDrawers() = halfDrawers.clear()

    fun supportsHalfLayout(type: String): Boolean = halfDrawerDefinition(type)?.getVariant(halfLayout) != null

    fun frontDepth(block: Block): Double {
        val furniture = halfFurniture(block)
        if (furniture != null) return frontDepth(furniture)
        val type = legacyDrawerType(block) ?: return 0.0
        return if ("_half_" in type && supportsHalfLayout(type)) 0.5 else 0.0
    }

    fun frontDepth(furniture: BukkitFurniture): Double {
        // New models carry their own 8/16 geometric offset. Older packs used
        // an element-position offset instead; retain matching labels until updated.
        if (furniture.config().getVariant(halfLayout) != null) return 0.5
        val element = furniture.currentVariant().elementConfigs()
            .filterIsInstance<ItemDisplayFurnitureElementConfig>().firstOrNull() ?: return 0.0
        val position = element.getPos(furniture)
        val origin = furniture.position()
        return when (facing(furniture)) {
            "east" -> origin.x - position.x
            "south" -> origin.z - position.z
            "west" -> position.x - origin.x
            else -> position.z - origin.z
        }.coerceIn(0.0, 0.5)
    }

    fun synchronizeHalfLayout(block: Block): Boolean {
        val old = halfFurniture(block) ?: return false
        val type = drawerType(old) ?: return false
        val definition = halfDrawerDefinition(type)?.takeIf { it.getVariant(halfLayout) != null } ?: return false
        if (old.config() === definition) {
            if (old.currentVariant().name() == halfLayout) return true
            return old.setVariant(halfLayout, false)
        }
        // CE snapshots hold their original definition. Resending that snapshot
        // alone cannot pick up a reloaded definition; recreate through the public API.
        val data = FurniturePersistentData.of(old.persistentData().copyTag()).apply { setVariant(halfLayout) }
        val replacement = CraftEngineFurniture.place(old.location(), definition, data, false)
        if (!CraftEngineFurniture.remove(old.bukkitEntity(), false, false)) {
            CraftEngineFurniture.remove(replacement.bukkitEntity(), false, false)
            return false
        }
        halfDrawers[block.key()] = replacement
        return true
    }

    fun drawerItemType(item: ItemStack?): String? {
        if (item == null || item.type.isAir || !CraftEngineItems.isCustomItem(item)) return null
        val id = CraftEngineItems.getCustomItemId(item)?.asString() ?: return null
        val prefix = "$namespace:$itemPrefix"
        return id.removePrefix(prefix).takeIf { id.startsWith(prefix) }
    }

    fun halfDrawerDefinition(type: String) = CraftEngineFurniture.byId(Key.of(namespace, "$blockPrefix$type"))

    fun halfDrawerLocation(block: Block, facing: String) = block.location.add(0.5, 0.0, 0.5).apply {
        yaw = when (facing) { "east" -> 90f; "south" -> 180f; "west" -> 270f; else -> 0f }
        pitch = 0f
    }

    fun placeHalfDrawer(block: Block, type: String, facing: String): BukkitFurniture? {
        val definition = halfDrawerDefinition(type)?.takeIf { it.getVariant(halfLayout) != null } ?: return null
        return CraftEngineFurniture.place(halfDrawerLocation(block, facing), definition, halfLayout, false).also {
            halfDrawers[block.key()] = it
        }
    }

    fun migrateHalfDrawer(block: Block, type: String, facing: String): Boolean {
        if (legacyDrawerType(block) != type) return drawerType(block) == type
        val furniture = halfFurniture(block) ?: placeHalfDrawer(block, type, facing) ?: return false
        if (CraftEngineBlocks.remove(block, false)) return true
        CraftEngineFurniture.remove(furniture.bukkitEntity(), false, false)
        halfDrawers.remove(block.key())
        return false
    }

    fun removeDrawer(block: Block): Boolean {
        val furniture = halfFurniture(block)
        if (furniture != null && legacyDrawerType(block) == null) {
            val removed = CraftEngineFurniture.remove(furniture.bukkitEntity(), false, false)
            if (removed) halfDrawers.remove(block.key())
            return removed
        }
        return CraftEngineBlocks.remove(block, false)
    }

    private fun Block.key() = DrawerKey(world.uid, x, y, z)

    fun isDrawerItem(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir || !CraftEngineItems.isCustomItem(item)) return false
        val id = CraftEngineItems.getCustomItemId(item)?.asString() ?: return false
        return id.startsWith("$namespace:$itemPrefix")
    }

    fun drawerUpgradeId(item: ItemStack?): String? {
        if (item == null || item.type.isAir || !CraftEngineItems.isCustomItem(item)) return null
        val id = CraftEngineItems.getCustomItemId(item)?.asString() ?: return null
        val prefix = "$namespace:upgrade_"
        return id.removePrefix(prefix).takeIf { id.startsWith(prefix) && it != "template" }
    }

    fun isDrawerKey(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir || !CraftEngineItems.isCustomItem(item)) return false
        return CraftEngineItems.getCustomItemId(item)?.asString() == "$namespace:drawer_key"
    }

    fun isPersonalKey(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir || !CraftEngineItems.isCustomItem(item)) return false
        return CraftEngineItems.getCustomItemId(item)?.asString() == "$namespace:personal_key"
    }

    fun placeDrawer(location: Location, type: String): Boolean = if ("_half_" in type) {
        placeHalfDrawer(location.block, type, "north") != null
    } else {
        CraftEngineBlocks.place(location, Key.of(namespace, "$blockPrefix$type"), true)
    }

    fun drawerItemId(type: String): Key = Key.of(namespace, "$itemPrefix$type")

    fun upgradeItemId(id: String): Key = Key.of(namespace, "upgrade_$id")

    fun customItemId(path: String): Key = Key.of(namespace, path.removePrefix("$namespace:"))

    private fun normalizeDrawerPrefix(prefix: String) = if (prefix == "drawer_oak_") "drawer_" else prefix
}
