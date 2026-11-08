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
        require(existingCloudProfileIds.all { it > 0 } && existingDeviceProfileIds.all { it > 0 }) {
            "Invalid profile IDs must be repaired before remapping"
        }
        val plan = inspect(existingCloudProfileIds, existingDeviceProfileIds)
        require(1 in plan.preservedCloudIds && 1 in plan.reservedLocalIds) {
            "Main must exist in both cloud and device profiles"
        }
        require(plan.reservedLocalIds.size <= 6) {
            "Device has more than five personal profiles"
        }
        require(plan.reservedLocalIds.all { it in 1..6 }) {
            "Device contains unsupported profile IDs"
        }
        // Existing cloud IDs may be outside 1..6, but customer secondary IDs
        // cannot: ProfileManager and its UI only support Main + five others.
        // Never generate an ID 7+ and leave activation to discover it later.
        val occupied = (plan.preservedCloudIds + plan.reservedLocalIds).toMutableSet()
        val available = (2..6).filterNot { it in occupied }
        check(available.size >= plan.collidingSecondaryIds.size) {
            "Not enough supported local profile IDs for safe migration"
        }
        return plan.collidingSecondaryIds.zip(available).toMap()
    }

    fun inspect(
        existingCloudProfileIds: List<Int>,
        existingDeviceProfileIds: List<Int>
    ): Plan {
        require(existingCloudProfileIds.all { it > 0 })
        require(existingDeviceProfileIds.all { it > 0 })
        require(existingCloudProfileIds.size == existingCloudProfileIds.distinct().size) {
            "Duplicate cloud profile IDs must be repaired before migration inspection"
        }
        require(existingDeviceProfileIds.size == existingDeviceProfileIds.distinct().size) {
            "Duplicate device profile IDs must be repaired before migration inspection"
        }
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
