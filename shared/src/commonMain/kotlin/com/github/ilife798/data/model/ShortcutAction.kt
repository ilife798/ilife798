package com.github.ilife798.data.model

/**
 * 快捷指令 / 外部链接可以触发的动作。
 *
 * 链接采用 REST 风格：资源在前、动作作为子路径，参数放在路径段里，
 * 例如 `ilife798://tasks/run`、`ilife798://devices/1080/stop`、`ilife798://accounts/2`。
 * 除账号凭据（`accounts/add/token`）外不接受 query 形式的参数，也不保留早期的短写法别名。
 */
enum class ShortcutAction(
    /** 资源路径模板，仅用于文档与调试展示。 */
    val path: String,
    /** 给用户看的中文名。 */
    val title: String,
    /** 所属分组，生成页面按此分卡片。 */
    val category: ShortcutCategory,
    /** 需要在链接里填写的参数；为空表示这条链接不需要任何参数。 */
    val params: List<ShortcutParam> = emptyList(),
    /** 是否支持 bg 参数及其缺省行为：null=不支持 bg；true=缺省后台执行；false=缺省进应用内。 */
    val bgDefault: Boolean? = null,
) {
    Home("home", "打开首页", ShortcutCategory.Navigation),
    Tasks("tasks", "打开积分任务", ShortcutCategory.Navigation),
    Score("score", "打开积分", ShortcutCategory.Navigation),
    Bill("bills", "打开账单", ShortcutCategory.Navigation),
    Cards("cards", "打开卡包", ShortcutCategory.Navigation),
    CardScan("cards/scan", "扫卡", ShortcutCategory.Navigation),
    Scan("scan", "扫一扫", ShortcutCategory.Navigation),
    Me("me", "打开我的", ShortcutCategory.Navigation),
    Backup("backup", "打开备份", ShortcutCategory.Navigation),
    Schedule("schedule", "打开定时任务", ShortcutCategory.Navigation),
    Refresh("refresh", "刷新数据", ShortcutCategory.Navigation),

    RunTasks("tasks/run", "运行当前账号任务", ShortcutCategory.Tasks, bgDefault = false),
    RunAllTasks("tasks/run/all", "依次运行全部账号", ShortcutCategory.Tasks, bgDefault = true),
    RunBatch(
        "tasks/run/{uid,…}",
        "依次运行指定账号",
        ShortcutCategory.Tasks,
        listOf(ShortcutParam("uids", "账号 uid", "多个用英文逗号分隔，例如 1001,1002")),
        bgDefault = true,
    ),
    StopTasks("tasks/stop", "停止积分任务", ShortcutCategory.Tasks, bgDefault = false),

    DeviceAdd(
        "devices/add",
        "添加设备",
        ShortcutCategory.Devices,
        listOf(ShortcutParam("id", "设备编号", "可留空，进页面后再填", required = false)),
    ),
    Device(
        "devices/{id}",
        "打开设备",
        ShortcutCategory.Devices,
        listOf(ShortcutParam("id", "设备编号", "例如 1080")),
    ),
    RunDevice(
        "devices/{id}/run",
        "启动设备",
        ShortcutCategory.Devices,
        listOf(ShortcutParam("id", "设备编号", "例如 1080")),
        bgDefault = false,
    ),
    StopDevice(
        "devices/{id}/stop",
        "停止设备",
        ShortcutCategory.Devices,
        listOf(ShortcutParam("id", "设备编号", "例如 1080")),
        bgDefault = false,
    ),

    Account("accounts", "打开账号", ShortcutCategory.Accounts),
    AccountAdd("accounts/add", "添加账号", ShortcutCategory.Accounts),
    AccountAddPhone(
        "accounts/add/phone/{phone}",
        "添加账号并预填手机号",
        ShortcutCategory.Accounts,
        listOf(ShortcutParam("phone", "手机号", "例如 13800001111")),
    ),
    AccountAddToken(
        "accounts/add/token",
        "添加账号并预填凭据",
        ShortcutCategory.Accounts,
        listOf(
            ShortcutParam("appToken", "设备控制凭据", "两个凭据至少填一个", required = false),
            ShortcutParam("token", "积分任务凭据", "两个凭据至少填一个", required = false),
        ),
        bgDefault = false,
    ),
    SwitchAccount(
        "accounts/{index|id}",
        "切换账号",
        ShortcutCategory.Accounts,
        listOf(ShortcutParam("account", "账号", "序号、uid 或手机号")),
    ),
}

/** 动作分组，只影响展示顺序。 */
enum class ShortcutCategory(
    val title: String,
) {
    Navigation("页面"),
    Tasks("积分任务"),
    Devices("设备"),
    Accounts("账号"),
}

/**
 * 一条动作需要的参数。
 *
 * [required] 为 false 时允许留空——例如添加设备可以不预填编号，
 * 添加账号至少要填一个凭据（这条规则在 [isSatisfied] 里单独判断）。
 */
data class ShortcutParam(
    val key: String,
    val label: String,
    val placeholder: String = "",
    val required: Boolean = true,
)

/**
 * 参数是否满足生成条件。
 *
 * 凭据这条是例外：两个槽位都可选，但必须至少填一个，否则链接解析端会直接拒绝。
 */
fun ShortcutAction.isSatisfied(values: Map<String, String>): Boolean {
    if (this == ShortcutAction.AccountAddToken) {
        return values["appToken"].orEmpty().isNotBlank() || values["token"].orEmpty().isNotBlank()
    }
    return params.filter { it.required }.all { values[it.key].orEmpty().isNotBlank() }
}

/**
 * 按 [values] 拼出可直接打开的链接。
 *
 * 参数一律放在路径段里（凭据除外，见下），值做百分号转义，避免用户填了 `/`、空格之类的字符
 * 把路径切断。`accounts/add/token` 是唯一使用 query 的动作，因为凭据里出现 `/` 很常见。
 */
fun buildShortcutUrl(
    scheme: String,
    action: ShortcutAction,
    values: Map<String, String> = emptyMap(),
): String {
    fun value(key: String): String = values[key].orEmpty().trim()

    val base =
        when (action) {
            ShortcutAction.AccountAddToken -> {
                val pairs =
                    action.params
                        .map { it.key to value(it.key) }
                        .filter { it.second.isNotEmpty() }
                        .joinToString("&") { (key, raw) -> "$key=${percentEncode(raw)}" }
                if (pairs.isEmpty()) "$scheme://${action.path}" else "$scheme://${action.path}?$pairs"
            }

            ShortcutAction.RunBatch -> {
                val uids = value("uids").replace(" ", "").replace("，", ",").trim(',')
                if (uids.isEmpty()) "$scheme://tasks/run" else "$scheme://tasks/run/${percentEncode(uids)}"
            }

            ShortcutAction.DeviceAdd -> {
                val id = value("id")
                if (id.isEmpty()) "$scheme://devices/add" else "$scheme://devices/add/${percentEncode(id)}"
            }

            ShortcutAction.Device -> {
                "$scheme://devices/${percentEncode(value("id"))}"
            }

            ShortcutAction.StopDevice -> {
                "$scheme://devices/${percentEncode(value("id"))}/stop"
            }

            ShortcutAction.RunDevice -> {
                "$scheme://devices/${percentEncode(value("id"))}/run"
            }

            ShortcutAction.AccountAddPhone -> {
                "$scheme://accounts/add/phone/${percentEncode(value("phone"))}"
            }

            ShortcutAction.SwitchAccount -> {
                "$scheme://accounts/${percentEncode(value("account"))}"
            }

            else -> {
                "$scheme://${action.path}"
            }
        }
    val bgDefault = action.bgDefault ?: return base
    val bg = value("bg").let { if (it.isEmpty()) bgDefault else it.equals("true", true) }
    val separator = if (base.contains('?')) '&' else '?'
    return "$base${separator}bg=$bg"
}

/** 只转义会破坏路径语义的字符，其余原样保留，链接更易读。 */
private fun percentEncode(raw: String): String {
    val builder = StringBuilder()
    for (byte in raw.encodeToByteArray()) {
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (char.isLetterOrDigit() || char in ENCODE_SAFE) {
            builder.append(char)
        } else {
            builder.append('%').append(value.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return builder.toString()
}

private const val ENCODE_SAFE = "-._~,:@"

/** 解析后的请求；[args] 里的键值来自路径段（少量来自 query）。 */
data class ShortcutRequest(
    val action: ShortcutAction,
    val args: Map<String, String> = emptyMap(),
    /** query 里的 bg 标志：仅 ?bg=true 为 true；null 表示未指定，按动作的 [ShortcutAction.bgDefault] 处理。 */
    val bg: Boolean? = null,
) {
    fun arg(name: String): String? = args[name]?.takeIf { it.isNotBlank() }

    fun intArg(name: String): Int? = arg(name)?.toIntOrNull()

    /** `tasks/run/1001,1002` 里逗号分隔的 uid；未指定表示只跑当前账号。 */
    fun uidList(): List<String>? =
        arg("uids")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
}

/**
 * 解析 `ilife798://…` 链接。纯函数、不依赖平台 API，因此可以直接单测。
 *
 * 协议头有两个等价写法：[SHORTCUT_SCHEMES]。路径部分接受 `://`、`:///`、`:` 三种分隔，
 * 末尾斜杠与大小写差异都不影响解析。
 */
fun parseShortcutUrl(url: String): ShortcutRequest? {
    val trimmed = url.trim()
    val rest = stripScheme(trimmed) ?: return null
    val pathPart = rest.substringBefore('?')
    val query = parseQuery(rest.substringAfter('?', ""))
    val segments =
        pathPart
            .trim('/')
            .split('/')
            .map { percentDecode(it).trim().lowercase() }
            .filter { it.isNotEmpty() }
    if (segments.isEmpty()) return null
    return route(segments, query)
}

// 长的放前面，避免短协议头抢先匹配
private fun stripScheme(url: String): String? {
    for (scheme in SHORTCUT_SCHEMES) {
        if (url.startsWith("$scheme://", ignoreCase = true)) return url.substring(scheme.length + 3)
        if (url.startsWith("$scheme:", ignoreCase = true)) return url.substring(scheme.length + 1)
    }
    return null
}

private fun route(
    segments: List<String>,
    query: Map<String, String>,
): ShortcutRequest? {
    val head = segments.firstOrNull() ?: return null
    val rest = segments.drop(1)

    fun request(
        action: ShortcutAction,
        vararg extra: Pair<String, String>,
    ): ShortcutRequest = ShortcutRequest(action, extra.toMap(), query["bg"]?.equals("true", true))

    return when (head) {
        "home" -> {
            request(ShortcutAction.Home)
        }

        "tasks" -> {
            when (rest.firstOrNull()) {
                null -> {
                    request(ShortcutAction.Tasks)
                }

                "run" -> {
                    when {
                        // tasks/run/all：字面量 all 表示全部账号，不当作 uid
                        rest.getOrNull(1) == "all" && rest.size == 2 -> request(ShortcutAction.RunAllTasks)

                        rest.size > 1 -> request(ShortcutAction.RunBatch, "uids" to rest.drop(1).joinToString(","))

                        else -> request(ShortcutAction.RunTasks)
                    }
                }

                "stop" -> {
                    request(ShortcutAction.StopTasks)
                }

                else -> {
                    null
                }
            }
        }

        "score" -> {
            request(ShortcutAction.Score)
        }

        "bills" -> {
            request(ShortcutAction.Bill)
        }

        "cards" -> {
            if (rest.firstOrNull() == "scan") request(ShortcutAction.CardScan) else request(ShortcutAction.Cards)
        }

        "scan" -> {
            request(ShortcutAction.Scan)
        }

        "devices" -> {
            when {
                // devices、devices/new、devices/add 都是「添加设备」
                rest.isEmpty() || rest[0] == "new" || rest[0] == "add" -> {
                    val prefill = rest.getOrNull(1)
                    if (prefill.isNullOrEmpty()) {
                        request(ShortcutAction.DeviceAdd)
                    } else {
                        request(ShortcutAction.DeviceAdd, "id" to prefill)
                    }
                }

                rest.size >= 2 && rest[1] == "stop" -> {
                    request(ShortcutAction.StopDevice, "id" to rest[0])
                }

                rest.size >= 2 && rest[1] == "run" -> {
                    request(ShortcutAction.RunDevice, "id" to rest[0])
                }

                else -> {
                    request(ShortcutAction.Device, "id" to rest[0])
                }
            }
        }

        "accounts" -> {
            when (rest.firstOrNull()) {
                null -> {
                    request(ShortcutAction.Account)
                }

                "add" -> {
                    when (rest.getOrNull(1)) {
                        null -> {
                            request(ShortcutAction.AccountAdd)
                        }

                        "phone" -> {
                            val phone = rest.getOrNull(2) ?: return null
                            request(ShortcutAction.AccountAddPhone, "phone" to phone)
                        }

                        "token" -> {
                            // 凭据放在路径里容易被日志/历史记录留痕，这里允许 query 形式
                            val appToken = query["apptoken"] ?: rest.getOrNull(2).orEmpty()
                            val token = query["token"] ?: rest.getOrNull(3).orEmpty()
                            if (appToken.isEmpty() && token.isEmpty()) return null
                            request(
                                ShortcutAction.AccountAddToken,
                                "appToken" to appToken,
                                "token" to token,
                            )
                        }

                        else -> {
                            null
                        }
                    }
                }

                // accounts/{index|id}：数字优先当作序号，其余当作 uid，由界面层解析
                else -> {
                    request(ShortcutAction.SwitchAccount, "account" to rest[0])
                }
            }
        }

        "me" -> {
            request(ShortcutAction.Me)
        }

        "backup" -> {
            request(ShortcutAction.Backup)
        }

        "schedule" -> {
            request(ShortcutAction.Schedule)
        }

        "refresh" -> {
            request(ShortcutAction.Refresh)
        }

        else -> {
            null
        }
    }
}

private fun parseQuery(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val result = mutableMapOf<String, String>()
    for (pair in raw.split('&')) {
        val index = pair.indexOf('=')
        if (index <= 0) continue
        val key = percentDecode(pair.substring(0, index)).trim().lowercase()
        if (key.isEmpty()) continue
        result[key] = percentDecode(pair.substring(index + 1)).trim()
    }
    return result
}

/** 只处理 `%XX` 与 `+`，其余按 UTF-8 原样保留——够覆盖路径段里的转义。 */
private fun percentDecode(raw: String): String {
    if ('%' !in raw && '+' !in raw) return raw
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < raw.length) {
        val char = raw[i]
        if (char == '%' && i + 2 < raw.length) {
            val hex = raw.substring(i + 1, i + 3)
            val value = hex.toIntOrNull(16)
            if (value != null) {
                bytes += value.toByte()
                i += 3
                continue
            }
        }
        if (char == '+') {
            bytes += ' '.code.toByte()
            i++
            continue
        }
        bytes += char.toString().encodeToByteArray().toList()
        i++
    }
    return bytes.toByteArray().decodeToString()
}

/** 主协议头。 */
const val SHORTCUT_SCHEME: String = "ilife798"

/** 全部可用的协议头，按长度降序排列以便前缀匹配。 */
val SHORTCUT_SCHEMES: List<String> = listOf("com.github.ilife798", "ilife798")

/** 生成链接时的默认协议头。 */
const val DEFAULT_SHORTCUT_SCHEME: String = "com.github.ilife798"
