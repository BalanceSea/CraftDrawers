package sea.balance.craftdrawers.platform

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import sea.balance.craftdrawers.domain.DrawerKey
import sea.balance.craftdrawers.domain.StoredDrawer

enum class DrawerContentsIconType { DECORATION, DRAWER_SLOT, UPGRADE_SLOT, INFO, CLOSE }

data class DrawerContentsIcon(
    val id: String,
    val type: DrawerContentsIconType,
    val drawerSlot: Int?,
    val upgradeSlot: Int?,
    val display: MenuDisplay,
)

data class DrawerContentsDefinition(
    val slotCount: Int,
    val title: String,
    val layout: List<DrawerContentsIcon?>,
    val empty: MenuDisplay,
    val lockedEmpty: MenuDisplay,
    val storedLore: List<String>,
    val invalid: MenuDisplay,
    val emptyUpgrade: MenuDisplay,
    val installedUpgrade: MenuDisplay,
)

class DrawerContentsMenuHolder(
    val drawerKey: DrawerKey,
    val record: StoredDrawer,
    val definition: DrawerContentsDefinition,
) : InventoryHolder {
    lateinit var backing: Inventory
    var actionPending = false
    override fun getInventory(): Inventory = backing
}
