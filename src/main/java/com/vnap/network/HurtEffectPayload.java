package com.vnap.network;

import java.util.UUID;

/** Local description of a supplemental hurt sound (no longer a network packet). */
public record HurtEffectPayload(UUID entityId, String effectId) {
}
