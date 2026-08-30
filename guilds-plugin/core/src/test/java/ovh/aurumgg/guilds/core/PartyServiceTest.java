package ovh.aurumgg.guilds.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Пати: приглашения, наследование лидерства, уборка брошенных групп. */
class PartyServiceTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BORIS = UUID.nameUUIDFromBytes("boris".getBytes());
    private static final UUID VERA = UUID.nameUUIDFromBytes("vera".getBytes());
    private static final UUID GLEB = UUID.nameUUIDFromBytes("gleb".getBytes());

    private final Map<UUID, String> names = new HashMap<>(Map.of(
            ANNA, "Анна", BORIS, "Борис", VERA, "Вера", GLEB, "Глеб"));

    private AtomicReference<Instant> now;
    private PartyService service;

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(Instant.parse("2026-01-01T12:00:00Z"));
        service = new PartyService(
                now::get, uuid -> names.getOrDefault(uuid, "?"), 4, Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("Приглашение принимается и добавляет в пати")
    void обычныйПуть() {
        assertTrue(service.create(ANNA).ok());
        assertTrue(service.invite(ANNA, BORIS).ok());
        assertTrue(service.accept(BORIS, null).ok());

        assertEquals(List.of(ANNA, BORIS), service.view(ANNA).orElseThrow().members());
        assertTrue(service.isLeader(ANNA));
    }

    @Test
    @DisplayName("Приглашение истекает само")
    void приглашениеИстекает() {
        service.create(ANNA);
        service.invite(ANNA, BORIS);

        now.set(now.get().plus(Duration.ofMinutes(5)));

        assertFalse(service.accept(BORIS, null).ok());
        assertTrue(service.view(BORIS).isEmpty());
    }

    @Test
    @DisplayName("Звать может любой участник, выгонять — только лидер")
    void ктоЧтоМожет() {
        service.create(ANNA);
        service.invite(ANNA, BORIS);
        service.accept(BORIS, null);

        // Пати — вещь на полчаса, бегать за лидером ради «позови ещё вот его»
        // не должно быть нужно.
        assertTrue(service.invite(BORIS, VERA).ok());
        service.accept(VERA, null);

        assertFalse(service.kick(BORIS, VERA).ok(), "выгонять может только лидер");
        assertTrue(service.kick(ANNA, VERA).ok());
    }

    @Test
    @DisplayName("Два приглашения не затирают друг друга, принять можно нужное")
    void двоеЗовутОдного() {
        service.create(ANNA);
        service.create(BORIS);
        service.invite(ANNA, VERA);
        service.invite(BORIS, VERA);

        assertTrue(service.accept(VERA, ANNA).ok());
        assertEquals(ANNA, service.view(VERA).orElseThrow().leader());
    }

    @Test
    @DisplayName("Без указания зовущего принимается самое свежее приглашение")
    void безУказанияБерётсяСвежее() {
        service.create(ANNA);
        service.create(BORIS);
        service.invite(ANNA, VERA);
        service.invite(BORIS, VERA);

        assertTrue(service.accept(VERA, null).ok());
        assertEquals(BORIS, service.view(VERA).orElseThrow().leader());
    }

    @Test
    @DisplayName("Лидерство наследует вступивший раньше остальных")
    void наследованиеПоВремениВступления() {
        service.create(ANNA);
        service.invite(ANNA, BORIS);
        service.accept(BORIS, null);
        now.set(now.get().plusSeconds(30));
        service.invite(ANNA, VERA);
        service.accept(VERA, null);

        assertTrue(service.leave(ANNA).ok());

        assertEquals(BORIS, service.view(BORIS).orElseThrow().leader());
        assertEquals(2, service.view(BORIS).orElseThrow().size());
    }

    @Test
    @DisplayName("Пати распускается только когда не осталось никого")
    void роспускТолькоПустой() {
        service.create(ANNA);
        service.invite(ANNA, BORIS);
        service.accept(BORIS, null);

        service.leave(ANNA);
        assertTrue(service.view(BORIS).isPresent(), "уход лидера группу не разваливает");

        service.leave(BORIS);
        assertTrue(service.view(BORIS).isEmpty());
    }

    @Test
    @DisplayName("Передача лидерства меняет лидера, состав не трогая")
    void передачаЛидерства() {
        service.create(ANNA);
        service.invite(ANNA, BORIS);
        service.accept(BORIS, null);

        assertTrue(service.promote(ANNA, BORIS).ok());
        assertTrue(service.isLeader(BORIS));
        assertFalse(service.isLeader(ANNA));
        assertEquals(2, service.view(ANNA).orElseThrow().size());
    }

    @Test
    @DisplayName("Больше вместимости в пати не пускают")
    void вместимость() {
        service.create(ANNA);
        for (UUID uuid : List.of(BORIS, VERA, GLEB)) {
            service.invite(ANNA, uuid);
            service.accept(uuid, null);
        }
        UUID пятый = UUID.nameUUIDFromBytes("fifth".getBytes());
        names.put(пятый, "Пятый");

        assertFalse(service.invite(ANNA, пятый).ok());
    }

    @Test
    @DisplayName("Вышедший из игры остаётся в пати, а брошенная пати убирается")
    void уборкаБрошенных() {
        service.create(ANNA);
        service.invite(ANNA, BORIS);
        service.accept(BORIS, null);

        // Все вышли, но пати сразу не исчезает: разрыв связи на минуту не
        // должен разваливать группу.
        assertEquals(0, service.purgeIdle(Set.of(), Duration.ofMinutes(10)));
        assertTrue(service.view(ANNA).isPresent());

        now.set(now.get().plus(Duration.ofMinutes(11)));
        assertEquals(1, service.purgeIdle(Set.of(), Duration.ofMinutes(10)));
        assertTrue(service.view(ANNA).isEmpty());
    }

    @Test
    @DisplayName("Пока хоть кто-то в сети, пати не убирается")
    void однимОнлайнПатиЖивёт() {
        service.create(ANNA);
        service.invite(ANNA, BORIS);
        service.accept(BORIS, null);

        now.set(now.get().plus(Duration.ofHours(3)));
        assertEquals(0, service.purgeIdle(Set.of(BORIS), Duration.ofMinutes(10)));
        assertTrue(service.view(ANNA).isPresent());
    }

    @Test
    @DisplayName("В чужую пати не вступают дважды и сами себя не зовут")
    void очевидныеОтказы() {
        service.create(ANNA);

        assertFalse(service.create(ANNA).ok());
        assertFalse(service.invite(ANNA, ANNA).ok());
        assertFalse(service.accept(GLEB, null).ok());
        assertFalse(service.leave(GLEB).ok());
        assertFalse(service.promote(GLEB, ANNA).ok());
    }
}
