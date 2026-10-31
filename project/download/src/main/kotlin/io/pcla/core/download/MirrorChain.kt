package io.pcla.core.download

/**
 * Mirror failover chain.
 *
 * PCL's `RangeNotSupportedException` is the same idea: move to the next source instead of
 * hammering one. Ordering: fewer failures first; ties keep the declaration order.
 */
class MirrorChain(urls: List<String>) {

    private val order: List<String> = urls.distinct().filter { it.isNotBlank() }
    private val failures = HashMap<String, Int>()

    init {
        require(order.isNotEmpty()) { "至少给一个下载地址" }
    }

    val primary: String get() = order.first()

    /** Candidates ordered by "higher success rate, earlier declaration". */
    @Synchronized
    fun candidates(): List<String> = order.sortedWith(
        compareBy<String> { failures[it] ?: 0 }.thenBy { order.indexOf(it) }
    )

    @Synchronized
    fun reportFailure(url: String) {
        failures[url] = (failures[url] ?: 0) + 1
    }

    @Synchronized
    fun reportSuccess(url: String) {
        failures.remove(url)
    }

    @Synchronized
    fun failureCount(url: String): Int = failures[url] ?: 0
}
