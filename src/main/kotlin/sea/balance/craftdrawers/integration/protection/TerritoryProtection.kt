package sea.balance.craftdrawers.integration.protection

import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.logging.Logger

/** Player operation checked against optional claim-protection plugins. */
enum class TerritoryAction {
    INTERACT,
    PLACE,
    BREAK,
}

fun interface TerritoryProtection {
    fun allows(player: Player, location: Location, action: TerritoryAction): Boolean
}

class TerritoryProtectionService(
    private val logger: Logger,
    private val protections: List<TerritoryProtection>,
) {
    private val warnedAdapters = mutableSetOf<Class<out TerritoryProtection>>()

    fun allows(player: Player, location: Location, action: TerritoryAction): Boolean {
        for (protection in protections) {
            val allowed = try {
                protection.allows(player, location, action)
            } catch (error: Exception) {
                warnOnce(protection, error)
                false
            } catch (error: LinkageError) {
                warnOnce(protection, error)
                false
            }
            if (!allowed) return false
        }
        return true
    }

    private fun warnOnce(protection: TerritoryProtection, error: Throwable) {
        if (warnedAdapters.add(protection.javaClass)) {
            logger.warning("${protection.javaClass.simpleName} protection check failed closed: ${error.message}")
        }
    }
}
