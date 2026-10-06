package ovh.aurumgg.companion.core.model;

/** Owner-only statistics. Null counters mean unavailable, never zero. */
public record SitePlayerInfo(boolean online, Long playTimeTicks, Long deaths, Long playerKills) {}
