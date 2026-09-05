package com.example.signage_front.data

/**
 * Full player orchestration state (see ORCHESTRATION_PLAN §4 / §6).
 *
 * @property base the content the player would show without any triggers
 * @property triggerQueue active triggers ordered high → low, each awaiting/interleaving
 * @property pendingInterrupt a trigger waiting for the right moment to interrupt
 */
data class OrchestratorState(
    val base: ResolvedContent,
    val triggerQueue: List<TriggerItem>,
    val pendingInterrupt: TriggerItem?
)

/**
 * An active trigger waiting to play. `actionType` is "playlist" or "media";
 * `actionId` is the target playlist id (playlist) or ad id (media).
 */
data class TriggerItem(
    val triggerId: Long,
    val priority: String, // "high" | "medium" | "low"
    val actionType: String,
    val actionId: Long,
    val firedAt: Long // epoch millis — used to track cooldown
)
