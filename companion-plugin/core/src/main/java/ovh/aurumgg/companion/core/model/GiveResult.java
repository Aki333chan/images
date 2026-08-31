package ovh.aurumgg.companion.core.model;

/**
 * Итог по одной строке списка выдачи.
 *
 * Результат построчный, а не общий «получилось/не получилось», потому что
 * список выдают целиком и частичный успех тут — норма: два предмета легли,
 * третий не поместился, у четвёртого опечатка в идентификаторе. Общий ответ
 * заставил бы человека гадать, какая именно строка виновата.
 *
 * @param id        идентификатор ровно в том виде, в каком его прислала панель
 * @param requested сколько просили выдать
 * @param given     сколько реально легло в инвентарь (0 — не легло ничего)
 * @param error     null при полном успехе; иначе почему не легло или легло не всё
 */
public record GiveResult(String id, int requested, int given, String error) {

    public static GiveResult ok(String id, int count) {
        return new GiveResult(id, count, count, null);
    }

    public static GiveResult failed(String id, int requested, String error) {
        return new GiveResult(id, requested, 0, error);
    }
}
