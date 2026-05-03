package com.mirrifytv.shared.model

import org.json.JSONObject

data class ConnectionInfo(
    val host: String,
    val port: Int,
    val sessionId: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("host", host)
            put("port", port)
            put("sessionId", sessionId)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): ConnectionInfo {
            val obj = JSONObject(json)
            return ConnectionInfo(
                host = obj.getString("host"),
                port = obj.getInt("port"),
                sessionId = obj.getString("sessionId")
            )
        }
    }
}
