package org.ChisaO_o.simpleSlots;

import org.bukkit.Location;

import java.util.UUID;

final class SlotMachine {
    enum PayoutMode { DEFAULT, MACHINE, TREASURY, LEGACY }

    final String id;
    String accountReference;
    double bet = 1.0;
    int pool = 0;
    boolean isSpinning = false;
    boolean paymentPending = false;
    boolean closing = false;
    String founderUuid = "";
    PayoutMode payoutMode = PayoutMode.DEFAULT;
    String payoutTreasuryId = "";
    Location shelfLoc;
    Location buttonLoc;
    Location hopperLoc;
    UUID hologramUuid;

    SlotMachine(String id) {
        this.id = id;
        this.accountReference = id;
    }

    static SlotMachine create(String id) {
        SlotMachine machine = new SlotMachine(id);
        machine.rotateAccountReference();
        return machine;
    }

    String accountReference() {
        return accountReference == null || accountReference.isBlank() ? id : accountReference;
    }

    void restoreAccountReference(String stored) {
        if (stored == null || stored.isBlank() || stored.length() > 128
                || stored.chars().anyMatch(Character::isISOControl)) {
            accountReference = id;
            return;
        }
        accountReference = stored;
    }

    void rotateAccountReference() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        int prefixLength = Math.min(id.length(), 128 - suffix.length() - 1);
        accountReference = id.substring(0, prefixLength) + "~" + suffix;
    }
}
