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

    private final WebTokenStore tokens;

    WebTokenCommand(WebTokenStore tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда только для игроков");
            return true;
        }

        if (!AuthIntegration.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(Component.text("Сначала войдите: /login <пароль>"));
            return true;
        }

        String code = tokens.issue(player.getUniqueId(), player.getName(), Instant.now());
        player.sendMessage(Component.text("Код для входа в панель: " + code));
        player.sendMessage(Component.text("Он одноразовый и действует несколько минут."));
        return true;
    }
}
