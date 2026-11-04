package sea.balance.craftdrawers

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.command.brigadier.Commands
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.kyori.adventure.text.Component
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent
import net.momirealms.craftengine.bukkit.api.event.FurnitureAttemptPlaceEvent
import net.momirealms.craftengine.bukkit.api.event.FurnitureHitEvent
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture
import net.momirealms.craftengine.core.entity.player.InteractionHand
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.Hopper
import org.bukkit.block.data.Directional
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Item as DroppedItem
import org.bukkit.entity.Player
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import sea.balance.craftdrawers.domain.DrawerKey
import sea.balance.craftdrawers.domain.DrawerSpec
import sea.balance.craftdrawers.domain.ItemClaim
import sea.balance.craftdrawers.domain.StoredDrawer
import sea.balance.craftdrawers.domain.StoredSlot
import sea.balance.craftdrawers.domain.SlotChange
import sea.balance.craftdrawers.integration.craftengine.CraftEngineAdapter
import sea.balance.craftdrawers.integration.protection.DominionProtection
import sea.balance.craftdrawers.integration.protection.ResidenceProtection
import sea.balance.craftdrawers.integration.protection.TerritoryAction
import sea.balance.craftdrawers.integration.protection.TerritoryProtectionService
import sea.balance.craftdrawers.platform.DisplayService
import sea.balance.craftdrawers.platform.DrawerContentsIconType
import sea.balance.craftdrawers.platform.DrawerContentsMenuHolder
import sea.balance.craftdrawers.platform.DrawerContentsMenuService
import sea.balance.craftdrawers.platform.LanguageService
import sea.balance.craftdrawers.storage.SqliteStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import org.bukkit.scheduler.BukkitTask
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.bukkit.util.BoundingBox
import kotlin.math.log

class CraftDrawersPlugin : JavaPlugin(), Listener {
    private lateinit var store: SqliteStore
    private lateinit var ce: CraftEngineAdapter
    private lateinit var displays: DisplayService
    private lateinit var language: LanguageService
    private lateinit var drawerContentsMenus: DrawerContentsMenuService
    private var territoryProtection: TerritoryProtectionService? = null
    private val databaseExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CraftDrawers-SQLite").apply { isDaemon = true }
    }
    private val loaded = ConcurrentHashMap<DrawerKey, StoredDrawer>()
    private val locks = ConcurrentHashMap<DrawerKey, ReentrantLock>()
    private val playersDeliveringClaims = ConcurrentHashMap.newKeySet<UUID>()
    private data class ClaimAcknowledgement(val playerId: UUID, val expected: Int, val remaining: Int)
    // Main-thread delivery fences: retry a failed SQLite acknowledgement, not
    // item insertion. Players may move a delivered stack before SQLite recovers.
    private val deferredClaimAcknowledgements = mutableMapOf<UUID, ClaimAcknowledgement>()
    private val scheduledTasks = CopyOnWriteArrayList<BukkitTask>()
    private val chunkLoadSequence = AtomicLong()
    private val activeChunkLoads = ConcurrentHashMap<String, Long>()
    private data class StressSettings(
        val worldName: String,
        val maxDrawers: Int,
        val maxAreaRadius: Int,
        val placementBatch: Int,
        val maxOperationsPerTick: Int,
        val seedMaterial: Material,
    )
    private data class StressFixture(val key: DrawerKey, val specId: String)
    private var stressSettings: StressSettings? = null
    private var stressFixtureFile: File? = null
    private val stressFixtures = linkedMapOf<DrawerKey, String>()
    private var stressTask: BukkitTask? = null
    private var stressBusy = false
    @Volatile private var stopping = false
    private var automationCursor = 0
    private var automationTicksSinceRun = 0
    private var automationIntervalTicks = 8
    private val storageUpgradeIds = setOf("storage_obsidian", "storage_copper", "storage_iron", "storage_gold", "storage_emerald", "storage_diamond", "storage_netherite")
    private val singleUpgradeIds = setOf("one_stack", "void", "hopper")
    private val woodTypes = listOf(
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped",
    )
    private val specById = buildList {
        val full = listOf(32, 16, 8)
        val half = listOf(16, 8, 4)
        woodTypes.forEach { wood ->
            listOf(1, 2, 4).forEachIndexed { index, slots ->
                add(DrawerSpec("${wood}_full_$slots", slots, false, full[index]))
                add(DrawerSpec("${wood}_half_$slots", slots, true, half[index]))
            }
        }
    }.associateBy { it.id }
    private val claimMarker by lazy { NamespacedKey(this, "delivery_claim") }
    private val claimCountMarker by lazy { NamespacedKey(this, "delivery_claim_count") }
    private val claimBeforeMarker by lazy { NamespacedKey(this, "delivery_claim_before") }

    override fun onEnable() {
//        val craftEngineVersion = server.pluginManager.getPlugin("CraftEngine")?.pluginMeta?.version
//        if (craftEngineVersion != "26.9.2") {
//            logger.severe("CraftDrawers requires CraftEngine 26.9.2; found ${craftEngineVersion ?: "missing"}")
//            server.pluginManager.disablePlugin(this)
//            return
//        }
        saveDefaultConfig()
        automationIntervalTicks = max(1, config.getInt("automation.hopper-interval-ticks", 8))
        if (config.getBoolean("protection.territory.enabled", true)) {
            val protections = buildList {
                if (server.pluginManager.isPluginEnabled("Residence")) {
                    add(ResidenceProtection())
                    logger.info("Residence territory protection enabled")
                }
                if (server.pluginManager.isPluginEnabled("Dominion")) {
                    add(DominionProtection())
                    logger.info("Dominion territory protection enabled")
                }
            }
            territoryProtection = protections.takeIf { it.isNotEmpty() }?.let { TerritoryProtectionService(logger, it) }
            if (protections.isEmpty()) logger.info("No supported territory plugin found; territory protection is inactive")
        } else {
            logger.info("Territory protection is disabled by configuration")
        }
        val databaseFile = File(dataFolder, config.getString("database.file", "drawers.db") ?: "drawers.db")
        if (!dataFolder.exists() && !dataFolder.mkdirs()) error("Could not create plugin data directory")
        try {
            store = SqliteStore(databaseFile.toPath(), config.getInt("database.busy-timeout-ms", 5000))
            ce = CraftEngineAdapter(
                config.getString("craft-engine.namespace", "craftdrawers")!!,
                config.getString("craft-engine.item-prefix", "drawer_")!!,
                config.getString("craft-engine.block-prefix", "drawer_")!!,
            )
            displays = DisplayService(this, ce)
            val presentation = runCatching { loadPresentation() }.getOrElse {
                logger.warning("加载语言/GUI配置文件失败: ${it.message}")
                loadPresentation(bundledOnly = true)
            }
            language = presentation.first
            drawerContentsMenus = presentation.second
            stressSettings = runCatching { loadStressSettings() }
                .onFailure { logger.warning("Performance stress commands disabled: ${it.message}") }
                .getOrNull()
        } catch (error: Throwable) {
            logger.severe("初始化SQLite失败: ${error.message}")
            server.pluginManager.disablePlugin(this)
            return
        }
        server.pluginManager.registerEvents(this, this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val root = Commands.literal("drawers")
                    .executes { context -> status(context.source.sender); 1 }
                    .then(Commands.literal("version").executes { context -> status(context.source.sender); 1 })
                    .then(Commands.literal("reload")
                        .requires { it.sender.hasPermission("craftdrawers.admin") }
                        .executes { context -> reloadPresentation(context.source.sender); 1 })
                    .then(Commands.literal("give")
                        .requires { it.sender.hasPermission("craftdrawers.admin") }
                        .then(Commands.argument("type", StringArgumentType.word())
                            .executes { context ->
                                    giveItem(context.source.sender, StringArgumentType.getString(context, "type"))
                                1
                            }
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                .executes { context ->
                                    giveItem(
                                        context.source.sender,
                                        StringArgumentType.getString(context, "type"),
                                        IntegerArgumentType.getInteger(context, "amount"),
                                    )
                                    1
                                })))
            stressSettings?.let { settings -> root.then(stressCommand(settings)) }
            event.registrar().register(root.build(), language.raw("command.description"))
        }
        scheduledTasks += server.scheduler.runTaskTimer(this, Runnable { processAutomation() }, 1L, 1L)
        scheduledTasks += server.scheduler.runTaskTimer(this, Runnable { retryPendingClaims() }, 40L, 100L)
        scheduledTasks += server.scheduler.runTask(this, Runnable {
            server.worlds.forEach { world -> world.loadedChunks.forEach(::restoreChunk) }
        })
        logger.info("CraftDrawers 插件已加载")
        if (stressSettings != null) {
            logger.warning("CraftDrawers Test build active; stress commands are limited to OPs in '${stressSettings!!.worldName}'.")
        }
        logger.info("作者: BalanceSea | QQ: 3643203568")
        logger.info("请勿倒卖与二次分发该插件")
        logger.info("否则作者有权将您移除售后群并不再给予技术帮助")
    }

    private fun loadStressSettings(): StressSettings? {
        getResource("stress-test.yml")?.use { } ?: return null
        saveResource("stress-test.yml", false)
        val file = File(dataFolder, "stress-test.yml")
        val yaml = YamlConfiguration.loadConfiguration(file)
        if (!yaml.getBoolean("enabled", false)) return null
        val worldName = yaml.getString("world-name")?.trim().orEmpty()
        require(worldName.isNotEmpty()) { "stress-test.yml must name a dedicated test world" }
        val seed = Material.matchMaterial(yaml.getString("seed-material", "STONE") ?: "STONE")
            ?.takeIf { it.isItem && it.maxStackSize > 0 }
            ?: error("stress-test.yml has an invalid seed-material")
        stressFixtureFile = File(dataFolder, "stress-fixtures.yml")
        YamlConfiguration.loadConfiguration(stressFixtureFile!!).getMapList("drawers").forEach { row ->
            runCatching {
                val key = DrawerKey(
                    UUID.fromString(row["world"] as String),
                    (row["x"] as Number).toInt(),
                    (row["y"] as Number).toInt(),
                    (row["z"] as Number).toInt(),
                )
                val type = row["type"] as String
                if (specById.containsKey(type)) stressFixtures[key] = type
            }.onFailure { logger.warning("Ignoring invalid stress fixture entry: " + it.message) }
        }
        return StressSettings(
            worldName,
            yaml.getInt("max-drawers", 256).coerceIn(1, 4096),
            yaml.getInt("max-area-radius", 64).coerceIn(2, 512),
            yaml.getInt("placement-batch-per-tick", 2).coerceIn(1, 16),
            yaml.getInt("max-operations-per-tick", 8).coerceIn(1, 64),
            seed,
        )
    }

    private fun saveStressFixtures() {
        val file = stressFixtureFile ?: return
        val yaml = YamlConfiguration()
        yaml.set("drawers", stressFixtures.map { (key, type) ->
            mapOf("world" to key.worldId.toString(), "x" to key.x, "y" to key.y, "z" to key.z, "type" to type)
        })
        yaml.save(file)
    }

    private fun stressCommand(settings: StressSettings) =
        Commands.literal("stress")
            .requires { it.sender.isOp }
            .executes { context -> stressStatus(context.source.sender, settings); 1 }
            .then(Commands.literal("status").executes { context -> stressStatus(context.source.sender, settings); 1 })
            .then(Commands.literal("clear").executes { context -> clearStressFixtures(context.source.sender, settings); 1 })
            .then(Commands.literal("setup")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, settings.maxDrawers))
                    .then(Commands.argument("drawer_type", StringArgumentType.word())
                        .then(Commands.argument("contents", StringArgumentType.word())
                            .executes { context ->
                                setupStressFixtures(
                                    context.source.sender,
                                    IntegerArgumentType.getInteger(context, "count"),
                                    StringArgumentType.getString(context, "drawer_type"),
                                    StringArgumentType.getString(context, "contents"),
                                    settings,
                                )
                                1
                            }))))
            .then(Commands.literal("run")
                .then(Commands.argument("operations_per_tick", IntegerArgumentType.integer(1, settings.maxOperationsPerTick))
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                        .executes { context ->
                            runStressMutations(
                                context.source.sender,
                                IntegerArgumentType.getInteger(context, "operations_per_tick"),
                                IntegerArgumentType.getInteger(context, "seconds"),
                                settings,
                            )
                            1
                        })))

    private fun stressStatus(sender: CommandSender, settings: StressSettings) {
        sender.sendMessage(Component.text(
            "Stress mode: world=" + settings.worldName + ", fixtures=" + stressFixtures.size +
                ", busy=" + stressBusy + ", maxDrawers=" + settings.maxDrawers +
                ", maxOpsPerTick=" + settings.maxOperationsPerTick,
        ))
        sender.sendMessage(Component.text(
            "Commands: /drawers stress setup <count> <drawer_type> <empty|filled>, " +
                "run <ops/tick> <seconds>, clear",
        ))
    }

    private fun setupStressFixtures(
        sender: CommandSender,
        count: Int,
        drawerType: String,
        contents: String,
        settings: StressSettings,
    ) {
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("Run setup in-game from the configured test world."))
            return
        }
        if (stressBusy) return sender.sendMessage(Component.text("A stress batch is already running."))
        if (stressFixtures.isNotEmpty()) return sender.sendMessage(Component.text("Clear the previous fixture set first."))
        if (player.world.name != settings.worldName) {
            return sender.sendMessage(Component.text("Run this only in the dedicated world '" + settings.worldName + "'."))
        }
        if (contents != "empty" && contents != "filled") {
            return sender.sendMessage(Component.text("Contents must be 'empty' or 'filled'."))
        }
        val specId = drawerType.removePrefix("drawer_")
        val spec = specById[specId] ?: return sender.sendMessage(Component.text("Unknown drawer type: " + drawerType))
        val world = player.world
        val origin = player.location.block
        val columns = 16
        val planned = (0 until count).map { index ->
            world.getBlockAt(origin.x + (index % columns) * 2, origin.y, origin.z + (index / columns) * 2)
        }
        planned.map { (it.x shr 4) to (it.z shr 4) }.distinct().forEach { (chunkX, chunkZ) ->
            world.getChunkAt(chunkX, chunkZ)
        }
        if (planned.any {
                max(kotlin.math.abs(it.x - origin.x), kotlin.math.abs(it.z - origin.z)) > settings.maxAreaRadius
            }
        ) {
            return sender.sendMessage(Component.text("The requested grid exceeds max-area-radius in stress-test.yml."))
        }
        for (block in planned) {
            val key = DrawerKey(world.uid, block.x, block.y, block.z)
            if (block.y !in world.minHeight until world.maxHeight || !world.worldBorder.isInside(block.location) ||
                !block.type.isAir || ce.drawerType(block) != null || ce.halfFurniture(block) != null ||
                store.specId(key) != null
            ) {
                return sender.sendMessage(Component.text(
                    "Setup aborted: target " + block.x + "," + block.y + "," + block.z +
                        " is occupied or already has drawer data.",
                ))
            }
        }

        planned.forEach { block ->
            stressFixtures[DrawerKey(world.uid, block.x, block.y, block.z)] = spec.id
        }
        saveStressFixtures()
        stressBusy = true
        var cursor = 0
        var placed = 0
        var failed = 0
        lateinit var task: BukkitTask
        task = server.scheduler.runTaskTimer(this, Runnable {
            repeat(settings.placementBatch) {
                if (cursor >= planned.size) return@repeat
                val block = planned[cursor++]
                val key = DrawerKey(world.uid, block.x, block.y, block.z)
                try {
                    if (!block.type.isAir || ce.drawerType(block) != null || ce.halfFurniture(block) != null ||
                        store.specId(key) != null
                    ) {
                        failed++
                        stressFixtures.remove(key)
                        saveStressFixtures()
                        return@repeat
                    }
                    if (!ce.placeDrawer(block.location, spec.id) || ce.drawerType(block) != spec.id) {
                        failed++
                        if (ce.drawerType(block) == spec.id) ce.removeDrawer(block)
                        displays.remove(key)
                        stressFixtures.remove(key)
                        loaded.remove(key)
                        if (store.specId(key) == spec.id) store.remove(key)
                        saveStressFixtures()
                        return@repeat
                    }
                    val record = loadOrCreate(key, spec, ce.facing(block), null)
                        ?: error("Could not initialize SQLite row")
                    if (contents == "filled") {
                        val sample = ItemStack(settings.seedMaterial, 1)
                        val blob = sample.serializeAsBytes()
                        val amount = spec.capacity(sample.maxStackSize)
                        record.slots.indices.forEach { slotIndex ->
                            val next = record.slots[slotIndex]
                                .copy(itemBlob = blob, amount = amount, locked = false).normalized()
                            val revision = store.updateSlot(
                                record,
                                SlotChange(slotIndex, next),
                                UUID.randomUUID(),
                                "STRESS_SETUP",
                            ) ?: error("SQLite row revision conflict during fixture setup")
                            record.slots[slotIndex] = next
                            record.revision = revision
                        }
                        loaded[key] = record
                        displays.refresh(record, spec)
                    }
                    placed++
                } catch (error: Throwable) {
                    failed++
                    logger.warning("Stress fixture failed at " + key + ": " + error.message)
                    runCatching {
                        if (ce.drawerType(block) == spec.id) ce.removeDrawer(block)
                        displays.remove(key)
                        loaded.remove(key)
                        store.remove(key)
                    }
                    stressFixtures.remove(key)
                    saveStressFixtures()
                }
            }
            if (cursor >= planned.size) {
                task.cancel()
                scheduledTasks.remove(task)
                stressTask = null
                stressBusy = false
                sender.sendMessage(Component.text(
                    "Stress setup complete: placed=" + placed + ", failed=" + failed +
                        ", contents=" + contents + ", type=" + drawerType + ".",
                ))
            }
        }, 1L, 1L)
        stressTask = task
        scheduledTasks += task
        sender.sendMessage(Component.text(
            "Preparing " + count + " drawers in '" + settings.worldName + "' in batches of " +
                settings.placementBatch + "/tick.",
        ))
    }

    private fun runStressMutations(sender: CommandSender, operationsPerTick: Int, seconds: Int, settings: StressSettings) {
        if (stressBusy) return sender.sendMessage(Component.text("A stress batch is already running."))
        val fixtures = stressFixtures.mapNotNull { (key, id) ->
            specById[id]?.let { StressFixture(key, id) to it }
        }.filter { (fixture, spec) ->
            val world = Bukkit.getWorld(fixture.key.worldId)
            world?.name == settings.worldName && world.isChunkLoaded(fixture.key.x shr 4, fixture.key.z shr 4) &&
                ce.drawerType(world.getBlockAt(fixture.key.x, fixture.key.y, fixture.key.z)) == spec.id &&
                (loaded[fixture.key]?.slots?.any { it.itemBlob != null && it.amount > 1L } == true ||
                    store.load(fixture.key, spec.slots)?.slots?.any { it.itemBlob != null && it.amount > 1L } == true)
        }
        if (fixtures.isEmpty()) {
            return sender.sendMessage(Component.text("No loaded, filled stress fixtures. Run setup with 'filled' first."))
        }

        stressBusy = true
        val startNanos = System.nanoTime()
        val deadline = startNanos + TimeUnit.SECONDS.toNanos(seconds.toLong())
        var fixtureCursor = 0
        var slotCursor = 0
        var operations = 0L
        var totalOperationNanos = 0L
        var maxOperationNanos = 0L
        var maxBatchNanos = 0L
        lateinit var task: BukkitTask
        task = server.scheduler.runTaskTimer(this, Runnable {
            val batchStart = System.nanoTime()
            repeat(operationsPerTick) {
                if (System.nanoTime() >= deadline) return@repeat
                val (fixture, spec) = fixtures[fixtureCursor++ % fixtures.size]
                val key = fixture.key
                val record = loaded[key] ?: store.load(key, spec.slots)?.also { loaded[key] = it } ?: return@repeat
                val index = slotCursor++ % spec.slots
                val slot = record.slots[index]
                val itemBlob = slot.itemBlob ?: return@repeat
                if (slot.amount <= 1L) return@repeat
                val lock = locks.computeIfAbsent(key) { ReentrantLock() }
                lock.withLock {
                    val before = System.nanoTime()
                    val sample = decodeItem(itemBlob) ?: return@withLock
                    val limit = capacity(key, spec, sample)
                    val nextAmount = if (slot.amount >= limit) limit - 1 else min(limit, slot.amount + 1)
                    if (nextAmount <= 0L) return@withLock
                    val updated = slot.copy(amount = nextAmount).normalized()
                    val revision = store.updateSlot(
                        record,
                        SlotChange(index, updated),
                        UUID.randomUUID(),
                        "STRESS_WRITE",
                    ) ?: return@withLock
                    slot.itemBlob = updated.itemBlob
                    slot.amount = updated.amount
                    slot.locked = updated.locked
                    record.revision = revision
                    loaded[key] = record
                    displays.refresh(record, spec)
                    refreshDrawerMenus(key)
                    val elapsed = System.nanoTime() - before
                    totalOperationNanos += elapsed
                    maxOperationNanos = max(maxOperationNanos, elapsed)
                    operations++
                }
            }
            maxBatchNanos = max(maxBatchNanos, System.nanoTime() - batchStart)
            if (System.nanoTime() >= deadline) {
                task.cancel()
                scheduledTasks.remove(task)
                stressTask = null
                stressBusy = false
                val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
                val averageMillis = if (operations == 0L) 0.0 else totalOperationNanos / operations / 1_000_000.0
                sender.sendMessage(Component.text(
                    "Stress writes complete: operations=" + operations +
                        ", wall=" + "%.2f".format(elapsedSeconds) + "s" +
                        ", ops/s=" + "%.1f".format(operations / elapsedSeconds) +
                        ", avg-op=" + "%.3f".format(averageMillis) + "ms" +
                        ", max-op=" + "%.3f".format(maxOperationNanos / 1_000_000.0) + "ms" +
                        ", max-batch=" + "%.3f".format(maxBatchNanos / 1_000_000.0) + "ms",
                ))
                sender.sendMessage(Component.text(
                    "This synthetic loop measures SQLite slot commits plus display/menu refresh, " +
                        "not client packet or network costs. Capture Spark profiler concurrently.",
                ))
            }
        }, 1L, 1L)
        stressTask = task
        scheduledTasks += task
        sender.sendMessage(Component.text(
            "Running " + operationsPerTick + " synthetic slot mutations/tick for " + seconds +
                "s; capture Spark profiler concurrently.",
        ))
    }

    private fun clearStressFixtures(sender: CommandSender, settings: StressSettings) {
        if (stressBusy) return sender.sendMessage(Component.text("A stress batch is already running."))
        if (stressFixtures.isEmpty()) return sender.sendMessage(Component.text("No tracked stress fixtures."))
        stressBusy = true
        val fixtures = stressFixtures.toList()
        var cursor = 0
        var removed = 0
        var skipped = 0
        lateinit var task: BukkitTask
        task = server.scheduler.runTaskTimer(this, Runnable {
            repeat(settings.placementBatch) {
                if (cursor >= fixtures.size) return@repeat
                val (key, expectedType) = fixtures[cursor++]
                val world = Bukkit.getWorld(key.worldId)
                if (world == null || world.name != settings.worldName) {
                    skipped++
                    return@repeat
                }
                world.getChunkAt(key.x shr 4, key.z shr 4)
                val block = world.getBlockAt(key.x, key.y, key.z)
                val actualType = ce.drawerType(block)
                val storedType = store.specId(key)
                if ((actualType != null && actualType != expectedType) ||
                    (storedType != null && storedType != expectedType)
                ) {
                    skipped++
                    logger.warning(
                        "Stress cleanup skipped " + key.x + "," + key.y + "," + key.z +
                            ": expected=" + expectedType + " actualBlock=" + actualType + " stored=" + storedType,
                    )
                    return@repeat
                }
                try {
                    closeMenusForDrawer(key)
                    displays.remove(key)
                    loaded.remove(key)
                    if (storedType == expectedType) store.remove(key)
                    if (actualType == expectedType && !ce.removeDrawer(block)) {
                        skipped++
                        return@repeat
                    }
                    stressFixtures.remove(key)
                    saveStressFixtures()
                    removed++
                } catch (error: Throwable) {
                    skipped++
                    logger.warning("Stress cleanup failed for " + key + ": " + error.message)
                }
            }
            if (cursor >= fixtures.size) {
                task.cancel()
                scheduledTasks.remove(task)
                stressTask = null
                stressBusy = false
                sender.sendMessage(Component.text(
                    "Stress cleanup complete: removed=" + removed + ", skipped=" + skipped +
                        ", remaining=" + stressFixtures.size + ".",
                ))
            }
        }, 1L, 1L)
        stressTask = task
        scheduledTasks += task
        sender.sendMessage(Component.text("Clearing only tracked fixtures in '" + settings.worldName + "'."))
    }

    private fun status(sender: CommandSender) {
        sender.sendMessage(message("command.status", "version" to pluginMeta.version, "minecraft" to server.minecraftVersion))
    }

    private fun message(key: String, vararg variables: Pair<String, Any>): Component =
        language.component(key, variables.associate { it.first to it.second.toString() })

    private fun drawerMessage(id: String, key: DrawerKey, vararg variables: Pair<String, Any>): Component =
        message(id, "world" to (Bukkit.getWorld(key.worldId)?.name ?: key.worldId.toString()),
            "x" to key.x, "y" to key.y, "z" to key.z, *variables)

    private fun ownerName(owner: UUID?): String = owner?.let { Bukkit.getPlayer(it)?.name ?: it.toString() }
        ?: language.raw("common.none")

    private fun itemLabel(item: ItemStack): String = CraftEngineItems.getCustomItemId(item)?.asString()
        ?: item.type.key.asString()

    private fun reloadPresentation(sender: CommandSender) {
        val replacement = runCatching { loadPresentation() }.getOrElse {
            sender.sendMessage(message("command.reload-failed", "error" to (it.message ?: it.javaClass.simpleName)))
            logger.warning("Could not reload GUI/language configuration; previous snapshot retained: ${it.message}")
            return
        }
        closeMenus()
        language = replacement.first
        drawerContentsMenus = replacement.second
        sender.sendMessage(message("command.reload-success"))
    }

    private fun loadPresentation(bundledOnly: Boolean = false): Pair<LanguageService, DrawerContentsMenuService> {
        val locale = if (bundledOnly) "zh_CN" else YamlConfiguration().apply {
            load(File(dataFolder, "config.yml"))
        }.getString("language", "zh_CN")!!
        val loadedLanguage = LanguageService.load(this, locale, bundledOnly)
        val drawerMenus = DrawerContentsMenuService.load(this, ce, loadedLanguage, bundledOnly)
        return loadedLanguage to drawerMenus
    }

    private fun closeMenus() {
        server.onlinePlayers.filter {
            it.openInventory.topInventory.holder is DrawerContentsMenuHolder
        }
            .forEach(Player::closeInventory)
    }

    fun giveDrawer(sender: CommandSender, type: String, amount: Int = 1) {
        val spec = specById[type] ?: return sender.sendMessage(message("command.unknown-type", "type" to type))
        giveItem(sender, "drawer_${spec.id}", amount)
    }

    private fun giveItem(sender: CommandSender, id: String, amount: Int = 1) {
        val itemId = ce.customItemId(id)
        val item = CraftEngineItems.byId(itemId)?.buildBukkitItem()?.apply { this.amount = amount }
            ?: return sender.sendMessage(message("command.item-unavailable", "item" to itemId.asString(), "type" to id))
        if (sender !is Player) return sender.sendMessage(message("command.player-only"))
        giveRemainder(sender, item)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraftEngineInteract(event: CustomBlockInteractEvent) {
        if (event.hand() != InteractionHand.MAIN_HAND) return
        val block = event.bukkitBlock()
        val type = ce.drawerType(block) ?: return
        val spec = specById[type] ?: return
        val facing = ce.facing(block)
        if (!event.clickedFace().name.equals(facing, ignoreCase = true)) return
        event.isCancelled = true
        val bukkitPlayer = event.player()
        val key = block.drawerKey()
        val record = loadOrCreate(key, spec, facing, null) ?: run {
            bukkitPlayer.sendActionBar(drawerMessage("storage.unavailable", key))
            return
        }
        // Left-click packets do not carry a face-local hit position. Using the
        // block centre here routes every extraction to the same drawer slot.
        val point = event.interactionPoint() ?: frontIntersection(bukkitPlayer, block, facing) ?: return
        if (event.action() == CustomBlockInteractEvent.Action.RIGHT_CLICK && bukkitPlayer.isSneaking &&
            bukkitPlayer.inventory.itemInMainHand.type.isAir
        ) {
            openDrawerMenu(bukkitPlayer, record, spec)
            return
        }
        val slotIndex = slotAt(point, block, facing, spec)
        if (slotIndex !in record.slots.indices) return
        interact(bukkitPlayer, event.action(), key, spec, slotIndex, record)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraftEnginePlace(event: CustomBlockPlaceEvent) {
        val type = ce.drawerType(event.bukkitBlock()) ?: return
        if (!territoryAllows(event.player(), event.location(), TerritoryAction.PLACE)) {
            event.isCancelled = true
            return
        }
        val spec = specById[type] ?: return
        val key = event.bukkitBlock().drawerKey()
        val facing = ce.facing(event.bukkitBlock())
        displays.remove(key)
        val record = loadOrCreate(key, spec, facing, null) ?: return
        if (record.facing != facing) {
            val updated = runCatching { store.updateFacing(key, facing) }
                .onFailure { logger.warning("Could not persist drawer facing at $key: ${it.message}") }
                .getOrDefault(false)
            if (updated) record.facing = facing
        }
        displays.refresh(record, spec)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHalfDrawerItemPlace(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val type = ce.drawerItemType(event.item) ?: return
        val spec = specById[type]?.takeIf { it.half } ?: return
        val clicked = event.clickedBlock ?: return
        // Leave an existing drawer's front interaction to its own event.
        if (ce.drawerType(clicked) != null && event.blockFace.name.equals(ce.facing(clicked), true)) return
        event.isCancelled = true
        placeHalfDrawer(event.player, clicked.getRelative(event.blockFace), clicked, type, spec)
    }

    private fun placeHalfDrawer(player: Player, target: Block, clicked: Block, type: String, spec: DrawerSpec) {
        if (player.gameMode != org.bukkit.GameMode.SURVIVAL && player.gameMode != org.bukkit.GameMode.CREATIVE) return
        if (!territoryAllows(player, target.location, TerritoryAction.PLACE)) return
        if (!target.type.isAir || ce.drawerType(target) != null) return
        if (target.y !in target.world.minHeight until target.world.maxHeight) return
        val source = player.inventory.itemInMainHand.clone()
        if (ce.drawerItemType(source) != type) return
        val facing = player.facing.oppositeFace.name.lowercase()
        val bounds = halfDrawerBounds(target, facing)
        if (target.world.getNearbyEntities(bounds).any { it is org.bukkit.entity.LivingEntity && it.boundingBox.overlaps(bounds) }) return
        val key = target.drawerKey()
        val definition = ce.halfDrawerDefinition(type) ?: run {
            player.sendActionBar(message("placement.pack-outdated"))
            logger.warning("Half drawer furniture $type is unavailable; install the matching CraftDrawers CraftEngine pack")
            return
        }
        val variant = definition.getVariant("wall_aligned") ?: run {
            player.sendActionBar(message("placement.pack-outdated"))
            logger.warning("Half drawer furniture $type is missing wall_aligned; install the matching CraftDrawers CE pack and reload CraftEngine")
            return
        }
        val attempt = FurnitureAttemptPlaceEvent(player, definition, variant,
            ce.halfDrawerLocation(target, facing), InteractionHand.MAIN_HAND, clicked)
        server.pluginManager.callEvent(attempt)
        if (attempt.isCancelled) return
        // Also let block-oriented protection plugins veto the occupied grid cell.
        val protection = BlockPlaceEvent(target, target.state, clicked, source, player, true, EquipmentSlot.HAND)
        server.pluginManager.callEvent(protection)
        if (protection.isCancelled || !protection.canBuild()) return
        if (!target.type.isAir || ce.drawerType(target) != null) return
        val furniture = ce.placeHalfDrawer(target, type, facing) ?: run {
            player.sendActionBar(drawerMessage("storage.unavailable", key))
            logger.warning("Half drawer furniture $type is unavailable; install the matching CraftDrawers CraftEngine pack")
            return
        }
        val placed = FurniturePlaceEvent(player, furniture, furniture.location(), InteractionHand.MAIN_HAND)
        server.pluginManager.callEvent(placed)
        if (placed.isCancelled || !player.inventory.itemInMainHand.isSimilar(source) || player.inventory.itemInMainHand.amount < 1) {
            ce.removeDrawer(target)
            return
        }
        val record = runCatching { loadOrCreate(key, spec, facing, null) }
            .onFailure { logger.severe("Could not initialize half drawer at $key: ${it.message}") }.getOrNull()
        if (record == null) {
            ce.removeDrawer(target)
            player.sendActionBar(drawerMessage("storage.unavailable", key))
            return
        }
        if (player.gameMode != org.bukkit.GameMode.CREATIVE) {
            val held = player.inventory.itemInMainHand
            player.inventory.setItemInMainHand(if (held.amount <= 1) null else held.clone().apply { amount-- })
        }
        displays.refresh(record, spec)
        player.swingMainHand()
    }

    private fun halfDrawerBounds(block: Block, facing: String): BoundingBox = when (facing) {
        "east" -> BoundingBox(block.x.toDouble(), block.y.toDouble(), block.z.toDouble(), block.x + 0.5, block.y + 1.0, block.z + 1.0)
        "south" -> BoundingBox(block.x.toDouble(), block.y.toDouble(), block.z.toDouble(), block.x + 1.0, block.y + 1.0, block.z + 0.5)
        "west" -> BoundingBox(block.x + 0.5, block.y.toDouble(), block.z.toDouble(), block.x + 1.0, block.y + 1.0, block.z + 1.0)
        else -> BoundingBox(block.x.toDouble(), block.y.toDouble(), block.z + 0.5, block.x + 1.0, block.y + 1.0, block.z + 1.0)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHalfDrawerInteract(event: FurnitureInteractEvent) {
        if (ce.drawerType(event.furniture()) == null) return
        if (event.hand() != InteractionHand.MAIN_HAND) return
        if (!isHalfDrawerFront(event.furniture(), event.interactionPoint())) {
            val type = ce.drawerItemType(event.player().inventory.itemInMainHand) ?: return
            val spec = specById[type]?.takeIf { it.half } ?: return
            event.isCancelled = true
            val block = event.furniture().location().block
            val point = event.interactionPoint()
            val face = when {
                abs(point.y - block.y - 1.0) < 0.015 -> BlockFace.UP
                abs(point.y - block.y) < 0.015 -> BlockFace.DOWN
                abs(point.x - block.x) < 0.015 -> BlockFace.WEST
                abs(point.x - block.x - 1.0) < 0.015 -> BlockFace.EAST
                abs(point.z - block.z) < 0.015 -> BlockFace.NORTH
                abs(point.z - block.z - 1.0) < 0.015 -> BlockFace.SOUTH
                // Only use the back direction as a tolerance fallback; the
                // front at the middle of the cell is handled separately.
                else -> BlockFace.valueOf(ce.facing(event.furniture()).uppercase()).oppositeFace
            }
            placeHalfDrawer(event.player(), block.getRelative(face), block, type, spec)
            return
        }
        event.isCancelled = true
        interactWithHalfDrawer(event.player(), event.furniture(), event.interactionPoint(), CustomBlockInteractEvent.Action.RIGHT_CLICK)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHalfDrawerHit(event: FurnitureHitEvent) {
        if (ce.drawerType(event.furniture()) == null) return
        // Front clicks extract; hitting a side/back follows the normal empty-drawer break checks.
        if (!isHalfDrawerFront(event.furniture(), event.hitPoint())) return
        event.isCancelled = true
        interactWithHalfDrawer(event.player(), event.furniture(), event.hitPoint(), CustomBlockInteractEvent.Action.LEFT_CLICK)
    }

    private fun isHalfDrawerFront(furniture: BukkitFurniture, point: Location): Boolean {
        val block = furniture.location().block
        val depth = ce.frontDepth(furniture)
        return when (ce.facing(furniture)) {
            "east" -> abs(point.x - block.x - 1.0 + depth) < 0.015
            "west" -> abs(point.x - block.x - depth) < 0.015
            "south" -> abs(point.z - block.z - 1.0 + depth) < 0.015
            else -> abs(point.z - block.z - depth) < 0.015
        } && point.y >= block.y - 0.001 && point.y <= block.y + 1.001
    }

    private fun interactWithHalfDrawer(player: Player, furniture: BukkitFurniture, point: Location, action: CustomBlockInteractEvent.Action) {
        if (!isHalfDrawerFront(furniture, point)) return
        val spec = ce.drawerType(furniture)?.let(specById::get) ?: return
        val block = furniture.location().block
        val key = block.drawerKey()
        val facing = ce.facing(furniture)
        val record = loadOrCreate(key, spec, facing, null) ?: run {
            player.sendActionBar(drawerMessage("storage.unavailable", key))
            return
        }
        if (action == CustomBlockInteractEvent.Action.RIGHT_CLICK && player.isSneaking && player.inventory.itemInMainHand.type.isAir) {
            openDrawerMenu(player, record, spec)
            return
        }
        val slot = slotAt(point, block, facing, spec)
        if (slot in record.slots.indices) interact(player, action, key, spec, slot, record)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHalfDrawerBreak(event: FurnitureBreakEvent) {
        val type = ce.drawerType(event.furniture()) ?: return
        event.isCancelled = true
        breakDrawer(event.furniture().location().block, type, event.player())
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun protectHalfDrawerCell(event: BlockPlaceEvent) {
        if (ce.halfFurniture(event.blockPlaced) != null) event.isCancelled = true
    }

    private fun drawerLocation(key: DrawerKey): Location? =
        Bukkit.getWorld(key.worldId)?.getBlockAt(key.x, key.y, key.z)?.location

    private fun territoryAllows(player: Player, location: Location?, action: TerritoryAction): Boolean {
        val protection = territoryProtection ?: return true
        if (location != null && protection.allows(player, location, action)) return true
        player.sendActionBar(message("protection.territory-denied"))
        return false
    }

    private fun interact(
        player: Player,
        action: CustomBlockInteractEvent.Action,
        key: DrawerKey,
        spec: DrawerSpec,
        slotIndex: Int,
        record: StoredDrawer,
    ) {
        if (!territoryAllows(player, drawerLocation(key), TerritoryAction.INTERACT)) return
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        lock.withLock {
            val held = player.inventory.itemInMainHand
            if (action == CustomBlockInteractEvent.Action.RIGHT_CLICK && ce.isPersonalKey(held)) {
                if (record.owner == null && store.setOwner(key, player.uniqueId)) {
                    record.owner = player.uniqueId
                    player.sendActionBar(message("ownership.claimed"))
                    refreshDrawerMenus(key)
                } else if (record.owner != player.uniqueId) {
                    player.sendActionBar(message("ownership.already-owned", "owner" to ownerName(record.owner)))
                }
                return
            }
            if (record.owner != null && record.owner != player.uniqueId && !player.hasPermission("craftdrawers.admin")) {
                player.sendActionBar(message("ownership.no-access"))
                return
            }
            val slot = record.slots[slotIndex]
            when (action) {
                CustomBlockInteractEvent.Action.LEFT_CLICK -> {
                    val itemBlob = slot.itemBlob ?: return
                    if (slot.amount <= 0) return
                    val sample = decodeItem(itemBlob) ?: return
                    val requested = if (player.isSneaking) min(sample.maxStackSize.toLong(), slot.amount) else 1L
                    val canFit = inventoryCapacity(player, sample)
                    val take = min(requested, canFit).toInt()
                    if (take <= 0) return
                    val claim = ItemClaim(UUID.randomUUID(), player.uniqueId, itemBlob.copyOf(), take)
                    val changed = slot.copy(amount = slot.amount - take).normalized()
                    val revision = store.updateSlot(record, SlotChange(slotIndex, changed), UUID.randomUUID(), "PLAYER_EXTRACT", claim)
                    if (revision == null) {
                        player.sendActionBar(message("storage.busy"))
                        return
                    }
                    slot.itemBlob = changed.itemBlob
                    slot.amount = changed.amount
                    record.revision = revision
                    val next = record
                    loaded[key] = next
                    deliverClaim(player, claim)
                    displays.refresh(next, spec)
                    refreshDrawerMenus(key)
                }
                CustomBlockInteractEvent.Action.RIGHT_CLICK -> {
                    if (ce.isDrawerKey(held)) {
                        val changed = slot.copy(locked = !slot.locked).normalized()
                        val revision = store.updateSlot(record, SlotChange(slotIndex, changed), UUID.randomUUID(), "TOGGLE_SLOT_LOCK")
                            ?: return
                        slot.itemBlob = changed.itemBlob
                        slot.locked = changed.locked
                        record.revision = revision
                        player.sendActionBar(message(if (changed.locked) "slot.locked" else "slot.unlocked", "slot" to slotIndex + 1))
                        refreshDrawerMenus(key)
                        return
                    }
                    val upgradeId = ce.drawerUpgradeId(held)
                    if (upgradeId != null) {
                        installUpgrade(player, key, spec, upgradeId, held) {
                            if (player.gameMode != org.bukkit.GameMode.CREATIVE) {
                                val remaining = held.amount - 1
                                player.inventory.setItemInMainHand(if (remaining <= 0) null else held.clone().apply { amount = remaining })
                            }
                        }
                        return
                    }
                    if (ce.isDrawerItem(held)) return
                    if (held.type.isAir) return
                    val sourceTemplate = held.clone().apply { amount = 1 }
                    val existing = slot.itemBlob?.let { decodeItem(it) ?: return }
                    if (existing != null && !existing.isSimilar(sourceTemplate)) return
                    if (slot.locked && existing == null) return
                    val capacity = capacity(key, spec, sourceTemplate)
                    val free = max(0L, capacity - slot.amount)
                    val isVoid = hasUpgrade(key, "void")
                    val requested = held.amount.toLong()
                    val accepted = min(requested, free).toInt()
                    if (accepted <= 0 && !isVoid) return
                    if (accepted == 0 && isVoid) {
                        if (player.gameMode != org.bukkit.GameMode.CREATIVE) player.inventory.setItemInMainHand(null)
                        return
                    }
                    val changed = slot.copy(
                        itemBlob = slot.itemBlob ?: sourceTemplate.serializeAsBytes(),
                        amount = slot.amount + accepted,
                    )
                    val revision = store.updateSlot(record, SlotChange(slotIndex, changed), UUID.randomUUID(), "PLAYER_INSERT")
                    if (revision == null) {
                        player.sendActionBar(message("storage.busy"))
                        return
                    }
                    slot.itemBlob = changed.itemBlob
                    slot.amount = changed.amount
                    record.revision = revision
                    val next = record
                    loaded[key] = next
                    if (player.gameMode != org.bukkit.GameMode.CREATIVE) {
                        val consumed = if (isVoid) requested.toInt() else accepted
                        val remaining = held.amount - consumed
                        player.inventory.setItemInMainHand(if (remaining <= 0) null else held.clone().apply { amount = remaining })
                    }
                    displays.refresh(next, spec)
                    refreshDrawerMenus(key)
                }
            }
        }
    }

    private fun loadOrCreate(key: DrawerKey, spec: DrawerSpec, facing: String, owner: UUID?): StoredDrawer? {
        loaded[key]?.let { current ->
            if (current.specId != spec.id) {
                logger.severe("Drawer type mismatch at $key: stored=${current.specId}, block=${spec.id}; data retained")
                loaded.remove(key, current)
                return null
            }
            synchronizeFacing(current, facing)
            return current
        }
        val current = store.load(key, spec.slots)
        if (current != null) {
            if (current.specId != spec.id) {
                logger.severe("Drawer type mismatch at $key: stored=${current.specId}, block=${spec.id}; data retained")
                return null
            }
            synchronizeFacing(current, facing)
            loaded[key] = current
            return current
        }
        val empty = StoredDrawer(
            key,
            spec.id,
            owner,
            facing,
            MutableList(spec.slots) { StoredSlot(null, 0, false) },
            0,
        )
        if (!store.create(key, spec.id, owner, facing, empty.slots)) return store.load(key, spec.slots)?.also { loaded[key] = it }
        loaded[key] = empty
        return empty
    }

    private fun synchronizeFacing(record: StoredDrawer, facing: String) {
        if (record.facing == facing) return
        if (store.updateFacing(record.key, facing)) record.facing = facing
    }

    private fun decodeItem(blob: ByteArray): ItemStack? = runCatching { ItemStack.deserializeBytes(blob).apply { amount = 1 } }
        .onFailure { logger.warning("Stored ItemStack decode failed: ${it.message}") }
        .getOrNull()

    private fun inventoryCapacity(player: Player, sample: ItemStack): Long {
        var capacity = 0L
        val maxPerStack = sample.maxStackSize
        for (item in player.inventory.storageContents) {
            when {
                item == null || item.type.isAir -> capacity += maxPerStack
                item.isSimilar(sample) -> capacity += max(0, min(maxPerStack, item.maxStackSize) - item.amount)
            }
        }
        return capacity
    }

    private fun deliverClaim(player: Player, pending: ItemClaim) {
        deferredClaimAcknowledgements[pending.id]?.let { acknowledgement ->
            if (!acknowledgeClaim(player, pending.id, acknowledgement)) return
        }
        // A queued claim snapshot can already have been delivered by another
        // main-thread callback. SQLite is authoritative at the point of delivery.
        val claim = store.claim(pending.id, player.uniqueId) ?: run {
            clearClaimMarker(player, pending.id.toString())
            return
        }
        val sample = decodeItem(claim.itemBlob) ?: run {
            logger.severe("Quarantined unreadable item claim ${claim.id}; item data was retained in SQLite")
            return
        }
        val marker = claim.id.toString()
        var alreadyDelivered = 0
        player.inventory.storageContents.forEachIndexed { index, stack ->
            if (stack == null || stack.type.isAir) return@forEachIndexed
            val data = stack.itemMeta.persistentDataContainer
            if (data.get(claimMarker, PersistentDataType.STRING) != marker) return@forEachIndexed
            val before = data.get(claimBeforeMarker, PersistentDataType.INTEGER)
            if (before != null && before > claim.amount) {
                // SQLite acknowledged this receipt before the marker was removed.
                player.inventory.setItem(index, withoutClaimMetadata(stack))
            } else {
                alreadyDelivered += (data.get(claimCountMarker, PersistentDataType.INTEGER) ?: stack.amount)
                    .coerceIn(0, stack.amount)
            }
        }
        var remaining = (claim.amount - alreadyDelivered).coerceAtLeast(0)
        // Fill matching stacks first, then empty slots. A receipt records only
        // the added quantity, so existing items are never counted as delivered.
        for (fillEmpty in listOf(false, true)) {
            for (index in player.inventory.storageContents.indices) {
                if (remaining == 0) break
                val current = player.inventory.getItem(index)?.takeUnless { it.type.isAir }
                if ((current == null) != fillEmpty) continue
                var receiptCount = 0
                if (current != null) {
                    val data = current.itemMeta.persistentDataContainer
                    val currentMarker = data.get(claimMarker, PersistentDataType.STRING)
                    if (currentMarker != null && currentMarker != marker) continue
                    val comparable = if (currentMarker == marker) withoutClaimMetadata(current) else current
                    if (!comparable.isSimilar(sample)) continue
                    if (currentMarker == marker) {
                        receiptCount = (data.get(claimCountMarker, PersistentDataType.INTEGER) ?: current.amount)
                            .coerceIn(0, current.amount)
                    }
                }
                val stackLimit = min(sample.maxStackSize, current?.maxStackSize ?: sample.maxStackSize)
                val count = min(remaining, (stackLimit - (current?.amount ?: 0)).coerceAtLeast(0))
                if (count == 0) continue
                val delivered = (current ?: sample).clone().apply {
                    amount = (current?.amount ?: 0) + count
                    editMeta { meta ->
                        meta.persistentDataContainer.set(claimMarker, PersistentDataType.STRING, marker)
                        meta.persistentDataContainer.set(claimCountMarker, PersistentDataType.INTEGER, receiptCount + count)
                        meta.persistentDataContainer.set(claimBeforeMarker, PersistentDataType.INTEGER, claim.amount)
                    }
                }
                player.inventory.setItem(index, delivered)
                remaining -= count
            }
        }
        val acknowledgement = ClaimAcknowledgement(player.uniqueId, claim.amount, remaining)
        deferredClaimAcknowledgements[claim.id] = acknowledgement
        // Clear receipts after every successful checkpoint, including partial
        // deliveries; otherwise the next retry subtracts the same items twice.
        if (!acknowledgeClaim(player, claim.id, acknowledgement)) {
            player.sendActionBar(message("claim.awaiting-confirmation", "item" to itemLabel(sample), "remaining" to claim.amount))
        } else if (remaining > 0) {
            player.sendActionBar(message("claim.inventory-full", "item" to itemLabel(sample), "remaining" to remaining))
        }
    }

    private fun acknowledgeClaim(player: Player, claimId: UUID, acknowledgement: ClaimAcknowledgement): Boolean {
        val acknowledged = runCatching {
            val current = store.claim(claimId, player.uniqueId)
            when {
                current == null -> acknowledgement.remaining == 0
                current.amount == acknowledgement.expected ->
                    store.updateClaim(claimId, acknowledgement.expected, acknowledgement.remaining)
                current.amount == acknowledgement.remaining -> true
                else -> false
            }
        }.onFailure { logger.severe("Could not acknowledge item claim $claimId: ${it.message}") }
            .getOrDefault(false)
        if (acknowledged) {
            deferredClaimAcknowledgements.remove(claimId)
            clearClaimMarker(player, claimId.toString())
        }
        return acknowledged
    }

    private fun withoutClaimMetadata(stack: ItemStack): ItemStack = stack.clone().apply {
        editMeta { meta ->
            meta.persistentDataContainer.remove(claimMarker)
            meta.persistentDataContainer.remove(claimCountMarker)
            meta.persistentDataContainer.remove(claimBeforeMarker)
        }
    }

    private fun clearClaimMarker(player: Player, marker: String) {
        player.inventory.contents.forEachIndexed { index, stack ->
            if (stack == null || stack.type.isAir || stack.itemMeta.persistentDataContainer.get(claimMarker, PersistentDataType.STRING) != marker) return@forEachIndexed
            player.inventory.setItem(index, withoutClaimMetadata(stack))
        }
        val cursor = player.itemOnCursor
        if (!cursor.type.isAir && cursor.itemMeta.persistentDataContainer.get(claimMarker, PersistentDataType.STRING) == marker) {
            player.setItemOnCursor(withoutClaimMetadata(cursor))
        }
    }

    private fun clearCompletedClaimMarkers(player: Player) {
        val markers = (player.inventory.contents.toList() + player.itemOnCursor).mapNotNull { stack ->
            if (stack == null || stack.type.isAir) null
            else stack.itemMeta.persistentDataContainer.get(claimMarker, PersistentDataType.STRING)
        }.distinct()
        markers.forEach { marker ->
            val id = runCatching { UUID.fromString(marker) }.getOrNull() ?: return@forEach
            if (store.claim(id, player.uniqueId) == null) clearClaimMarker(player, marker)
        }
    }

    private fun installUpgrade(
        player: Player,
        key: DrawerKey,
        spec: DrawerSpec,
        upgradeId: String,
        held: ItemStack,
        preferredSlot: Int? = null,
        consume: () -> Unit,
    ) {
        val family = upgradeFamily(upgradeId)
        val installed = store.upgrades(key)
        if (family in setOf("one_stack", "void", "hopper", "magnet") && installed.any { upgradeFamily(it.second) == family }) {
            player.sendActionBar(message("upgrade.family-installed", "upgrade_name" to drawerContentsMenus.upgradeName(upgradeId)))
            return
        }
        if (installed.size >= 7) {
            player.sendActionBar(message("upgrade.full"))
            return
        }
        val freeSlot = if (preferredSlot != null) {
            if (preferredSlot !in 0 until 7 || store.upgradeId(key, preferredSlot) != null) return
            preferredSlot
        } else {
            store.firstFreeUpgradeSlot(key) ?: return
        }
        if (!store.insertUpgrade(key, freeSlot, upgradeId)) return
        if (held.type.isAir) return
        consume()
        loaded[key]?.let { displays.refresh(it, spec) }
        refreshDrawerMenus(key)
    }

    private fun upgradeFamily(id: String): String = when {
        id.startsWith("storage_") -> "storage"
        id.startsWith("magnet_") -> "magnet"
        id in singleUpgradeIds -> id
        else -> id
    }

    private fun openDrawerMenu(player: Player, record: StoredDrawer, spec: DrawerSpec) {
        if (!territoryAllows(player, drawerLocation(record.key), TerritoryAction.INTERACT)) return
        if (record.owner != null && record.owner != player.uniqueId && !player.hasPermission("craftdrawers.admin")) {
            player.sendActionBar(message("ownership.no-access"))
            return
        }
        player.openInventory(drawerContentsMenus.create(player, record, spec, store.upgrades(record.key).toMap()))
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrawerMenuClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? DrawerContentsMenuHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (stopping || event.click != ClickType.LEFT || event.rawSlot < 0 || holder.actionPending) return
        val bottomSlot = if (event.rawSlot >= event.view.topInventory.size && event.clickedInventory === player.inventory)
            event.slot.takeIf { it in player.inventory.storageContents.indices } else null
        val icon = holder.definition.layout.getOrNull(event.rawSlot)
        if (bottomSlot == null && icon?.type !in setOf(
                DrawerContentsIconType.CLOSE,
                DrawerContentsIconType.INFO,
                DrawerContentsIconType.UPGRADE_SLOT,
            )
        ) return
        holder.actionPending = true
        server.scheduler.runTask(this, Runnable {
            try {
                if (stopping || !player.isOnline || player.openInventory.topInventory.holder !== holder) return@Runnable
                val key = holder.drawerKey
                val record = loaded[key]
                val world = Bukkit.getWorld(key.worldId)
                if (record !== holder.record || world == null || player.world != world ||
                    !world.isChunkLoaded(key.x shr 4, key.z shr 4) ||
                    ce.drawerType(world.getBlockAt(key.x, key.y, key.z)) != record.specId
                ) {
                    player.closeInventory()
                    player.sendActionBar(message("gui.upgrades.session-expired"))
                    return@Runnable
                }
                val drawerLocation = world.getBlockAt(key.x, key.y, key.z).location
                if (!territoryAllows(player, drawerLocation, TerritoryAction.INTERACT)) {
                    player.closeInventory()
                    return@Runnable
                }
                if (record.owner != null && record.owner != player.uniqueId && !player.hasPermission("craftdrawers.admin")) {
                    player.closeInventory()
                    player.sendActionBar(message("ownership.no-access"))
                    return@Runnable
                }
                if (bottomSlot != null) {
                    moveMenuCursor(player, bottomSlot)
                    return@Runnable
                }
                when (icon?.type) {
                    DrawerContentsIconType.CLOSE -> player.closeInventory()
                    DrawerContentsIconType.INFO, DrawerContentsIconType.DECORATION, DrawerContentsIconType.DRAWER_SLOT, null -> Unit
                    DrawerContentsIconType.UPGRADE_SLOT -> {
                        val slot = icon.upgradeSlot ?: return@Runnable
                        val spec = specById[record.specId] ?: return@Runnable
                        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
                        lock.withLock {
                            val installed = store.upgradeId(key, slot)
                            val cursor = player.itemOnCursor.clone()
                            if (installed != null && cursor.type.isAir) {
                                val item = CraftEngineItems.byId(ce.upgradeItemId(installed))?.buildBukkitItem() ?: return@withLock
                                val claim = store.removeUpgradeToClaim(key, slot, player.uniqueId, item.serializeAsBytes()) ?: return@withLock
                                deliverClaim(player, claim)
                                refreshDrawerMenus(key)
                            } else if (installed != null) {
                                player.sendActionBar(message("upgrade.cursor-not-empty"))
                            } else if (!cursor.type.isAir) {
                                // The click event is deferred by one tick. Check the
                                // current cursor before committing the database slot.
                                val currentCursor = player.itemOnCursor
                                if (currentCursor.type.isAir || !currentCursor.isSimilar(cursor) || currentCursor.amount != cursor.amount) {
                                    player.sendActionBar(message("gui.upgrades.session-expired"))
                                    return@withLock
                                }
                                val upgradeId = ce.drawerUpgradeId(currentCursor)
                                if (upgradeId == null) {
                                    player.sendActionBar(message("upgrade.not-upgrade", "item" to itemLabel(currentCursor)))
                                    return@withLock
                                }
                                installUpgrade(player, key, spec, upgradeId, currentCursor, slot) {
                                    if (player.gameMode != org.bukkit.GameMode.CREATIVE) {
                                        val remaining = currentCursor.amount - 1
                                        player.setItemOnCursor(if (remaining <= 0) ItemStack.empty() else currentCursor.clone().apply { amount = remaining })
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                logger.warning("Could not process drawer menu at ${holder.drawerKey}: ${error.message}")
                if (player.isOnline) player.sendActionBar(drawerMessage("storage.unavailable", holder.drawerKey))
            } finally {
                holder.actionPending = false
            }
        })
    }

    /** Explicit left-click selection in the player inventory while all vanilla GUI actions are cancelled. */
    private fun moveMenuCursor(player: Player, slot: Int) {
        val target = player.inventory.getItem(slot)?.clone()?.takeUnless { it.type.isAir }
        val cursor = player.itemOnCursor.clone()
        when {
            cursor.type.isAir -> {
                if (target == null) return
                player.inventory.setItem(slot, null)
                player.setItemOnCursor(target)
            }
            target == null -> {
                player.inventory.setItem(slot, cursor)
                player.setItemOnCursor(ItemStack.empty())
            }
            target.isSimilar(cursor) -> {
                val moved = min(cursor.amount, (min(cursor.maxStackSize, target.maxStackSize) - target.amount).coerceAtLeast(0))
                if (moved == 0) return
                target.amount += moved
                player.inventory.setItem(slot, target)
                val remaining = cursor.amount - moved
                player.setItemOnCursor(if (remaining == 0) ItemStack.empty() else cursor.apply { amount = remaining })
            }
            else -> {
                player.inventory.setItem(slot, cursor)
                player.setItemOnCursor(target)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrawerMenuDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is DrawerContentsMenuHolder) event.isCancelled = true
    }

    private fun refreshDrawerMenus(key: DrawerKey) {
        val upgrades = store.upgrades(key).toMap()
        val record = loaded[key] ?: return
        val spec = specById[record.specId] ?: return
        server.onlinePlayers.forEach { viewer ->
            val inventory = viewer.openInventory.topInventory
            val holder = inventory.holder as? DrawerContentsMenuHolder ?: return@forEach
            if (holder.drawerKey != key) return@forEach
            if (holder.record !== record ||
                record.owner != null && record.owner != viewer.uniqueId && !viewer.hasPermission("craftdrawers.admin")
            ) {
                viewer.closeInventory()
            } else {
                drawerContentsMenus.render(inventory, viewer, record, spec, upgrades)
            }
        }
    }

    private fun giveRemainder(player: Player, stack: ItemStack) {
        val overflow = player.inventory.addItem(stack)
        overflow.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun capacityUpgradeMultiplier(key: DrawerKey): Long {
        var multiplier = 0L
        for ((_, id) in store.upgrades(key)) {
            if (id !in storageUpgradeIds) continue
            multiplier += when (id) {
                "storage_obsidian" -> 2L
                "storage_copper" -> 4L
                "storage_iron" -> 8L
                "storage_gold" -> 16L
                "storage_emerald" -> 64L
                "storage_diamond" -> 256L
                "storage_netherite" -> 2048L
                else -> 0L
            }
        }
        return multiplier
    }

    private fun hasUpgrade(key: DrawerKey, upgradeId: String): Boolean =
        store.upgrades(key).any { it.second == upgradeId }

    private fun capacity(key: DrawerKey, spec: DrawerSpec, sample: ItemStack): Long {
        if (hasUpgrade(key, "one_stack")) return sample.maxStackSize.toLong()
        val multiplier = 1L + capacityUpgradeMultiplier(key)
        return runCatching { Math.multiplyExact(spec.capacity(sample.maxStackSize), multiplier) }
            .getOrElse { Long.MAX_VALUE }
    }

    private fun slotAt(point: Location, block: Block, facing: String, spec: DrawerSpec): Int {
        val localX = point.x - block.x
        val localY = point.y - block.y
        val localZ = point.z - block.z
        val screenU = when (facing.lowercase()) {
            "north" -> 1.0 - localX
            "south" -> localX
            "east" -> 1.0 - localZ
            "west" -> localZ
            else -> 1.0 - localX
        }
        val column = if (screenU < 0.5) 0 else 1
        val row = if (localY >= 0.5) 0 else 1
        return when (spec.slots) {
            1 -> 0
            2 -> row
            4 -> row * 2 + column
            else -> -1
        }
    }

    private fun frontIntersection(player: Player, block: Block, facing: String): Location? {
        val eye = player.eyeLocation
        val direction = eye.direction
        val horizontalX = facing.equals("east", true) || facing.equals("west", true)
        val plane = when (facing.lowercase()) {
            "east" -> block.x + 1.0
            "west" -> block.x.toDouble()
            "south" -> block.z + 1.0
            else -> block.z.toDouble()
        }
        val component = if (horizontalX) direction.x else direction.z
        if (abs(component) < 1.0e-8) return null
        val distance = (plane - if (horizontalX) eye.x else eye.z) / component
        if (distance < 0.0) return null
        val hit = eye.clone().add(direction.multiply(distance))
        val u = if (horizontalX) hit.z - block.z else hit.x - block.x
        val v = hit.y - block.y
        if (u !in -1.0e-6..1.000001 || v !in -1.0e-6..1.000001) return null
        return hit
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCraftEngineBreak(event: CustomBlockBreakEvent) {
        val block = event.bukkitBlock()
        val type = ce.drawerType(block) ?: return
        event.isCancelled = true
        val player = event.player().platformEntity()
        breakDrawer(block, type, player)
    }

    private fun breakDrawer(block: Block, type: String, player: Player) {
        if (!territoryAllows(player, block.location, TerritoryAction.BREAK)) return
        val key = block.drawerKey()
        val spec = specById[type] ?: return
        val record = runCatching { loadOrCreate(key, spec, ce.facing(block), null) }
            .onFailure { logger.severe("Could not load drawer before break at $key: ${it.message}") }
            .getOrNull()
        if (record == null) {
            player.sendActionBar(drawerMessage("break.unavailable", key))
            return
        }
        if (record.owner != null && record.owner != player.uniqueId && !player.hasPermission("craftdrawers.admin")) {
            player.sendActionBar(message("break.owner-only", "owner" to ownerName(record.owner)))
            return
        }
        if (record.slots.any { it.amount > 0 }) {
            player.sendActionBar(message("break.not-empty", "used" to record.slots.count { it.amount > 0 }, "capacity" to spec.slots))
            return
        }
        val upgrades = runCatching { store.upgrades(key) }
            .onFailure { logger.severe("Could not inspect drawer upgrades at $key: ${it.message}") }
            .getOrNull()
        if (upgrades == null) {
            player.sendActionBar(drawerMessage("break.unavailable", key))
            return
        }
        if (upgrades.isNotEmpty()) {
            player.sendActionBar(message("break.has-upgrades"))
            return
        }
        if (ce.removeDrawer(block)) {
            closeMenusForDrawer(key)
            displays.remove(key)
            loaded.remove(key)
            runCatching { store.remove(key) }
                .onFailure { logger.severe("Could not remove empty drawer data at $key: ${it.message}") }
            if (player.gameMode != org.bukkit.GameMode.CREATIVE) {
                CraftEngineItems.byId(ce.drawerItemId(type))?.buildBukkitItem()?.let { giveRemainder(player, it) }
            }
        } else {
            displays.refresh(record, spec)
            player.sendActionBar(drawerMessage("break.failed", key))
        }
    }

    @EventHandler
    fun onChunkLoad(event: org.bukkit.event.world.ChunkLoadEvent) {
        restoreChunk(event.chunk)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDrawerEntitiesLoad(event: org.bukkit.event.world.EntitiesLoadEvent) {
        val chunk = event.chunk
        server.scheduler.runTask(this, Runnable {
            if (!stopping && chunk.world.isChunkLoaded(chunk.x, chunk.z)) restoreChunk(chunk)
        })
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDrawerCraftEngineReload(event: CraftEngineReloadEvent) {
        server.scheduler.runTask(this, Runnable {
            if (!stopping && ::ce.isInitialized) {
                ce.forgetHalfDrawers()
                server.worlds.forEach { world -> world.loadedChunks.forEach(::restoreChunk) }
            }
        })
    }

    private fun restoreChunk(chunk: org.bukkit.Chunk) = restoreChunk(chunk, 0)

    private fun restoreChunk(chunk: org.bukkit.Chunk, attempt: Int) {
        if (stopping) return
        val worldId = chunk.world.uid
        val chunkX = chunk.x
        val chunkZ = chunk.z
        val loadKey = chunkLoadKey(worldId, chunkX, chunkZ)
        val loadToken = chunkLoadSequence.incrementAndGet()
        activeChunkLoads[loadKey] = loadToken
        databaseExecutor.execute {
            if (stopping) return@execute
            val rows = runCatching {
                store.loadChunk(worldId, chunkX, chunkZ).mapNotNull { key ->
                    val spec = specById[store.specId(key)] ?: return@mapNotNull null
                    store.load(key, spec.slots)?.let { spec to it }
                }
            }
            if (stopping) return@execute
            runCatching {
                server.scheduler.runTask(this, Runnable {
                    if (stopping || activeChunkLoads[loadKey] != loadToken) return@Runnable
                    val world = Bukkit.getWorld(worldId) ?: return@Runnable
                    if (!world.isChunkLoaded(chunkX, chunkZ)) return@Runnable
                    if (rows.isFailure) {
                        logger.warning("Could not load drawers for chunk $chunkX,$chunkZ: ${rows.exceptionOrNull()?.message}")
                        if (attempt < 3) {
                            scheduledTasks += server.scheduler.runTaskLater(this, Runnable {
                                if (!stopping && activeChunkLoads[loadKey] == loadToken && world.isChunkLoaded(chunkX, chunkZ)) {
                                    restoreChunk(world.getChunkAt(chunkX, chunkZ), attempt + 1)
                                }
                            }, 40L)
                        }
                        return@Runnable
                    }
                    ce.indexHalfDrawers(world.getChunkAt(chunkX, chunkZ))
                    rows.getOrThrow().forEach { (spec, row) ->
                        runCatching {
                            val block = world.getBlockAt(row.key.x, row.key.y, row.key.z)
                            if (spec.half && ce.legacyDrawerType(block) == spec.id) {
                                if (!ce.migrateHalfDrawer(block, spec.id, ce.facing(block))) {
                                    logger.warning("Could not migrate half drawer at ${row.key}; block and SQLite inventory retained. Install the matching CraftEngine pack")
                                }
                            }
                            if (spec.half && ce.legacyDrawerType(block) == null && ce.halfFurniture(block) != null) {
                                if (!ce.synchronizeHalfLayout(block)) {
                                    logger.warning("Half drawer at ${row.key} still uses its previous layout; update the CraftDrawers CE pack (wall_aligned variant required) and reload CraftEngine")
                                }
                            }
                            if (ce.drawerType(block) == spec.id) {
                                // Existing entries were installed on the main thread.
                                // A newly placed drawer can restart its revision at 0.
                                loaded.putIfAbsent(row.key, row)
                            } else {
                                logger.warning("Drawer data at ${row.key} has no matching CraftEngine block; data retained")
                            }
                        }.onFailure { logger.warning("Could not restore drawer data at ${row.key}: ${it.message}") }
                    }
                    // Keep working displays on read failure. On success include
                    // drawers placed while the query was in flight in the rebuild.
                    displays.cleanupChunk(worldId, chunkX, chunkZ)
                    loaded.values.filter { row ->
                        row.key.worldId == worldId && (row.key.x shr 4) == chunkX && (row.key.z shr 4) == chunkZ
                    }.forEach { row ->
                        runCatching {
                            val spec = specById[row.specId] ?: return@runCatching
                            val block = world.getBlockAt(row.key.x, row.key.y, row.key.z)
                            if (ce.drawerType(block) == spec.id) {
                                synchronizeFacing(row, ce.facing(block))
                                displays.refresh(row, spec)
                            }
                        }.onFailure { logger.warning("Could not restore drawer display at ${row.key}: ${it.message}") }
                    }
                })
            }.onFailure { if (!stopping) logger.warning("Could not schedule drawer chunk load: ${it.message}") }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: org.bukkit.event.world.ChunkUnloadEvent) {
        val chunk = event.chunk
        closeMenusInChunk(chunk.world.uid, chunk.x, chunk.z)
        activeChunkLoads.remove(chunkLoadKey(chunk.world.uid, chunk.x, chunk.z))
        ce.forgetChunk(chunk)
        val identities = loaded.keys.filter { it.worldId == chunk.world.uid && (it.x shr 4) == chunk.x && (it.z shr 4) == chunk.z }
        identities.forEach { key ->
            displays.remove(key)
            loaded.remove(key)
        }
    }

    private fun closeMenusInChunk(worldId: UUID, chunkX: Int, chunkZ: Int) {
        server.onlinePlayers.forEach { player ->
            val holder = player.openInventory.topInventory.holder
            val key = when (holder) {
                is DrawerContentsMenuHolder -> holder.drawerKey
                else -> return@forEach
            }
            if (key.worldId == worldId && (key.x shr 4) == chunkX && (key.z shr 4) == chunkZ) {
                player.closeInventory()
            }
        }
    }

    private fun closeMenusForDrawer(key: DrawerKey) {
        server.onlinePlayers.forEach { player ->
            val holder = player.openInventory.topInventory.holder
            val holderKey = when (holder) {
                is DrawerContentsMenuHolder -> holder.drawerKey
                else -> return@forEach
            }
            if (holderKey == key) player.closeInventory()
        }
    }

    private fun transferIntoDrawer(key: DrawerKey, spec: DrawerSpec, stack: ItemStack, amount: Int): Int {
        val row = loaded[key] ?: store.load(key, spec.slots)?.also { loaded[key] = it } ?: run {
            val block = Bukkit.getWorld(key.worldId)?.getBlockAt(key.x, key.y, key.z) ?: return 0
            loadOrCreate(key, spec, ce.facing(block), null) ?: return 0
        }
        if (row.specId != spec.id) {
            loaded.remove(key, row)
            logger.severe("Automation skipped drawer type mismatch at $key: stored=${row.specId}, block=${spec.id}")
            return 0
        }
        val template = stack.clone().apply { this.amount = 1 }
        val existingIndex = row.slots.indexOfFirst { slot ->
            slot.itemBlob?.let(::decodeItem)?.isSimilar(template) == true && slot.amount < capacity(key, spec, template)
        }
        val emptyIndex = row.slots.indexOfFirst { it.itemBlob == null && !it.locked }
        val index = if (existingIndex >= 0) existingIndex else emptyIndex
        if (index < 0) return 0
        val slot = row.slots[index]
        val cap = capacity(key, spec, template)
        val accepted = min(amount.toLong(), (cap - slot.amount).coerceAtLeast(0)).toInt()
        if (accepted <= 0) return 0
        val next = commitSlot(
            row,
            index,
            slot.copy(itemBlob = slot.itemBlob ?: template.serializeAsBytes(), amount = slot.amount + accepted),
            "AUTOMATION_INSERT",
            null,
        ) ?: return 0
        loaded[key] = next
        displays.refresh(next, spec)
        refreshDrawerMenus(key)
        return accepted
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onItemPickup(event: org.bukkit.event.entity.EntityPickupItemEvent) {
        val drawer = event.entity.location.block.getRelative(BlockFace.UP)
        val type = ce.drawerType(drawer) ?: return
        val spec = specById[type] ?: return
        val key = drawer.drawerKey()
        val magnetRange = magnetRange(key) ?: return
        val center = drawer.location.add(0.5, 0.5, 0.5)
        if (event.item.location.distanceSquared(center) > magnetRange * magnetRange) return
        val dropped = event.item.itemStack
        val accepted = transferIntoDrawer(key, spec, dropped, dropped.amount)
        if (accepted > 0) {
            event.isCancelled = true
            dropped.amount -= accepted
            if (dropped.amount <= 0) event.item.remove() else event.item.itemStack = dropped
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (ce.halfFurniture(event.block.getRelative(event.direction)) != null ||
            event.blocks.any { ce.drawerType(it) != null || ce.halfFurniture(it.getRelative(event.direction)) != null }
        ) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any { ce.drawerType(it) != null || ce.halfFurniture(it.getRelative(event.direction.oppositeFace)) != null }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { block -> ce.drawerType(block) != null }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { block -> ce.drawerType(block) != null }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = deliverPending(event.player)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val keys = loaded.keys.filter { key ->
            val record = loaded[key]
            record?.owner == event.player.uniqueId
        }
        keys.forEach { key ->
            locks.remove(key)
        }
    }

    private fun deliverPending(player: Player) {
        if (stopping) return
        if (!playersDeliveringClaims.add(player.uniqueId)) return
        databaseExecutor.execute {
            val claims = runCatching { store.pendingClaims(player.uniqueId) }.onFailure {
                logger.warning("Could not load claims for ${player.uniqueId}: ${it.message}")
            }.getOrDefault(emptyList())
            server.scheduler.runTask(this, Runnable {
                try {
                    if (!stopping && player.isOnline) {
                        deferredClaimAcknowledgements.entries.toList()
                            .filter { it.value.playerId == player.uniqueId }
                            .forEach { acknowledgeClaim(player, it.key, it.value) }
                        clearCompletedClaimMarkers(player)
                        claims.forEach { deliverClaim(player, it) }
                    }
                } finally {
                    playersDeliveringClaims.remove(player.uniqueId)
                }
            })
        }
    }

    private fun retryPendingClaims() {
        if (stopping) return
        server.onlinePlayers.forEach(::deliverPending)
    }

    private fun processAutomation() {
        if (stopping) return
        if (++automationTicksSinceRun < automationIntervalTicks) return
        automationTicksSinceRun = 0
        val limit = max(1, config.getInt("automation.max-transfers-per-run", 32))
        var count = 0
        val candidates = loaded.keys.toList()
        if (candidates.isEmpty()) return
        val scanLimit = min(candidates.size, max(16, config.getInt("automation.max-drawers-scanned-per-run", 128)))
        val start = Math.floorMod(automationCursor, candidates.size)
        automationCursor = (start + scanLimit) % candidates.size
        for (offset in 0 until scanLimit) {
            if (count >= limit) break
            val key = candidates[(start + offset) % candidates.size]
            val block = Bukkit.getWorld(key.worldId)?.getBlockAt(key.x, key.y, key.z) ?: continue
            val type = ce.drawerType(block) ?: continue
            val spec = specById[type] ?: continue
            val record = loaded[key] ?: continue
            if (record.specId != spec.id) {
                loaded.remove(key, record)
                logger.severe("Automation skipped drawer type mismatch at $key: stored=${record.specId}, block=${spec.id}")
                continue
            }
            if (transferFromAdjacentHopper(block, key, spec)) count++
            if (count >= limit) break
            if (transferToBelowHopper(block, key, spec)) count++
            if (count >= limit) break
            if (hasAnyMagnet(key)) count += collectNearbyDrops(block, key, spec, limit - count)
        }
    }

    private fun hasAnyMagnet(key: DrawerKey): Boolean = store.upgrades(key).any {
        it.second in setOf("magnet_1", "magnet_2", "magnet_3")
    }

    private fun magnetRange(key: DrawerKey): Double? = when {
        store.upgrades(key).any { it.second == "magnet_3" } -> 8.25
        store.upgrades(key).any { it.second == "magnet_2" } -> 4.5
        store.upgrades(key).any { it.second == "magnet_1" } -> 1.75
        else -> null
    }

    private fun collectNearbyDrops(block: Block, key: DrawerKey, spec: DrawerSpec, budget: Int): Int {
        val range = magnetRange(key) ?: return 0
        val center = block.location.add(0.5, 0.5, 0.5)
        var moved = 0
        for (entity in block.world.getNearbyEntities(center, range, range, range).filterIsInstance<DroppedItem>()) {
            if (moved >= budget) break
            val dropped = entity.itemStack
            val accepted = transferIntoDrawer(key, spec, dropped, dropped.amount)
            if (accepted <= 0) continue
            dropped.amount -= accepted
            if (dropped.amount == 0) entity.remove() else entity.itemStack = dropped
            moved++
        }
        return moved
    }

    private fun transferFromAdjacentHopper(block: Block, key: DrawerKey, spec: DrawerSpec): Boolean {
        for (face in listOf(BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            val neighbor = block.getRelative(face)
            val hopper = neighbor.state as? Hopper ?: continue
            val directional = hopper.blockData as? Directional ?: continue
            if (neighbor.getRelative(directional.facing) != block) continue
            if (hopper.transferCooldown > 0) continue
            if (hopper.inventory.storageContents.none { it != null && !it.type.isAir }) continue

            // Hopper is a captured BlockState. Apply its cooldown while its
            // inventory still matches the snapshot; a later update would put
            // these pre-transfer contents back into the live hopper.
            hopper.transferCooldown = 8
            if (!hopper.update(true, false)) continue

            // Reacquire after applying the state, then change the live inventory
            // without calling update on this fresh snapshot afterward.
            val currentHopper = neighbor.state as? Hopper ?: continue
            val inventory = currentHopper.inventory
            val sourceIndex = inventory.storageContents.indexOfFirst { it != null && !it.type.isAir }
            if (sourceIndex < 0) continue
            val source = inventory.getItem(sourceIndex) ?: continue
            val accepted = transferIntoDrawer(key, spec, source, source.amount)
            if (accepted <= 0) continue
            source.amount -= accepted
            if (source.amount <= 0) inventory.setItem(sourceIndex, null) else inventory.setItem(sourceIndex, source)
            return true
        }
        return false
    }

    private fun transferToBelowHopper(block: Block, key: DrawerKey, spec: DrawerSpec): Boolean {
        val hopperBlock = block.getRelative(BlockFace.DOWN)
        val hopper = hopperBlock.state as? Hopper ?: return false
        if (hopper.transferCooldown > 0) return false
        val row = loaded[key] ?: return false
        if (row.specId != spec.id) {
            loaded.remove(key, row)
            logger.severe("Hopper output skipped drawer type mismatch at $key: stored=${row.specId}, block=${spec.id}")
            return false
        }
        val index = row.slots.indexOfFirst { it.amount > 0 && it.itemBlob != null }
        if (index < 0) return false
        val slot = row.slots[index]
        val template = decodeItem(slot.itemBlob ?: return false) ?: return false
        if (inventoryCapacity(hopper.inventory, template) <= 0) return false
        val next = commitSlot(row, index, slot.copy(amount = slot.amount - 1), "HOPPER_EXTRACT", null) ?: return false
        val overflow = hopper.inventory.addItem(template.clone().apply { amount = 1 })
        if (overflow.isNotEmpty()) {
            val inserted = (1L - overflow.values.sumOf { it.amount.toLong() }).coerceIn(0L, 1L)
            if (inserted == 0L) {
                val restored = commitSlot(next, index, slot.copy(), "HOPPER_EXTRACT_ROLLBACK", null)
                if (restored == null) {
                    logger.severe("Could not restore drawer stock after failed hopper transfer at $key")
                } else {
                    loaded[key] = restored
                    displays.refresh(restored, spec)
                }
                return false
            }
        }
        loaded[key] = next
        displays.refresh(next, spec)
        hopper.transferCooldown = 8
        hopper.update(true, false)
        return true
    }

    private fun inventoryCapacity(inventory: org.bukkit.inventory.Inventory, sample: ItemStack): Long {
        var available = 0L
        for (item in inventory.storageContents) {
            when {
                item == null || item.type.isAir -> available += sample.maxStackSize
                item.isSimilar(sample) -> available += (min(item.maxStackSize, sample.maxStackSize) - item.amount).coerceAtLeast(0)
            }
        }
        return available
    }

    private fun drawerKeyIfKnown(block: Block): DrawerKey? =
        ce.drawerType(block)?.let { block.drawerKey() }

    private fun commitSlot(
        record: StoredDrawer,
        index: Int,
        updated: StoredSlot,
        operation: String,
        claim: ItemClaim?,
    ): StoredDrawer? {
        val normalized = updated.normalized()
        val revision = store.updateSlot(record, SlotChange(index, normalized), UUID.randomUUID(), operation, claim)
            ?: return null
        record.slots[index] = normalized
        record.revision = revision
        refreshDrawerMenus(record.key)
        return record
    }

    private fun Block.drawerKey() = DrawerKey(world.uid, x, y, z)

    private fun chunkLoadKey(worldId: UUID, x: Int, z: Int) = "$worldId:$x:$z"

    override fun onDisable() {
        stopping = true
        closeMenus()
        activeChunkLoads.clear()
        scheduledTasks.forEach(BukkitTask::cancel)
        if (::displays.isInitialized) loaded.keys.toList().forEach(displays::remove)
        try {
            databaseExecutor.shutdown()
            if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                databaseExecutor.shutdownNow()
                databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)
            }
            if (::store.isInitialized) store.close()
        } catch (error: Throwable) {
            logger.severe("CraftDrawers 关闭时出现错误: ${error.message}")
        }
        logger.info("CraftDrawers 已卸载")
    }
}
