package org.ChisaO_o.simpleSlots;

import org.bukkit.Location;

import java.util.UUID;

final class SlotMachine {
    final String id;
    double bet = 1.0;
    int pool = 0;
    boolean isSpinning = false;
    Location shelfLoc;
    Location buttonLoc;
    Location hopperLoc;
    UUID hologramUuid;

    SlotMachine(String id) {
        this.id = id;
    }
}
