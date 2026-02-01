package com.netninja.openclaw

import com.google.gson.Gson

private val gson = Gson()

data class OpenClawMessage(
  val type: String = "",
  val nodeId: String = "",
  val capabilities: List<String>? = emptyList(),
  val payload: String? = null
) {
  companion object {
    fun fromJson(json: String): OpenClawMessage? =
      runCatching { gson.fromJson(json, OpenClawMessage::class.java) }.getOrNull()

    fun registered(nodeId: String): OpenClawMessage =
      OpenClawMessage(type = "REGISTERED", nodeId = nodeId)
  }

  fun toJson(): String = gson.toJson(this)
}
