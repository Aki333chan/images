package dev.addons.npc.service;

import dev.addons.npc.model.DialogueMode;
import dev.addons.npc.model.NpcDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.entity.Player;

public final class DialogueService {
    private final MessageService messages;
    private final Map<UUID, Map<String, Integer>> positions = new HashMap<>();

    public DialogueService(MessageService messages) {
        this.messages = messages;
    }

    public void send(Player player, NpcDefinition npc, Map<String, ?> placeholders) {
        List<String> lines = npc.messages();
        if (lines.isEmpty()) {
            return;
        }
        if (npc.dialogueMode() == DialogueMode.ALL) {
            lines.forEach(line -> messages.raw(player, line, placeholders));
            return;
        }
        int index;
        if (npc.dialogueMode() == DialogueMode.RANDOM) {
            index = ThreadLocalRandom.current().nextInt(lines.size());
        } else {
            Map<String, Integer> playerPositions = positions.get(player.getUniqueId());
            if (playerPositions == null) {
                playerPositions = new HashMap<>();
                positions.put(player.getUniqueId(), playerPositions);
            }
            index = playerPositions.getOrDefault(npc.id(), 0) % lines.size();
            playerPositions.put(npc.id(), (index + 1) % lines.size());
        }
        messages.raw(player, lines.get(index), placeholders);
    }

    public void clear() {
        positions.clear();
    }

    public void clear(UUID playerUuid) {
        positions.remove(playerUuid);
    }
}
