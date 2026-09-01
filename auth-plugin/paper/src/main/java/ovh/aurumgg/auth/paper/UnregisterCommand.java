package ovh.aurumgg.auth.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import ovh.aurumgg.auth.core.HelpBook;
import ovh.aurumgg.auth.core.AuthOutcome;
import ovh.aurumgg.auth.core.AuthService;

/**
 * /unregister &lt;пароль&gt; — игрок удаляет свою регистрацию сам.
 *
 * ПАРОЛЬ ОБЯЗАТЕЛЕН. Команда стирает аккаунт, и подтверждение здесь — не
 * формальность: это единственное, что отличает решение владельца от шутки
 * того, кто на минуту сел за его компьютер.
 *
 * После удаления игрок кикается. Оставлять его в игре под несуществующим
 * аккаунтом бессмысленно: следующий заход всё равно начнётся с регистрации, а
 * до тех пор он ходил бы по серверу в подвешенном состоянии.
 */
final class UnregisterCommand extends AuthCommandBase {

    UnregisterCommand(AurumAuthPlugin plugin, AuthService service, AuthGuardListener guard) {
        super(plugin, service, guard);
    }

    @Override
    protected void run(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(AurumAuthPlugin.colored(HelpBook.line(
                    "/unregister <ваш пароль>",
                    "удалить свой аккаунт; вход после этого — только заново через /register")));
            player.sendMessage(AurumAuthPlugin.prefixed(
                    "Аккаунт будет удалён, ник освободится. Отменить это нельзя."));
            return;
        }

        service.unregisterSelf(player.getUniqueId(), args[0].toCharArray()).thenAccept(outcome ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (outcome.kind() != AuthOutcome.Kind.OK) {
                        player.sendMessage(AurumAuthPlugin.prefixed(outcome.message()));
                        return;
                    }
                    guard.cancelTimeout(player.getUniqueId());
                    player.kick(Component.text(
                            "Регистрация удалена. Зайдите снова, чтобы зарегистрироваться заново."));
                }));
    }
}
