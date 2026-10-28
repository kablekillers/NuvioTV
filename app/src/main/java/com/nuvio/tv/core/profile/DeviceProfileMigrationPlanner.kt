package com.nuvio.tv.core.profile

/**
 * Device-only profile migration must never overwrite or delete existing
 * shared-account cloud profiles. This planner identifies collisions before
 * any migration writes are attempted.
 */
internal object DeviceProfileMigrationPlanner {
    data class Plan(
        val preservedCloudIds: List<Int>,
        val reservedLocalIds: List<Int>,
        val requiresLocalIdRemapping: Boolean
    )

    fun inspect(
        existingCloudProfileIds: List<Int>,
        existingDeviceProfileIds: List<Int>
    ): Plan {
        require(existingCloudProfileIds.all { it > 0 })
        require(existingDeviceProfileIds.all { it > 0 })
        val cloud = existingCloudProfileIds.distinct().sorted()
        val local = existingDeviceProfileIds.distinct().sorted()
        val collisions = local.any { it != 1 && it in cloud }
        return Plan(
            preservedCloudIds = cloud,
            reservedLocalIds = local,
            requiresLocalIdRemapping = collisions
        )
    }
}
