package androidx.media3.datasource

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class LocalhostZeroCopyDataSourceTest {

    @Test
    fun testOpenAndReadZeroCopy() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val expectedData = "Hello, zero-copy socket loopback data pipeline!"
        val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${expectedData.length}\r\n" +
                "Connection: close\r\n" +
                "\r\n"

        thread {
            try {
                val client = serverSocket.accept()
                val reader = client.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }
                val out = client.getOutputStream()
                out.write(responseHeaders.toByteArray())
                out.write(expectedData.toByteArray())
                out.flush()
                client.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val mockUri = mockk<Uri>()
        every { mockUri.host } returns "127.0.0.1"
        every { mockUri.port } returns port
        every { mockUri.path } returns "/stream.mp4"
        every { mockUri.query } returns null

        val dataSource = LocalhostZeroCopyDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .build()
        
        try {
            val bytesRemaining = dataSource.open(dataSpec)
            assertEquals(expectedData.length.toLong(), bytesRemaining)
            assertTrue(dataSource.supportsByteBufferRead())

            val buffer = ByteBuffer.allocateDirect(100)
            val bytesRead = dataSource.read(buffer, expectedData.length)
            assertEquals(expectedData.length, bytesRead)
            
            buffer.flip()
            val bytes = ByteArray(bytesRead)
            buffer.get(bytes)
            val actualData = String(bytes)
            assertEquals(expectedData, actualData)
        } finally {
            dataSource.close()
            serverSocket.close()
        }
    }

    @Test
    fun testCaseInsensitiveContentLengthAndPartialContent() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val expectedData = "Partial payload"
        val responseHeaders = "HTTP/1.1 206 Partial Content\r\n" +
                "cOnTeNt-LeNgTh: ${expectedData.length}\r\n" +
                "Connection: close\r\n" +
                "\r\n"

        thread {
            try {
                val client = serverSocket.accept()
                val reader = client.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }
                val out = client.getOutputStream()
                out.write(responseHeaders.toByteArray())
                out.write(expectedData.toByteArray())
                out.flush()
                client.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val mockUri = mockk<Uri>()
        every { mockUri.host } returns "127.0.0.1"
        every { mockUri.port } returns port
        every { mockUri.path } returns "/stream.mp4"
        every { mockUri.query } returns null

        val dataSource = LocalhostZeroCopyDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .build()
        
        try {
            val bytesRemaining = dataSource.open(dataSpec)
            assertEquals(expectedData.length.toLong(), bytesRemaining)

            val buffer = ByteBuffer.allocateDirect(100)
            val bytesRead = dataSource.read(buffer, expectedData.length)
            assertEquals(expectedData.length, bytesRead)
            
            buffer.flip()
            val bytes = ByteArray(bytesRead)
            buffer.get(bytes)
            assertEquals(expectedData, String(bytes))
        } finally {
            dataSource.close()
            serverSocket.close()
        }
    }

    @Test
    fun testExcessBytesHandling() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        // 15 bytes of body
        val expectedData = "BufferLookAhead"
        val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${expectedData.length}\r\n" +
                "\r\n"

        thread {
            try {
                val client = serverSocket.accept()
                val out = client.getOutputStream()
                val fullPayload = responseHeaders.toByteArray() + expectedData.toByteArray()
                out.write(fullPayload)
                out.flush()
                client.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val mockUri = mockk<Uri>()
        every { mockUri.host } returns "127.0.0.1"
        every { mockUri.port } returns port
        every { mockUri.path } returns "/stream.mp4"
        every { mockUri.query } returns null

        val dataSource = LocalhostZeroCopyDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .build()
        
        try {
            val bytesRemaining = dataSource.open(dataSpec)
            assertEquals(expectedData.length.toLong(), bytesRemaining)

            val buffer = ByteBuffer.allocateDirect(100)
            val bytesRead = dataSource.read(buffer, expectedData.length)
            assertEquals(expectedData.length, bytesRead)
            
            buffer.flip()
            val bytes = ByteArray(bytesRead)
            buffer.get(bytes)
            assertEquals(expectedData, String(bytes))
        } finally {
            dataSource.close()
            serverSocket.close()
        }
    }

    @Test
    fun testHttpError404() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val observedRequest = java.util.concurrent.atomic.AtomicReference<String?>()
        val responseHeaders = "HTTP/1.1 404 Not Found\r
" +
                "Content-Length: 0\r
" +
                "\r
"

        thread {
            try {
                val client = serverSocket.accept()
                // Consume the request headers before returning the error, just as
                // the successful loopback test server does.
                val reader = client.getInputStream().bufferedReader()
                observedRequest.set(reader.readLine())
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }
                val out = client.getOutputStream()
                out.write(responseHeaders.toByteArray())
                out.flush()
                client.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val mockUri = mockk<Uri>()
        every { mockUri.host } returns "127.0.0.1"
        every { mockUri.port } returns port
        every { mockUri.path } returns "/stream.mp4"
        every { mockUri.query } returns null
        // A real HTTP URI is required by the datasource's HTTP error path.
        // MockK's default toString() is not a usable URL.
        every { mockUri.scheme } returns "http"
        every { mockUri.toString() } returns "http://127.0.0.1:$port/stream.mp4"

        val dataSource = LocalhostZeroCopyDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(mockUri)
            .build()
        
        try {
            val error = assertThrows(HttpDataSource.HttpDataSourceException::class.java) {
                dataSource.open(dataSpec)
            }
            // The zero-copy wrapper exposes the HTTP 404 as its cause.
            val responseError = generateSequence(error as Throwable) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
            assertTrue("Expected HTTP 404 in the exception chain", responseError != null)
            assertEquals(
                "Request=${observedRequest.get()}, HTTP error=${responseError!!.message}, " +
                    "exception chain=" + generateSequence(error as Throwable) { it.cause }.joinToString(" -> "),
                404, responseError!!.responseCode
            )
        } finally {
            dataSource.close()
            serverSocket.close()
        }
    }
}
