package ovh.aurumgg.auth.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import ovh.aurumgg.auth.api.PremiumVerdict;
import ovh.aurumgg.auth.core.AuthConfig;
import ovh.aurumgg.auth.core.AuthService;
import ovh.aurumgg.auth.core.premium.PremiumChecker;

/**
 * Всё, что нужно узнать до входа игрока в мир.
 *
 * ПОЧЕМУ ИМЕННО ЭТО СОБЫТИЕ. AsyncPlayerPreLoginEvent выполняется вне главного
 * потока — Bukkit сам зовёт его в потоке подключения. Значит, именно здесь и
 * только здесь можно позволить себе поход в MariaDB и запрос к Mojang, не
 * останавливая сервер. К моменту PlayerJoinEvent (а он уже на главном потоке)
 * всё известно, и там остаётся только применить решение.
 *
 * Приоритет LOWEST: остальные плагины, слушающие pre-login, должны видеть
 * подключение уже с посчитанным premium-вердиктом.
 */
final class PreLoginListener implements Listener {

    private final AuthService service;
    private final PremiumChecker premium;
    private final AuthConfig config;

    PreLoginListener(AuthService service, PremiumChecker premium, AuthConfig config) {
        this.service = service;
        this.premium = premium;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            // Кто-то уже отказал (бан, вайтлист) — не тратим запрос к Mojang.
            return;
        }

        String ip = event.getAddress() == null ? "" : event.getAddress().getHostAddress();
        PremiumVerdict verdict = config.premiumEnabled()
                ? premium.check(event.getUniqueId(), event.getName())
                : PremiumVerdict.UNKNOWN;

        service.onPreLogin(event.getUniqueId(), event.getName(), ip, verdict);
    }
}
