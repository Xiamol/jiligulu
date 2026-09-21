package com.jiligulu.app.data.update

import org.junit.Assert.*
import org.junit.Test

class GithubReleasesTest {
    @Test fun `repository accepts public project names and rejects credentials or arbitrary endpoints`() {
        assertEquals("owner/ledger", GithubReleases.normalizeRepository("https://github.com/owner/ledger.git"))
        assertNull(GithubReleases.normalizeRepository("https://example.com/owner/ledger"))
        assertNull(GithubReleases.normalizeRepository("https://user:token@github.com/owner/ledger"))
        assertNull(GithubReleases.normalizeRepository("owner/../ledger"))
    }

    @Test fun `version comparison is numeric and only stable release labels are accepted`() {
        assertTrue(ReleaseVersion.parse("v0.10.0")!! > ReleaseVersion.parse("0.9.9")!!)
        assertTrue(ReleaseVersion.parse("1.0.0")!! > ReleaseVersion.parse("0.99.99")!!)
        assertNull(ReleaseVersion.parse("v0.5.2-beta"))
        assertNull(ReleaseVersion.parse("latest"))
    }

    @Test fun `public release with apk is parsed without executing notes or following foreign urls`() {
        val info = GithubReleases.parseRelease("Owner/ledger", payload("https://github.com/owner/ledger/releases/download/v0.5.2/app.apk"))
        assertEquals("0.5.2", info.version)
        assertEquals("<script>plain release notes</script>", info.notes)
        assertEquals("https://github.com/Owner/ledger/releases/latest", info.pageUrl)
    }

    @Test fun `draft prerelease missing apk and foreign download are rejected`() {
        listOf(
            payload("https://evil.example/app.apk"),
            payload("https://github.com/other/project/releases/download/v0.5.2/app.apk"),
            payload("https://github.com/owner/ledger/releases/download/v0.5.2/app.apk").replace("\"draft\":false", "\"draft\":true"),
            "{\"tag_name\":\"v0.5.2\",\"assets\":[]}"
        ).forEach { raw -> assertTrue(runCatching { GithubReleases.parseRelease("owner/ledger", raw) }.isFailure) }
    }

    private fun payload(url: String) = """{"tag_name":"v0.5.2","draft":false,"prerelease":false,"body":"<script>plain release notes</script>","assets":[{"name":"app.apk","browser_download_url":"$url"}]}"""
}
