package org.ChisaO_o.simpleSlots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import ovh.aurumgg.core.api.ManagedAccountStatus;

class SlotMachineAccountTest {
    @Test
    void restoredLegacyMachineKeepsItsOriginalAccount() {
        SlotMachine machine = new SlotMachine("spawn");
        machine.restoreAccountReference(null);

        assertEquals("spawn", machine.accountReference());
    }

    @Test
    void recreatedMachineGetsASeparateBoundedAccount() {
        SlotMachine first = SlotMachine.create("spawn");
        SlotMachine second = SlotMachine.create("spawn");

        assertNotEquals("spawn", first.accountReference());
        assertNotEquals(first.accountReference(), second.accountReference());
        assertTrue(first.accountReference().startsWith("spawn~"));
        assertTrue(first.accountReference().length() <= 128);
    }

    @Test
    void longMachineNameStillProducesAValidAccountReference() {
        SlotMachine machine = SlotMachine.create("x".repeat(180));

        assertEquals(128, machine.accountReference().length());
    }

    @Test
    void onlyRetiredAccountsRequireANewGeneration() {
        assertTrue(SlotEconomyService.isRetired(ManagedAccountStatus.CLOSING));
        assertTrue(SlotEconomyService.isRetired(ManagedAccountStatus.CLOSED));
        assertFalse(SlotEconomyService.isRetired(ManagedAccountStatus.ACTIVE));
        assertFalse(SlotEconomyService.isRetired(ManagedAccountStatus.FROZEN));
    }
}
