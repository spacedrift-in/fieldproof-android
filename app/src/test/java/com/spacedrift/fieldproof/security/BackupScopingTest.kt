package com.spacedrift.fieldproof.security

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.spacedrift.fieldproof.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Guards the privacy promise that captured evidence never leaves the device via
 * Android cloud backup or device-to-device transfer. Backup is disabled in the
 * manifest and every backup domain is explicitly excluded in both the legacy
 * and Android 12+ rule files as defence in depth.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupScopingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `backup is disabled in the manifest`() {
        val flags = context.applicationInfo.flags
        assertFalse(
            "android:allowBackup must be false for the evidence app",
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0
        )
    }

    @Test
    fun `legacy full backup rules exclude every data domain`() {
        val domains = excludedDomains(R.xml.backup_rules)
        assertEquals(BACKUP_DOMAINS, domains)
    }

    @Test
    fun `data extraction rules exclude cloud backup and device transfer domains`() {
        val cloudBackup = excludedDomains(R.xml.data_extraction_rules, "cloud-backup")
        val deviceTransfer = excludedDomains(R.xml.data_extraction_rules, "device-transfer")

        assertEquals(BACKUP_DOMAINS, cloudBackup)
        assertEquals(BACKUP_DOMAINS, deviceTransfer)
    }

    @Test
    fun `backup domains cover database, files, preferences and external storage`() {
        assertTrue(BACKUP_DOMAINS.containsAll(listOf("database", "file", "sharedpref", "external")))
    }

    private fun excludedDomains(
        resourceId: Int,
        section: String? = null
    ): Set<String> {
        val parser = context.resources.getXml(resourceId)
        val excluded = mutableSetOf<String>()
        var currentSection: String? = null

        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name
                if (name == "cloud-backup" || name == "device-transfer") {
                    currentSection = name
                } else if (name == "exclude" && (section == null || section == currentSection)) {
                    parser.getAttributeValue(null, "domain")?.let(excluded::add)
                }
            }
            event = parser.next()
        }
        parser.close()
        return excluded
    }

    private companion object {
        val BACKUP_DOMAINS = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref"
        )
    }
}
