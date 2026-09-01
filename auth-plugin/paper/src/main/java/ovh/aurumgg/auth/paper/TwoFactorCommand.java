package ovh.aurumgg.auth.paper;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import ovh.aurumgg.auth.api.AuthStatus;
import ovh.aurumgg.auth.core.AuthConfig;
import ovh.aurumgg.auth.core.AuthService;
import ovh.aurumgg.auth.core.HelpBook;
import ovh.aurumgg.auth.core.totp.Totp;

/**
 * Двухфакторка: /2fa.
 *
 * <pre>
 * /2fa &lt;код&gt;          ввод кода при входе
 * /2fa enable          начать настройку — покажет секрет для приложения
 * /2fa confirm &lt;код&gt;  подтвердить настройку
 * /2fa disable &lt;код&gt;  выключить
 * </pre>
 *
 * ПО ЖЕЛАНИЮ И ТОЛЬКО ПО ЖЕЛАНИЮ. Никакой настройки «включить всем» нет
 * намеренно: принудительная двухфакторка на игровом сервере означает, что часть
 * игроков просто перестанет заходить, а часть потеряет доступ вместе с
 * телефоном. Кому нужно — включит.
 *
 * Ввод кода и подкоманды различаются по виду аргумента: код — это шесть цифр,
 * и спутать его с «enable» невозможно.
 */
final class TwoFactorCommand extends AuthCommandBase {

    private final AuthConfig config;

    TwoFactorCommand(
            AurumAuthPlugin plugin, AuthService service, AuthGuardListener guard, AuthConfig config) {
        super(plugin, service, guard);
        this.config = config;
    }

    @Override
    protected void run(Player player, String[] args) {
        if (args.length == 0) {
            usage(player);
            return;
        }

        String first = args[0].toLowerCase(Locale.ROOT);

        // Шесть цифр — это код, а не подкоманда.
        if (first.length() == Totp.DIGITS && first.chars().allMatch(Character::isDigit)) {
            submit(player, args[0]);
            return;
        }

        switch (first) {
            case "enable", "on", "включить" -> begin(player);
            case "confirm", "подтвердить" -> {
                if (args.length < 2) {
                    player.sendMessage(AurumAuthPlugin.prefixed("Использование: /2fa confirm <код>"));
                    return;
                }
                service.confirmTotp(player.getUniqueId(), args[1])
                        .thenAccept(outcome -> finish(player, outcome));
            }
            case "disable", "off", "выключить" -> {
                if (args.length < 2) {
                    // Код обязателен: иначе двухфакторку снял бы любой, кто на
                    // минуту сел за компьютер с уже вошедшим игроком.
                    player.sendMessage(AurumAuthPlugin.prefixed("Использование: /2fa disable <код>"));
                    return;
                }
                service.disableTotp(player.getUniqueId(), args[1])
                        .thenAccept(outcome -> finish(player, outcome));
            }
            default -> usage(player);
        }
    }

    /** Код при входе. */
    private void submit(Player player, String code) {
        boolean awaiting = service.status(player.getUniqueId())
                .filter(status -> status == AuthStatus.AWAITING_TOTP)
                .isPresent();
        if (!awaiting) {
            player.sendMessage(AurumAuthPlugin.prefixed(
                    "Код сейчас не нужен. Сначала /login <пароль>"));
            return;
        }
        service.submitTotp(player.getUniqueId(), code, addressOf(player))
                .thenAccept(outcome -> finish(player, outcome));
    }

    /**
     * Начало настройки: показать секрет.
     *
     * Секрет показывается и строкой, и ссылкой otpauth. QR-код в чат не
     * нарисовать, поэтому строка — основной способ: её вводят в приложении
     * вручную. Ссылка кликабельна и на телефоне откроет приложение сразу.
     */
    private void begin(Player player) {
        if (!service.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(AurumAuthPlugin.prefixed("Сначала войдите: /login <пароль>"));
            return;
        }
        service.beginTotpSetup(player.getUniqueId(), config.totpIssuer()).thenAccept(setup ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (setup.isEmpty()) {
                        player.sendMessage(AurumAuthPlugin.prefixed("Не удалось начать настройку"));
                        return;
                    }
                    player.sendMessage(AurumAuthPlugin.prefixed(
                            "Добавьте в Google Authenticator (или любое другое приложение):"));
                    player.sendMessage(Component.text("  " + Totp.readable(setup.get().secret()))
                            .color(NamedTextColor.AQUA));
                    player.sendMessage(Component.text("  [открыть в приложении]")
                            .color(NamedTextColor.GRAY)
                            .clickEvent(ClickEvent.openUrl(setup.get().otpauthUri())));
                    player.sendMessage(AurumAuthPlugin.prefixed(
                            "Затем подтвердите: /2fa confirm <код из приложения>"));
                    player.sendMessage(AurumAuthPlugin.prefixed(
                            "Пока не подтвердите — вход остаётся по одному паролю."));
                }));
    }

    /** Справка: строка на команду, чтобы порядок шагов читался сверху вниз. */
    private void usage(Player player) {
        List<String> lines = HelpBook.titled("Двухфакторная авторизация", "/2fa help")
                .add("/2fa <код>", "ввести код при входе — то, что нужно каждый день")
                .add("/2fa enable", "начать подключение: покажет секрет для приложения")
                .add("/2fa confirm <код>", "подтвердить подключение первым кодом")
                .add("/2fa disable <код>", "выключить двухфакторку")
                .build()
                .page(1);
        for (String line : lines) player.sendMessage(AurumAuthPlugin.colored(line));
    }
}
