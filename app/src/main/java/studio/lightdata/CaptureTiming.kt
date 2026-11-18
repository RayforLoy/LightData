package studio.lightdata

/** Camera2 timestamp is first-row exposure START, not the frame's central instant. */
internal fun exposurePoseTimestamp(startNs: Long, exposureNs: Long, skewNs: Long?,
                                   centerRow: Double = .5): Long {
    if (startNs <= 0) return 0L // Unknown camera/IMU clock relationship.
    require(centerRow.isFinite() && centerRow in 0.0..1.0)
    val exposureOffset = exposureNs.coerceAtLeast(0) / 2
    val rowOffset = ((skewNs ?: 0L).coerceAtLeast(0) * centerRow).toLong()
    val available = Long.MAX_VALUE - startNs
    val offset = exposureOffset.coerceAtMost(available)
    return startNs + offset + rowOffset.coerceAtMost(available - offset)
}
