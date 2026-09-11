package org.ChisaO_o.simpleSlots;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NativeEconomyRegressionTest {
    @Test
    void productionCodeDoesNotWriteThroughVault() throws IOException {
        Path source = Path.of("src/main/java");
        String all = Files.walk(source).filter(path -> path.toString().endsWith(".java"))
                .map(path -> {
                    try { return Files.readString(path); }
                    catch (IOException error) { throw new IllegalStateException(error); }
                }).reduce("", String::concat);

        assertFalse(all.contains("withdrawPlayer("));
        assertFalse(all.contains("depositPlayer("));
        assertFalse(all.contains("net.milkbowl.vault"));
    }
}
