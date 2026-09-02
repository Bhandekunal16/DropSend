package com.example.domain.model

object TransferStateMachine {

    private val terminalStates = setOf(
        SessionState.COMPLETED,
        SessionState.CANCELLED,
        SessionState.FAILED,
        SessionState.EXPIRED
    )

    private val legalTransitions: Map<SessionState, Set<SessionState>> = mapOf(
        SessionState.IDLE to setOf(
            SessionState.DISCOVERING,
            SessionState.CONNECTING,
            SessionState.IDLE
        ),
        SessionState.DISCOVERING to setOf(
            SessionState.IDLE,
            SessionState.DEVICE_FOUND,
            SessionState.CONNECTING,
            SessionState.AUTHENTICATING,
            SessionState.WAITING_FOR_ACCEPT,
            SessionState.FAILED,
            SessionState.CANCELLED,
            SessionState.DISCOVERING
        ),
        SessionState.DEVICE_FOUND to setOf(
            SessionState.CONNECTING,
            SessionState.DISCOVERING,
            SessionState.IDLE,
            SessionState.CANCELLED,
            SessionState.FAILED
        ),
        SessionState.CONNECTING to setOf(
            SessionState.AUTHENTICATING,
            SessionState.FAILED,
            SessionState.CANCELLED,
            SessionState.IDLE,
            SessionState.DISCONNECTED
        ),
        SessionState.AUTHENTICATING to setOf(
            SessionState.WAITING_FOR_ACCEPT,
            SessionState.TRANSFERRING,
            SessionState.FAILED,
            SessionState.CANCELLED,
            SessionState.DISCONNECTED,
            SessionState.IDLE
        ),
        SessionState.WAITING_FOR_ACCEPT to setOf(
            SessionState.TRANSFERRING,
            SessionState.FAILED,
            SessionState.CANCELLED,
            SessionState.DISCONNECTED,
            SessionState.IDLE
        ),
        SessionState.TRANSFERRING to setOf(
            SessionState.VERIFYING,
            SessionState.COMPLETED,
            SessionState.FAILED,
            SessionState.CANCELLED,
            SessionState.DISCONNECTED
        ),
        SessionState.VERIFYING to setOf(
            SessionState.COMPLETED,
            SessionState.TRANSFERRING, // Next file in multi-file batch
            SessionState.FAILED,
            SessionState.CANCELLED
        ),
        SessionState.COMPLETED to setOf(
            SessionState.IDLE
        ),
        SessionState.FAILED to setOf(
            SessionState.IDLE
        ),
        SessionState.CANCELLED to setOf(
            SessionState.IDLE
        ),
        SessionState.EXPIRED to setOf(
            SessionState.IDLE
        ),
        SessionState.DISCONNECTED to setOf(
            SessionState.IDLE,
            SessionState.CONNECTING,
            SessionState.FAILED
        )
    )

    fun isLegalTransition(from: SessionState, to: SessionState): Boolean {
        if (from == to) return true
        val allowed = legalTransitions[from] ?: emptySet()
        return allowed.contains(to)
    }

    fun isTerminal(state: SessionState): Boolean {
        return terminalStates.contains(state)
    }

    fun canCancel(state: SessionState): Boolean {
        return !isTerminal(state) && state != SessionState.IDLE
    }
}

