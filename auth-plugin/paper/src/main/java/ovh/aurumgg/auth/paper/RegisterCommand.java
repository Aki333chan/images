package ovh.aurumgg.auth.paper;

import org.bukkit.entity.Player;
import ovh.aurumgg.auth.core.AuthService;

/**
 * /register &lt;пароль&gt; &lt;пароль ещё раз&gt;
 *
 * Подтверждение обязательно и спрашивается сразу: восстановления пароля пока
 * нет, и опечатка при регистрации означала бы потерянный аккаунт.
 */
final class RegisterCommand extends AuthCommandBase {

    RegisterCommand(AurumAuthPlugin plugin, AuthService service, AuthGuardListener guard) {
        super(plugin, service, guard);
    }

    @Override
    protected void run(Player player, String[] args) {
        if (service.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(AurumAuthPlugin.prefixed("Вы уже вошли"));
            return;
        }
        if (args.length != 2) {
            player.sendMessage(AurumAuthPlugin.prefixed(
                    "Использование: /register <пароль> <пароль ещё раз>"));
            return;
        }

        service.register(
                        player.getUniqueId(),
                        args[0].toCharArray(),
                        args[1].toCharArray(),
                        addressOf(player))
                .thenAccept(outcome -> finish(player, outcome));
    }
}
