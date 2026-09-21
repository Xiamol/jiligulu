package com.jiligulu.app.data.update

import android.app.Application
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReleaseUpdateRepositoryTest {
    @Test fun `unconfigured update source never makes a request and successful auto check is throttled`() = runBlocking {
        val prefs = UserPrefs(RuntimeEnvironment.getApplication())
        prefs.setUpdateRepository("")
        prefs.setAutoCheckUpdates(true)
        var calls = 0
        val current = requireNotNull(ReleaseVersion.parse(BuildConfig.VERSION_NAME))
        val latest = "${current.major}.${current.minor}.${current.patch + 1}"
        val repo = ReleaseUpdateRepository(prefs) {
            calls++
            """{"tag_name":"v$latest","assets":[{"name":"jiligulu.apk","browser_download_url":"https://github.com/owner/ledger/releases/download/v$latest/jiligulu.apk"}]}"""
        }
        repo.check(automatic = true)
        assertEquals(0, calls)
        repo.configure("owner/ledger")
        repo.check(automatic = true)
        assertEquals(latest, repo.state.value.available?.version)
        // 发现新版本时不写检查时间，后续自动检查不会被节流，
        // 保证「下载失败退出后重开仍能收到提示」。
        repo.check(automatic = true)
        assertEquals(2, calls)
        assertEquals(0, prefs.updateCheckedAt.first())
    }

    @Test fun `up-to-date check records timestamp and throttles subsequent automatic checks`() = runBlocking {
        val prefs = UserPrefs(RuntimeEnvironment.getApplication())
        prefs.setAutoCheckUpdates(true)
        var calls = 0
        val current = requireNotNull(ReleaseVersion.parse(BuildConfig.VERSION_NAME))
        val repo = ReleaseUpdateRepository(prefs) {
            calls++
            """{"tag_name":"v$current","assets":[{"name":"jiligulu.apk","browser_download_url":"https://github.com/owner/ledger/releases/download/v$current/jiligulu.apk"}]}"""
        }
        repo.configure("owner/ledger")
        repo.check(automatic = true)
        assertEquals(null, repo.state.value.available)
        assertTrue(prefs.updateCheckedAt.first() > 0)
        repo.check(automatic = true)
        assertEquals(1, calls) // 已是最新时写时间，6 小时内自动检查被节流
        repo.check()
        assertEquals(2, calls) // 手动检查不受节流影响
    }

    @Test fun `checking records current version so a different installed version is not throttled`() = runBlocking {
        // 覆盖安装会把 DataStore 原样带过去。若只按时间戳节流，
        // 用户装完新版 6 小时内收不到自动检查——这正是 0.5.2 升 0.5.3 时踩到的坑。
        val prefs = UserPrefs(RuntimeEnvironment.getApplication())
        prefs.setUpdateRepository("owner/ledger")
        prefs.setAutoCheckUpdates(true)
        val current = requireNotNull(ReleaseVersion.parse(BuildConfig.VERSION_NAME))
        // 模拟「上一个版本」留下的检查记录
        prefs.setUpdateCheckedAt(System.currentTimeMillis(), "0.0.1")
        var calls = 0
        val repo = ReleaseUpdateRepository(prefs) {
            calls++
            """{"tag_name":"v$current","assets":[{"name":"jiligulu.apk","browser_download_url":"https://github.com/owner/ledger/releases/download/v$current/jiligulu.apk"}]}"""
        }
        repo.check(automatic = true)
        assertEquals(1, calls) // 版本不同 → 时间戳节流失效，必须真的发起检查
        assertEquals(BuildConfig.VERSION_NAME, prefs.updateCheckedVersion.first())
        repo.check(automatic = true)
        assertEquals(1, calls) // 同一版本 + 刚查过 → 正常节流
    }

    @Test fun `failed check is shown as failure rather than latest and disabling auto keeps manual check usable`() = runBlocking {
        val prefs = UserPrefs(RuntimeEnvironment.getApplication())
        prefs.setUpdateRepository("owner/ledger")
        prefs.setAutoCheckUpdates(false)
        var calls = 0
        val repo = ReleaseUpdateRepository(prefs) { calls++; throw java.io.IOException("test") }
        repo.check(automatic = true)
        assertEquals(0, calls)
        repo.check()
        assertEquals(1, calls)
        assertNotNull(repo.state.value.error)
        assertFalse(repo.state.value.checked)
        assertFalse(repo.state.value.checking)
    }
}
