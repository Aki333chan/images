package ovh.aurumgg.companion.core.model;

/**
 * Адрес, с которого заходил игрок, — для панели.
 *
 * Приходит из AurumAuth. Сам companion эти данные не собирает и никак не
 * использует: он только передаёт их дальше.
 *
 * @param ip        адрес; IPv6 тоже
 * @param firstSeen когда с него зашли впервые, epoch ms
 * @param lastSeen  когда в последний раз, epoch ms
 */
public record IpRecordInfo(String ip, long firstSeen, long lastSeen) {}
