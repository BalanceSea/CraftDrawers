package sea.balance.craftdrawers.storage

import sea.balance.craftdrawers.domain.DrawerKey
import sea.balance.craftdrawers.domain.ItemClaim
import sea.balance.craftdrawers.domain.StoredDrawer
import sea.balance.craftdrawers.domain.StoredSlot
import sea.balance.craftdrawers.domain.SlotChange
import java.io.Closeable
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SqliteStore(file: Path, busyTimeoutMs: Int) : Closeable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
    private val lock = ReentrantLock()

    init {
        require(busyTimeoutMs in 0..60_000)
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA synchronous=FULL")
            statement.execute("PRAGMA busy_timeout=$busyTimeoutMs")
            statement.execute(
                """CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS drawers (
                    world_uuid TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    drawer_id TEXT NOT NULL,
                    owner_uuid TEXT,
                    facing TEXT NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(world_uuid, x, y, z)
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS drawer_slots (
                    world_uuid TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    slot_index INTEGER NOT NULL,
                    item_blob BLOB,
                    amount INTEGER NOT NULL DEFAULT 0 CHECK(amount >= 0),
                    locked INTEGER NOT NULL DEFAULT 0 CHECK(locked IN (0, 1)),
                    PRIMARY KEY(world_uuid, x, y, z, slot_index),
                    FOREIGN KEY(world_uuid, x, y, z)
                      REFERENCES drawers(world_uuid, x, y, z) ON DELETE CASCADE
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS drawer_upgrades (
                    world_uuid TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    upgrade_slot INTEGER NOT NULL,
                    upgrade_id TEXT NOT NULL,
                    PRIMARY KEY(world_uuid, x, y, z, upgrade_slot),
                    FOREIGN KEY(world_uuid, x, y, z)
                      REFERENCES drawers(world_uuid, x, y, z) ON DELETE CASCADE
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS transfer_operations (
                    operation_uuid TEXT PRIMARY KEY,
                    operation_type TEXT NOT NULL,
                    world_uuid TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    before_revision INTEGER NOT NULL,
                    after_revision INTEGER,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS player_claims (
                    claim_uuid TEXT PRIMARY KEY,
                    player_uuid TEXT NOT NULL,
                    item_blob BLOB NOT NULL,
                    amount INTEGER NOT NULL CHECK(amount > 0),
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""".trimIndent(),
            )
            statement.execute("CREATE INDEX IF NOT EXISTS player_claims_player_idx ON player_claims(player_uuid)")
            statement.execute("CREATE INDEX IF NOT EXISTS drawers_chunk_idx ON drawers(world_uuid, x, z)")
            statement.execute("INSERT OR IGNORE INTO schema_migrations(version) VALUES (1)")
        }
        // Repair legacy zero-stock templates without touching stock or locked types.
        transaction {
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE drawer_slots SET item_blob=NULL WHERE amount=0 AND locked=0 AND item_blob IS NOT NULL")
                statement.execute("INSERT OR IGNORE INTO schema_migrations(version) VALUES (2)")
            }
        }
    }

    fun load(key: DrawerKey, slotCount: Int): StoredDrawer? = lock.withLock {
        val drawer = connection.prepareStatement(
            "SELECT drawer_id, owner_uuid, facing, revision FROM drawers WHERE world_uuid=? AND x=? AND y=? AND z=?",
        ).use { query ->
            bindKey(query, key)
            query.executeQuery().use { result ->
                if (!result.next()) return@withLock null
                listOf(
                    result.getString("drawer_id"),
                    result.getString("owner_uuid"),
                    result.getString("facing"),
                    result.getLong("revision"),
                )
            }
        }
        val slots = MutableList(slotCount) { StoredSlot(null, 0, false) }
        connection.prepareStatement(
            "SELECT slot_index, item_blob, amount, locked FROM drawer_slots WHERE world_uuid=? AND x=? AND y=? AND z=?",
        ).use { query ->
            bindKey(query, key)
            query.executeQuery().use { result ->
                while (result.next()) {
                    val index = result.getInt("slot_index")
                    if (index !in slots.indices) continue
                    val blob = result.getBytes("item_blob")
                    slots[index] = StoredSlot(
                        blob,
                        result.getLong("amount"),
                        result.getInt("locked") != 0,
                    ).normalized()
                }
            }
        }
        StoredDrawer(
            key,
            drawer[0] as String,
            (drawer[1] as String?)?.let(UUID::fromString),
            drawer[2] as String,
            slots,
            drawer[3] as Long,
        )
    }

    fun create(key: DrawerKey, specId: String, owner: UUID?, facing: String, slots: List<StoredSlot>): Boolean =
        lock.withLock { transaction {
        val inserted = connection.prepareStatement(
            """INSERT OR IGNORE INTO drawers(world_uuid,x,y,z,drawer_id,owner_uuid,facing,revision)
                VALUES(?,?,?,?,?,?,?,0)""".trimIndent(),
        ).use { insert ->
            bindKey(insert, key)
            insert.setString(5, specId)
            insert.setString(6, owner?.toString())
            insert.setString(7, facing)
            insert.executeUpdate() == 1
        }
        if (inserted) saveSlots(key, slots)
        inserted
    } }

    fun updateSlot(
        snapshot: StoredDrawer,
        change: SlotChange,
        operationId: UUID,
        operationType: String,
        claim: ItemClaim? = null,
    ): Long? = lock.withLock { transaction {
        val slot = change.slot.normalized()
        val nextRevision = snapshot.revision + 1
        val begin = connection.prepareStatement(
            """INSERT INTO transfer_operations(operation_uuid,operation_type,world_uuid,x,y,z,status,before_revision)
                VALUES(?,?,?,?,?,?,?,?)""".trimIndent(),
        ).use { statement ->
            statement.setString(1, operationId.toString())
            statement.setString(2, operationType)
            bindKey(statement, snapshot.key, 3)
            statement.setString(7, "PREPARED")
            statement.setLong(8, snapshot.revision)
            statement.executeUpdate() == 1
        }
        if (!begin) return@transaction null
        val updated = connection.prepareStatement(
            "UPDATE drawers SET revision=? WHERE world_uuid=? AND x=? AND y=? AND z=? AND revision=?",
        ).use { statement ->
            statement.setLong(1, nextRevision)
            bindKey(statement, snapshot.key, 2)
            statement.setLong(6, snapshot.revision)
            statement.executeUpdate() == 1
        }
        if (!updated) {
            connection.prepareStatement("DELETE FROM transfer_operations WHERE operation_uuid=?").use {
                it.setString(1, operationId.toString())
                it.executeUpdate()
            }
            return@transaction null
        }
        connection.prepareStatement(
            """INSERT INTO drawer_slots(world_uuid,x,y,z,slot_index,item_blob,amount,locked)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(world_uuid,x,y,z,slot_index) DO UPDATE SET
                  item_blob=excluded.item_blob, amount=excluded.amount, locked=excluded.locked""".trimIndent(),
        ).use { update ->
            bindKey(update, snapshot.key)
            update.setInt(5, change.index)
            update.setBytes(6, slot.itemBlob)
            update.setLong(7, slot.amount)
            update.setInt(8, if (slot.locked) 1 else 0)
            update.executeUpdate()
        }
        claim?.let { itemClaim ->
            connection.prepareStatement(
                "INSERT INTO player_claims(claim_uuid,player_uuid,item_blob,amount) VALUES(?,?,?,?)",
            ).use { statement ->
                statement.setString(1, itemClaim.id.toString())
                statement.setString(2, itemClaim.playerId.toString())
                statement.setBytes(3, itemClaim.itemBlob)
                statement.setInt(4, itemClaim.amount)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            "UPDATE transfer_operations SET status='COMMITTED', after_revision=? WHERE operation_uuid=?",
        ).use { statement ->
            statement.setLong(1, nextRevision)
            statement.setString(2, operationId.toString())
            statement.executeUpdate()
        }
        nextRevision
    } }

    fun specId(key: DrawerKey): String? = lock.withLock {
        connection.prepareStatement("SELECT drawer_id FROM drawers WHERE world_uuid=? AND x=? AND y=? AND z=?").use {
            bindKey(it, key)
            it.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    fun setOwner(key: DrawerKey, owner: UUID): Boolean = lock.withLock {
        connection.prepareStatement(
            "UPDATE drawers SET owner_uuid=? WHERE world_uuid=? AND x=? AND y=? AND z=? AND owner_uuid IS NULL",
        ).use { statement ->
            statement.setString(1, owner.toString())
            bindKey(statement, key, 2)
            statement.executeUpdate() == 1
        }
    }

    fun upgradeId(key: DrawerKey, slot: Int): String? = lock.withLock {
        connection.prepareStatement(
            "SELECT upgrade_id FROM drawer_upgrades WHERE world_uuid=? AND x=? AND y=? AND z=? AND upgrade_slot=?",
        ).use {
            bindKey(it, key)
            it.setInt(5, slot)
            it.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    fun upgrades(key: DrawerKey): List<Pair<Int, String>> = lock.withLock {
        connection.prepareStatement(
            "SELECT upgrade_slot,upgrade_id FROM drawer_upgrades WHERE world_uuid=? AND x=? AND y=? AND z=? ORDER BY upgrade_slot",
        ).use { statement ->
            bindKey(statement, key)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getInt(1) to result.getString(2))
                }
            }
        }
    }

    fun firstFreeUpgradeSlot(key: DrawerKey): Int? = lock.withLock {
        val occupied = upgrades(key).mapTo(mutableSetOf()) { it.first }
        (0 until 7).firstOrNull { it !in occupied }
    }

    fun removeUpgrade(key: DrawerKey, slot: Int): String? = lock.withLock { transaction {
        val id = connection.prepareStatement(
            "SELECT upgrade_id FROM drawer_upgrades WHERE world_uuid=? AND x=? AND y=? AND z=? AND upgrade_slot=?",
        ).use { statement ->
            bindKey(statement, key)
            statement.setInt(5, slot)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        } ?: return@transaction null
        connection.prepareStatement(
            "DELETE FROM drawer_upgrades WHERE world_uuid=? AND x=? AND y=? AND z=? AND upgrade_slot=?",
        ).use { statement ->
            bindKey(statement, key)
            statement.setInt(5, slot)
            statement.executeUpdate()
        }
        id
    } }

    fun removeUpgradeToClaim(key: DrawerKey, slot: Int, playerId: UUID, itemBlob: ByteArray): ItemClaim? =
        lock.withLock { transaction {
            val id = connection.prepareStatement(
                "SELECT upgrade_id FROM drawer_upgrades WHERE world_uuid=? AND x=? AND y=? AND z=? AND upgrade_slot=?",
            ).use { statement ->
                bindKey(statement, key)
                statement.setInt(5, slot)
                statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
            } ?: return@transaction null
            val claim = ItemClaim(UUID.randomUUID(), playerId, itemBlob, 1)
            connection.prepareStatement(
                "DELETE FROM drawer_upgrades WHERE world_uuid=? AND x=? AND y=? AND z=? AND upgrade_slot=?",
            ).use { statement ->
                bindKey(statement, key)
                statement.setInt(5, slot)
                if (statement.executeUpdate() != 1) return@transaction null
            }
            connection.prepareStatement(
                "INSERT INTO player_claims(claim_uuid,player_uuid,item_blob,amount) VALUES(?,?,?,1)",
            ).use { statement ->
                statement.setString(1, claim.id.toString())
                statement.setString(2, playerId.toString())
                statement.setBytes(3, itemBlob)
                statement.executeUpdate()
            }
            claim
        } }

    fun pendingClaims(playerId: UUID): List<ItemClaim> = lock.withLock {
        connection.prepareStatement(
            "SELECT claim_uuid,item_blob,amount FROM player_claims WHERE player_uuid=? ORDER BY created_at,claim_uuid",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(
                        ItemClaim(
                            UUID.fromString(result.getString("claim_uuid")),
                            playerId,
                            result.getBytes("item_blob"),
                            result.getInt("amount"),
                        ),
                    )
                }
            }
        }
    }

    fun claim(claimId: UUID, playerId: UUID): ItemClaim? = lock.withLock {
        connection.prepareStatement("SELECT item_blob,amount FROM player_claims WHERE claim_uuid=? AND player_uuid=?").use {
            it.setString(1, claimId.toString())
            it.setString(2, playerId.toString())
            it.executeQuery().use { result ->
                if (result.next()) ItemClaim(claimId, playerId, result.getBytes("item_blob"), result.getInt("amount")) else null
            }
        }
    }

    fun updateClaim(claimId: UUID, expectedAmount: Int, remaining: Int): Boolean = lock.withLock {
        require(remaining in 0..expectedAmount)
        if (remaining <= 0) {
            connection.prepareStatement("DELETE FROM player_claims WHERE claim_uuid=? AND amount=?").use {
                it.setString(1, claimId.toString())
                it.setInt(2, expectedAmount)
                it.executeUpdate() == 1
            }
        } else {
            connection.prepareStatement("UPDATE player_claims SET amount=? WHERE claim_uuid=? AND amount=?").use {
                it.setInt(1, remaining)
                it.setString(2, claimId.toString())
                it.setInt(3, expectedAmount)
                it.executeUpdate() == 1
            }
        }
    }

    fun insertUpgrade(key: DrawerKey, slot: Int, upgradeId: String): Boolean = lock.withLock { transaction {
        connection.prepareStatement(
            """INSERT OR IGNORE INTO drawer_upgrades(world_uuid,x,y,z,upgrade_slot,upgrade_id)
                VALUES(?,?,?,?,?,?)""".trimIndent(),
        ).use { statement ->
            bindKey(statement, key)
            statement.setInt(5, slot)
            statement.setString(6, upgradeId)
            statement.executeUpdate() == 1
        }
    } }

    fun upgradeCount(key: DrawerKey): Int = lock.withLock {
        connection.prepareStatement(
            "SELECT COUNT(*) FROM drawer_upgrades WHERE world_uuid=? AND x=? AND y=? AND z=?",
        ).use { statement ->
            bindKey(statement, key)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    fun updateFacing(key: DrawerKey, facing: String): Boolean = lock.withLock {
        connection.prepareStatement(
            "UPDATE drawers SET facing=? WHERE world_uuid=? AND x=? AND y=? AND z=?",
        ).use { statement ->
            statement.setString(1, facing)
            bindKey(statement, key, 2)
            statement.executeUpdate() == 1
        }
    }

    fun remove(key: DrawerKey) {
        lock.withLock {
        connection.prepareStatement("DELETE FROM drawers WHERE world_uuid=? AND x=? AND y=? AND z=?").use {
            bindKey(it, key)
            it.executeUpdate()
        }
        }
    }

    fun loadChunk(worldId: UUID, chunkX: Int, chunkZ: Int): List<DrawerKey> = lock.withLock { buildList {
        connection.prepareStatement(
            "SELECT world_uuid,x,y,z FROM drawers WHERE world_uuid=? AND (x >> 4)=? AND (z >> 4)=?",
        ).use { query ->
            query.setString(1, worldId.toString())
            query.setInt(2, chunkX)
            query.setInt(3, chunkZ)
            query.executeQuery().use { result ->
                while (result.next()) add(
                    DrawerKey(
                        UUID.fromString(result.getString("world_uuid")),
                        result.getInt("x"),
                        result.getInt("y"),
                        result.getInt("z"),
                    ),
                )
            }
        }
    } }

    private fun saveSlots(key: DrawerKey, slots: List<StoredSlot>) {
        connection.prepareStatement(
            """INSERT INTO drawer_slots(world_uuid,x,y,z,slot_index,item_blob,amount,locked)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(world_uuid,x,y,z,slot_index) DO UPDATE SET
                  item_blob=excluded.item_blob, amount=excluded.amount, locked=excluded.locked""".trimIndent(),
        ).use { update ->
            slots.forEachIndexed { index, slot ->
                bindKey(update, key)
                update.setInt(5, index)
                update.setBytes(6, slot.itemBlob)
                update.setLong(7, slot.amount)
                update.setInt(8, if (slot.locked) 1 else 0)
                update.addBatch()
            }
            update.executeBatch()
        }
    }

    private fun <T> transaction(block: () -> T): T {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            return block().also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun bindKey(statement: java.sql.PreparedStatement, key: DrawerKey, start: Int = 1) {
        statement.setString(start, key.worldId.toString())
        statement.setInt(start + 1, key.x)
        statement.setInt(start + 2, key.y)
        statement.setInt(start + 3, key.z)
    }

    override fun close() {
        lock.withLock {
            try {
                connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            } finally {
                connection.close()
            }
        }
    }

    companion object {
    }
}
