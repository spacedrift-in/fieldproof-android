package com.spacedrift.fieldproof.di

import com.spacedrift.fieldproof.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the cleartext/end-point configuration:
 * - release builds must never permit cleartext HTTP,
 * - the manifest must route traffic through a network security config,
 * - the backend base URL must be injected at build time (not hardcoded) and be a
 *   well-formed Retrofit base URL.
 */
class NetworkConfigurationTest {

    private fun file(path: String): File {
        // Unit tests run from the module directory; fall back to the repo layout.
        val direct = File(path)
        if (direct.exists()) return direct
        return File("app/$path").also { check(it.exists()) { "missing $path" } }
    }

    private fun read(path: String): String = file(path).readText()

    @Test
    fun `release network security config denies cleartext traffic`() {
        val xml = read("src/release/res/xml/network_security_config.xml")

        assertTrue(
            "release base-config must deny cleartext",
            xml.contains("""<base-config cleartextTrafficPermitted="false" />""")
        )
    }

    @Test
    fun `debug network security config allows localhost cleartext for development`() {
        val xml = read("src/debug/res/xml/network_security_config.xml")

        assertTrue(
            "debug base-config allows cleartext for local development",
            xml.contains("""<base-config cleartextTrafficPermitted="true" />""")
        )
    }

    @Test
    fun `manifest routes traffic through a network security config`() {
        val manifest = read("src/main/AndroidManifest.xml")

        assertTrue(
            "manifest must reference a network security config",
            manifest.contains("""android:networkSecurityConfig="@xml/network_security_config"""")
        )
        assertFalse(
            "manifest must not blanket-enable cleartext",
            manifest.contains("""android:usesCleartextTraffic="true"""")
        )
    }

    @Test
    fun `api base url is injected and is a valid retrofit base url`() {
        // Retrofit requires a trailing slash; an http(s) scheme is mandatory.
        val baseUrl = BuildConfig.API_BASE_URL
        assertTrue(
            "base URL must use http(s): $baseUrl",
            baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
        )
        assertTrue("base URL must end with '/': $baseUrl", baseUrl.endsWith("/"))
    }
}
