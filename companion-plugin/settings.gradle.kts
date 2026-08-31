// Резолвер тулчейнов: позволяет Gradle самому скачать нужный JDK, если его
// нет в системе. Без него сборка модуля paper падает с
// «Cannot find a Java installation … matching {languageVersion=25}» на любой
// машине, где JDK 25 не установлен вручную.
//
// Если JDK 25 в системе уже есть, Gradle берёт его и в сеть за ним не ходит.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "aurum-companion"

/**
 * Подключить соседний проект составной сборкой.
 *
 * Отдельной функцией — ради сообщения об ошибке. Если папки рядом нет, Gradle
 * сам скажет только «Included build … does not exist», и из этой строки не
 * понять ни зачем companion сдался чужой проект, ни что с этим делать.
 * Особенно если скачивали одну папку, а не репозиторий целиком, — а так его и
 * скачивают чаще всего.
 *
 * Путь можно переопределить свойством, если проекты лежат не рядом:
 *   gradlew.bat :paper:jar -Paurum.auth-plugin.dir=C:/code/auth-plugin
 */
fun includeSibling(name: String, why: String) {
    val override = providers.gradleProperty("aurum.$name.dir").orNull
    val dir = if (override != null) File(override) else File(settingsDir, "../$name")
    if (!File(dir, "settings.gradle.kts").isFile) {
        // Строками списком, а не одним текстовым блоком: в сообщении есть
        // подставленные значения, и trimIndent у блока считает отступ по
        // ИСХОДНОМУ тексту — многострочная подстановка съезжает.
        throw GradleException(
            listOf(
                "",
                "Рядом с companion-plugin нет проекта $name.",
                "Искали здесь: " + dir.absolutePath,
                "",
                "Зачем он нужен: $why",
                "",
                "Эти классы в готовый jar НЕ попадают — зависимость compileOnly.",
                "Они нужны только компилятору, а в игре их отдаёт сам плагин, если",
                "он установлен. Не установлен — companion работает как обычно,",
                "соответствующая часть просто отвечает «недоступно».",
                "",
                "Что делать: положить папку $name рядом с companion-plugin. Проще",
                "всего скачать репозиторий целиком, а не одну папку:",
                "",
                "    git clone https://github.com/Aki333chan/images.git aurum",
                "    cd aurum/companion-plugin",
                "",
                "Либо указать путь вручную:",
                "",
                "    gradlew.bat :paper:jar -Paurum.$name.dir=C:/путь/к/$name",
                "",
            ).joinToString("\n"),
        )
    }
    includeBuild(dir)
}
// Составная сборка: companion собирается против публичного API плагина
// авторизации (ovh.aurumgg:auth-api). Gradle подставит сюда соседний проект
// вместо похода в репозиторий — артефакт нигде не публикуется, и это
// избавляет от необходимости его публиковать ради одного интерфейса.
//
// В рантайме реализацию даёт сам AurumAuth через ServicesManager, поэтому
// зависимость compileOnly и мягкая: без установленного AurumAuth companion
// работает, просто /webtoken отвечает, что авторизация недоступна.
includeSibling(
    "auth-plugin",
    "companion компилируется против ovh.aurumgg:auth-api — интерфейса AurumAuth, " +
        "через который идут /webtoken и сброс пароля игроку из панели.",
)

// То же самое для плагина гильдий: companion собирается против ovh.aurumgg:
// guilds-api, а реализацию в рантайме отдаёт сам AurumGuilds через
// ServicesManager. Зависимость compileOnly и мягкая — без установленного
// AurumGuilds companion работает, просто раздел гильдий отвечает 503.
includeSibling(
    "guilds-plugin",
    "companion компилируется против ovh.aurumgg:guilds-api — интерфейса AurumGuilds, " +
        "через который панель показывает гильдии и вмешивается в них.",
)

// core — чистая Java без Bukkit: HTTP-сервер, JSON, авторизация, клиент тикетов.
//        Собирается и тестируется где угодно, включая CI без доступа к репозиторию Paper.
// paper — адаптер к Bukkit/Paper API: точка входа плагина, команда /ticket.
//         Требует JDK 25 — Paper 26.x скомпилирован под него.
include("core", "paper")
