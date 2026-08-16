package ovh.aurumgg.companion.core.model;

/**
 * Установленный на сервере плагин: имя так, как его зарегистрировал Bukkit,
 * и версия.
 *
 * Имя — именно то, что стоит в plugin.yml, а не «человеческое» название.
 * Это важнее, чем кажется: EssentialsX регистрируется как «Essentials»,
 * а InvSee++ — как «InvSeePlusPlus». Панель сверяется именно с этими
 * строками, поэтому здесь ничего не нормализуем.
 */
public record PluginInfo(String name, String version, boolean enabled) {}
