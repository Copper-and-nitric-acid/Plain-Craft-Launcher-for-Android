package io.pcla.core.download

/**
 * 换源链。
 *
 * PCL 的 `RangeNotSupportedException` 是同一思路：一个源不行就换下一个，而不是死磕。
 * 排序规则：失败次数少的优先；失败次数相同保持声明顺序（玩家写的顺序就是他的偏好）。
 */
class MirrorChain(urls: List<String>) {

    private val order: List<String> = urls.distinct().filter { it.isNotBlank() }
    private val failures = HashMap<String, Int>()

    init {
        require(order.isNotEmpty()) { "至少给一个下载地址" }
    }

    val primary: String get() = order.first()

    /** 按「成功率高、声明靠前」排好的候选列表。 */
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
