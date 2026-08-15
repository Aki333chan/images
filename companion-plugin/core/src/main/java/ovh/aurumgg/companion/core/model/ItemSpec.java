package ovh.aurumgg.companion.core.model;

/**
 * Что положить в слот. null-объект (см. {@link #clear()}) означает «очистить слот».
 *
 * @param id    идентификатор материала; null — очистить слот
 * @param count размер стака
 */
public record ItemSpec(String id, int count) {

    public static ItemSpec clear() {
        return new ItemSpec(null, 0);
    }

    public boolean isClear() {
        return id == null || id.isBlank() || count <= 0;
    }
}
