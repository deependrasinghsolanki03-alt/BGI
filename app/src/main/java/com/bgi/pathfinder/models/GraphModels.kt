// app/src/main/java/com/bgi/pathfinder/models/GraphModels.kt
package com.bgi.pathfinder.models

import kotlin.math.*

/** Nominatim search result */
data class SearchResult(
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val type: String = ""
)

/** Graph node (intersection on road network) */
data class Node(
    val id: String,
    val lat: Double,
    val lng: Double,
    val name: String = ""
)

/** Graph edge (road segment) */
data class Edge(
    val from: String,
    val to: String,
    val distance: Double,
    val trafficMultiplier: Double = 1.0,
    val roadQualityMultiplier: Double = 1.0,
    val isOneWay: Boolean = false,
    val roadType: String = "",
    val speedKmh: Double = 25.0
)

/** A* result */
data class PathResult(
    val path: List<String>,
    val totalCost: Double,
    val totalDistanceMeters: Double,
    val estimatedTimeMinutes: Double,
    val nodesExplored: Int,
    val computeTimeMs: Long
) {
    val isFound: Boolean get() = path.isNotEmpty()
}

enum class PathMode { STANDARD, WEIGHTED }

/** Graph data structure */
class Graph {
    private val nodes = mutableMapOf<String, Node>()
    private val adjacencyList = mutableMapOf<String, MutableList<Edge>>()

    fun addNode(node: Node) {
        nodes[node.id] = node
        if (!adjacencyList.containsKey(node.id)) {
            adjacencyList[node.id] = mutableListOf()
        }
    }

    fun addEdge(edge: Edge) {
        adjacencyList.getOrPut(edge.from) { mutableListOf() }.add(edge)
        if (!edge.isOneWay) {
            adjacencyList.getOrPut(edge.to) { mutableListOf() }.add(
                edge.copy(from = edge.to, to = edge.from)
            )
        }
    }

    fun getNode(id: String): Node? = nodes[id]
    fun getEdges(nodeId: String): List<Edge> = adjacencyList[nodeId] ?: emptyList()
    fun getAllNodes(): Collection<Node> = nodes.values
    fun nodeCount(): Int = nodes.size
    fun edgeCount(): Int = adjacencyList.values.sumOf { it.size }
    fun isEmpty(): Boolean = nodes.isEmpty()

    /** Find the nearest graph node to a coordinate (within maxRadius meters) */
    fun findNearestNode(lat: Double, lng: Double, maxRadius: Double = 5000.0): Node? {
        var nearest: Node? = null
        var minDist = Double.MAX_VALUE
        for (node in nodes.values) {
            val dist = haversineDistance(lat, lng, node.lat, node.lng)
            if (dist < minDist) { minDist = dist; nearest = node }
        }
        return if (minDist <= maxRadius) nearest else null
    }

    companion object {
        fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val R = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLng / 2).pow(2)
            return R * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
