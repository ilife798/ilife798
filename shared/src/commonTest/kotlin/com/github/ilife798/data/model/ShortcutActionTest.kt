package com.github.ilife798.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutActionTest {
    @Test
    fun parsesResourcePaths() {
        assertEquals(ShortcutAction.Home, parseShortcutUrl("ilife798://home")?.action)
        assertEquals(ShortcutAction.Tasks, parseShortcutUrl("ilife798://tasks")?.action)
        assertEquals(ShortcutAction.Score, parseShortcutUrl("ilife798://score")?.action)
        assertEquals(ShortcutAction.Bill, parseShortcutUrl("ilife798://bills")?.action)
        assertEquals(ShortcutAction.Cards, parseShortcutUrl("ilife798://cards")?.action)
        assertEquals(ShortcutAction.Me, parseShortcutUrl("ilife798://me")?.action)
        assertEquals(ShortcutAction.Backup, parseShortcutUrl("ilife798://backup")?.action)
        assertEquals(ShortcutAction.Schedule, parseShortcutUrl("ilife798://schedule")?.action)
        assertEquals(ShortcutAction.Refresh, parseShortcutUrl("ilife798://refresh")?.action)
        assertEquals(ShortcutAction.Account, parseShortcutUrl("ilife798://accounts")?.action)
        assertTrue(parseShortcutUrl("ilife798://tasks")?.args?.isEmpty() == true)
    }

    @Test
    fun parsesActionsAsSubPaths() {
        assertEquals(ShortcutAction.RunTasks, parseShortcutUrl("ilife798://tasks/run")?.action)
        assertEquals(ShortcutAction.StopTasks, parseShortcutUrl("ilife798://tasks/stop")?.action)
        assertEquals(ShortcutAction.CardScan, parseShortcutUrl("ilife798://cards/scan")?.action)
        assertEquals(ShortcutAction.DeviceAdd, parseShortcutUrl("ilife798://devices/new")?.action)
        assertEquals(ShortcutAction.DeviceAdd, parseShortcutUrl("ilife798://devices")?.action)
    }

    @Test
    fun parsesPathParameters() {
        // 参数一律落在路径段里。
        assertEquals(ShortcutAction.Device, parseShortcutUrl("ilife798://devices/1080")?.action)
        assertEquals("1080", parseShortcutUrl("ilife798://devices/1080")?.arg("id"))
        assertEquals(ShortcutAction.StopDevice, parseShortcutUrl("ilife798://devices/1080/stop")?.action)
        assertEquals("1080", parseShortcutUrl("ilife798://devices/1080/stop")?.arg("id"))
        assertEquals(ShortcutAction.SwitchAccount, parseShortcutUrl("ilife798://accounts/2")?.action)
        // 序号与 uid 都原样带出，由界面层决定按哪种方式定位账号
        assertEquals("2", parseShortcutUrl("ilife798://accounts/2")?.arg("account"))
        assertEquals(ShortcutAction.SwitchAccount, parseShortcutUrl("ilife798://accounts/abc123")?.action)
        assertEquals("abc123", parseShortcutUrl("ilife798://accounts/abc123")?.arg("account"))
    }

    @Test
    fun parsesRunAllTasks() {
        assertEquals(ShortcutAction.RunAllTasks, parseShortcutUrl("ilife798://tasks/run/all")?.action)
        // all 是关键字，不能被当成一个 uid
        assertNull(parseShortcutUrl("ilife798://tasks/run/all")?.uidList())
        // 链接大小写不敏感
        assertEquals(ShortcutAction.RunAllTasks, parseShortcutUrl("ILIFE798://Tasks/Run/All")?.action)
    }

    @Test
    fun parsesDeviceAddVariants() {
        for (url in listOf("ilife798://devices/new", "ilife798://devices/add", "ilife798://devices")) {
            assertEquals(ShortcutAction.DeviceAdd, parseShortcutUrl(url)?.action, "解析失败：$url")
            assertNull(parseShortcutUrl(url)?.arg("id"), "不该带预填编号：$url")
        }
        for (url in listOf("ilife798://devices/new/1080", "ilife798://devices/add/1080")) {
            assertEquals(ShortcutAction.DeviceAdd, parseShortcutUrl(url)?.action, "解析失败：$url")
            assertEquals("1080", parseShortcutUrl(url)?.arg("id"), "预填编号丢失：$url")
        }
    }

    @Test
    fun parsesAccountAddVariants() {
        assertEquals(ShortcutAction.AccountAdd, parseShortcutUrl("ilife798://accounts/add")?.action)
        assertTrue(parseShortcutUrl("ilife798://accounts/add")?.args?.isEmpty() == true)

        assertEquals(ShortcutAction.AccountAddPhone, parseShortcutUrl("ilife798://accounts/add/phone/13800001111")?.action)
        assertEquals("13800001111", parseShortcutUrl("ilife798://accounts/add/phone/13800001111")?.arg("phone"))
        // 缺手机号就不成立
        assertNull(parseShortcutUrl("ilife798://accounts/add/phone"))

        assertEquals(ShortcutAction.AccountAddToken, parseShortcutUrl("ilife798://accounts/add/token?appToken=aaa&token=bbb")?.action)
        val token = parseShortcutUrl("ilife798://accounts/add/token?appToken=aaa&token=bbb")
        assertEquals("aaa", token?.arg("appToken"))
        assertEquals("bbb", token?.arg("token"))
        // 只给一个槽位也成立
        assertEquals("aaa", parseShortcutUrl("ilife798://accounts/add/token?appToken=aaa")?.arg("appToken"))
        // 两个槽位都空则拒绝
        assertNull(parseShortcutUrl("ilife798://accounts/add/token"))
    }

    @Test
    fun acceptsSchemeAliases() {
        for (scheme in listOf("ilife798", "com.github.ilife798")) {
            assertEquals(ShortcutAction.RunTasks, parseShortcutUrl("$scheme://tasks/run")?.action, "协议头不识别：$scheme")
            assertEquals(ShortcutAction.Home, parseShortcutUrl("$scheme://home")?.action, "协议头不识别：$scheme")
        }
        // 已移除的别名与相近但不匹配的都拒绝
        assertNull(parseShortcutUrl("ilife798-unofficial://tasks"))
        assertNull(parseShortcutUrl("ilife798-other://tasks"))
        assertNull(parseShortcutUrl("com.github.ilife7989://tasks"))
    }

    @Test
    fun parsesUidsAsPathSegment() {
        val request = parseShortcutUrl("ilife798://tasks/run/1001,1002")
        assertEquals(ShortcutAction.RunBatch, request?.action)
        assertEquals(listOf("1001", "1002"), request?.uidList())
        // 不带 uid 表示只跑当前账号。
        assertEquals(ShortcutAction.RunTasks, parseShortcutUrl("ilife798://tasks/run")?.action)
        assertNull(parseShortcutUrl("ilife798://tasks/run")?.uidList())
    }

    @Test
    fun toleratesSchemeVariants() {
        for (
        url in
        listOf(
            "ilife798://tasks/run",
            "ilife798:///tasks/run",
            "ilife798:tasks/run",
            "ilife798://tasks/run/",
            "ILIFE798://Tasks/Run",
        )
        ) {
            assertEquals(ShortcutAction.RunTasks, parseShortcutUrl(url)?.action, "解析失败：$url")
        }
    }

    @Test
    fun ignoresQueryAndDoesNotTakeArgsFromIt() {
        // query 不再参与取参数：动作仍能解析，但参数只认路径段。
        assertEquals(ShortcutAction.RunTasks, parseShortcutUrl("ilife798://tasks/run?uids=1001")?.action)
        assertNull(parseShortcutUrl("ilife798://tasks/run?uids=1001")?.uidList())
        assertEquals(ShortcutAction.DeviceAdd, parseShortcutUrl("ilife798://devices?id=abc")?.action)
        assertNull(parseShortcutUrl("ilife798://devices?id=abc")?.arg("id"))
    }

    @Test
    fun decodesPercentEscapes() {
        assertEquals("a b", parseShortcutUrl("ilife798://devices/a%20b")?.arg("id"))
        assertEquals("a/b", parseShortcutUrl("ilife798://devices/a%2Fb")?.arg("id"))
    }

    @Test
    fun rejectsRemovedShortAliases() {
        // 早期短写法已移除，不再兼容。
        assertNull(parseShortcutUrl("ilife798://run-tasks"))
        assertNull(parseShortcutUrl("ilife798://run-all"))
        assertNull(parseShortcutUrl("ilife798://stop-tasks"))
        assertNull(parseShortcutUrl("ilife798://wallet"))
        assertNull(parseShortcutUrl("ilife798://mine"))
        assertNull(parseShortcutUrl("ilife798://reload"))
        assertNull(parseShortcutUrl("ilife798://card-scan"))
        assertNull(parseShortcutUrl("ilife798://device-add"))
        assertNull(parseShortcutUrl("ilife798://switch"))
        assertNull(parseShortcutUrl("ilife798://task"))
        assertNull(parseShortcutUrl("ilife798://bill"))
    }

    @Test
    fun rejectsUnknownOrForeignLinks() {
        assertNull(parseShortcutUrl(""))
        assertNull(parseShortcutUrl("   "))
        assertNull(parseShortcutUrl("https://example.com/tasks/run"))
        assertNull(parseShortcutUrl("ilife798://"))
        assertNull(parseShortcutUrl("ilife798://nope"))
        assertNull(parseShortcutUrl("ilife798://tasks/nope"))
    }

    @Test
    fun exposesPathTemplatesForEveryAction() {
        // 枚举里每个动作都要有一条可供文档展示的路径模板，且互不重复。
        val paths = ShortcutAction.entries.map { it.path }
        assertEquals(paths.size, paths.toSet().size)
        assertTrue(paths.all { it.isNotBlank() })
    }

    @Test
    fun parsesDeviceRun() {
        assertEquals(ShortcutAction.RunDevice, parseShortcutUrl("ilife798://devices/1080/run")?.action)
        assertEquals("1080", parseShortcutUrl("ilife798://devices/1080/run")?.arg("id"))
    }

    @Test
    fun parsesBgFlag() {
        // 只有 bg=true 才算后台执行
        assertEquals(true, parseShortcutUrl("ilife798://devices/1080/run?bg=true")?.bg)
        assertEquals(true, parseShortcutUrl("ilife798://tasks/run/all?bg=TRUE")?.bg)
        assertEquals(false, parseShortcutUrl("ilife798://tasks/run/all?bg=false")?.bg)
        assertEquals(false, parseShortcutUrl("ilife798://tasks/stop?bg=1")?.bg)
        assertNull(parseShortcutUrl("ilife798://tasks/stop")?.bg)
        assertEquals(true, parseShortcutUrl("ilife798://accounts/add/token?appToken=aaa&bg=true")?.bg)
        assertNull(parseShortcutUrl("ilife798://accounts/add/token?appToken=aaa")?.bg)
    }

    @Test
    fun buildsUrlWithBgFlag() {
        assertEquals("ilife798://devices/1080/run?bg=false", buildShortcutUrl("ilife798", ShortcutAction.RunDevice, mapOf("id" to "1080")))
        assertEquals(
            "ilife798://devices/1080/run?bg=true",
            buildShortcutUrl(
                "ilife798",
                ShortcutAction.RunDevice,
                mapOf(
                    "id" to "1080",
                    "bg" to "true",
                ),
            ),
        )
        assertEquals("ilife798://tasks/run/all?bg=true", buildShortcutUrl("ilife798", ShortcutAction.RunAllTasks))
        // 凭据 query 与 bg 自适应拼接（& 连接）
        assertEquals(
            "ilife798://accounts/add/token?appToken=aaa&bg=true",
            buildShortcutUrl("ilife798", ShortcutAction.AccountAddToken, mapOf("appToken" to "aaa", "bg" to "true")),
        )
        assertEquals(
            "ilife798://accounts/add/token?appToken=aaa&token=bbb&bg=false",
            buildShortcutUrl("ilife798", ShortcutAction.AccountAddToken, mapOf("appToken" to "aaa", "token" to "bbb")),
        )
        // 不支持 bg 的动作不带 bg 参数
        assertEquals("ilife798://home", buildShortcutUrl("ilife798", ShortcutAction.Home))
    }
}
