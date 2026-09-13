package ovh.aurumgg.core.api;

import java.util.List;
import java.util.Objects;

public record ManagedAccountPage(List<ManagedAccount> accounts, int offset, int limit, long total) {
    public ManagedAccountPage {
        accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts"));
        if (offset < 0 || limit < 1 || total < 0) throw new IllegalArgumentException("Invalid account page");
    }
}
