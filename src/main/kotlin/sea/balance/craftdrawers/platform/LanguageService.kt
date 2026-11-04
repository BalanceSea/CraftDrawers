package sea.balance.craftdrawers.platform

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Locale

/** Immutable presentation snapshot; replaced only after a successful reload. */
class LanguageService private constructor(
    val locale: String,
    private val configured: YamlConfiguration,
    private val defaults: YamlConfiguration,
) {
    private val miniMessage = MiniMessage.miniMessage()

    private fun value(key: String): Any? = configured.get(key) ?: defaults.get(key)

    fun raw(key: String): String = value(key) as? String
        ?: error("Language key '$key' must be a string")

    fun component(key: String, variables: Map<String, String> = emptyMap()): Component =
        template(raw(key), variables)

    fun template(text: String, variables: Map<String, String> = emptyMap()): Component =
        miniMessage.deserialize(expand(text, variables, 0)).decoration(TextDecoration.ITALIC, false)

    fun lore(lines: List<String>, variables: Map<String, String>): List<Component> = lines.flatMap { line ->
        val key = wholeLanguageReference.matchEntire(line.trim())?.groupValues?.get(1)
        val translated = key?.let(::value)
        if (translated is List<*>) {
            translated.map { template(it as? String ?: error("Lore '$key' must contain strings"), variables) }
        } else listOf(template(line, variables))
    }

    fun validate(text: String) { template(text) }

    fun validateLore(lines: List<String>) { lore(lines, emptyMap()) }

    private fun expand(text: String, variables: Map<String, String>, depth: Int): String {
        require(depth < 12) { "Language references are recursive or too deeply nested" }
        // Convert trusted templates before inserting escaped variable values.
        val colors = legacyHex.replace(text) { match -> "<reset><#${match.groupValues[1]}>" }
        val legacy = legacyColor.replace(colors) { match ->
            legacyTags.getValue(match.groupValues[1].lowercase(Locale.ROOT))
        }
        return placeholder.replace(legacy) { match ->
            val token = match.groupValues[1]
            when {
                token == "prefix" -> expand(raw("prefix"), variables, depth + 1)
                token.startsWith("lang:") -> expand(raw(token.removePrefix("lang:")), variables, depth + 1)
                variables.containsKey(token) -> miniMessage.escapeTags(variables.getValue(token))
                else -> match.value
            }
        }
    }

    companion object {
        private val placeholder = Regex("\\{([^{}]+)}")
        private val wholeLanguageReference = Regex("\\{lang:([^{}]+)}")
        private val legacyHex = Regex("&#([0-9a-fA-F]{6})")
        private val legacyColor = Regex("&([0-9a-fk-or])", RegexOption.IGNORE_CASE)
        private val legacyTags = mapOf(
            "0" to "<reset><black>", "1" to "<reset><dark_blue>", "2" to "<reset><dark_green>",
            "3" to "<reset><dark_aqua>", "4" to "<reset><dark_red>", "5" to "<reset><dark_purple>",
            "6" to "<reset><gold>", "7" to "<reset><gray>", "8" to "<reset><dark_gray>",
            "9" to "<reset><blue>", "a" to "<reset><green>", "b" to "<reset><aqua>",
            "c" to "<reset><red>", "d" to "<reset><light_purple>", "e" to "<reset><yellow>",
            "f" to "<reset><white>", "k" to "<obfuscated>", "l" to "<bold>",
            "m" to "<strikethrough>", "n" to "<underlined>", "o" to "<italic>", "r" to "<reset>",
        )

        fun load(plugin: JavaPlugin, locale: String, bundledOnly: Boolean = false): LanguageService {
            require(locale.matches(Regex("[A-Za-z0-9_-]{2,32}"))) { "Invalid language name '$locale'" }
            listOf("lang/zh_CN.yml", "lang/en_US.yml").forEach { ensureResource(plugin, it) }
            val bundledPath = "lang/${if (locale == "en_US") "en_US" else "zh_CN"}.yml"
            val defaults = bundledYaml(plugin, bundledPath)
            val localeFile = File(plugin.dataFolder, "lang/$locale.yml")
            val configured = if (bundledOnly || !localeFile.isFile) defaults else YamlConfiguration().apply {
                load(localeFile)
            }
            require(configured.getInt("version", 1) == 1) { "Unsupported language format version" }
            defaults.getKeys(true).forEach { key ->
                if (configured.contains(key) && !defaults.isConfigurationSection(key)) {
                    when {
                        defaults.isString(key) -> require(configured.isString(key)) { "Language '$key' must be a string" }
                        defaults.isList(key) -> require(configured.get(key) is List<*> &&
                            (configured.get(key) as List<*>).all { it is String }) { "Language '$key' must be a string list" }
                    }
                }
            }
            return LanguageService(locale, configured, defaults).also { service ->
                // Detect reference cycles and missing translated lore before replacing a live menu.
                (defaults.getKeys(true) + configured.getKeys(true)).forEach { key ->
                    when (val entry = service.value(key)) {
                        is String -> service.validate(entry)
                        is List<*> -> service.validateLore(entry.map { it as? String ?: error("Language '$key' must be a string list") })
                    }
                }
            }
        }
    }
}

internal fun ensureResource(plugin: JavaPlugin, path: String) {
    if (!File(plugin.dataFolder, path).isFile) plugin.saveResource(path, false)
}

internal fun bundledYaml(plugin: JavaPlugin, path: String): YamlConfiguration =
    YamlConfiguration().apply {
        val stream = plugin.getResource(path) ?: error("Missing bundled resource '$path'")
        stream.reader(Charsets.UTF_8).use { load(it) }
    }
