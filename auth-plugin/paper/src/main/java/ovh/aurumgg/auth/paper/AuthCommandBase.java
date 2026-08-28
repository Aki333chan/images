package ovh.aurumgg.auth.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ovh.aurumgg.auth.core.AuthOutcome;
import ovh.aurumgg.auth.core.AuthService;

/**
 * Общее для /login и /register.
 *
 * ГЛАВНОЕ ЗДЕСЬ — ВОЗВРАТ НА ГЛАВНЫЙ ПОТОК. Проверка пароля уходит в рабочий
 * пул и завершается там же; всё, что после неё делается с игроком (сообщение,
 * снятие таймаута, показ отложенного join-сообщения), обязано выполняться на
 * главном потоке сервера. Bukkit API не потокобезопасен, и обращение к игроку
 * из чужого потока ломается не сразу и не всегда — тем оно и неприятно.
 */
abstract class AuthCommandBase implements CommandExecutor {

    protected final AurumAuthPlugin plugin;
    protected final AuthService service;
    protected final AuthGuardListener guard;

    AuthCommandBase(AurumAuthPlugin plugin, AuthService service, AuthGuardListener guard) {
        this.plugin = plugin;
        this.service = service;
        this.guard = guard;
    }

    @Override
    public final boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда только для игроков");
            return true;
        }
        // Пароль пришёл аргументом команды, а значит уже мог попасть в чужие
        // логи команд. Сами мы его не логируем нигде и ни при каких условиях —
        // ни при ошибке, ни в отладочном выводе.
        run(player, args);
        return true;
    }

    protected abstract void run(Player player, String[] args);

    /**
     * Применить результат на главном потоке.
     *
     * Вызывается из рабочего потока: runTask возвращает выполнение туда, где
     * с игроком можно работать.
     */
    protected void finish(Player player, AuthOutcome outcome) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage(AurumAuthPlugin.prefixed(outcome.message()));
            if (!outcome.isSuccess()) {
                // Не вошёл. Показываем подсказку заново — уже по новой
                // ступени: после верного пароля с двухфакторкой это «введите
                // код», после принятого токена — «придумайте новый пароль».
                // В чат при этом ничего не дублируется: текст ответа команды
                // человек только что прочитал.
                guard.onStageChanged(player);
                return;
            }

            // Вошёл: убрать подсказку, снять таймаут, вернуть полный список
            // команд клиенту.
            guard.onAuthenticated(player);
            // Придержанное сообщение о входе показываем только теперь —
            // ровно то, которое сформировал EssentialsX.
            plugin.releaseJoinMessage(player.getUniqueId());
        });
    }

    /** Адрес игрока для привязки сессии. */
    protected static String addressOf(Player player) {
        return player.getAddress() == null || player.getAddress().getAddress() == null
                ? ""
                : player.getAddress().getAddress().getHostAddress();
    }
}
