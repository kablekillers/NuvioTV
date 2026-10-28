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
        REQUIRE_CUSTOMER_MIGRATION
    }

    fun plan(current: AccountProfileMode, requested: AccountProfileMode): Action {
        if (current == requested) return Action.KEEP_CURRENT
        return if (requested == AccountProfileMode.CUSTOMER_DEVICE_LOCAL) {
            Action.REQUIRE_CUSTOMER_MIGRATION
        } else {
            Action.APPLY_CLOUD_MODE
        }
    }

    fun mayApplyWithoutMigration(current: AccountProfileMode, requested: AccountProfileMode): Boolean =
        plan(current, requested) != Action.REQUIRE_CUSTOMER_MIGRATION
}
