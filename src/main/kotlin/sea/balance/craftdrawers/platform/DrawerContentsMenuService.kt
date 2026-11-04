package sea.balance.craftdrawers.platform

import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import sea.balance.craftdrawers.domain.DrawerSpec
import sea.balance.craftdrawers.domain.StoredDrawer
import sea.balance.craftdrawers.domain.StoredSlot
import sea.balance.craftdrawers.integration.craftengine.CraftEngineAdapter
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** Unified drawer GUI; stored item displays are read-only projections of SQLite. */
class DrawerContentsMenuService private constructor(
    private val plugin: JavaPlugin,
    private val language: LanguageService,
    private val ce: CraftEngineAdapter,
    private val definitions: Map<Int, DrawerContentsDefinition>,
) {
    private val missingBackgroundWarnings = ConcurrentHashMap.newKeySet<String>()

    fun create(player: Player, drawer: StoredDrawer, spec: DrawerSpec, upgrades: Map<Int, String>): Inventory {
        val definition = definitions[spec.slots] ?: error("No contents layout for ${spec.slots} slots")
        val holder = DrawerContentsMenuHolder(drawer.key, drawer, definition)
        holder.backing = Bukkit.createInventory(holder, definition.layout.size,
            language.template(definition.title, context(drawer, 0, 0)))
        render(holder.backing, player, drawer, spec, upgrades)
        return holder.backing
    }

    fun render(
        inventory: Inventory,
        player: Player,
        drawer: StoredDrawer,
        spec: DrawerSpec,
        upgrades: Map<Int, String>,
    ) {
        val holder = inventory.holder as? DrawerContentsMenuHolder ?: return
        val context = context(drawer, 0, 0).toMutableMap().apply {
            put("player", player.name)
            put("used", drawer.slots.count { it.amount > 0 }.toString())
        }
        holder.definition.layout.forEachIndexed { rawSlot, icon ->
            val stack = when (icon?.type) {
                null -> null
                DrawerContentsIconType.DECORATION, DrawerContentsIconType.INFO, DrawerContentsIconType.CLOSE ->
                    display(icon.display, null, context)
                DrawerContentsIconType.UPGRADE_SLOT -> {
                    val index = icon.upgradeSlot ?: error("Upgrade icon has no slot binding")
                    val upgradeId = upgrades[index]
                    val values = context.toMutableMap().apply {
                        put("slot", (index + 1).toString())
                        put("upgrade", upgradeId ?: "")
                        put("upgrade_name", upgradeId?.let(::upgradeName) ?: language.raw("common.none"))
                    }
                    if (upgradeId == null) {
                        display(holder.definition.emptyUpgrade.overlay(icon.display), null, values)
                    } else {
                        val id = ce.upgradeItemId(upgradeId)
                        val sample = CraftEngineItems.byId(id)?.buildBukkitItem()
                        if (sample == null) display(holder.definition.invalid, null, values + ("error" to id.asString()))
                        else display(holder.definition.installedUpgrade.overlay(icon.display), sample, values)
                    }
                }
                DrawerContentsIconType.DRAWER_SLOT -> {
                    val index = icon.drawerSlot ?: error("Drawer contents icon has no slot binding")
                    val slot = drawer.slots[index]
                    val values = context.toMutableMap().apply {
                        put("slot", (index + 1).toString())
                        put("amount", slot.amount.toString())
                    }
                    when {
                        slot.amount > 0 && slot.itemBlob != null -> storedItem(holder.definition, slot, index, values)
                        slot.itemBlob != null && slot.locked ->
                            drawerBackground(holder.definition, drawer, spec, holder.definition.lockedEmpty, values)
                        slot.itemBlob == null && slot.amount == 0L ->
                            drawerBackground(holder.definition, drawer, spec, holder.definition.empty, values)
                        else -> display(holder.definition.invalid, null, values)
                    }
                }
            }
            inventory.setItem(rawSlot, stack)
        }
    }

    private fun storedItem(
        definition: DrawerContentsDefinition,
        slot: StoredSlot,
        index: Int,
        context: Map<String, String>,
    ): ItemStack {
        val sample = runCatching { ItemStack.deserializeBytes(slot.itemBlob!!).asOne() }.getOrElse {
            plugin.logger.warning("Could not decode drawer GUI sample at slot ${index + 1}: ${it.message}")
            return display(definition.invalid, null, context)
        }
        sample.editMeta { meta ->
            val existing = meta.lore().orEmpty()
            meta.lore(existing + language.lore(definition.storedLore, context))
        }
        // The inventory's native stack counter is capped by the item's max size.
        // The exact Long count is also appended to its name and lore.
        sample.amount = min(slot.amount, sample.maxStackSize.toLong()).toInt().coerceAtLeast(1)
        return sample
    }

    fun upgradeName(id: String): String = runCatching { language.raw("upgrades.names.$id") }.getOrDefault(id)

    private fun drawerBackground(
        definition: DrawerContentsDefinition,
        drawer: StoredDrawer,
        spec: DrawerSpec,
        display: MenuDisplay,
        context: Map<String, String>,
    ): ItemStack {
        val wood = drawer.specId.removeSuffix("_full_${spec.slots}").removeSuffix("_half_${spec.slots}")
        val id = ce.customItemId("gui_drawer_${wood}_${spec.slots}")
        val base = CraftEngineItems.byId(id)?.buildBukkitItem()
        if (base == null) {
            if (missingBackgroundWarnings.add(id.asString())) {
                plugin.logger.warning("Missing CraftEngine drawer GUI background ${id.asString()}; update the CE pack")
            }
            return display(definition.invalid, null, context)
        }
        return display(display, base, context)
    }

    private fun display(config: MenuDisplay, original: ItemStack?, context: Map<String, String>): ItemStack {
        val item = when {
            config.material == null -> original?.clone() ?: ItemStack(Material.PAPER)
            config.material.startsWith("ce:") -> CraftEngineItems.byId(Key.of(config.material.removePrefix("ce:")))
                ?.buildBukkitItem() ?: ItemStack(Material.BARRIER)
            else -> ItemStack(Material.matchMaterial(config.material) ?: error("Unknown GUI material '${config.material}'"))
        }
        config.amount?.let { item.amount = min(it, item.maxStackSize) }
        item.editMeta { meta ->
            config.name?.let { meta.displayName(language.template(it, context)) }
            config.lore?.let { meta.lore(language.lore(it, context)) }
            config.customModelData?.let { meta.setCustomModelData(it) }
            config.glow?.let { meta.setEnchantmentGlintOverride(it) }
        }
        return item
    }

    private fun context(drawer: StoredDrawer, slot: Int, amount: Long): Map<String, String> = mapOf(
        "type" to drawer.specId,
        "world" to (Bukkit.getWorld(drawer.key.worldId)?.name ?: drawer.key.worldId.toString()),
        "x" to drawer.key.x.toString(),
        "y" to drawer.key.y.toString(),
        "z" to drawer.key.z.toString(),
        "slot" to (slot + 1).toString(),
        "amount" to amount.toString(),
    )

    companion object {
        private val configuredSlots = listOf(1, 2, 4)

        fun load(
            plugin: JavaPlugin,
            ce: CraftEngineAdapter,
            language: LanguageService,
            bundledOnly: Boolean = false,
        ): DrawerContentsMenuService {
        val definitions = configuredSlots.associateWith { count ->
                val path = "menus/drawers/$count.yml"
                ensureResource(plugin, path)
                val yaml = if (bundledOnly) bundledYaml(plugin, path) else YamlConfiguration().apply {
                    load(File(plugin.dataFolder, path))
                }
                require(yaml.getInt("Version", -1) == 1) { "$path has an unsupported menu format version" }
                val title = yaml.getString("Title") ?: error("$path requires Title")
                language.validate(title)
                val rowValues = yaml.getList("Layout") ?: error("$path Layout must be a list")
                require(rowValues.size in 1..6 && rowValues.all { it is String }) { "$path Layout must contain 1..6 rows" }
                val iconSection = yaml.getConfigurationSection("Icons") ?: error("$path Icons is required")
                val icons = iconSection.getKeys(false).associateWith { id ->
                    val icon = iconSection.getConfigurationSection(id) ?: error("$path icon '$id' must be a section")
                    val drawerSlot = if (icon.contains("drawer-slot")) {
                        require(icon.isInt("drawer-slot")) { "$path icon '$id' drawer-slot must be an integer" }
                        icon.getInt("drawer-slot").also {
                            require(it in 1..count) { "$path icon '$id' drawer-slot must be 1..$count" }
                        } - 1
                    } else null
                    val upgradeSlot = if (icon.contains("upgrade-slot")) {
                        require(icon.isInt("upgrade-slot")) { "$path icon '$id' upgrade-slot must be an integer" }
                        icon.getInt("upgrade-slot").also { require(it in 1..7) { "$path icon '$id' upgrade-slot must be 1..7" } } - 1
                    } else null
                    require(drawerSlot == null || upgradeSlot == null) { "$path icon '$id' cannot be both a drawer and upgrade slot" }
                    val type = when {
                        drawerSlot != null -> DrawerContentsIconType.DRAWER_SLOT
                        upgradeSlot != null -> DrawerContentsIconType.UPGRADE_SLOT
                        else -> runCatching {
                        DrawerContentsIconType.valueOf(icon.getString("type", "decoration")!!.uppercase(Locale.ROOT))
                        }.getOrElse { error("$path icon '$id' has an unknown type") }
                    }
                    require(type != DrawerContentsIconType.DRAWER_SLOT || drawerSlot != null) {
                        "$path drawer-slot icon '$id' requires drawer-slot"
                    }
                    require(type != DrawerContentsIconType.UPGRADE_SLOT || upgradeSlot != null) {
                        "$path upgrade-slot icon '$id' requires upgrade-slot"
                    }
                    DrawerContentsIcon(id, type, drawerSlot, upgradeSlot,
                        MenuConfig.readDisplay(icon.getConfigurationSection("display"), language))
                }
                val layout = rowValues.flatMap { row ->
                    val ids = MenuConfig.parseRow(row as String)
                    require(ids.size == 9) { "$path each Layout row must contain exactly 9 GUI slots" }
                    ids.map { id -> if (id == " ") null else icons[id] ?: error("$path Layout references missing icon '$id'") }
                }
                val slotBindings = layout.mapNotNull { it?.drawerSlot }
                require(slotBindings.size == count && slotBindings.toSet() == (0 until count).toSet()) {
                    "$path must bind every drawer slot 1..$count exactly once"
                }
                val upgradeBindings = layout.mapNotNull { it?.upgradeSlot }
                require(upgradeBindings.size == 7 && upgradeBindings.toSet() == (0..6).toSet()) {
                    "$path must bind every upgrade slot 1..7 exactly once"
                }
                val empty = readDisplay(yaml.getConfigurationSection("Empty"), language)
                val locked = readDisplay(yaml.getConfigurationSection("Locked-Empty"), language)
                val storedLore = readStringList(yaml.getList("Stored-Lore"), "$path Stored-Lore")
                val invalid = readDisplay(yaml.getConfigurationSection("Invalid"), language)
                val emptyUpgradeSection = yaml.getConfigurationSection("Upgrade-Icons.Empty")
                val installedUpgradeSection = yaml.getConfigurationSection("Upgrade-Icons.Installed")
                DrawerContentsDefinition(
                    count, title, layout, empty, locked, storedLore, invalid,
                    readDisplay(emptyUpgradeSection?.getConfigurationSection("display") ?: emptyUpgradeSection, language),
                    readDisplay(installedUpgradeSection?.getConfigurationSection("display") ?: installedUpgradeSection, language),
                )
            }
            return DrawerContentsMenuService(plugin, language, ce, definitions)
        }

        private fun readDisplay(section: ConfigurationSection?, language: LanguageService): MenuDisplay =
            MenuConfig.readDisplay(section, language)

        private fun readStringList(values: List<*>?, key: String): List<String> {
            require(values != null && values.all { it is String }) { "$key must be a list of strings" }
            return values.map { it as String }
        }
    }
}
