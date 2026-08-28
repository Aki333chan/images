package ovh.aurumgg.auth.paper;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ovh.aurumgg.auth.core.AuthConfig;
import ovh.aurumgg.auth.core.AuthService;
import ovh.aurumgg.auth.core.DeferredMessages;

/**
 * Сообщения о входе и выходе: отложить, а не заглушить.
 *
 * <h2>Из-за чего вообще ссорятся AuthMe и EssentialsX</h2>
 *
 * AuthMe обрабатывает PlayerJoinEvent на приоритете LOWEST и сразу делает
 * setJoinMessage(null) — логика понятная: игрок ещё не вошёл, объявлять о нём
 * рано. Беда в том, что происходит дальше.
 *
 * EssentialsX обрабатывает то же событие на HIGHEST, то есть ПОСЛЕ. Проверено
 * по его исходникам (EssentialsPlayerListener, метод joinFlow): он читает
 * текущее сообщение события и первым же делом проверяет
 *
 * <pre>else if (message == null || hideJoinQuitMessages()) effectiveMessage = null;</pre>
 *
 * То есть null на входе — это для него «сообщения быть не должно», и ветка с
 * собственным настроенным текстом (custom-join-message) не выполняется вовсе.
 * Погашенное на LOWEST сообщение EssentialsX не восстанавливает и не
 * перегенерирует — оно теряется навсегда. Ровно это и выглядит как «AuthMe
 * съел мои join-сообщения и MOTD».
 *
 * <h2>Что делаем вместо этого</h2>
 *
 * Не мешаем EssentialsX работать. Даём ему на HIGHEST сформировать свой текст
 * как обычно, а сами вступаем на MONITOR — последнем приоритете, когда
 * итоговое сообщение уже готово. Забираем его себе, гасим показ и
 * воспроизводим после успешного входа. Игрок видит именно то сообщение,
 * которое настроено в EssentialsX, просто на несколько секунд позже.
 *
 * <h2>Честно про цену этого решения</h2>
 *
 * MONITOR по документации Bukkit предназначен только для наблюдения, менять
 * событие на нём не полагается. Здесь это делается сознательно, потому что
 * промежуточного приоритета между HIGHEST и MONITOR не существует, а любое
 * вмешательство ДО EssentialsX уничтожает его сообщение — то есть выбор стоит
 * между «нарушить соглашение о MONITOR» и «повторить ровно ту поломку, от
 * которой уходим». Побочный эффект: плагины, которые тоже читают сообщение на
 * MONITOR и зарегистрировались после нас (например, мосты в Discord), увидят
 * null. Если это мешает, режим join-messages.mode: ignore выключает
 * вмешательство целиком.
 *
 * Механизм проверен по актуальным исходникам EssentialsX, но подтвердить его
 * на живом сервере с конкретной сборкой всё равно стоит: у EssentialsX это
 * внутренняя логика, а не публичный контракт, и она может измениться.
 */
final class JoinMessageListener implements Listener {

    private final AuthService service;
    private final AuthConfig config;
    private final DeferredMessages<Component> joins;

    JoinMessageListener(
            AuthService service, AuthConfig config, DeferredMessages<Component> joins) {
        this.service = service;
        this.config = config;
        this.joins = joins;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (config.joinMessageMode() == AuthConfig.JoinMessageMode.IGNORE) return;

        UUID uuid = event.getPlayer().getUniqueId();
        // Вошедшего по сессии или через прокси объявляем как обычно: он уже
        // в игре, откладывать нечего.
        if (service.isAuthenticated(uuid)) return;

        Component message = event.joinMessage();
        if (config.joinMessageMode() == AuthConfig.JoinMessageMode.DEFER) {
            joins.hold(uuid, message);
        }
        event.joinMessage(null);
    }

    /**
     * Выход.
     *
     * ЗДЕСЬ ЖЕ И ТОЛЬКО ЗДЕСЬ снимается состояние игрока в сервисе. Причина в
     * порядке: слушатели одного приоритета вызываются в порядке регистрации, и
     * если бы состояние убирал кто-то другой на том же MONITOR, мы читали бы
     * «не авторизован» у всех подряд и гасили сообщение о выходе даже тем, кто
     * спокойно играл час. AuthService.onQuit возвращает прежнее состояние,
     * поэтому уборка и чтение здесь — одно действие.
     *
     * У не вошедшего сообщение о выходе гасится, а не откладывается: о его
     * появлении никто не объявлял, и «игрок вышел» без предшествующего «игрок
     * зашёл» выглядит как сбой. Показывать потом тут нечего и некому.
     *
     * Заодно выбрасываем придержанное сообщение о входе — иначе оно осталось
     * бы в памяти навсегда у каждого, кого выкинуло по таймауту.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        boolean wasAuthenticated = service.onQuit(uuid);
        joins.drop(uuid);

        if (config.joinMessageMode() == AuthConfig.JoinMessageMode.IGNORE) return;
        if (wasAuthenticated) return;
        event.quitMessage(null);
    }

    /**
     * Показать придержанное сообщение — вызывается после успешного входа.
     *
     * Только с главного потока: рассылка сообщения всем — операция сервера,
     * а не рабочего потока авторизации.
     */
    void releaseJoinMessage(UUID uuid) {
        if (config.joinMessageMode() != AuthConfig.JoinMessageMode.DEFER) return;
        joins.take(uuid).ifPresent(message -> Bukkit.getServer().sendMessage(message));
    }
}
