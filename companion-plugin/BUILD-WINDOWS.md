# Сборка companion-плагина на Windows 11

Итог: файл `AurumCompanion-0.1.0.jar`, который кладётся в папку `plugins/`
игрового сервера.

Собирать можно на любой машине — плагин не зависит от того, где его собрали.
Удобно на своём компьютере, а готовый `.jar` потом загрузить на сервер через
файловый менеджер Pterodactyl.

---

## Что понадобится

**Только JDK.** Gradle ставить не нужно: в репозитории лежит «обёртка»
(`gradlew.bat`), которая скачает нужную версию сама при первом запуске.

Версий Java две, и путать их нельзя:

| | Зачем | Версия |
| --- | --- | --- |
| **JDK для запуска Gradle** | на нём работает сама сборка | 17 или новее |
| **JDK 25** | им компилируется модуль `paper` | ровно 25 |

Модуль `paper` требует именно **JDK 25**, потому что Paper 26.x собран под
него, и компилятор более старой версии не прочитает его class-файлы.

**Скачивать JDK 25 вручную не обязательно.** Gradle умеет доставать его сам —
за это отвечает резолвер тулчейнов, уже прописанный в `settings.gradle.kts`.
Если в системе есть любой JDK 17+, этого достаточно.

Проверить, что Java вообще есть, — в PowerShell:

```powershell
java -version
```

Если команда не найдена, поставьте JDK. Проще всего через встроенный
менеджер пакетов Windows:

```powershell
winget install Microsoft.OpenJDK.21
```

После установки **закройте и откройте PowerShell заново** — иначе он не
увидит новую переменную `PATH`.

---

## Шаг 1. Получить исходники

Если Git не установлен: `winget install Git.Git` (и снова перезапустить
PowerShell).

```powershell
cd $HOME
git clone https://github.com/Aki333chan/images.git aurum
cd aurum
git checkout claude/pterodactyl-admin-panel-core-984zye
cd companion-plugin
```

> Можно и без Git: на странице репозитория **Code → Download ZIP**,
> распаковать, и в PowerShell перейти в папку `companion-plugin` внутри
> распакованного архива.

## Шаг 2. Собрать

```powershell
.\gradlew.bat :paper:jar
```

Именно `.\gradlew.bat` — с точкой и обратной косой чертой. Без `.\`
PowerShell откажется запускать файл из текущей папки.

**Первый запуск идёт долго — 3–10 минут.** Gradle скачивает сам себя,
при необходимости JDK 25 и библиотеку Paper API. Последующие сборки
занимают секунды.

Ожидаемо в конце:

```
BUILD SUCCESSFUL in 4m 12s
```

## Шаг 3. Забрать jar

```powershell
dir paper\build\libs
```

Нужный файл — `AurumCompanion-0.1.0.jar`. Полный путь:

```
%USERPROFILE%\aurum\companion-plugin\paper\build\libs\AurumCompanion-0.1.0.jar
```

Открыть папку в проводнике:

```powershell
explorer paper\build\libs
```

## Шаг 4. Установить на сервер

1. Pterodactyl → нужный сервер → **Files** → папка `plugins`.
2. **Upload** → выбрать `AurumCompanion-0.1.0.jar`.
3. Перезапустить сервер.
4. При первом запуске плагин создаст `plugins/AurumCompanion/config.yml`.
   Откройте его, задайте порт и токен, снова перезапустите сервер.
5. Порт плагина добавьте как дополнительный allocation на адрес `10.0.0.2`
   (так же, как порт RCON — см. шаг 1 в `deploy/DEPLOY.md`).
6. В панели: карточка сервера → вкладка **Настройки** → блок
   **Companion-плагин** → адрес вида `http://10.0.0.2:8085` и тот же токен.

---

## Если не получилось

- **`gradlew.bat не распознан как имя командлета`** → вы не в той папке или
  забыли `.\`. Проверьте, что `dir` показывает файл `gradlew.bat`.

- **`Cannot find a Java installation … matching {languageVersion=25}`** →
  Gradle не нашёл JDK 25 и не смог его скачать (обычно нет интернета или
  мешает корпоративный прокси). Поставьте JDK 25 вручную и повторите:

  ```powershell
  winget install Microsoft.OpenJDK.25
  ```

  Если этого пакета нет — возьмите сборку Temurin 25 с
  [adoptium.net](https://adoptium.net/) и при установке отметьте
  «Set JAVA_HOME variable». После установки перезапустите PowerShell.

- **`Could not GET 'https://repo.papermc.io/…'`** → нет доступа к
  репозиторию Paper. Проверьте интернет; если сеть за прокси, Gradle нужно
  о нём сказать — создайте файл `gradle.properties` рядом с `gradlew.bat`:

  ```properties
  systemProp.https.proxyHost=адрес.прокси
  systemProp.https.proxyPort=8080
  ```

- **`error: invalid source release: 25`** → сборка всё-таки взяла старый
  JDK. Проверьте, какие Java видит Gradle:

  ```powershell
  .\gradlew.bat -q javaToolchains
  ```

  В списке должна быть запись с версией 25.

- **Сборка падает на антивирусе или «файл занят»** → Windows Defender иногда
  держит файлы в `build\`. Остановите фоновые процессы Gradle и повторите:

  ```powershell
  .\gradlew.bat --stop
  .\gradlew.bat :paper:jar
  ```

---

## Полезное

Прогнать тесты логики (не требуют JDK 25 и интернета к Paper):

```powershell
.\gradlew.bat :core:test
```

Собрать заново с нуля, если что-то подозрительно:

```powershell
.\gradlew.bat clean :paper:jar
```

На Linux и macOS всё то же самое, только вместо `.\gradlew.bat` — `./gradlew`.
