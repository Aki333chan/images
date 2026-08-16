package ovh.aurumgg.companion.core.model;

import java.util.List;

/**
 * Права игрока в структурированном виде — то, что отдаёт LuckPerms через
 * свой Developer API.
 *
 * Собирается только из постоянных нод (data(), не transientData()):
 * временные ноды выдаются другими плагинами на время сессии, показывать их
 * как «права игрока» и давать по ним снимать — вводить в заблуждение.
 */
public record PermissionsInfo(
        String primaryGroup,
        List<String> groups,
        List<PermissionEntry> permissions) {

    /** Одна нода: право и знак (true — выдано, false — явный запрет). */
    public record PermissionEntry(String permission, boolean value) {}
}
