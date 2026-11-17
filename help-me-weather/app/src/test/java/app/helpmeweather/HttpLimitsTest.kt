package app.helpmeweather

import app.helpmeweather.data.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class HttpLimitsTest {
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    @Test fun gzipBombIsRejectedAfterDecompression() {
        val bomb = gzip(ByteArray(10 * 1024 * 1024)) // 10 MB of zeros → ~10 KB compressed
        assert(bomb.size < 64 * 1024)
        assertThrows(IOException::class.java) {
            Http.readBounded(GZIPInputStream(ByteArrayInputStream(bomb)), limit = 2L * 1024 * 1024, failOnOverflow = true)
        }
    }

    @Test fun oversizedErrorBodyIsTruncatedNotLoaded() {
        val body = ByteArray(5 * 1024 * 1024) { 'x'.code.toByte() }
        val out = Http.readBounded(ByteArrayInputStream(body), limit = 16 * 1024, failOnOverflow = false)
        assertEquals(16 * 1024, out.length)
    }

    @Test fun normalBodyIsReadFully() {
        val json = """{"ok":true,"list":[1,2,3]}"""
        assertEquals(json, Http.readBounded(ByteArrayInputStream(json.toByteArray()), limit = 1024, failOnOverflow = true))
    }
}
