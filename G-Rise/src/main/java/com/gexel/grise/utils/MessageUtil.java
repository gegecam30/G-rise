package com.gexel.grise.utils;

import com.gexel.grise.GRisePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/**
 * Utility class for loading and sending localised messages.
 *
 * <h3>How it works</h3>
 * Messages are stored in {@code plugins/G-Rise/messages.yml} (copied from
 * the jar on first run). All messages support the {@code &} colour code
 * format, which is converted to Adventure {@link Component}s before sending.
 *
 * <h3>Variable substitution</h3>
 * {@link #format(String, String...)} accepts pairs of
 * {@code ("{placeholder}", "value")} that are applied with a simple
 * {@code String.replace} before colour parsing. No regex is used, keeping
 * the method fast for hot paths.
 *
 * @author Gexel
 */
public class MessageUtil {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final GRisePlugin plugin;
    private FileConfiguration messages;

    /** The plugin prefix prepended to all messages (from messages.yml). */
    private String prefix;

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public MessageUtil(GRisePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Reloads messages from disk (useful after {@code /gr admin reload}). */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(file);
        prefix   = messages.getString("prefix", "&8[&bG-Rise&8] &r");
    }

    // -----------------------------------------------------------------------
    // Core send methods
    // -----------------------------------------------------------------------

    /**
     * Sends a message from messages.yml to a {@link CommandSender}.
     *
     * @param sender  recipient
     * @param key     dot-path key in messages.yml (e.g. {@code "game.started"})
     * @param replacements alternating placeholder/value pairs
     */
    public void send(CommandSender sender, String key, String... replacements) {
        String raw = format(key, replacements);
        sender.sendMessage(LEGACY.deserialize(raw));
    }

    /**
     * Returns the formatted, colour-coded string for a given key.
     *
     * @param key          dot-path key
     * @param replacements alternating placeholder/value pairs
     * @return colour-coded string with prefix prepended
     */
    public String format(String key, String... replacements) {
        String raw = messages.getString(key, "&cMissing message: " + key);
        raw = applyReplacements(raw, replacements);
        return colorize(prefix) + colorize(raw);
    }

    /**
     * Returns the raw color-coded string for a path (no prefix added).
     *
     * @param key dot-path key
     * @return the raw string from messages.yml, colour codes translated
     */
    public String colorize(String key) {
        String raw = messages.getString(key, key); // fall back to key itself
        return raw.replace("&", "\u00a7");
    }

    /**
     * Returns the list of strings for a list-type key (e.g. help entries).
     *
     * @param key dot-path key
     * @return list of strings, each with colour codes applied
     */
    public List<String> getList(String key) {
        return messages.getStringList(key).stream()
                       .map(s -> s.replace("&", "\u00a7"))
                       .toList();
    }

    /**
     * Converts an {@code &}-coded raw string to an Adventure {@link Component}.
     *
     * @param raw raw string with {@code &} colour codes
     * @return adventure component
     */
    public Component toComponent(String raw) {
        return LEGACY.deserialize(raw);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String applyReplacements(String text, String[] pairs) {
        if (pairs == null || pairs.length == 0) return text;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        return text;
    }
}
