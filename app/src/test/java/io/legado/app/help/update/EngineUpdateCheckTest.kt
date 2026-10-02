package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎更新通道的纯决策层测试（不含网络；网络层走 fetchManifest，失败即抛、启动路径静默）。
 *
 * 断言重点不是"字段填对了"，而是三条**不能错**的行为：
 *  ① 只给本变体发本变体的包（小说引擎拿到视频包 = 装错软件）；
 *  ② 不比自己新就绝不提示（否则每次启动都弹窗）；
 *  ③ 说明缺字段要兜底（UpdateDialog 收到空正文会直接 dismiss）。
 */
class EngineUpdateCheckTest {

    private fun manifest(
        version: String? = "1.9.0",
        changelog: String? = "1.9.0：修搜索。",
        minAppVersion: String? = "4.44.0",
        releasedAt: String? = "2026-10-02 10:00:00",
        items: List<EngineManifestItem>? = listOf(
            EngineManifestItem(
                id = "app",
                name = "阅读引擎（四合一）",
                version = version,
                file = "reading-engine-1.9.0.apk",
                size = 26213718,
                url = "https://example.com/reading-engine-1.9.0.apk"
            ),
            EngineManifestItem(
                id = "novel",
                name = "小说引擎",
                version = version,
                file = "reading-engine-novel-1.9.0.apk",
                size = 25981342,
                url = "https://example.com/reading-engine-novel-1.9.0.apk"
            )
        )
    ) = EngineManifest(
        version = version,
        releasedAt = releasedAt,
        minAppVersion = minAppVersion,
        changelog = changelog,
        items = items
    )

    @Test
    fun newerVersionIsOfferedToItsOwnVariant() {
        val info = pickUpdateInfo(manifest(), "novel", "1.8.0")
        assertNotNull(info)
        assertEquals("1.9.0", info!!.tagName)
        assertEquals("reading-engine-novel-1.9.0.apk", info.fileName)
        assertEquals("https://example.com/reading-engine-novel-1.9.0.apk", info.downloadUrl)
        assertEquals(25981342L, info.size)
    }

    @Test
    fun variantNeverGetsAnotherVariantsPackage() {
        // 反证：小说引擎绝不能拿到四合一 / 视频的包 —— 装上去就是另一个软件。
        val info = pickUpdateInfo(manifest(), "novel", "1.8.0")
        assertNotNull(info)
        assertTrue(
            "下载的必须是 novel 变体自己的包，实际=${info!!.fileName}",
            info.fileName.contains("novel")
        )
        val none = pickUpdateInfo(manifest(), "video", "1.8.0")
        assertNull("清单里没有 video 条目时绝不拿别的凑数", none)
    }

    @Test
    fun sameOrNewerLocalVersionOffersNothing() {
        assertNull(pickUpdateInfo(manifest(), "app", "1.9.0"))
        assertNull(pickUpdateInfo(manifest(), "app", "2.0.0"))
    }

    @Test
    fun patchVersionComparisonUsesTheSameRuler() {
        // 1.8.1 在 1.8.0 之上、在 1.9.0 之下 —— 与旧通道同一把尺子。
        assertTrue(compareReleaseVersions("1.8.1", "1.8.0") > 0)
        val info = pickUpdateInfo(manifest(version = "1.8.1"), "app", "1.8.0")
        assertNotNull(info)
        assertNull(pickUpdateInfo(manifest(version = "1.8.1"), "app", "1.8.1"))
    }

    @Test
    fun itemVersionFallsBackToTopLevelVersion() {
        val m = manifest(items = listOf(
            EngineManifestItem(id = "app", version = null, url = "https://example.com/a.apk")
        ))
        val info = pickUpdateInfo(m, "app", "1.8.0")
        assertNotNull(info)
        assertEquals("1.9.0", info!!.tagName)
    }

    @Test
    fun missingDownloadUrlOffersNothing() {
        val m = manifest(items = listOf(EngineManifestItem(id = "app", version = "1.9.0")))
        assertNull(pickUpdateInfo(m, "app", "1.8.0"))
    }

    @Test
    fun updateLogKeepsChangelogAndWarnsMinAppVersion() {
        val info = pickUpdateInfo(manifest(), "app", "1.8.0")!!
        assertTrue("说明要含更新内容", info.updateLog.contains("修搜索"))
        assertTrue("说明要含最低客户端版本", info.updateLog.contains("4.44.0"))
    }

    @Test
    fun updateLogFallsBackWhenChangelogMissing() {
        val m = manifest(changelog = null, items = listOf(
            EngineManifestItem(
                id = "app",
                version = "1.9.0",
                note = "条目自己的说明",
                url = "https://example.com/a.apk"
            )
        ))
        val info = pickUpdateInfo(m, "app", "1.8.0")!!
        assertTrue(info.updateLog.contains("条目自己的说明"))

        val m2 = manifest(changelog = null, items = listOf(
            EngineManifestItem(id = "app", version = "1.9.0", url = "https://example.com/a.apk")
        ))
        val info2 = pickUpdateInfo(m2, "app", "1.8.0")!!
        // 空正文会被 UpdateDialog 当"没有数据"直接 dismiss —— 必须留一句兜底。
        assertTrue(info2.updateLog.isNotBlank())
    }

    @Test
    fun minAppVersionLineCanBeOmitted() {
        val info = pickUpdateInfo(manifest(minAppVersion = null), "app", "1.8.0")!!
        assertTrue(!info.updateLog.contains("需要客户端"))
    }

    @Test
    fun malformedOrEmptyJsonYieldsNull() {
        assertNull(parseManifestJson(null))
        assertNull(parseManifestJson(""))
        assertNull(parseManifestJson("   "))
        assertNull(parseManifestJson("{不是 JSON"))
    }

    @Test
    fun realManifestShapeParses() {
        val body = """
            {
              "version": "1.8.0",
              "updatedAt": "2026-10-01 15:15:00",
              "releasedAt": "2026-10-01 15:15:00",
              "minAppVersion": "4.44.0",
              "changelog": "1.8.0：免激活。",
              "items": [
                {"id":"app","name":"阅读引擎（四合一）","version":"1.8.0",
                 "file":"reading-engine-1.8.0.apk","size":26213718,
                 "url":"https://example.com/reading-engine-1.8.0.apk"}
              ]
            }
        """.trimIndent()
        val m = parseManifestJson(body)
        assertNotNull(m)
        assertEquals("1.8.0", m!!.version)
        assertEquals("4.44.0", m.minAppVersion)
        assertEquals(1, m.items?.size)
        assertEquals("app", m.items!![0].id)
        val info = pickUpdateInfo(m, "app", "1.7.0")
        assertNotNull(info)
        assertEquals(26213718L, info!!.size)
    }

    @Test
    fun oldManifestWithoutNewFieldsStillWorks() {
        // 旧清单没有 releasedAt / minAppVersion / changelog —— 不许因此不提示或崩溃。
        val body = """
            {"version":"1.8.0","items":[
              {"id":"app","version":"1.8.0","url":"https://example.com/a.apk"}]}
        """.trimIndent()
        val m = parseManifestJson(body)!!
        val info = pickUpdateInfo(m, "app", "1.7.0")
        assertNotNull(info)
        assertEquals(0L, info!!.createdAt)
        assertTrue(info.updateLog.isNotBlank())
    }

    @Test
    fun releasedAtParsesLeniently() {
        assertTrue(parseReleasedAtMillis("2026-10-01 15:15:00") > 0L)
        assertTrue(parseReleasedAtMillis("2026-10-01 15:15") > 0L)
        assertTrue(parseReleasedAtMillis("2026-10-01") > 0L)
        assertEquals(0L, parseReleasedAtMillis("昨天"))
        assertEquals(0L, parseReleasedAtMillis(null))
        assertEquals(0L, parseReleasedAtMillis(""))
    }

    @Test
    fun updateInfoCarriesReleasedAtForDialogSubtitle() {
        val info = pickUpdateInfo(manifest(), "app", "1.8.0")!!
        assertTrue("对话框副标题要能显示发布日期", info.createdAt > 0L)
    }
}
