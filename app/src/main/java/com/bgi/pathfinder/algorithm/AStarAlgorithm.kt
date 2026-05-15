// app/src/main/java/com/bgi/pathfinder/algorithm/AStarAlgorithm.kt
package com.bgi.pathfinder.algorithm

import com.bgi.pathfinder.models.*
import java.util.PriorityQueue

/**
 * A* Pathfinding with support for:
 *  - Standard shortest distance
 *  - Weighted cost (traffic × road quality)
 *  - Alternative route finding (penalizes edges from a previous route)
 */
class AStarAlgorithm(private val graph: Graph) {

    private data class AStarNode(
        val nodeId: String,
        val gCost: Double,
        val fCost: Double
    ) : Comparable<AStarNode> {
        override fun compareTo(other: AStarNode): Int = fCost.compareTo(other.fCost)
    }

    /**
     * Find optimal path from [startId] to [goalId].
     * @param penalizedEdges Set of "from->to" edge keys that get a 3x penalty (for alternative routes)
     */
    fun findPath(
        startId: String,
        goalId: String,
        mode: PathMode,
        penalizedEdges: Set<String> = emptySet()
    ): PathResult {
        val startTime = System.currentTimeMillis()
        val startNode = graph.getNode(startId)
        val goalNode = graph.getNode(goalId)

        if (startNode == null || goalNode == null) {
            android.util.Log.e("AStar", "Failed to start A*: Start or Goal node is null ($startId -> $goalId)")
            return emptyResult(startTime)
        }

        android.util.Log.d("AStar", "Starting A* search from ${startNode.id} to ${goalNode.id} using mode: $mode")

        val openSet = PriorityQueue<AStarNode>()
        val closedSet = mutableSetOf<String>()
        val gCosts = mutableMapOf<String, Double>()
        val parentMap = mutableMapOf<String, String?>()
        var nodesExplored = 0

        openSet.add(AStarNode(startId, 0.0, heuristic(startNode, goalNode)))
        gCosts[startId] = 0.0
        parentMap[startId] = null

        while (openSet.isNotEmpty()) {
            val current = openSet.poll()
            if (current.nodeId in closedSet) continue
            nodesExplored++

            if (current.nodeId == goalId) {
                android.util.Log.d("AStar", "✅ Path Found! Nodes explored: $nodesExplored")
                val path = reconstructPath(parentMap, goalId)
                val (totalDist, totalTime) = calculateDistanceAndTime(path)
                return PathResult(
                    path = path,
                    totalCost = current.gCost,
                    totalDistanceMeters = totalDist,
                    estimatedTimeMinutes = totalTime,
                    nodesExplored = nodesExplored,
                    computeTimeMs = System.currentTimeMillis() - startTime
                )
            }

            closedSet.add(current.nodeId)

            for (edge in graph.getEdges(current.nodeId)) {
                if (edge.to in closedSet) continue

                var edgeCost = calculateEdgeCost(edge, mode)

                // Apply penalty for alternative route calculation
                val edgeKey = "${edge.from}->${edge.to}"
                if (edgeKey in penalizedEdges) {
                    edgeCost *= 3.0
                }

                val tentativeG = current.gCost + edgeCost
                if (tentativeG < gCosts.getOrDefault(edge.to, Double.MAX_VALUE)) {
                    gCosts[edge.to] = tentativeG
                    parentMap[edge.to] = current.nodeId
                    val neighbor = graph.getNode(edge.to)
                    val h = if (neighbor != null) heuristic(neighbor, goalNode) else 0.0
                    openSet.add(AStarNode(edge.to, tentativeG, tentativeG + h))
                }
            }
        }

        android.util.Log.w("AStar", "❌ A* failed. Explored $nodesExplored nodes but could not reach ${goalNode.id}")
        return emptyResult(startTime, nodesExplored)
    }

    /** Find both standard and weighted routes. If identical, weighted uses penalty. */
    fun findMultipleRoutes(startId: String, goalId: String): List<PathResult> {
        val results = mutableListOf<PathResult>()

        // Route 1: Shortest distance
        val shortest = findPath(startId, goalId, PathMode.STANDARD)
        if (!shortest.isFound) return results
        results.add(shortest)

        // Route 2: Weighted (traffic + road quality)
        val weighted = findPath(startId, goalId, PathMode.WEIGHTED)
        if (weighted.isFound) {
            // If same path as shortest, force alternative via penalty
            if (weighted.path == shortest.path) {
                val penalized = buildPenaltySet(shortest.path)
                val alt = findPath(startId, goalId, PathMode.WEIGHTED, penalized)
                if (alt.isFound) results.add(alt)
            } else {
                results.add(weighted)
            }
        }

        return results
    }

    /** Build set of edge keys from a path for penalization */
    private fun buildPenaltySet(path: List<String>): Set<String> {
        val set = mutableSetOf<String>()
        for (i in 0 until path.size - 1) {
            set.add("${path[i]}->${path[i + 1]}")
        }
        return set
    }

    private fun calculateEdgeCost(edge: Edge, mode: PathMode): Double {
        return when (mode) {
            PathMode.STANDARD -> edge.distance
            PathMode.WEIGHTED -> edge.distance * edge.trafficMultiplier * edge.roadQualityMultiplier
        }
    }

    /** Calculate actual distance (m) and estimated time (min) along a path */
    private fun calculateDistanceAndTime(path: List<String>): Pair<Double, Double> {
        var totalDist = 0.0
        var totalTimeSec = 0.0
        for (i in 0 until path.size - 1) {
            val edges = graph.getEdges(path[i])
            val edge = edges.firstOrNull { it.to == path[i + 1] }
            if (edge != null) {
                totalDist += edge.distance
                totalTimeSec += (edge.distance / 1000.0) / edge.speedKmh * 3600.0
            }
        }
        return Pair(totalDist, totalTimeSec / 60.0)
    }

    private fun heuristic(from: Node, to: Node): Double {
        return Graph.haversineDistance(from.lat, from.lng, to.lat, to.lng)
    }

    private fun reconstructPath(parentMap: Map<String, String?>, goalId: String): List<String> {
        val path = mutableListOf<String>()
        var current: String? = goalId
        while (current != null) { path.add(current); current = parentMap[current] }
        return path.reversed()
    }

    private fun emptyResult(startTime: Long, explored: Int = 0) = PathResult(
        emptyList(), Double.MAX_VALUE, 0.0, 0.0, explored,
        System.currentTimeMillis() - startTime
    )
}
