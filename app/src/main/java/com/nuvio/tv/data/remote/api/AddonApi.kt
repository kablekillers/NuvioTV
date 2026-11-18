package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.data.remote.dto.SubtitleResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

interface AddonApi {

    @GET
    suspend fun getManifest(@Url manifestUrl: String): Response<AddonManifestDto>

    @GET
    suspend fun getCatalog(@Url catalogUrl: String): Response<CatalogResponseDto>

    /** Same request answered only from the HTTP disk cache (504 when nothing is cached). */
    @GET
    suspend fun getCachedCatalog(
        @Url catalogUrl: String,
        @Header("Cache-Control") cacheControl: String = "only-if-cached, max-stale=2147483647"
    ): Response<CatalogResponseDto>

    @GET
    suspend fun getMeta(@Url metaUrl: String): Response<MetaResponseDto>

    @GET
    suspend fun getStreams(@Url streamUrl: String): Response<StreamResponseDto>

    @GET
    suspend fun getSubtitles(@Url subtitleUrl: String): Response<SubtitleResponseDto>
}
