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
        val requiresLocalIdRemapping: Boolean,
        val collidingSecondaryIds: List<Int>
    )

    /**
     * Produces a deterministic, non-mutating remap proposal. The caller must
     * migrate every local profile-scoped data store atomically before applying it.
     * Cloud IDs are never rewritten, and Main (1) always remains Main.
     */
    fun proposeLocalRemap(
        existingCloudProfileIds: List<Int>,
        existingDeviceProfileIds: List<Int>
    ): Map<Int, Int> {
        require(existingCloudProfileIds.size == existingCloudProfileIds.distinct().size) {
            "Duplicate cloud profile IDs must be repaired before remapping"
        }
        require(existingDeviceProfileIds.size == existingDeviceProfileIds.distinct().size) {
            "Duplicate device profile IDs must be repaired before remapping"
        }
        val plan = inspect(existingCloudProfileIds, existingDeviceProfileIds)
        require(1 in plan.preservedCloudIds && 1 in plan.reservedLocalIds) {
            "Main must exist in both cloud and device profiles"
        }
        val occupied = (plan.preservedCloudIds + plan.reservedLocalIds).toMutableSet()
        val result = linkedMapOf<Int, Int>()
        var candidate = 2
        for (id in plan.collidingSecondaryIds) {
            while (candidate in occupied) {
                check(candidate < Int.MAX_VALUE) { "No available local profile identifier" }
                candidate++
            }
            result[id] = candidate
            occupied.add(candidate)
        }
        return result
    }

    fun inspect(
        existingCloudProfileIds: List<Int>,
        existingDeviceProfileIds: List<Int>
    ): Plan {
        require(existingCloudProfileIds.all { it > 0 })
        require(existingDeviceProfileIds.all { it > 0 })
        val cloud = existingCloudProfileIds.distinct().sorted()
        val local = existingDeviceProfileIds.distinct().sorted()
        // Local and cloud secondary IDs must never be conflated, even when
        // the same numeric ID refers to unrelated people on different devices.
        val collisions = local.filter { it != 1 && it in cloud }
        return Plan(
            preservedCloudIds = cloud,
            reservedLocalIds = local,
            requiresLocalIdRemapping = collisions.isNotEmpty(),
            collidingSecondaryIds = collisions
        )
    }
}
