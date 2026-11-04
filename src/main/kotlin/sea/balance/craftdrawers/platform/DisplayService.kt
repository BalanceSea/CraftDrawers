package sea.balance.craftdrawers.platform

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import sea.balance.craftdrawers.domain.DrawerKey
import sea.balance.craftdrawers.domain.DrawerSpec
import sea.balance.craftdrawers.domain.StoredDrawer
import sea.balance.craftdrawers.integration.craftengine.CraftEngineAdapter
import java.util.Locale
import kotlin.math.PI

class DisplayService(private val plugin: JavaPlugin, private val ce: CraftEngineAdapter) {
    private val drawerKey = NamespacedKey(plugin, "drawer")
    private val slotKey = NamespacedKey(plugin, "slot")
    private val roleKey = NamespacedKey(plugin, "role")
    private val brightness = Display.Brightness(
        plugin.config.getInt("display.block-light", 15).coerceIn(0, 15),
        plugin.config.getInt("display.sky-light", 15).coerceIn(0, 15),
    )
    private val faceInset = 0.005
    private val faceGap = plugin.config.getDouble("display.face-gap", 0.012).coerceIn(0.005, 0.10)
    private val itemDepth = plugin.config.getDouble("display.item-depth", 0.008).coerceIn(0.001, faceGap).toFloat()
    private val textScale = plugin.config.getDouble("display.text-scale", 0.32).coerceIn(0.10, 0.60).toFloat()
    private val itemScales = mapOf(
        1 to plugin.config.getDouble("display.item-scale.single", 0.36).coerceIn(0.10, 0.50).toFloat(),
        2 to plugin.config.getDouble("display.item-scale.two", 0.26).coerceIn(0.10, 0.35).toFloat(),
        4 to plugin.config.getDouble("display.item-scale.four", 0.24).coerceIn(0.10, 0.30).toFloat(),
    )

    fun refresh(drawer: StoredDrawer, spec: DrawerSpec) {
        if (!drawer.key.isChunkLoaded()) return
        drawer.slots.forEachIndexed { index, slot ->
            val itemEntity = findDisplay(drawer.key, index, "item", ItemDisplay::class.java)
            val textEntity = findDisplay(drawer.key, index, "text", TextDisplay::class.java)
            val location = slotLocation(drawer.key, drawer.facing, spec, index)
            // Treat the icon and its count as one label within the panel.
            val itemAt = location.clone().add(0.0, if (spec.slots == 1) 0.08 else 0.06, 0.0)
            val textAt = location.clone().add(0.0, if (spec.slots == 1) -0.20 else -0.15, 0.0)
            val outward = (faceInset + faceGap).toFloat()

            if (slot.amount <= 0 || slot.itemBlob == null) {
                itemEntity?.remove()
                if (!plugin.config.getBoolean("drawers.empty-count-text", false)) {
                    textEntity?.remove()
                    return@forEachIndexed
                }
            } else {
                val sample = runCatching { ItemStack.deserializeBytes(slot.itemBlob).asOne() }.getOrNull()
                if (sample == null) {
                    itemEntity?.remove()
                    textEntity?.remove()
                    plugin.logger.warning("Could not render stored item for ${drawer.key} slot $index")
                    return@forEachIndexed
                }
                val item = itemEntity ?: spawnItem(itemAt, drawer.key, index)
                item.teleport(itemAt)
                item.brightness = brightness
                item.setItemStack(sample)
                item.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GUI
                // Use the inventory icon pose and flatten its local depth before
                // rotating onto the drawer face. A block item must not float
                // half its thickness in front of the panel to avoid clipping.
                item.transformation = transformation(itemScales.getValue(spec.slots), drawer.facing, outward, itemDepth)
            }

            val text = textEntity ?: spawnText(textAt, drawer.key, index)
            text.teleport(textAt)
            text.brightness = brightness
            text.text(Component.text(if (slot.amount > 0) formatAmount(slot.amount) else "0", NamedTextColor.WHITE))
            text.isShadowed = false
            text.textOpacity = 255.toByte()
            text.transformation = transformation(textScale, drawer.facing, outward + 0.001f)
        }
        cleanupOrphans(drawer.key, drawer.slots.size)
    }

    fun remove(key: DrawerKey) {
        loadedChunk(key)?.entities?.filter { it.drawerIdentity() == key.encode() }?.forEach(Entity::remove)
    }

    fun cleanupChunk(worldId: java.util.UUID, chunkX: Int, chunkZ: Int) {
        val world = Bukkit.getWorld(worldId) ?: return
        if (!world.isChunkLoaded(chunkX, chunkZ)) return
        world.getChunkAt(chunkX, chunkZ).entities
            .filter { it.persistentDataContainer.has(drawerKey, PersistentDataType.STRING) }
            .forEach(Entity::remove)
    }

    private fun spawnItem(location: Location, key: DrawerKey, slot: Int): ItemDisplay =
        location.world.spawn(location, ItemDisplay::class.java) { entity ->
            mark(entity, key, slot, "item")
            entity.isPersistent = false
            entity.isInvulnerable = true
            entity.setGravity(false)
            entity.isSilent = true
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GUI
            entity.billboard = Display.Billboard.FIXED
            entity.brightness = brightness
            entity.viewRange = 1.0f
            // Custom item models can exceed a fixed culling box.
            entity.displayWidth = 0f
            entity.displayHeight = 0f
        }

    private fun spawnText(location: Location, key: DrawerKey, slot: Int): TextDisplay =
        location.world.spawn(location, TextDisplay::class.java) { entity ->
            mark(entity, key, slot, "text")
            entity.isPersistent = false
            entity.isInvulnerable = true
            entity.setGravity(false)
            entity.isSilent = true
            entity.billboard = Display.Billboard.FIXED
            entity.brightness = brightness
            entity.alignment = TextDisplay.TextAlignment.CENTER
            entity.lineWidth = 200
            entity.isDefaultBackground = false
            entity.backgroundColor = org.bukkit.Color.fromARGB(0, 0, 0, 0)
            entity.isShadowed = false
            entity.textOpacity = 255.toByte()
            entity.viewRange = 1.0f
            entity.displayWidth = 0f
            entity.displayHeight = 0f
        }

    private fun <T : Entity> findDisplay(key: DrawerKey, slot: Int, role: String, type: Class<T>): T? =
        loadedChunk(key)?.entities?.asSequence()
            ?.filter { it.drawerIdentity() == key.encode() }
            ?.filter { it.persistentDataContainer.get(slotKey, PersistentDataType.INTEGER) == slot }
            ?.filter { it.persistentDataContainer.get(roleKey, PersistentDataType.STRING) == role }
            ?.filterIsInstance(type)
            ?.firstOrNull()

    private fun mark(entity: Entity, key: DrawerKey, slot: Int, role: String) {
        entity.persistentDataContainer.set(drawerKey, PersistentDataType.STRING, key.encode())
        entity.persistentDataContainer.set(slotKey, PersistentDataType.INTEGER, slot)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role)
    }

    private fun cleanupOrphans(key: DrawerKey, slots: Int) {
        loadedChunk(key)?.entities?.filter { entity ->
            entity.drawerIdentity() == key.encode() &&
                (entity.persistentDataContainer.get(slotKey, PersistentDataType.INTEGER) ?: Int.MAX_VALUE) >= slots
        }?.forEach(Entity::remove)
    }

    private fun Entity.drawerIdentity(): String? =
        persistentDataContainer.get(drawerKey, PersistentDataType.STRING)

    private fun loadedChunk(key: DrawerKey) = Bukkit.getWorld(key.worldId)
        ?.takeIf { it.isChunkLoaded(key.x shr 4, key.z shr 4) }
        ?.getChunkAt(key.x shr 4, key.z shr 4)

    private fun DrawerKey.isChunkLoaded(): Boolean = loadedChunk(this) != null

    private fun slotLocation(key: DrawerKey, facing: String, spec: DrawerSpec, slot: Int): Location {
        val (screenU, localY) = faceOffset(spec.slots, slot)
        val world = Bukkit.getWorld(key.worldId) ?: error("Drawer world ${key.worldId} is unavailable")
        // Half-depth bodies sit against the rear wall; their front is at the
        // centre of the same grid cell, not on its outer boundary.
        val depth = if (spec.half) ce.frontDepth(world.getBlockAt(key.x, key.y, key.z)) else 0.0
        val (x, y, z) = when (facing.lowercase()) {
            "south" -> Triple(key.x + screenU, key.y + localY, key.z + 1.0 - depth - faceInset)
            "east" -> Triple(key.x + 1.0 - depth - faceInset, key.y + localY, key.z + 1.0 - screenU)
            "west" -> Triple(key.x + depth + faceInset, key.y + localY, key.z + screenU)
            else -> Triple(key.x + 1.0 - screenU, key.y + localY, key.z + depth + faceInset)
        }
        return Location(world, x, y, z)
    }

    private fun transformation(scale: Float, facing: String, outward: Float, depth: Float = scale) = Transformation(
        // Keep entity origins in the drawer's own chunk, even at a chunk border;
        // move the rendered model outside the face in world-oriented local space.
        when (facing.lowercase()) {
            "south" -> Vector3f(0f, 0f, outward)
            "east" -> Vector3f(outward, 0f, 0f)
            "west" -> Vector3f(-outward, 0f, 0f)
            else -> Vector3f(0f, 0f, -outward)
        },
        Quaternionf().rotationY(
            // The display front is local +Z; this is a quaternion angle, not
            // Bukkit's clockwise entity yaw.
            when (facing.lowercase()) {
                "south" -> 0f
                "east" -> (PI / 2).toFloat()
                "west" -> (-PI / 2).toFloat()
                else -> PI.toFloat()
            },
        ),
        Vector3f(scale, scale, depth),
        Quaternionf(),
    )

    private fun faceOffset(slots: Int, index: Int): Pair<Double, Double> = when (slots) {
        1 -> 0.5 to 0.50
        2 -> 0.50 to (if (index == 0) 0.75 else 0.25)
        4 -> (if (index % 2 == 0) 0.25 else 0.75) to (if (index < 2) 0.75 else 0.25)
        else -> error("Unsupported drawer slot count: $slots")
    }

    private fun formatAmount(amount: Long): String = when {
        amount >= 1_000_000 -> String.format(Locale.ROOT, "%.1fm", amount / 1_000_000.0)
        amount >= 10_000 -> String.format(Locale.ROOT, "%.1fk", amount / 1_000.0)
        else -> amount.toString()
    }

    private fun DrawerKey.encode() = "${worldId}:$x:$y:$z"
}
