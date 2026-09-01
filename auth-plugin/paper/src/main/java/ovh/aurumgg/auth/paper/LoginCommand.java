package ovh.aurumgg.auth.paper;

import org.bukkit.entity.Player;
import ovh.aurumgg.auth.core.HelpBook;
import ovh.aurumgg.auth.core.AuthService;

/** /login &lt;пароль&gt; */
final class LoginCommand extends AuthCommandBase {

    LoginCommand(AurumAuthPlugin plugin, AuthService service, AuthGuardListener guard) {
        super(plugin, service, guard);
    }

    @Override
    protected void run(Player player, String[] args) {
        if (service.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(AurumAuthPlugin.prefixed("Вы уже вошли"));
            return;
        }
        if (args.length != 1) {
            player.sendMessage(AurumAuthPlugin.colored(HelpBook.line(
                    "/login <пароль>", "войти под своим аккаунтом")));
            return;
        }

        // toCharArray, а не String: строка с паролем осталась бы в пуле строк
        // до сборки мусора, и снять её дампом кучи можно было бы ещё долго.
        // Массив сервис затирает сразу после проверки.
        service.login(player.getUniqueId(), args[0].toCharArray(), addressOf(player))
                .thenAccept(outcome -> finish(player, outcome));
    }
}
