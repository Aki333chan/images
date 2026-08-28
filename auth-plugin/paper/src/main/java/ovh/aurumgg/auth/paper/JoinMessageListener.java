package ovh.aurumgg.auth.paper;

import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ovh.aurumgg.auth.core.AuthConfig;
import ovh.aurumgg.auth.core.AuthService;
import ovh.aurumgg.auth.core.DeferredMessages;
import ovh.aurumgg.auth.core.MessageSettings;

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

    /**
     * Цвета задаются кодами вида &amp;a — так их пишут в конфигах EssentialsX и
     * почти всех остальных плагинов. MiniMessage (&lt;green&gt;) здесь НЕ
     * разбирается намеренно: человек, который переносит текст из старого
     * конфига, не должен обнаружить в чате «&amp;e» вместо жёлтого.
     */
    private static final LegacyComponentSerializer COLORS = LegacyComponentSerializer.legacyAmpersand();

    private final AuthService service;
    private final AuthConfig config;
    private final DeferredMessages<Component> joins;
    /** Тексты берутся из ссылки, а не копией: /auth reload меняет их на живом сервере. */
    private volatile MessageSettings messages;

    JoinMessageListener(
            AuthService service, AuthConfig config, DeferredMessages<Component> joins) {
        this.service = service;
        this.config = config;
        this.joins = joins;
        this.messages = config.messages();
    }

    void updateMessages(MessageSettings updated) {
        this.messages = updated;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        MessageSettings texts = messages;

        // Свои тексты включены — чужое сообщение гасим всегда, даже у
        // вошедшего по сессии. Иначе он увидел бы и стандартное, и наше.
        // Своё покажется из releaseJoinMessage, когда игрок действительно
        // окажется в игре.
        if (texts.joinEnabled()) {
            event.joinMessage(null);
            return;
        }

        if (config.joinMessageMode() == AuthConfig.JoinMessageMode.IGNORE) return;

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

        if (!wasAuthenticated) {
            // Не вошедшего не объявляли — и о выходе объявлять нечего.
            if (config.joinMessageMode() != AuthConfig.JoinMessageMode.IGNORE) {
                event.quitMessage(null);
            }
            return;
        }

        // Вошедший: если включены свои тексты, показываем их вместо чужого.
        if (customQuit(event.getPlayer())) event.quitMessage(null);
    }

    /**
     * Показать сообщение о входе и приветствие — после успешного входа.
     *
     * Только с главного потока: рассылка сообщения всем — операция сервера,
     * а не рабочего потока авторизации.
     *
     * Если включены свои тексты, они ЗАМЕНЯЮТ придержанное чужое: два
     * сообщения о входе подряд — худший из возможных исходов. Именно поэтому
     * свои тексты по умолчанию выключены (см. MessageSettings).
     */
    void releaseJoinMessage(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        MessageSettings texts = messages;

        if (texts.joinEnabled()) {
            joins.drop(uuid);
            if (player != null) {
                boolean fresh = service.isFreshRegistration(uuid);
                String template = fresh ? texts.firstJoinText() : texts.joinText();
                broadcast(template, player);
            }
        } else if (config.joinMessageMode() == AuthConfig.JoinMessageMode.DEFER) {
            joins.take(uuid).ifPresent(message -> Bukkit.getServer().sendMessage(message));
        }

        if (texts.motdEnabled() && player != null) {
            for (String line : texts.motdLines()) {
                player.sendMessage(COLORS.deserialize(MessageSettings.apply(line, placeholders(player))));
            }
        }
    }

    /**
     * Своё сообщение о выходе.
     *
     * Возвращает true, если сообщение мы взяли на себя, — тогда исходное
     * событие гасится, чтобы не показать оба.
     */
    private boolean customQuit(Player player) {
        MessageSettings texts = messages;
        if (!texts.quitEnabled()) return false;
        broadcast(texts.quitText(), player);
        return true;
    }

    private void broadcast(String template, Player player) {
        String text = MessageSettings.apply(template, placeholders(player));
        // Пустой текст — это «сообщения не нужно»: осмысленный способ
        // выключить одно сообщение, оставив остальные.
        if (text.isBlank()) return;
        Bukkit.getServer().sendMessage(COLORS.deserialize(text));
    }

    private static Map<String, String> placeholders(Player player) {
        return Map.of(
                "player", player.getName(),
                "online", String.valueOf(Bukkit.getOnlinePlayers().size()),
                "max", String.valueOf(Bukkit.getMaxPlayers()));
    }
}
