package ovh.aurumgg.auth.paper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ovh.aurumgg.auth.api.AuthStatus;
import ovh.aurumgg.auth.core.AuthAccount;
import ovh.aurumgg.auth.core.AuthService;

/**
 * Администраторские команды: /auth &lt;подкоманда&gt;.
 *
 * <pre>
 * /auth reset  &lt;ник&gt;  — выдать токен сброса пароля
 * /auth info   &lt;ник&gt;  — что известно об аккаунте
 * /auth unlock &lt;ник&gt;  — снять блокировку по неудачным попыткам
 * /auth logout &lt;ник&gt;  — разавторизовать игрока в сети и погасить сессию
 * /auth reload        — перечитать тексты сообщений
 * </pre>
 *
 * ОДНА КОМАНДА С ПОДКОМАНДАМИ, А НЕ ПЯТЬ ОТДЕЛЬНЫХ. Пять команд верхнего
 * уровня — это пять шансов столкнуться именами с другим плагином (/info и
 * /reload заняты примерно везде) и пять записей в списке команд сервера.
 *
 * ЧЕГО ЗДЕСЬ НАМЕРЕННО НЕТ — команды «задать игроку пароль». Пароль,
 * переданный аргументом, попадает в историю команд, в логи консоли и в чужие
 * плагины логирования; сброс токеном решает ту же задачу, не создавая
 * долгоживущей копии пароля. Токен для этого и придуман.
 */
final class AuthAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reset", "info", "unlock", "logout", "reload");

    private final AurumAuthPlugin plugin;
    private final AuthService service;

    AuthAdminCommand(AurumAuthPlugin plugin, AuthService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!allowed(sender, "aurumauth.admin.reload")) return true;
            plugin.reloadMessages();
            sender.sendMessage(AurumAuthPlugin.prefixed("Тексты сообщений перечитаны"));
            return true;
        }

        if (args.length < 2) {
            usage(sender);
            return true;
        }
        String name = args[1];

        switch (sub) {
            case "reset" -> reset(sender, name);
            case "info" -> info(sender, name);
            case "unlock" -> unlock(sender, name);
            case "logout" -> logout(sender, name);
            default -> usage(sender);
        }
        return true;
    }

    /**
     * Выдать токен сброса.
     *
     * Токен уходит ТОЛЬКО тому, кто выполнил команду. Ни broadcast, ни лога с
     * ним нет: это временный ключ от чужого аккаунта, и лишняя его копия —
     * лишний способ им воспользоваться. По той же причине его нет и в тексте
     * ошибки, когда что-то пошло не так.
     */
    private void reset(CommandSender sender, String name) {
        if (!allowed(sender, "aurumauth.admin.reset")) return;
        service.issueResetToken(name).thenAccept(token -> back(() -> {
            if (token.isEmpty()) {
                sender.sendMessage(AurumAuthPlugin.prefixed("Аккаунт «" + name + "» не найден"));
                return;
            }
            long minutes = Math.max(1, Duration.between(Instant.now(), token.get().expiresAt()).toMinutes());
            sender.sendMessage(AurumAuthPlugin.prefixed(
                    "Токен сброса для " + token.get().username() + ": " + token.get().token()));
            sender.sendMessage(AurumAuthPlugin.prefixed(
                    "Действует " + minutes + " мин. Игрок вводит: /reset " + token.get().token()));
        }));
    }

    private void info(CommandSender sender, String name) {
        if (!allowed(sender, "aurumauth.admin.info")) return;
        service.lookup(name).thenAccept(found -> back(() -> {
            if (found.isEmpty()) {
                sender.sendMessage(AurumAuthPlugin.prefixed("Аккаунт «" + name + "» не найден"));
                return;
            }
            AuthAccount account = found.get();
            sender.sendMessage(AurumAuthPlugin.prefixed("Аккаунт " + account.username()));
            sender.sendMessage(Component.text("  UUID: " + account.uuid()));
            sender.sendMessage(Component.text("  Зарегистрирован: " + account.registeredAt()));
            sender.sendMessage(Component.text("  Последний вход: "
                    + (account.lastLoginAt() == null ? "никогда" : account.lastLoginAt())));
            // Адрес последнего входа — сведения о человеке, а не о сервере.
            // Показываем только тому, кому отдельно разрешено их видеть.
            if (sender.hasPermission("aurumauth.admin.info.ip")) {
                sender.sendMessage(Component.text("  Последний адрес: "
                        + (account.lastIp() == null ? "нет данных" : account.lastIp())));
            }

            Optional<AuthStatus> status = onlineStatus(account.username());
            sender.sendMessage(Component.text("  Сейчас: "
                    + status.map(Enum::name).orElse("не в сети")));
        }));
    }

    private void unlock(CommandSender sender, String name) {
        if (!allowed(sender, "aurumauth.admin.unlock")) return;
        service.unlock(name);
        sender.sendMessage(AurumAuthPlugin.prefixed("Блокировка по попыткам входа для «" + name + "» снята"));
    }

    private void logout(CommandSender sender, String name) {
        if (!allowed(sender, "aurumauth.admin.logout")) return;
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            sender.sendMessage(AurumAuthPlugin.prefixed("Игрока «" + name + "» нет в сети"));
            return;
        }
        boolean changed = service.forceLogout(player.getUniqueId());
        player.sendMessage(AurumAuthPlugin.prefixed("Вход сброшен администратором: /login <пароль>"));
        sender.sendMessage(AurumAuthPlugin.prefixed(changed
                ? "Игрок «" + name + "» разавторизован, сессия погашена"
                : "Игрок «" + name + "» и так не был авторизован; сессия погашена"));
    }

    private Optional<AuthStatus> onlineStatus(String username) {
        Player player = Bukkit.getPlayerExact(username);
        return player == null ? Optional.empty() : service.status(player.getUniqueId());
    }

    /** Права проверяются по каждой подкоманде отдельно — они очень разные по весу. */
    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage(AurumAuthPlugin.prefixed("Недостаточно прав: " + permission));
        return false;
    }

    /** Вернуться на главный поток: ответы приходят из рабочего пула. */
    private void back(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(AurumAuthPlugin.prefixed("/auth reset|info|unlock|logout <ник> | /auth reload"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
