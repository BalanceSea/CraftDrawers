package sea.balance.craftdrawers.platform

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

data class MenuDisplay(
    val material: String? = null,
    val name: String? = null,
    val lore: List<String>? = null,
    val amount: Int? = null,
    val customModelData: Int? = null,
    val glow: Boolean? = null,
) {
    fun overlay(other: MenuDisplay) = MenuDisplay(
        other.material ?: material,
        other.name ?: name,
        other.lore ?: lore,
        other.amount ?: amount,
        other.customModelData ?: customModelData,
        other.glow ?: glow,
    )
}

object MenuConfig {
    fun readDisplay(section: ConfigurationSection?, language: LanguageService): MenuDisplay {
        if (section == null) return MenuDisplay()
        val material = section.getString("material")?.also {
            if (it.startsWith("ce:")) {
                net.momirealms.craftengine.core.util.Key.of(it.removePrefix("ce:"))
            } else {
                require(Material.matchMaterial(it)?.let { type -> type.isItem && !type.isAir } == true) {
                    "Invalid menu material '$it'"
                }
            }
        }
        val name = section.getString("name")?.also(language::validate)
        val lore = if (section.contains("lore")) {
            val lines = section.getList("lore") ?: error("Menu lore must be a list")
            require(lines.all { it is String }) { "Menu lore must contain strings" }
            lines.map { it as String }.also(language::validateLore)
        } else null
        val amount = if (section.contains("amount")) section.getInt("amount").also {
            require(it in 1..64) { "Menu amount must be 1..64" }
        } else null
        val customModelData = if (section.contains("custom-model-data")) section.getInt("custom-model-data").also {
            require(it >= 0) { "custom-model-data must be nonnegative" }
        } else null
        val glow = if (section.contains("glow")) section.getBoolean("glow") else null
        return MenuDisplay(material, name, lore, amount, customModelData, glow)
    }

    /** TrMenu-style character slots and backtick-delimited named icon IDs. */
    fun parseRow(row: String): List<String> = buildList {
        var index = 0
        while (index < row.length) {
            if (row[index] == '`') {
                val end = row.indexOf('`', index + 1)
                require(end > index + 1) { "Unclosed or empty backtick icon ID in Layout" }
                add(row.substring(index + 1, end))
                index = end + 1
            } else {
                val point = row.codePointAt(index)
                add(String(Character.toChars(point)))
                index += Character.charCount(point)
            }
        }
    }
}
