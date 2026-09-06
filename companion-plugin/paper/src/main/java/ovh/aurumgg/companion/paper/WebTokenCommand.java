package ovh.aurumgg.companion.paper;

import java.time.Instant;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ovh.aurumgg.companion.core.webtoken.WebTokenStore;

/**
 * /webtoken — одноразовый код для входа в панель из игры.
 *
 * ГЛАВНОЕ ИЗМЕНЕНИЕ ЭТОЙ ВЕРСИИ — откуда берётся ответ на вопрос «а этот игрок
 * вообще вошёл». Раньше команда сама ходила SQL-запросом в таблицу AuthMe и
 * читала isLogged/hasSession. Теперь спрашивает у AurumAuth через его API —
 * см. AuthIntegration.
 *
 * Проверка здесь не формальность. Код, выданный не вошедшему, означал бы вход
 * в панель под чужим аккаунтом: до авторизации «игрок Стив» — это всего лишь
 * тот, кто набрал ник Стива при подключении.
 */
final class WebTokenCommand implements CommandExecutor {

    private final AurumCompanionPlugin plugin;
    private final WebTokenStore tokens;

    WebTokenCommand(AurumCompanionPlugin plugin, WebTokenStore tokens) {
        this.plugin = plugin;
        this.tokens = tokens;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.text("webtoken.playersOnly"));
            return true;
        }

        if (!AuthIntegration.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(Component.text(plugin.text("webtoken.loginFirst")));
            return true;
        }

        String code = tokens.issue(player.getUniqueId(), player.getName(), Instant.now());
        player.sendMessage(Component.text(
                plugin.text("webtoken.code", java.util.Map.of("code", code))));
        player.sendMessage(Component.text(plugin.text("webtoken.codeHint")));
        return true;
    }
}
