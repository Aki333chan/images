package ovh.aurumgg.companion.core.model;

import java.util.List;

public record ManagedAccountPageInfo(List<ManagedAccountInfo> accounts, int offset, int limit, long total) {}
