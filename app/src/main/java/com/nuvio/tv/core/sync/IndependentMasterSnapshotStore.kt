package com.nuvio.tv.core.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import android.util.AtomicFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * App-private storage for an independent master configuration snapshot.
 *
 * Not wired into production sync yet. Caller must verify primary/master profile
 * and gather addons, collections and home catalog settings consistently.
 * No cloud upload, Nuvio RPC, or customer distribution is performed here.
 */
internal class IndependentMasterSnapshotStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.filesDir, "kk_master_config_v1.json"))
    private val json = Json { ignoreUnknownKeys = false }

    @Synchronized
    fun save(snapshot: JsonObject) {
        require(snapshot["schema"]?.jsonPrimitive?.content == SCHEMA) {
            "Unsupported independent master configuration schema"
        }
        require(snapshot.containsKey("addons") &&
                snapshot.containsKey("collections") &&
                snapshot.containsKey("home_catalog_settings")) {
            "Missing a required master configuration surface"
        }
        val bytes = json.encodeToString(JsonObject.serializer(), snapshot)
            .toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Master configuration snapshot too large" }

        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun load(): JsonObject? {
        val bytes = try {
            file.openRead().use { stream ->
                val buffer = stream.readBytes()
                require(buffer.size <= MAX_BYTES) { "Snapshot exceeds size limit" }
                buffer
            }
        } catch (_: java.io.FileNotFoundException) {
            return null
        }
        val parsed = json.parseToJsonElement(String(bytes, StandardCharsets.UTF_8)) as? JsonObject
            ?: error("Snapshot is not a JSON object")
        require(parsed["schema"]?.jsonPrimitive?.content == SCHEMA) {
            "Unsupported snapshot schema"
        }
        return parsed
    }

    companion object {
        const val SCHEMA = "kable-killers-master-config-v1"
        private const val MAX_BYTES = 8 * 1024 * 1024
    }
}
