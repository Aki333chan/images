package ovh.aurumgg.companion.paper;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ovh.aurumgg.companion.core.ticket.TicketCooldown;
import ovh.aurumgg.companion.core.webtoken.WebTokenStore;

/** Website identity proof only; no network/SQL call on the main game thread. */
final class AurumLinkCommand implements CommandExecutor {
    private final AurumCompanionPlugin plugin;
    private final WebTokenStore tokens;
    private final TicketCooldown cooldown = new TicketCooldown(10);
    private final String siteUrl;
    private final String serverId;
    private final boolean enabled;

    AurumLinkCommand(AurumCompanionPlugin plugin, WebTokenStore tokens) {
        this.plugin = plugin;
        this.tokens = tokens;
        siteUrl = plugin.getConfig().getString("site.base-url", "https://aurumgg.ovh").replaceAll("/+$", "");
        serverId = plugin.getConfig().getString("panel.server-id", "");
        boolean configured;
        try {
            URI uri = URI.create(siteUrl);
            UUID.fromString(serverId);
            configured = "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && uri.getPath().isEmpty();
        } catch (IllegalArgumentException e) { configured = false; }
        enabled = plugin.getConfig().getBoolean("site.enabled", false) && tokens != null && configured;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.text("ticket.playersOnly"));
            return true;
        }
        if (!enabled) {
            player.sendMessage(Component.text(plugin.text("siteLink.unavailable"), NamedTextColor.RED));
            return true;
        }
        // Offline-mode without a working AurumAuth API must fail closed.
        boolean authenticated = AuthIntegration.provider().map(api -> api.isAuthenticated(player.getUniqueId()))
                .orElse(plugin.getServer().getOnlineMode());
        if (!authenticated) {
            player.sendMessage(Component.text(plugin.text("webtoken.loginFirst"), NamedTextColor.RED));
            return true;
        }
        long wait = cooldown.secondsRemaining(player.getUniqueId());
        if (wait > 0) {
            player.sendMessage(Component.text(plugin.text("ticket.cooldown", Map.of("seconds", String.valueOf(wait))), NamedTextColor.YELLOW));
            return true;
        }
        String code = tokens.issue(player.getUniqueId(), player.getName(), Instant.now());
        player.sendMessage(Component.text(plugin.text("siteLink.code", Map.of("code", code)), NamedTextColor.GRAY)
                .replaceText(builder -> builder.matchLiteral(code)
                        .replacement(Component.text(code, NamedTextColor.GOLD).decorate(TextDecoration.BOLD)))
                .clickEvent(ClickEvent.copyToClipboard(code))
                .hoverEvent(HoverEvent.showText(Component.text(plugin.text("siteLink.copy")))));
        // Fragment is not sent to the web server or included in HTTP Referer.
        String url = siteUrl + "/minecraft/link?server=" + serverId + "#code=" + code;
        player.sendMessage(Component.text(plugin.text("siteLink.open"), NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url)));
        player.sendMessage(Component.text(plugin.text("siteLink.hint"), NamedTextColor.GRAY));
        return true;
    }

    void forget(UUID player) { cooldown.forget(player); }
}
