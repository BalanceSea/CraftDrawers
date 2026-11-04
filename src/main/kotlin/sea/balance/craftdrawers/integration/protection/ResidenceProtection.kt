package sea.balance.craftdrawers.integration.protection

import com.bekvon.bukkit.residence.api.ResidenceApi
import com.bekvon.bukkit.residence.containers.Flags
import org.bukkit.Location
import org.bukkit.entity.Player

class ResidenceProtection : TerritoryProtection {
    override fun allows(player: Player, location: Location, action: TerritoryAction): Boolean {
        val residence = ResidenceApi.getResidenceManager().getByLoc(location) ?: return true
        val flag = when (action) {
            TerritoryAction.INTERACT -> Flags.container
            TerritoryAction.PLACE -> Flags.place
            TerritoryAction.BREAK -> Flags.destroy
        }
        return residence.permissions.playerHas(player, flag, false)
    }
}
