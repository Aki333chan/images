package ovh.aurumgg.auth.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранилище в памяти вместо MariaDB.
 *
 * Отдельно умеет притворяться сломанным (failing) — недоступная база это не
 * экзотика, а обычный вторник, и поведение входа в этот момент важнее
 * половины остального.
 */
final class FakeAuthRepository implements AuthRepository {

    private final Map<UUID, AuthAccount> byUuid = new ConcurrentHashMap<>();
    /** В каком потоке нас звали — по этому проверяется, что не в главном. */
    final List<String> callThreads = new ArrayList<>();
    boolean failing;

    @Override
    public void initSchema() {}

    @Override
    public Optional<AuthAccount> findByUuid(UUID uuid) throws Exception {
        record();
        return Optional.ofNullable(byUuid.get(uuid));
    }

    @Override
    public Optional<AuthAccount> findByUsername(String username) throws Exception {
        record();
        return byUuid.values().stream()
                .filter(a -> a.username().toLowerCase(Locale.ROOT).equals(username.toLowerCase(Locale.ROOT)))
                .findFirst();
    }

    @Override
    public void create(AuthAccount account) throws Exception {
        record();
        byUuid.put(account.uuid(), account);
    }

    @Override
    public void touchLogin(UUID uuid, Instant at, String ip) throws Exception {
        record();
        AuthAccount existing = byUuid.get(uuid);
        if (existing == null) return;
        byUuid.put(uuid, new AuthAccount(existing.uuid(), existing.username(), existing.passwordHash(),
                existing.email(), existing.registeredAt(), at, ip));
    }

    @Override
    public void updatePasswordHash(UUID uuid, String passwordHash) throws Exception {
        record();
        AuthAccount existing = byUuid.get(uuid);
        if (existing == null) return;
        byUuid.put(uuid, new AuthAccount(existing.uuid(), existing.username(), passwordHash,
                existing.email(), existing.registeredAt(), existing.lastLoginAt(), existing.lastIp()));
    }

    // ---------------------------------------------------------- токены сброса

    private record Reset(UUID uuid, Instant expiresAt, boolean used) {}

    private final Map<String, Reset> resets = new ConcurrentHashMap<>();

    @Override
    public void createResetToken(UUID uuid, String tokenHash, Instant issuedAt, Instant expiresAt)
            throws Exception {
        record();
        resets.values().removeIf(r -> r.uuid().equals(uuid));
        resets.put(tokenHash, new Reset(uuid, expiresAt, false));
    }

    @Override
    public Optional<UUID> consumeResetToken(String tokenHash, Instant now) throws Exception {
        record();
        Reset reset = resets.get(tokenHash);
        if (reset == null || reset.used() || !now.isBefore(reset.expiresAt())) return Optional.empty();
        resets.put(tokenHash, new Reset(reset.uuid(), reset.expiresAt(), true));
        return Optional.of(reset.uuid());
    }

    @Override
    public int purgeResetTokens(Instant now) throws Exception {
        int before = resets.size();
        resets.values().removeIf(r -> r.used() || !now.isBefore(r.expiresAt()));
        return before - resets.size();
    }

    Optional<AuthAccount> peek(UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    private void record() throws Exception {
        synchronized (callThreads) {
            callThreads.add(Thread.currentThread().getName());
        }
        if (failing) throw new IllegalStateException("база недоступна");
    }

    @Override
    public void close() {}
}
