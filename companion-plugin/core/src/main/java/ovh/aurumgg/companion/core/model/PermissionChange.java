package ovh.aurumgg.companion.core.model;

/**
 * Запрошенное изменение прав.
 *
 * Намеренно описывает ОДНО действие: панель шлёт «добавить группу vip» или
 * «снять право essentials.fly», а не пачку. Так каждое действие попадает в
 * журнал аудита отдельной записью с понятной формулировкой.
 */
public record PermissionChange(Kind kind, String key, boolean value, boolean remove) {

    public enum Kind {
        /** Группа: нода наследования (InheritanceNode в терминах LuckPerms). */
        GROUP,
        /** Обычное право вида essentials.fly. */
        PERMISSION
    }

    /** Результат применения — с причиной отказа, пригодной для показа человеку. */
    public record Result(boolean applied, String reason) {
        public static Result ok() {
            return new Result(true, null);
        }

        public static Result rejected(String reason) {
            return new Result(false, reason);
        }
    }
}
