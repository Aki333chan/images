package ovh.aurumgg.core.paper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ovh.aurumgg.core.api.CurrencySpec;
import ovh.aurumgg.core.engine.migration.MigrationImportResult;
import ovh.aurumgg.core.engine.migration.MigrationRepository;
import ovh.aurumgg.core.engine.migration.MigrationRunSummary;
import ovh.aurumgg.core.engine.migration.MigrationService;
import ovh.aurumgg.core.engine.migration.ObservedPlayerBalance;
import ovh.aurumgg.core.engine.migration.PlayerLedgerBalance;

final class MigrationCoordinator {
    private static final DateTimeFormatter EXPORT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final AurumCorePlugin plugin;
    private final BalanceObserver vault;
    private final CurrencySpec currency;
    private final MigrationRepository repository;
    private final MigrationService service;
    private final Executor databaseExecutor;
    private final int playersPerTick;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile BukkitTask scanTask;

    MigrationCoordinator(AurumCorePlugin plugin, BalanceObserver vault, CurrencySpec currency,
                         MigrationRepository repository, MigrationService service,
                         Executor databaseExecutor, int playersPerTick) {
        this.plugin = plugin;
        this.vault = vault;
        this.currency = currency;
        this.repository = repository;
        this.service = service;
        this.databaseExecutor = databaseExecutor;
        this.playersPerTick = playersPerTick;
    }

    void dryRun(CommandSender sender) {
        if (!vault.available()) {
            sender.sendMessage(plugin.messages().component("migration-vault-unavailable"));
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            sender.sendMessage(plugin.messages().component("migration-busy"));
            return;
        }
        UUID runId = UUID.randomUUID();
        String providerAtStart = vault.providerName();
        List<OfflinePlayer> players = Arrays.stream(plugin.getServer().getOfflinePlayers())
                .filter(player -> player.hasPlayedBefore() || player.isOnline())
                .sorted(Comparator.comparing(OfflinePlayer::getUniqueId))
                .toList();
        sender.sendMessage(plugin.messages().component("migration-started", Map.of(
                "run", runId.toString(), "players", Integer.toString(players.size()))));

        scanTask = new BukkitRunnable() {
            private final List<ObservedPlayerBalance> balances = new ArrayList<>(players.size());
            private int index;
            private int failures;

            @Override public void run() {
                if (!vault.providerName().equals(providerAtStart)) {
                    cancel();
                    scanTask = null;
                    busy.set(false);
                    sender.sendMessage(plugin.messages().component("migration-provider-changed"));
                    return;
                }
                int limit = Math.min(players.size(), index + playersPerTick);
                while (index < limit) {
                    OfflinePlayer player = players.get(index++);
                    var observed = vault.balance(player);
                    if (observed.isPresent()) {
                        balances.add(new ObservedPlayerBalance(player.getUniqueId(), player.getName(), observed.get()));
                    } else {
                        failures++;
                    }
                }
                if (index < players.size()) return;
                cancel();
                scanTask = null;
                int finalFailures = failures;
                databaseExecutor.execute(() -> {
                    try {
                        MigrationRunSummary summary = repository.saveSnapshot(runId, providerAtStart, currency,
                                List.copyOf(balances), finalFailures);
                        reply(sender, "migration-saved", summaryValues(summary));
                    } catch (Exception exception) {
                        plugin.getLogger().severe("Migration snapshot failed: " + exception.getMessage());
                        reply(sender, "migration-failed", Map.of("reason", safeMessage(exception)));
                    } finally {
                        busy.set(false);
                    }
                });
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    void status(CommandSender sender, UUID runId) {
        databaseExecutor.execute(() -> {
            try {
                var summary = runId == null ? repository.latest(currency) : repository.summary(runId, currency);
                if (summary.isEmpty()) reply(sender, "migration-not-found", Map.of());
                else reply(sender, "migration-summary", summaryValues(summary.get()));
            } catch (Exception exception) {
                reply(sender, "migration-failed", Map.of("reason", safeMessage(exception)));
            }
        });
    }

    void verify(CommandSender sender, UUID runId) {
        databaseExecutor.execute(() -> {
            try {
                MigrationRunSummary summary = repository.refreshComparison(runId, currency);
                reply(sender, "migration-summary", summaryValues(summary));
            } catch (Exception exception) {
                reply(sender, "migration-failed", Map.of("reason", safeMessage(exception)));
            }
        });
    }

    void importRun(CommandSender sender, UUID runId) {
        if (!busy.compareAndSet(false, true)) {
            sender.sendMessage(plugin.messages().component("migration-busy"));
            return;
        }
        sender.sendMessage(plugin.messages().component("migration-importing", Map.of("run", runId.toString())));
        databaseExecutor.execute(() -> {
            try {
                MigrationImportResult result = service.importRun(runId);
                String key = result.status() == MigrationImportResult.Status.VERIFIED
                        ? "migration-imported" : "migration-blocked";
                Map<String, String> values = new java.util.HashMap<>(summaryValues(result.summary()));
                values.put("imported", Integer.toString(result.imported()));
                values.put("reason", result.message());
                reply(sender, key, values);
            } catch (Exception exception) {
                plugin.getLogger().severe("Migration import failed: " + exception.getMessage());
                reply(sender, "migration-failed", Map.of("reason", safeMessage(exception)));
            } finally {
                busy.set(false);
            }
        });
    }

    void rollbackExport(CommandSender sender) {
        databaseExecutor.execute(() -> {
            try {
                List<PlayerLedgerBalance> rows = repository.allPlayerBalances(currency);
                Path directory = plugin.getDataFolder().toPath().resolve("exports");
                Files.createDirectories(directory);
                Path file = directory.resolve("balances-" + EXPORT_TIME.format(Instant.now(Clock.systemUTC())) + ".csv");
                writeCsv(file, rows);
                reply(sender, "migration-exported", Map.of(
                        "players", Integer.toString(rows.size()), "file", file.toAbsolutePath().toString()));
            } catch (Exception exception) {
                reply(sender, "migration-failed", Map.of("reason", safeMessage(exception)));
            }
        });
    }

    void close() {
        BukkitTask task = scanTask;
        if (task != null) task.cancel();
        scanTask = null;
        busy.set(false);
    }

    private void writeCsv(Path file, List<PlayerLedgerBalance> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("player_uuid,balance,currency\n");
            for (PlayerLedgerBalance row : rows) {
                writer.write(row.playerId() + "," + row.balance().toPlainString() + "," + currency.id() + "\n");
            }
        }
    }

    private void reply(CommandSender sender, String key, Map<String, String> values) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin,
                () -> sender.sendMessage(plugin.messages().component(key, values)));
    }

    private static Map<String, String> summaryValues(MigrationRunSummary summary) {
        return Map.of(
                "run", summary.runId().toString(),
                "status", summary.status(),
                "players", Integer.toString(summary.players()),
                "failures", Integer.toString(summary.readFailures()),
                "mismatches", Integer.toString(summary.mismatches()),
                "negative", Integer.toString(summary.negativeBalances()),
                "external", plain(summary.externalTotal()),
                "internal", plain(summary.internalTotal())
        );
    }

    private static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String safeMessage(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }
}
