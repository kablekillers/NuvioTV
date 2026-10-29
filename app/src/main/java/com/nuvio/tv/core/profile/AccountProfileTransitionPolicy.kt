package com.nuvio.tv.core.profile

/**
 * Pure transition planner for changing between the legacy, family, and
 * customer profile modes. A switch into device-local mode requires an
 * explicit migration; changing a string in settings is not sufficient.
 */
internal object AccountProfileTransitionPolicy {
    enum class Action {
        KEEP_CURRENT,
        APPLY_CLOUD_MODE,
        REQUIRE_CUSTOMER_MIGRATION,
        REQUIRE_CLOUD_RECONCILIATION
    }

    fun plan(current: AccountProfileMode, requested: AccountProfileMode): Action {
        if (current == requested) return Action.KEEP_CURRENT
        return when {
            requested == AccountProfileMode.CUSTOMER_DEVICE_LOCAL ->
                Action.REQUIRE_CUSTOMER_MIGRATION
            current == AccountProfileMode.CUSTOMER_DEVICE_LOCAL ->
                Action.REQUIRE_CLOUD_RECONCILIATION
            else -> Action.APPLY_CLOUD_MODE
        }
    }

    fun mayApplyWithoutMigration(current: AccountProfileMode, requested: AccountProfileMode): Boolean =
        plan(current, requested) in setOf(Action.KEEP_CURRENT, Action.APPLY_CLOUD_MODE)
}
