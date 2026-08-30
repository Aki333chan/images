package ovh.aurumgg.guilds.paper;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.guilds.api.GuildSettings;
import ovh.aurumgg.guilds.core.GuildService;
import ovh.aurumgg.guilds.core.StoredGuild;

/**
 * /guild settings — меню настроек гильдии.
 *
 * <h2>Меню, а не постоянный интерфейс</h2>
 *
 * Это обычный инвентарь, который открывается командой и закрывается по Esc.
 * Ничего постоянно висящего на экране здесь нет — тем оно и отличается от
 * сайдбара.
 *
 * <h2>Клики гасятся все до единого</h2>
 *
 * {@code event.setCancelled(true)} стоит ПЕРВОЙ строкой обработчика, до любых
 * проверок и до разбора, куда именно нажали. Причина: иконки настроек — это
 * настоящие предметы, и незакрытый клик означает, что игрок вынесет из меню
 * алмазный меч. Отдельно гасится и перетаскивание — {@code InventoryDragEvent}
 * это другое событие, и один только клик его не покрывает.
 *
 * <h2>Как добавить настройку</h2>
 *
 * Добавить один элемент в {@link #items()}. Раскладка по слотам считается сама,
 * размер меню тоже; никакие константы править не нужно.
 */
final class GuildSettingsMenu implements Listener {

    /** Заголовок — по нему же узнаём своё меню среди чужих. */
    private static final Component TITLE = Msg.colored("&8Настройки гильдии");

    /** Один пункт меню. */
    private interface Item {
        Material icon(StoredGuild guild);

        String title(StoredGuild guild);

        List<String> lore(StoredGuild guild);

        void click(Player player, StoredGuild guild);

        /** Показывать ли пункт вообще. */
        default boolean visible(StoredGuild guild, GuildService guilds) {
            return true;
        }
    }

    private final Plugin plugin;
    private final GuildService guilds;
    private final ChatPrompt prompts;

    GuildSettingsMenu(Plugin plugin, GuildService guilds, ChatPrompt prompts) {
        this.plugin = plugin;
        this.guilds = guilds;
        this.prompts = prompts;
    }

    void open(Player player) {
        StoredGuild guild = guilds.guildOf(player.getUniqueId()).orElse(null);
        if (guild == null) {
            Msg.send(player, "Вы не состоите в гильдии");
            return;
        }
        if (!guild.leader().equals(player.getUniqueId())) {
            Msg.send(player, "Настройки гильдии меняет лидер");
            return;
        }

        List<Item> visible = visibleItems(guild);
        // Размер кратен девяти и подбирается под число пунктов: пустая вторая
        // строка в меню из трёх иконок выглядит как «тут что-то не загрузилось».
        int size = Math.max(9, ((visible.size() - 1) / 9 + 1) * 9);
        Inventory inventory = Bukkit.createInventory(null, size, TITLE);

        for (int i = 0; i < visible.size(); i++) {
            inventory.setItem(i, render(visible.get(i), guild));
        }
        player.openInventory(inventory);
    }

    // ------------------------------------------------------------- пункты

    private List<Item> items() {
        List<Item> items = new ArrayList<>();

        items.add(new Item() {
            @Override
            public Material icon(StoredGuild guild) {
                return guild.settings().friendlyFire() ? Material.IRON_SWORD : Material.SHIELD;
            }

            @Override
            public String title(StoredGuild guild) {
                return "&fДружественный огонь: "
                        + (guild.settings().friendlyFire() ? "&cразрешён" : "&aвыключен");
            }

            @Override
            public List<String> lore(StoredGuild guild) {
                return List.of("&7Могут ли участники гильдии", "&7бить друг друга.",
                        "", "&eНажмите, чтобы переключить");
            }

            @Override
            public void click(Player player, StoredGuild guild) {
                apply(player, settings -> settings.withFriendlyFire(!settings.friendlyFire()));
            }

            @Override
            public boolean visible(StoredGuild guild, GuildService service) {
                // Если PvP выключено во всех мирах, настройка ничего не меняет,
                // и показывать её значило бы обещать несуществующий эффект.
                //
                // Через игровое правило, а не World#getPVP(): последний с
                // Paper 1.21.9 объявлен устаревшим именно в пользу правила.
                return Bukkit.getWorlds().stream()
                        .anyMatch(world -> Boolean.TRUE.equals(world.getGameRuleValue(GameRules.PVP)));
            }
        });

        items.add(new Item() {
            @Override
            public Material icon(StoredGuild guild) {
                return switch (guild.settings().joinPolicy()) {
                    case OPEN -> Material.OAK_DOOR;
                    case INVITE -> Material.PAPER;
                    case CLOSED -> Material.BARRIER;
                };
            }

            @Override
            public String title(StoredGuild guild) {
                return "&fВступление: &e" + guild.settings().joinPolicy().title();
            }

            @Override
            public List<String> lore(StoredGuild guild) {
                return List.of("&7Открыта — входят без приглашения.",
                        "&7По приглашению — только позванные.",
                        "&7Закрыта — не принимает никого.",
                        "", "&eНажмите, чтобы переключить");
            }

            @Override
            public void click(Player player, StoredGuild guild) {
                apply(player, settings -> settings.withJoinPolicy(settings.joinPolicy().next()));
            }
        });

        items.add(new Item() {
            @Override
            public Material icon(StoredGuild guild) {
                return Material.WRITABLE_BOOK;
            }

            @Override
            public String title(StoredGuild guild) {
                return "&fОписание гильдии";
            }

            @Override
            public List<String> lore(StoredGuild guild) {
                String motd = guild.settings().motd();
                return List.of("&7" + (motd.isBlank() ? "не задано" : motd),
                        "", "&eНажмите, чтобы изменить");
            }

            @Override
            public void click(Player player, StoredGuild guild) {
                prompts.ask(player, "Новое описание гильдии:", (who, text) ->
                        apply(who, settings -> settings.withMotd(text)));
            }
        });

        items.add(new Item() {
            @Override
            public Material icon(StoredGuild guild) {
                return Material.GOLD_INGOT;
            }

            @Override
            public String title(StoredGuild guild) {
                return "&fСнимать из банка: &e" + guild.settings().bankAccess().title();
            }

            @Override
            public List<String> lore(StoredGuild guild) {
                return List.of("&7Вкладывать может любой участник —", "&7это его собственные деньги.",
                        "&7Настраивается только расход.", "", "&eНажмите, чтобы переключить");
            }

            @Override
            public void click(Player player, StoredGuild guild) {
                apply(player, settings -> settings.withBankAccess(settings.bankAccess().next()));
            }

            @Override
            public boolean visible(StoredGuild guild, GuildService service) {
                // Без Vault банка нет, и настраивать доступ к нему не к чему.
                return service.bankAvailable();
            }
        });

        items.add(new Item() {
            @Override
            public Material icon(StoredGuild guild) {
                return Material.NAME_TAG;
            }

            @Override
            public String title(StoredGuild guild) {
                return "&fТег гильдии: &b[" + guild.tag() + "]";
            }

            @Override
            public List<String> lore(StoredGuild guild) {
                return List.of("&7Виден рядом с ником у всех участников.",
                        "&7До " + guilds.config().maxTagLength() + " символов, должен быть свободен.",
                        "", "&eНажмите, чтобы изменить");
            }

            @Override
            public void click(Player player, StoredGuild guild) {
                prompts.ask(player, "Новый тег гильдии:", (who, text) ->
                        guilds.changeTag(who.getUniqueId(), text).thenAccept(result -> sync(() -> {
                            Msg.result(who, result);
                            if (result.ok()) open(who);
                        })));
            }
        });

        return items;
    }

    // ------------------------------------------------------------- клики

    /**
     * Гасим ВСЁ и сразу.
     *
     * Иконки — настоящие предметы; незакрытый клик означает, что игрок вынесет
     * из меню алмазный меч. Отмена стоит до любых проверок именно поэтому:
     * любой ранний выход из метода не должен оставлять клик разрешённым.
     */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().title())) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Клик по нижнему инвентарю (своему) внутри нашего меню тоже гасится
        // выше, но обрабатывать его как выбор пункта не нужно.
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        StoredGuild guild = guilds.guildOf(player.getUniqueId()).orElse(null);
        if (guild == null || !guild.leader().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }

        List<Item> visible = visibleItems(guild);
        int slot = event.getSlot();
        if (slot < 0 || slot >= visible.size()) return;
        visible.get(slot).click(player, guild);
    }

    /** Перетаскивание — отдельное событие, одним кликом оно не покрывается. */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (TITLE.equals(event.getView().title())) event.setCancelled(true);
    }

    // --------------------------------------------------------- внутреннее

    private List<Item> visibleItems(StoredGuild guild) {
        return items().stream().filter(item -> item.visible(guild, guilds)).toList();
    }

    private ItemStack render(Item item, StoredGuild guild) {
        ItemStack stack = new ItemStack(item.icon(guild));
        ItemMeta meta = stack.getItemMeta();
        // Курсив по умолчанию у названий предметов — из ванильной механики
        // переименования, и в меню он выглядит как случайность.
        meta.displayName(Msg.colored(item.title(guild)).decoration(TextDecoration.ITALIC, false));
        meta.lore(item.lore(guild).stream()
                .map(line -> Msg.colored(line).decoration(TextDecoration.ITALIC, false))
                .toList());
        stack.setItemMeta(meta);
        return stack;
    }

    /** Сохранить изменение и открыть меню заново, чтобы игрок увидел результат. */
    private void apply(Player player, java.util.function.UnaryOperator<GuildSettings> change) {
        guilds.updateSettings(player.getUniqueId(), change).thenAccept(result -> sync(() -> {
            if (!result.ok()) {
                Msg.result(player, result);
                return;
            }
            open(player);
        }));
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, action);
    }
}
