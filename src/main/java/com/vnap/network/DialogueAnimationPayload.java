package com.vnap.network;

import java.util.UUID;

/** Local description of one spoken line (no longer a network packet). */
public record DialogueAnimationPayload(UUID entityId, String groupId, int variantIndex, int durationTicks) {
}
