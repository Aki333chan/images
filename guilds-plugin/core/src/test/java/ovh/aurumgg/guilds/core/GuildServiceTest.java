package ovh.aurumgg.guilds.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ovh.aurumgg.guilds.api.BankAccess;
import ovh.aurumgg.guilds.api.GuildActionResult;
import ovh.aurumgg.guilds.api.GuildRank;
import ovh.aurumgg.guilds.api.JoinPolicy;

/** Правила гильдий — без MariaDB, без LuckPerms, без Vault и без Bukkit. */
class GuildServiceTest {

    private static final UUID LEADER = UUID.nameUUIDFromBytes("leader".getBytes());
    private static final UUID OFFICER = UUID.nameUUIDFromBytes("officer".getBytes());
    private static final UUID MEMBER = UUID.nameUUIDFromBytes("member".getBytes());
    private static final UUID STRANGER = UUID.nameUUIDFromBytes("stranger".getBytes());

    private final Map<UUID, String> names = new HashMap<>(Map.of(
            LEADER, "Лидер", OFFICER, "Офицер", MEMBER, "Участник", STRANGER, "Прохожий"));

    private AtomicReference<Instant> now;
    private FakeGuildRepository repository;
    private RecordingHooks hooks;
    private FakeEconomy economy;
    private GuildService service;

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(Instant.parse("2026-01-01T12:00:00Z"));
        repository = new FakeGuildRepository();
        hooks = new RecordingHooks();
        economy = new FakeEconomy();

        Logger logger = Logger.getLogger("guilds-test");
        logger.setLevel(Level.OFF);

        service = new GuildService(
                GuildsConfig.fromMap(Map.of()),
                repository,
                hooks,
                economy,
                uuid -> names.getOrDefault(uuid, "неизвестный"),
                logger,
                now::get);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    // ------------------------------------------------------------ создание

    @Test
    @DisplayName("Гильдия создаётся сразу с тегом и лидером")
    void создание() {
        GuildActionResult result = service.create(LEADER, "Драконы", "DRG").join();

        assertTrue(result.ok(), result.message());
        StoredGuild guild = service.guildOf(LEADER).orElseThrow();
        assertEquals("Драконы", guild.name());
        assertEquals("DRG", guild.tag());
        assertEquals(LEADER, guild.leader());
        assertEquals(GuildRank.LEADER, service.membership(LEADER).orElseThrow().rank());
    }

    @Test
    @DisplayName("Имя и тег заняты без учёта регистра")
    void уникальностьБезУчётаРегистра() {
        service.create(LEADER, "Драконы", "DRG").join();

        assertFalse(service.create(OFFICER, "дРаКоНы", "XYZ").join().ok(),
                "«Драконы» и «дРаКоНы» для любого читателя — одна и та же гильдия");
        assertFalse(service.create(OFFICER, "Другие", "drg").join().ok());
        assertTrue(service.create(OFFICER, "Другие", "OTH").join().ok());
    }

    @Test
    @DisplayName("Создание гильдии сразу заводит группу и записывает в неё лидера")
    void созданиеТрогаетМостОдинРаз() {
        service.create(LEADER, "Драконы", "DRG").join();

        assertEquals(1, hooks.count("created "));
        assertEquals(1, hooks.count("joined "));
    }

    @Test
    @DisplayName("Состоять можно только в одной гильдии")
    void второйГильдииНеБывает() {
        service.create(LEADER, "Драконы", "DRG").join();
        assertFalse(service.create(LEADER, "Другие", "OTH").join().ok());

        service.create(OFFICER, "Другие", "OTH").join();
        service.invite(OFFICER, LEADER).join();
        assertFalse(service.join(LEADER, "Другие").join().ok());
    }

    // ------------------------------------------------------- набор состава

    @Test
    @DisplayName("Приглашение живёт ограниченное время")
    void приглашениеИстекает() {
        service.create(LEADER, "Драконы", "DRG").join();
        assertTrue(service.invite(LEADER, MEMBER).join().ok());

        now.set(now.get().plus(Duration.ofHours(2)));

        assertFalse(service.join(MEMBER, null).join().ok(), "просроченное приглашение не работает");
        assertTrue(service.guildOf(MEMBER).isEmpty());
    }

    @Test
    @DisplayName("В открытую гильдию входят без приглашения, в закрытую — никак")
    void политикиВступления() {
        service.create(LEADER, "Драконы", "DRG").join();

        assertFalse(service.join(MEMBER, "Драконы").join().ok(), "по умолчанию — по приглашению");

        service.updateSettings(LEADER, settings -> settings.withJoinPolicy(JoinPolicy.OPEN)).join();
        assertTrue(service.join(MEMBER, "Драконы").join().ok());

        service.updateSettings(LEADER, settings -> settings.withJoinPolicy(JoinPolicy.CLOSED)).join();
        assertFalse(service.invite(LEADER, STRANGER).join().ok(),
                "в закрытую гильдию нельзя даже пригласить — иначе приглашение никуда не ведёт");
    }

    @Test
    @DisplayName("Офицер приглашает и выгоняет, участник — нет")
    void правоНаНабор() {
        buildGuild();

        assertTrue(service.invite(OFFICER, STRANGER).join().ok());
        assertFalse(service.invite(MEMBER, STRANGER).join().ok());

        assertTrue(service.kick(OFFICER, MEMBER).join().ok());
        assertTrue(service.guildOf(MEMBER).isEmpty());
    }

    @Test
    @DisplayName("Выгнать можно только того, кто ниже по рангу")
    void офицерНеВыгоняетРавных() {
        buildGuild();
        service.setRank(LEADER, MEMBER, GuildRank.OFFICER).join();

        // Иначе один поссорившийся офицер за минуту разбирает гильдию.
        assertFalse(service.kick(OFFICER, MEMBER).join().ok());
        assertFalse(service.kick(OFFICER, LEADER).join().ok());
        assertTrue(service.kick(LEADER, OFFICER).join().ok());
    }

    // ---------------------------------------------------------- лидерство

    @Test
    @DisplayName("Лидер не уходит, бросив гильдию")
    void лидерСначалаПередаётДела() {
        buildGuild();

        assertFalse(service.leave(LEADER).join().ok());
        assertTrue(service.transfer(LEADER, OFFICER).join().ok());
        assertTrue(service.leave(LEADER).join().ok());

        assertEquals(OFFICER, service.byName("Драконы").orElseThrow().leader());
    }

    @Test
    @DisplayName("Прежний лидер после передачи становится офицером, а не рядовым")
    void прежнийЛидерОстаётсяОфицером() {
        buildGuild();
        service.transfer(LEADER, MEMBER).join();

        assertEquals(GuildRank.LEADER, service.membership(MEMBER).orElseThrow().rank());
        assertEquals(GuildRank.OFFICER, service.membership(LEADER).orElseThrow().rank());
    }

    @Test
    @DisplayName("Последний участник выходит — гильдия распускается сама")
    void последнийУчастникРаспускаетГильдию() {
        service.create(LEADER, "Драконы", "DRG").join();
        long id = service.byName("Драконы").orElseThrow().id();

        assertTrue(service.leave(LEADER).join().ok());

        assertTrue(service.byId(id).isEmpty());
        assertTrue(repository.peek(id).isEmpty());
        assertEquals(1, hooks.count("deleted "));
    }

    @Test
    @DisplayName("Распустить может только лидер")
    void роспускТолькоЛидером() {
        buildGuild();

        assertFalse(service.disband(OFFICER).join().ok());
        assertTrue(service.disband(LEADER).join().ok());
        assertTrue(service.byName("Драконы").isEmpty());
    }

    // ----------------------------------------------- вмешательство извне

    @Test
    @DisplayName("Удаление аккаунта лидера передаёт лидерство, а не рушит гильдию")
    void удалениеАккаунтаЛидера() {
        buildGuild();
        long id = service.byName("Драконы").orElseThrow().id();

        service.onAccountDeleted(LEADER, "Лидер").join();

        StoredGuild guild = service.byId(id).orElseThrow();
        assertEquals(OFFICER, guild.leader(), "наследует старший по рангу");
        assertEquals(2, guild.members().size());
        assertTrue(service.guildOf(LEADER).isEmpty());
    }

    @Test
    @DisplayName("Удаление аккаунта единственного участника распускает гильдию")
    void удалениеАккаунтаПоследнего() {
        service.create(LEADER, "Драконы", "DRG").join();
        long id = service.byName("Драконы").orElseThrow().id();

        service.onAccountDeleted(LEADER, "Лидер").join();

        assertTrue(service.byId(id).isEmpty());
    }

    @Test
    @DisplayName("Среди равных по рангу наследует вступивший раньше")
    void наследуетСтарейший() {
        service.create(LEADER, "Драконы", "DRG").join();
        service.updateSettings(LEADER, settings -> settings.withJoinPolicy(JoinPolicy.OPEN)).join();

        service.join(MEMBER, "Драконы").join();
        now.set(now.get().plus(Duration.ofDays(1)));
        service.join(STRANGER, "Драконы").join();

        service.adminRemove("Лидер", "ГМ").join();

        assertEquals(MEMBER, service.byName("Драконы").orElseThrow().leader());
    }

    @Test
    @DisplayName("Административные команды работают по имени гильдии и игрока")
    void административныеДействия() {
        buildGuild();
        long id = service.byName("Драконы").orElseThrow().id();

        assertTrue(service.adminTransfer(id, "Участник", "ГМ").join().ok());
        assertEquals(MEMBER, service.byId(id).orElseThrow().leader());

        assertTrue(service.adminRemove("Офицер", "ГМ").join().ok());
        assertTrue(service.guildOf(OFFICER).isEmpty());

        assertTrue(service.adminDisband(id, "ГМ").join().ok());
        assertTrue(service.byId(id).isEmpty());
    }

    @Test
    @DisplayName("Исключить того, кто ни в какой гильдии не состоит, нечем")
    void исключениеНесостоящего() {
        buildGuild();
        assertFalse(service.adminRemove("Прохожий", "ГМ").join().ok());
    }

    // --------------------------------------------------------------- тег

    @Test
    @DisplayName("Смена тега трогает мост ровно один раз — суффикс висит на группе")
    void сменаТегаОдинЗапрос() {
        buildGuild();
        hooks.calls.clear();

        assertTrue(service.changeTag(LEADER, "NEW").join().ok());

        assertEquals(1, hooks.count("tag "));
        assertEquals(0, hooks.count("joined "), "участников по отдельности трогать не нужно");
        assertEquals("NEW", service.byName("Драконы").orElseThrow().tag());
    }

    @Test
    @DisplayName("Занятый чужой тег не отдаётся, свой же собственный — не помеха")
    void сменаТегаПроверяетЗанятость() {
        buildGuild();
        service.create(STRANGER, "Другие", "OTH").join();

        assertFalse(service.changeTag(LEADER, "OTH").join().ok());
        assertTrue(service.changeTag(LEADER, "DRG").join().ok(), "тег и так наш");
    }

    // -------------------------------------------------------------- банк

    @Test
    @DisplayName("Вкладывает любой участник, снимает лидер")
    void банк() {
        buildGuild();
        economy.give(MEMBER, 500);

        assertTrue(service.deposit(MEMBER, 300).join().ok());
        assertEquals(200, economy.balance(MEMBER));
        assertEquals(300, service.byName("Драконы").orElseThrow().bank());

        assertFalse(service.withdraw(MEMBER, 100).join().ok(), "по умолчанию снимает только лидер");
        assertTrue(service.withdraw(LEADER, 100).join().ok());
        assertEquals(100, economy.balance(LEADER));
        assertEquals(200, service.byName("Драконы").orElseThrow().bank());
    }

    @Test
    @DisplayName("Настройка открывает банк офицерам")
    void банкДляОфицеров() {
        buildGuild();
        economy.give(LEADER, 100);
        service.deposit(LEADER, 100).join();

        assertFalse(service.withdraw(OFFICER, 50).join().ok());
        service.updateSettings(LEADER,
                settings -> settings.withBankAccess(BankAccess.LEADER_AND_OFFICERS)).join();
        assertTrue(service.withdraw(OFFICER, 50).join().ok());
    }

    @Test
    @DisplayName("Каждая операция с банком попадает в лог")
    void банкПишетсяВЛог() {
        buildGuild();
        economy.give(MEMBER, 100);
        service.deposit(MEMBER, 100).join();
        service.withdraw(LEADER, 40).join();

        var log = repository.allBankEntries();
        assertEquals(2, log.size());
        assertTrue(log.get(0).deposit());
        assertEquals(100, log.get(0).amount());
        assertFalse(log.get(1).deposit());
        assertEquals(60, log.get(1).balanceAfter());
        assertEquals("Лидер", log.get(1).actorName());
    }

    @Test
    @DisplayName("Отказ выдачи возвращает деньги в банк обратной записью")
    void отказВыдачиНеТеряетДеньги() {
        buildGuild();
        economy.give(LEADER, 100);
        service.deposit(LEADER, 100).join();
        economy.rejectDeposits = true;

        assertFalse(service.withdraw(LEADER, 40).join().ok());

        assertEquals(100, service.byName("Драконы").orElseThrow().bank(), "деньги остались в банке");
        assertEquals(0, economy.balance(LEADER));
        // В логе должны быть оба движения: иначе разбор упрётся в снятие,
        // которого на самом деле не было.
        assertEquals(3, repository.allBankEntries().size());
    }

    @Test
    @DisplayName("Без Vault банк недоступен, а гильдии работают")
    void безVaultБанкаНет() {
        economy.available = false;
        buildGuild();

        assertFalse(service.bankAvailable());
        assertFalse(service.deposit(MEMBER, 10).join().ok());
        // Всё остальное на месте.
        assertTrue(service.setRank(LEADER, MEMBER, GuildRank.OFFICER).join().ok());
        assertTrue(service.changeTag(LEADER, "NEW").join().ok());
    }

    // -------------------------------------------------------- сохранение

    @Test
    @DisplayName("Состав после перезапуска читается из базы тем же")
    void переживаетПерезапуск() {
        buildGuild();
        service.transfer(LEADER, OFFICER).join();
        long id = service.byName("Драконы").orElseThrow().id();

        GuildService restarted = new GuildService(
                GuildsConfig.fromMap(Map.of()), repository, GuildHooks.noop(), economy,
                uuid -> names.getOrDefault(uuid, "неизвестный"),
                Logger.getLogger("guilds-test"), now::get);
        try {
            restarted.load();
            StoredGuild guild = restarted.byId(id).orElseThrow();
            assertEquals(OFFICER, guild.leader());
            assertEquals(3, guild.members().size());
            assertEquals(GuildRank.OFFICER, restarted.membership(LEADER).orElseThrow().rank());
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            restarted.close();
        }
    }

    /** Гильдия из трёх человек: лидер, офицер, участник. */
    private void buildGuild() {
        service.create(LEADER, "Драконы", "DRG").join();
        service.invite(LEADER, OFFICER).join();
        service.join(OFFICER, null).join();
        service.invite(LEADER, MEMBER).join();
        service.join(MEMBER, null).join();
        service.setRank(LEADER, OFFICER, GuildRank.OFFICER).join();
    }
}
