package com.mthkr.marstek

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RpcRequest(
    val id: Int,
    val method: String,
    val params: RpcParams
)

@Serializable
data class RpcParams(
    val id: Int = 0
)

@Serializable
data class RpcResponse(
    val id: Int,
    val src: String? = null,
    val result: JsonElement? = null,
    val error: JsonElement? = null
)

@Serializable
data class ESGetStatusResult(
    val bat_soc: Int? = null,
    val ongrid_power: Int? = null,
    val offgrid_power: Int? = null,
    val bat_power: Int? = null,
    val bat_cap: Int? = null
)

@Serializable
data class BatGetStatusResult(
    val soc: Int? = null,
    val bat_temp: Double? = null,
    val bat_capacity: Int? = null,
    val rated_capacity: Int? = null
)