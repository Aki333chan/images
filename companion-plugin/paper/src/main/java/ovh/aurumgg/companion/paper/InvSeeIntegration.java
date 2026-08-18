package ovh.aurumgg.companion.paper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import ovh.aurumgg.companion.core.model.InventoryInfo;

/**
 * Инвентари игроков, которых нет в сети, — через InvSee++.
 *
 * ПОЧЕМУ РЕФЛЕКСИЯ, А НЕ ЗАВИСИМОСТЬ НА СБОРКЕ.
 * Артефакт InvSee++ (com.janboerman.invsee:invsee-plus-plus_plugin) лежит в
 * GitHub Packages, а тот требует авторизации даже для публичных пакетов.
 * Прописать такой репозиторий в build.gradle.kts значит сделать сборку
 * плагина невозможной без токена GitHub — цена, несоразмерная одной
 * необязательной функции. Поэтому вызовы идут рефлексией.
 *
 * Держится это на одном устойчивом факте: возвращаемый MainSpectatorInventory
 * наследует org.bukkit.inventory.Inventory, а он у нас есть на сборке. То
 * есть рефлексией добываем только объект, а читаем его штатным Bukkit API.
 *
 * Цепочка вызовов (проверена по исходникам InvSee++):
 *   Plugin p = pluginManager.getPlugin("InvSeePlusPlus");
 *   InvseeAPI api = p.getApi();
 *   CompletableFuture&lt;SpectateResponse&lt;MainSpectatorInventory&gt;&gt; f =
 *       api.mainSpectatorInventory(uuid, name, api.mainInventoryCreationOptions());
 *   if (f.get().isSuccess()) Inventory inv = (Inventory) f.get().getInventory();
 *
 * Любая осечка (другая версия, изменившаяся сигнатура) трактуется как
 * «данных нет»: панель покажет понятное сообщение, сервер не пострадает.
 */
final class InvSeeIntegration {

    /** Имя InvSee++ в Bukkit — именно такое, а не «InvSee++». */
    static final String PLUGIN_NAME = "InvSeePlusPlus";

    private static final long TIMEOUT_SECONDS = 5;

    private InvSeeIntegration() {}

    static boolean isAvailable() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        return plugin != null && plugin.isEnabled();
    }

    /**
     * @param playerName ник нужен самому InvSee++ для создания инвентаря;
     *                   если панель его не передала, читать нечего
     * @return пусто, если InvSee++ нет, ник неизвестен либо данных о игроке нет
     */
    static Optional<InventoryInfo> read(UUID playerUuid, String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();

        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) return Optional.empty();

        try {
            Object api = plugin.getClass().getMethod("getApi").invoke(plugin);
            Object options = api.getClass().getMethod("mainInventoryCreationOptions").invoke(api);

            Method fetch = findFetchMethod(api.getClass(), options.getClass());
            if (fetch == null) return Optional.empty();

            Object raw = fetch.invoke(api, playerUuid, playerName, options);
            if (!(raw instanceof CompletableFuture<?> future)) return Optional.empty();

            Object response = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response == null) return Optional.empty();

            Object success = response.getClass().getMethod("isSuccess").invoke(response);
            if (!Boolean.TRUE.equals(success)) return Optional.empty();

            Object inventory = response.getClass().getMethod("getInventory").invoke(response);
            if (!(inventory instanceof Inventory bukkitInventory)) return Optional.empty();

            return Optional.of(toInventoryInfo(bukkitInventory));
        } catch (Exception | NoClassDefFoundError e) {
            // Версия InvSee++ несовместима либо чтение не удалось — для
            // вызывающего это неотличимо от «плагина нет», и это правильно:
            // выше по стеку решение одно и то же.
            return Optional.empty();
        }
    }

    /**
     * Ищем mainSpectatorInventory(UUID, String, CreationOptions).
     * По имени и арности, а не по точной сигнатуре: тип options берём у
     * самого API, и захардкоживать его имя незачем.
     */
    private static Method findFetchMethod(Class<?> apiClass, Class<?> optionsClass) {
        for (Method method : apiClass.getMethods()) {
            if (!method.getName().equals("mainSpectatorInventory")) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 3) continue;
            if (!params[0].equals(UUID.class)) continue;
            if (!params[1].equals(String.class)) continue;
            if (!params[2].isAssignableFrom(optionsClass)) continue;
            return method;
        }
        return null;
    }

    /**
     * Раскладываем содержимое в ту же модель, что и живой инвентарь.
     *
     * Слоты берём как есть: у InvSee++ раскладка спектаторского инвентаря
     * совпадает с обычной в первых 36 позициях, а дальше идут броня и курсор,
     * которые панель для офлайн-режима не показывает.
     */
    private static InventoryInfo toInventoryInfo(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        List<ovh.aurumgg.companion.core.model.ItemInfo> items =
                new java.util.ArrayList<>();
        int limit = Math.min(contents.length, 36);
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) continue;
            items.add(ItemMapper.describe(slot, stack));
        }
        return new InventoryInfo(items, List.of(), null);
    }
}
