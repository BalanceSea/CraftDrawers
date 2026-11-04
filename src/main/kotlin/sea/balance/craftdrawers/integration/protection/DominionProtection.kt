package sea.balance.craftdrawers.integration.protection

import cn.lunadeer.dominion.api.DominionAPI
import cn.lunadeer.dominion.api.dtos.flag.Flags
import org.bukkit.Location
import org.bukkit.entity.Player

class DominionProtection : TerritoryProtection {
    override fun allows(player: Player, location: Location, action: TerritoryAction): Boolean {
        val api = DominionAPI.getInstance() ?: return true
        val flags = when (action) {
            TerritoryAction.INTERACT -> arrayOf(Flags.CHEST)
            TerritoryAction.PLACE -> arrayOf(Flags.PLACE)
            TerritoryAction.BREAK -> arrayOf(Flags.BREAK_BLOCK)
        }
        return flags.all { api.checkPrivilegeFlagSilence(location, it, player) }
    }
}
