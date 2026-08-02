package github.freshchromatic.freshlib.item.materialresolvers;

import github.freshchromatic.freshlib.item.MaterialResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code head} or {@code head:<player name | base64 texture | texture url>} — a player skull,
 * resolved without depending on any external plugin.
 */
public final class PlayerHeadMaterialResolver implements MaterialResolver {
    private static final Pattern TEXTURE_URL_IN_JSON = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    public boolean supports(String materialTag) {
        String lower = materialTag.trim().toLowerCase(Locale.ROOT);
        return lower.equals("head") || lower.startsWith("head:");
    }

    @Override
    public ItemStack resolve(String materialTag, Player player) {
        String trimmed = materialTag.trim();
        String value = trimmed.regionMatches(true, 0, "head:", 0, 5)
                ? trimmed.substring("head:".length()).trim()
                : "";

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (value.isEmpty()) {
            return item;
        }

        item.editMeta(meta -> {
            if (!(meta instanceof SkullMeta skullMeta)) return;
            URL textureUrl = resolveTextureUrl(value);
            if (textureUrl != null) {
                PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
                PlayerTextures textures = profile.getTextures();
                textures.setSkin(textureUrl);
                profile.setTextures(textures);
                skullMeta.setOwnerProfile(profile);
            } else {
                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(value));
            }
        });
        return item;
    }

    private URL resolveTextureUrl(String value) {
        try {
            if (value.startsWith("http://") || value.startsWith("https://")) {
                return new URL(value);
            }
            String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            Matcher matcher = TEXTURE_URL_IN_JSON.matcher(decoded);
            if (matcher.find()) {
                return new URL(matcher.group(1));
            }
        } catch (IllegalArgumentException | MalformedURLException ignored) {
            // not a URL or base64 texture string — fall back to owning-player lookup
        }
        return null;
    }
}
