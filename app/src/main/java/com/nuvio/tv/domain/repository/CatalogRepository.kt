package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int = 0,
        skipStep: Int = 100,
        extraArgs: Map<String, String> = emptyMap(),
        supportsSkip: Boolean = false,
        posterScreen: com.nuvio.tv.core.poster.CustomPosterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.HOME
    ): Flow<NetworkResult<CatalogRow>>

    /**
     * Like [getCatalog], but first emits the last copy of the first page from the on-device
     * HTTP cache (if any) so rows appear instantly, then the fresh network result.
     * A network failure after a cached emission is not reported, so cached rows stay visible.
     */
    fun getCatalogCachedFirst(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skipStep: Int = 100,
        supportsSkip: Boolean = false
    ): Flow<NetworkResult<CatalogRow>> = getCatalog(
        addonBaseUrl = addonBaseUrl,
        addonId = addonId,
        addonName = addonName,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        skip = 0,
        skipStep = skipStep,
        supportsSkip = supportsSkip
    )
}
