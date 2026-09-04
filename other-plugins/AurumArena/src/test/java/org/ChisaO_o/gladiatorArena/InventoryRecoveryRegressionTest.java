package org.ChisaO_o.gladiatorArena;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InventoryRecoveryRegressionTest {
    @Test void freshlySavedInventoryIsReadableBeforeYamlReload() {
        // ItemStack construction requires a running Paper registry on 26.2; null slots
        // are sufficient here because this regression concerns the container type.
        ItemStack[] original = new ItemStack[3];
        YamlConfiguration journal = new YamlConfiguration();

        journal.set("inventory", RecoveryStore.serializableItems(original));

        assertInstanceOf(List.class, journal.get("inventory"));
        ItemStack[] restored = RecoveryStore.decodeItems(journal.get("inventory"), original.length);
        assertEquals(3, restored.length);
        assertNull(restored[0]);
        assertNull(restored[1]);
        assertNull(restored[2]);
    }

    @Test void affectedInMemoryArraySnapshotsRemainRecoverable() {
        ItemStack[] legacySnapshot = new ItemStack[3];

        ItemStack[] restored = RecoveryStore.decodeItems(legacySnapshot, legacySnapshot.length);

        assertEquals(3, restored.length);
        assertNull(restored[0]);
        assertNull(restored[1]);
        assertNull(restored[2]);
    }

    @Test void decodedInventoryIsPaddedOrClippedToCurrentInventorySize() {
        ItemStack[] padded = RecoveryStore.decodeItems(java.util.Arrays.asList(null, null), 4);
        assertEquals(4, padded.length);
        assertNull(padded[0]);
        assertNull(padded[3]);

        ItemStack[] clipped = RecoveryStore.decodeItems(java.util.Arrays.asList(null, null), 1);
        assertEquals(1, clipped.length);
        assertNull(clipped[0]);
    }

    @Test void liveSnapshotUsesADetachedInventoryArray() {
        ItemStack[] inventory = new ItemStack[36];

        ItemStack[] snapshot = RecoveryStore.cloneItems(inventory);

        assertNotSame(inventory, snapshot);
        assertEquals(inventory.length, snapshot.length);
        inventory[0] = null;
        assertNull(snapshot[0]);
    }
}
