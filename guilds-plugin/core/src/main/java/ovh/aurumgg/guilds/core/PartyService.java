package ovh.aurumgg.guilds.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import ovh.aurumgg.guilds.api.GuildActionResult;
import ovh.aurumgg.guilds.api.PartyView;

/**
 * Пати — временные группы, живущие только в памяти.
 *
 * <h2>Почему без базы</h2>
 *
 * Пати собирают на один поход и распускают через полчаса. Писать её в базу
 * значило бы платить запросом за каждое приглашение ради данных, которые в
 * следующий раз никому не понадобятся, — и получать после перезапуска сервера
 * список групп, о которых все уже забыли. Перезапуск распускает пати, и это
 * правильное поведение, а не потеря данных.
 *
 * Гильдии, наоборот, в базе: они постоянные, и переживать перезапуск — их
 * главное свойство.
 *
 * <h2>Порядок вступления — не украшение</h2>
 *
 * Участники лежат в {@link LinkedHashMap}, то есть в порядке вступления, и
 * именно поэтому наследник лидера ищется одним взятием первого элемента.
 * Отдельного поля «когда вступил» для этого не нужно, а порядок вставки
 * LinkedHashMap гарантирует.
 *
 * <h2>Вышедший из игры остаётся в пати</h2>
 *
 * И это осознанно: разрыв связи на минуту не должен разваливать группу, ради
 * удобства которой всё и затевалось. В сайдбаре такой участник показан серым.
 * Чтобы брошенные пати не копились в памяти вечно, есть {@link #purgeIdle}.
 */
public final class PartyService {

    /** Пати изнутри. */
    private static final class Party {
        final long id;
        UUID leader;
        /** Порядок вставки = порядок вступления. От него зависит наследование. */
        final LinkedHashMap<UUID, Instant> members = new LinkedHashMap<>();
        /** Когда в пати последний раз кто-то был в сети — для уборки брошенных. */
        Instant lastSeenOnline;

        Party(long id, UUID leader, Instant now) {
            this.id = id;
            this.leader = leader;
            this.members.put(leader, now);
            this.lastSeenOnline = now;
        }
    }

    /** Приглашение: кто позвал, в какую пати и до какого момента оно живёт. */
    private record Invite(long partyId, UUID inviter, Instant expiresAt) {}

    private final Supplier<Instant> clock;
    private final NameResolver names;
    // Не final: /guild admin reload их меняет. volatile — читаются из потока
    // команд, а меняются из потока перезагрузки.
    private volatile int maxMembers;
    private volatile Duration inviteTtl;

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, Party> parties = new ConcurrentHashMap<>();
    private final Map<UUID, Long> partyOf = new ConcurrentHashMap<>();
    /**
     * Приглашения: приглашённый → все зовущие его пати.
     *
     * Список, а не одно приглашение: позвать одного и того же человека могут
     * сразу двое, и затирать первое приглашение вторым значило бы решать за
     * него, к кому идти.
     */
    private final Map<UUID, List<Invite>> invites = new ConcurrentHashMap<>();

    public PartyService(
            Supplier<Instant> clock, NameResolver names, int maxMembers, Duration inviteTtl) {
        this.clock = clock;
        this.names = names;
        this.maxMembers = maxMembers;
        this.inviteTtl = inviteTtl;
    }

    // ------------------------------------------------------------- чтение

    /** Пати игрока. Синхронно и из памяти: это спрашивают на каждом тике HUD. */
    public Optional<PartyView> view(UUID player) {
        Party party = partyOf(player);
        if (party == null) return Optional.empty();
        return Optional.of(toView(party));
    }

    /** Участники пати игрока, включая его самого. Пусто, если пати нет. */
    public List<UUID> members(UUID player) {
        Party party = partyOf(player);
        return party == null ? List.of() : List.copyOf(party.members.keySet());
    }

    public boolean isLeader(UUID player) {
        Party party = partyOf(player);
        return party != null && party.leader.equals(player);
    }

    /**
     * Применить перечитанный config.yml.
     *
     * Уже собранные пати не трогаются: уменьшили лимит — существующие
     * переполненные группы остаются как есть, новых сверх лимита не собрать.
     * Разгонять людей по домам из-за правки числа в конфиге плагин не должен.
     */
    public void applyConfig(int maxMembers, Duration inviteTtl) {
        this.maxMembers = maxMembers;
        this.inviteTtl = inviteTtl;
    }

    public int maxMembers() {
        return maxMembers;
    }

    // ------------------------------------------------------------ команды

    public synchronized GuildActionResult create(UUID player) {
        if (partyOf(player) != null) return GuildActionResult.fail("Вы уже в пати");
        Party party = new Party(nextId.getAndIncrement(), player, clock.get());
        parties.put(party.id, party);
        partyOf.put(player, party.id);
        // Приглашения, которые игрок не принял, теряют смысл: он уже в пати.
        invites.remove(player);
        return GuildActionResult.ok("Пати создана. Зовите: /party invite <ник>");
    }

    /**
     * Позвать в пати.
     *
     * Звать может ЛЮБОЙ участник, а не только лидер. Пати — вещь на полчаса, и
     * заставлять всех бегать за лидером ради «позови ещё вот его» значит
     * добавить трения ровно там, где его быть не должно. Выгонять при этом
     * может только лидер: выгнать — решение, которое кто-то оспорит.
     */
    public synchronized GuildActionResult invite(UUID inviter, UUID target) {
        if (inviter.equals(target)) return GuildActionResult.fail("Себя звать некуда");

        Party party = partyOf(inviter);
        if (party == null) {
            return GuildActionResult.fail("Сначала создайте пати: /party create");
        }
        if (partyOf(target) != null) {
            return GuildActionResult.fail(names.nameOf(target) + " уже в пати");
        }
        if (party.members.size() >= maxMembers) {
            return GuildActionResult.fail("В пати уже " + maxMembers + " человек — больше не помещается");
        }

        Instant now = clock.get();
        List<Invite> pending = new ArrayList<>(active(target, now));
        if (pending.stream().anyMatch(invite -> invite.partyId() == party.id)) {
            return GuildActionResult.fail(names.nameOf(target) + " уже приглашён");
        }
        pending.add(new Invite(party.id, inviter, now.plus(inviteTtl)));
        invites.put(target, pending);

        return GuildActionResult.ok(names.nameOf(target) + " приглашён. Приглашение действует "
                + minutes(inviteTtl));
    }

    /**
     * Принять приглашение.
     *
     * @param from от кого именно; null — самое свежее из действующих
     */
    public synchronized GuildActionResult accept(UUID player, UUID from) {
        if (partyOf(player) != null) return GuildActionResult.fail("Вы уже в пати");

        Instant now = clock.get();
        List<Invite> pending = active(player, now);
        if (pending.isEmpty()) {
            // Отдельный текст про истечение здесь не нужен: с точки зрения
            // игрока «протухло» и «не звали» — одно и то же состояние, а
            // разница в формулировке ничего ему не даёт.
            return GuildActionResult.fail("Вас никто не звал в пати");
        }

        Invite chosen = null;
        if (from == null) {
            // Самое свежее: если зовут двое, человек почти наверняка отвечает
            // на последнее приглашение, которое видел.
            chosen = pending.get(pending.size() - 1);
        } else {
            for (Invite invite : pending) {
                if (invite.inviter().equals(from)) chosen = invite;
            }
            if (chosen == null) {
                return GuildActionResult.fail(names.nameOf(from) + " вас в пати не звал");
            }
        }

        Party party = parties.get(chosen.partyId());
        if (party == null) {
            // Пати успела распасться между приглашением и ответом.
            invites.remove(player);
            return GuildActionResult.fail("Этой пати больше нет");
        }
        if (party.members.size() >= maxMembers) {
            return GuildActionResult.fail("В пати уже нет места");
        }

        party.members.put(player, now);
        party.lastSeenOnline = now;
        partyOf.put(player, party.id);
        invites.remove(player);
        return GuildActionResult.ok("Вы в пати " + names.nameOf(party.leader));
    }

    /**
     * Выйти из пати.
     *
     * Если ушёл лидер, лидерство переходит следующему по времени вступления
     * среди оставшихся. Пати распускается только когда не осталось никого —
     * уход лидера сам по себе группу не разваливает.
     */
    public synchronized GuildActionResult leave(UUID player) {
        Party party = partyOf(player);
        if (party == null) return GuildActionResult.fail("Вы не в пати");

        removeFrom(party, player);
        return GuildActionResult.ok("Вы вышли из пати");
    }

    /** Выгнать. Только лидер. */
    public synchronized GuildActionResult kick(UUID actor, UUID target) {
        Party party = partyOf(actor);
        if (party == null) return GuildActionResult.fail("Вы не в пати");
        if (!party.leader.equals(actor)) return GuildActionResult.fail("Выгонять может только лидер пати");
        if (actor.equals(target)) {
            return GuildActionResult.fail("Чтобы уйти самому, есть /party leave");
        }
        if (!party.members.containsKey(target)) {
            return GuildActionResult.fail(names.nameOf(target) + " не в вашей пати");
        }

        removeFrom(party, target);
        return GuildActionResult.ok(names.nameOf(target) + " выгнан из пати");
    }

    /** Передать лидерство. Только лидер. */
    public synchronized GuildActionResult promote(UUID actor, UUID target) {
        Party party = partyOf(actor);
        if (party == null) return GuildActionResult.fail("Вы не в пати");
        if (!party.leader.equals(actor)) {
            return GuildActionResult.fail("Передавать лидерство может только лидер пати");
        }
        if (actor.equals(target)) return GuildActionResult.fail("Вы и так лидер");
        if (!party.members.containsKey(target)) {
            return GuildActionResult.fail(names.nameOf(target) + " не в вашей пати");
        }

        party.leader = target;
        return GuildActionResult.ok(names.nameOf(target) + " теперь лидер пати");
    }

    // ------------------------------------------------------------ уборка

    /**
     * Отметить, кто сейчас в сети, и распустить брошенные пати.
     *
     * Вышедший игрок из пати не удаляется — разрыв связи не должен разваливать
     * группу. Но пати, в которой никого нет в сети дольше {@code idleAfter},
     * уже никому не нужна: это чистая память, которую иначе не освободит
     * ничто, кроме перезапуска.
     *
     * @return сколько пати распущено
     */
    public synchronized int purgeIdle(Set<UUID> onlineNow, Duration idleAfter) {
        Instant now = clock.get();
        int removed = 0;
        for (Party party : List.copyOf(parties.values())) {
            boolean anyOnline = party.members.keySet().stream().anyMatch(onlineNow::contains);
            if (anyOnline) {
                party.lastSeenOnline = now;
                continue;
            }
            if (party.lastSeenOnline.plus(idleAfter).isAfter(now)) continue;
            for (UUID member : party.members.keySet()) partyOf.remove(member, party.id);
            parties.remove(party.id);
            removed++;
        }
        // Заодно выбрасываем истёкшие приглашения: иначе карта росла бы на
        // каждом приглашении, которое никто не принял.
        invites.entrySet().removeIf(entry -> {
            entry.setValue(active(entry.getKey(), now));
            return entry.getValue().isEmpty();
        });
        return removed;
    }

    // --------------------------------------------------------- внутреннее

    private Party partyOf(UUID player) {
        Long id = partyOf.get(player);
        return id == null ? null : parties.get(id);
    }

    /**
     * Убрать участника, разобравшись с лидерством и роспуском.
     *
     * Единственное место, где меняется состав: наследование лидера и роспуск
     * пустой пати — правила, которые обязаны работать одинаково и при выходе,
     * и при исключении.
     */
    private void removeFrom(Party party, UUID player) {
        party.members.remove(player);
        partyOf.remove(player, party.id);

        if (party.members.isEmpty()) {
            parties.remove(party.id);
            return;
        }
        if (party.leader.equals(player)) {
            // Первый в LinkedHashMap = вступивший раньше всех из оставшихся.
            party.leader = party.members.keySet().iterator().next();
        }
    }

    private List<Invite> active(UUID target, Instant now) {
        List<Invite> pending = invites.get(target);
        if (pending == null) return List.of();
        List<Invite> alive = new ArrayList<>(pending.size());
        for (Invite invite : pending) {
            if (invite.expiresAt().isAfter(now) && parties.containsKey(invite.partyId())) {
                alive.add(invite);
            }
        }
        return alive;
    }

    private PartyView toView(Party party) {
        // Лидер первым: и в сайдбаре, и в списке участников он должен быть
        // сверху независимо от того, когда получил лидерство.
        List<UUID> ordered = new ArrayList<>(party.members.size());
        ordered.add(party.leader);
        for (UUID member : party.members.keySet()) {
            if (!member.equals(party.leader)) ordered.add(member);
        }
        return new PartyView(party.id, party.leader, List.copyOf(ordered));
    }

    private static String minutes(Duration duration) {
        long seconds = Math.max(1, duration.toSeconds());
        return seconds < 60 ? seconds + " с" : (seconds / 60) + " мин";
    }
}
