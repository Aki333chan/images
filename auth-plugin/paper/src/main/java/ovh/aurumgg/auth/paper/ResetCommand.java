package ovh.aurumgg.auth.paper;

import org.bukkit.entity.Player;
import ovh.aurumgg.auth.core.AuthService;
import ovh.aurumgg.auth.core.ResetTokens;

/**
 * Сброс пароля в две ступени: сначала токен, потом новый пароль.
 *
 * <pre>
 * /reset ABCD2345                 — токен, выданный администратором
 * /reset новыйпароль новыйпароль  — новый пароль и подтверждение
 * </pre>
 *
 * ПОЧЕМУ ОДНА КОМАНДА, А НЕ ДВЕ. Человек, которому сбросили пароль, помнит
 * ровно одно слово — «reset». Заставлять его после токена вспоминать вторую
 * команду значит терять его ровно на середине; сервер и так уже сказал, что
 * делать дальше. Ступени различаются числом аргументов, и это различие
 * однозначно: токен — всегда один аргумент и всегда восемь знаков из
 * ограниченного алфавита, новый пароль — всегда два аргумента.
 */
final class ResetCommand extends AuthCommandBase {

    ResetCommand(AurumAuthPlugin plugin, AuthService service, AuthGuardListener guard) {
        super(plugin, service, guard);
    }

    @Override
    protected void run(Player player, String[] args) {
        if (service.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(AurumAuthPlugin.prefixed(
                    "Вы уже вошли. Сброс нужен только тем, кто не помнит пароль."));
            return;
        }

        if (args.length == 1) {
            if (!ResetTokens.looksLikeToken(args[0])) {
                // Отдельная подсказка вместо «токен не подошёл»: чаще всего
                // сюда попадают, набрав пароль вместо токена, и путать
                // человека ещё сильнее незачем.
                player.sendMessage(AurumAuthPlugin.prefixed(
                        "Токен — это 8 знаков, его выдаёт администратор. "
                                + "Если он у вас уже принят, введите: /reset <пароль> <пароль ещё раз>"));
                return;
            }
            service.redeemResetToken(player.getUniqueId(), args[0])
                    .thenAccept(outcome -> finish(player, outcome));
            return;
        }

        if (args.length == 2) {
            service.setNewPassword(
                            player.getUniqueId(),
                            args[0].toCharArray(),
                            args[1].toCharArray(),
                            addressOf(player))
                    .thenAccept(outcome -> finish(player, outcome));
            return;
        }

        player.sendMessage(AurumAuthPlugin.prefixed(
                "Использование: /reset <токен> — затем /reset <пароль> <пароль ещё раз>"));
    }
}
