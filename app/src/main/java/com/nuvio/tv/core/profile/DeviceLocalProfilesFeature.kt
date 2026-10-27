package com.nuvio.tv.core.profile

/**
 * Single rollout switch for device-only secondary profiles.
 *
 * Keep disabled until profile migration, Main-only cloud metadata updates,
 * all sync paths, and personal viewing isolation have been verified.
 * Do not enable in a customer release without an explicit migration plan.
 */
internal object DeviceLocalProfilesFeature {
    const val ENABLED: Boolean = false
}
