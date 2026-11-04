package sea.balance.craftdrawers.domain

import java.util.UUID

data class DrawerKey(val worldId: UUID, val x: Int, val y: Int, val z: Int)

data class DrawerSpec(
    val id: String,
    val slots: Int,
    val half: Boolean,
    val stackUnits: Int,
) {
    fun capacity(maxStackSize: Int): Long = Math.multiplyExact(stackUnits.toLong(), maxStackSize.toLong())
}

data class StoredSlot(
    var itemBlob: ByteArray?,
    var amount: Long,
    var locked: Boolean,
) {
    /** Only a type-locked slot retains its item template after being emptied. */
    fun normalized(): StoredSlot = if (amount == 0L && !locked) copy(itemBlob = null) else this
}

data class SlotChange(val index: Int, val slot: StoredSlot)

data class StoredDrawer(
    val key: DrawerKey,
    val specId: String,
    var owner: UUID?,
    var facing: String,
    val slots: MutableList<StoredSlot>,
    var revision: Long,
)

data class ItemClaim(
    val id: UUID,
    val playerId: UUID,
    val itemBlob: ByteArray,
    val amount: Int,
)
