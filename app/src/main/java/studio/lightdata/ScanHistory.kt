package studio.lightdata

/** Opt-in diagnostics retain a contiguous prefix, never a misleading moving window. */
internal class ScanHistory<T>(private val byteLimit: Long) {
    init { require(byteLimit >= 0) }
    data class Snapshot<T>(val shots: List<T>, val attempts: Int, val droppedRejected: Int,
                          val droppedAccepted: Int, val bytes: Long) {
        val complete get() = droppedRejected + droppedAccepted == 0
    }
    private val shots = mutableListOf<T>()
    private var bytes = 0L
    private var attempts = 0
    private var droppedRejected = 0
    private var droppedAccepted = 0
    val truncated get() = droppedRejected + droppedAccepted > 0
    fun add(shot: T, size: Long, accepted: Boolean): Boolean {
        require(size >= 0)
        attempts++
        if (truncated || size > byteLimit - bytes) {
            if (accepted) droppedAccepted++ else droppedRejected++
            return false
        }
        shots.add(shot)
        bytes += size
        return true
    }
    fun snapshot() = Snapshot(shots.toList(), attempts, droppedRejected, droppedAccepted, bytes)
    fun isEmpty() = shots.isEmpty()
    fun clear() {
        shots.clear(); bytes = 0; attempts = 0; droppedRejected = 0; droppedAccepted = 0
    }
}
