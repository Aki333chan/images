package ovh.aurumgg.companion.core.model;

import java.util.List;

/**
 * Что панель знает о тюрьмах EssentialsX на этом сервере.
 *
 * ЗАЧЕМ ЭТО ВООБЩЕ ЧИТАТЬ, А НЕ СПРАШИВАТЬ ЧЕРЕЗ RCON. Список тюрем панели
 * нужен выпадающим списком: их бывает и сотня, и набирать имя руками —
 * значит регулярно промахиваться и получать «Jail does not exist» вместо
 * действия. Разбирать ответ команды {@code /jails} панель могла бы, но это
 * текст на языке сервера, зависящий от версии; здесь то же самое берётся
 * структурой прямо из плагина.
 *
 * @param available стоит ли EssentialsX; false — остальные поля пусты
 * @param jails     имена тюрем в том порядке, в каком их отдаёт плагин
 * @param jailed    кто сидит прямо сейчас
 */
public record JailsInfo(boolean available, List<String> jails, List<JailedPlayer> jailed) {

    public static JailsInfo unavailable() {
        return new JailsInfo(false, List.of(), List.of());
    }
}
