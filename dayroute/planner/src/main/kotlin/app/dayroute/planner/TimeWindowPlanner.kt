package app.dayroute.planner

/**
 * Orders a day's stops around their time windows.
 *
 * Each candidate order is scored by simulating the drive:
 *  - every minute driving costs 1
 *  - every minute waiting for a window to open costs a little ([waitWeight])
 *  - every minute late for a relaxed window costs [flexibleLateWeight]
 *  - every minute late for a strict window costs [strictLateWeight] (effectively forbidden)
 *
 * Up to [exactSearchLimit] stops every order is searched (with pruning).
 * Beyond that, stops are inserted one by one at their cheapest position,
 * most urgent first, then the order is polished by moving single stops
 * and reversing segments until nothing improves.
 */
class TimeWindowPlanner(
    private val estimator: TravelTimeEstimator,
    private val exactSearchLimit: Int = 8,
    private val waitWeight: Double = 0.1,
    private val flexibleLateWeight: Double = 5.0,
    private val strictLateWeight: Double = 10_000.0,
) : RoutePlanner {

    override fun plan(request: PlanRequest): DayPlan {
        if (request.stops.isEmpty()) return DayPlan(emptyList())
        val ctx = Context(request)
        val order = if (request.stops.size <= exactSearchLimit) ctx.exactOrder() else ctx.heuristicOrder()
        return DayPlan(ctx.simulate(order))
    }

    private inner class Context(private val request: PlanRequest) {
        private val stops = request.stops
        private val n = stops.size
        // drive[i][j]: index 0 is the start point, i+1 is stops[i]
        private val drive: Array<IntArray> = run {
            val points = listOf(request.start) + stops.map { it.location }
            Array(points.size) { i -> IntArray(points.size) { j -> if (i == j) 0 else estimator.minutesBetween(points[i], points[j]) } }
        }

        private inner class Step(val arrival: Int, val drive: Int, val wait: Int, val late: Int, val cost: Double, val departure: Int)

        private fun step(fromNode: Int, clock: Int, stopIndex: Int): Step {
            val stop = stops[stopIndex]
            val d = drive[fromNode][stopIndex + 1]
            val arrival = clock + d
            val window = stop.window
            val opens = window?.earliest?.minutes
            val wait = if (opens != null && arrival < opens) opens - arrival else 0
            val start = arrival + wait
            val closes = window?.latest?.minutes
            val late = if (closes != null && start > closes) start - closes else 0
            val lateWeight = if (window?.strict == true) strictLateWeight else flexibleLateWeight
            val cost = d + wait * waitWeight + late * lateWeight
            return Step(arrival, d, wait, late, cost, start + stop.dwellMinutes)
        }

        fun cost(order: List<Int>): Double {
            var node = 0
            var clock = request.startTime.minutes
            var total = 0.0
            for (i in order) {
                val s = step(node, clock, i)
                total += s.cost
                clock = s.departure
                node = i + 1
            }
            return total
        }

        fun simulate(order: List<Int>): List<PlannedStop> {
            var node = 0
            var clock = request.startTime.minutes
            return order.map { i ->
                val s = step(node, clock, i)
                clock = s.departure
                node = i + 1
                PlannedStop(stops[i], ClockTime(s.arrival), s.drive, s.wait, s.late)
            }
        }

        /** Depth-first search over every order, cutting branches that are already worse than the best. */
        fun exactOrder(): List<Int> {
            var best = Double.MAX_VALUE
            var bestOrder: List<Int> = stops.indices.toList()
            val used = BooleanArray(n)
            val path = IntArray(n)

            fun search(depth: Int, node: Int, clock: Int, costSoFar: Double) {
                if (costSoFar >= best) return
                if (depth == n) {
                    best = costSoFar
                    bestOrder = path.toList()
                    return
                }
                for (i in 0 until n) {
                    if (used[i]) continue
                    val s = step(node, clock, i)
                    used[i] = true
                    path[depth] = i
                    search(depth + 1, i + 1, s.departure, costSoFar + s.cost)
                    used[i] = false
                }
            }
            search(0, 0, request.startTime.minutes, 0.0)
            return bestOrder
        }

        fun heuristicOrder(): List<Int> {
            // Most urgent first: strict deadlines, then relaxed deadlines, then the rest.
            val insertionOrder = stops.indices.sortedWith(
                compareBy<Int>(
                    { if (stops[it].window?.strict == true && stops[it].window?.latest != null) 0 else if (stops[it].window?.latest != null) 1 else 2 },
                    { stops[it].window?.latest?.minutes ?: Int.MAX_VALUE },
                )
            )
            val route = mutableListOf<Int>()
            for (i in insertionOrder) {
                var bestPos = 0
                var bestCost = Double.MAX_VALUE
                for (pos in 0..route.size) {
                    route.add(pos, i)
                    val c = cost(route)
                    route.removeAt(pos)
                    if (c < bestCost) { bestCost = c; bestPos = pos }
                }
                route.add(bestPos, i)
            }
            return improve(route)
        }

        /** Local search: relocate single stops and reverse segments while it helps. */
        private fun improve(start: List<Int>): List<Int> {
            var route = start
            var current = cost(route)
            var improved = true
            var rounds = 0
            while (improved && rounds++ < 50) {
                improved = false
                for (from in route.indices) for (to in route.indices) {
                    if (from == to) continue
                    val candidate = route.toMutableList().apply { add(to, removeAt(from)) }
                    val c = cost(candidate)
                    if (c < current - 1e-9) { route = candidate; current = c; improved = true }
                }
                for (i in 0 until route.size - 1) for (j in i + 1 until route.size) {
                    val candidate = route.subList(0, i) + route.subList(i, j + 1).reversed() + route.subList(j + 1, route.size)
                    val c = cost(candidate)
                    if (c < current - 1e-9) { route = candidate; current = c; improved = true }
                }
            }
            return route
        }
    }
}
