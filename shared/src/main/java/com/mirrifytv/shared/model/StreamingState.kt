package com.mirrifytv.shared.model

sealed class StreamingState {
    object Idle : StreamingState()
    object Connecting : StreamingState()
    data class Connected(val sessionId: String) : StreamingState()
    object Streaming : StreamingState()
    data class Error(val message: String) : StreamingState()
    object Disconnected : StreamingState()
}
