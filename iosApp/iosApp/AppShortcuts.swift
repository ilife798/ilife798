import AppIntents
import SwiftUI
import shared

// iOS 快捷指令（App Intents）接入层。
//
// 分工：这里只负责「让用户在 Siri / Spotlight / 快捷指令里能选到东西」，
// 真正的动作执行一律交回 Kotlin——把选择拼成一条 ilife798:// 链接，
// 交给 IosShortcuts.handle(url:) 解析派发，与 Android 的 handleIntent、
// 应用内「打开 URL」走同一条已经测过的路径。这样动作语义只有一份实现，
// 不会出现「快捷指令能做、链接做不到」的分叉。
//
// 代价：Intent 需要应用回到前台才能被 Compose 侧的 AppIntentEffects 消费，
// 所以下面每个 Intent 都显式 openAppWhenRun = true。真正的无界面执行要等
// Kotlin 侧抽出独立的执行层，那是另一件事，不在这一版范围里。

// MARK: - 设备实体

/// 快捷指令里可选的设备。
///
/// 数据来自上次首页加载时写下的快照（无网络也能选），动作执行时再由 Kotlin 侧
/// 校验编号是否仍然有效——拿旧列表让人选、再让动作自己失败，比下拉空白好。
struct DeviceEntity: AppEntity {
    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "设备")
    static let defaultQuery = DeviceEntityQuery()

    let id: String
    let name: String
    let detail: String

    init(id: String, name: String, detail: String) {
        self.id = id
        self.name = name
        self.detail = detail
    }

    init(choice: ShortcutChoice) {
        self.id = choice.key
        self.name = choice.title
        self.detail = choice.subtitle
    }

    var displayRepresentation: DisplayRepresentation {
        // 显式分支而不是三元：三元里的 nil 要让编译器同时推断
        // LocalizedStringResource 与可选类型，容易变成 "ambiguous without more context"。
        if detail.isEmpty {
            return DisplayRepresentation(title: LocalizedStringResource(stringLiteral: name))
        }
        return DisplayRepresentation(
            title: LocalizedStringResource(stringLiteral: name),
            subtitle: LocalizedStringResource(stringLiteral: detail)
        )
    }
}

struct DeviceEntityQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [DeviceEntity] {
        IosShortcuts.shared.devices()
            .map { DeviceEntity(choice: $0) }
            .filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [DeviceEntity] {
        IosShortcuts.shared.devices().map { DeviceEntity(choice: $0) }
    }
}

// MARK: - 账号实体

/// 快捷指令里可选的账号。id 用账号列表下标：切换账号的链接本来就接受序号，
/// 而只登录了设备控制通道的账号没有 uid，下标总是有效。
struct AccountEntity: AppEntity {
    static let typeDisplayRepresentation = TypeDisplayRepresentation(name: "账号")
    static let defaultQuery = AccountEntityQuery()

    let id: String
    let name: String
    let detail: String

    init(id: String, name: String, detail: String) {
        self.id = id
        self.name = name
        self.detail = detail
    }

    init(choice: ShortcutChoice) {
        self.id = choice.key
        self.name = choice.title
        self.detail = choice.subtitle
    }

    var displayRepresentation: DisplayRepresentation {
        // 显式分支而不是三元：三元里的 nil 要让编译器同时推断
        // LocalizedStringResource 与可选类型，容易变成 "ambiguous without more context"。
        if detail.isEmpty {
            return DisplayRepresentation(title: LocalizedStringResource(stringLiteral: name))
        }
        return DisplayRepresentation(
            title: LocalizedStringResource(stringLiteral: name),
            subtitle: LocalizedStringResource(stringLiteral: detail)
        )
    }
}

struct AccountEntityQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [AccountEntity] {
        IosShortcuts.shared.accounts()
            .map { AccountEntity(choice: $0) }
            .filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [AccountEntity] {
        IosShortcuts.shared.accounts().map { AccountEntity(choice: $0) }
    }
}

// MARK: - 统一的派发

/// 把链接交给 Kotlin。返回值只用来决定要不要提示「没识别」，不影响动作本身。
private func dispatch(_ url: String) -> Bool {
    IosShortcuts.shared.handle(url: url)
}

// MARK: - 积分任务

struct RunTasksIntent: AppIntent {
    static var title: LocalizedStringResource = "运行当前账号任务"
    static var description = IntentDescription("在当前账号上开始执行积分任务，不跳转页面。")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult & ProvidesDialog {
        // 不带 bg：进任务页并显示进度。Intent 必须把应用拉到前台才能被 Compose 消费，
        // 既然已经在前台了，让用户看见正在跑比「悄悄跑」更诚实。
        let ok = dispatch("ilife798://tasks/run")
        return .result(dialog: ok ? "已开始运行当前账号任务" : "链接未被识别，任务没有开始")
    }
}

struct RunAllTasksIntent: AppIntent {
    static var title: LocalizedStringResource = "运行全部账号任务"
    static var description = IntentDescription("依次执行所有已登录账号的积分任务。")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let ok = dispatch("ilife798://tasks/run/all")
        return .result(dialog: ok ? "已开始依次运行全部账号" : "链接未被识别，任务没有开始")
    }
}

struct StopTasksIntent: AppIntent {
    static var title: LocalizedStringResource = "停止积分任务"
    static var description = IntentDescription("停止正在执行的积分任务。")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let ok = dispatch("ilife798://tasks/stop")
        return .result(dialog: ok ? "已停止积分任务" : "链接未被识别，无法停止")
    }
}

// MARK: - 设备

struct RunDeviceIntent: AppIntent {
    static var title: LocalizedStringResource = "启动设备"
    static var description = IntentDescription("启动指定设备。")
    static var openAppWhenRun: Bool = true

    @Parameter(title: "设备")
    var device: DeviceEntity

    static var parameterSummary: some ParameterSummary {
        Summary("启动 \(\.$device)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let ok = dispatch("ilife798://devices/\(device.id)/run")
        return .result(dialog: ok ? "已开始启动 \(device.name)" : "链接未被识别，设备没有启动")
    }
}

struct StopDeviceIntent: AppIntent {
    static var title: LocalizedStringResource = "停止设备"
    static var description = IntentDescription("停止指定设备。")
    static var openAppWhenRun: Bool = true

    @Parameter(title: "设备")
    var device: DeviceEntity

    static var parameterSummary: some ParameterSummary {
        Summary("停止 \(\.$device)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let ok = dispatch("ilife798://devices/\(device.id)/stop")
        return .result(dialog: ok ? "已停止 \(device.name)" : "链接未被识别，无法停止")
    }
}

// MARK: - 账号

/// 静默导入一条设备控制凭据。
///
/// 只收一个凭据槽位：iOS 16 的 Intent 还不支持可选参数，硬塞两个必填参数会逼用户
/// 填他根本没有的那条。需要同时导入设备控制与积分两条凭据时，仍走
/// accounts/add/token 链接（详情页里能生成）。
struct AddAccountIntent: AppIntent {
    static var title: LocalizedStringResource = "添加账号"
    static var description = IntentDescription("用设备控制凭据直接添加账号，不跳转页面。")
    static var openAppWhenRun: Bool = true

    @Parameter(title: "设备控制凭据")
    var appToken: String

    static var parameterSummary: some ParameterSummary {
        Summary("添加账号，设备控制凭据 \(\.$appToken)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        // 凭据里常出现 -._~ 之类的字符，和 Kotlin 侧 percentEncode 的白名单保持一致，
        // 否则会多转义一层，服务端拿到的凭据就变了。
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let encoded = appToken.addingPercentEncoding(withAllowedCharacters: allowed) ?? appToken
        // bg=true：静默导入，不把用户丢到登录页
        let ok = dispatch("ilife798://accounts/add/token?appToken=\(encoded)&bg=true")
        return .result(dialog: ok ? "已提交导入，结果请在应用内查看" : "链接未被识别，账号没有导入")
    }
}

struct SwitchAccountIntent: AppIntent {
    static var title: LocalizedStringResource = "切换账号"
    static var description = IntentDescription("切换到指定账号。")
    static var openAppWhenRun: Bool = true

    @Parameter(title: "账号")
    var account: AccountEntity

    static var parameterSummary: some ParameterSummary {
        Summary("切换到 \(\.$account)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let ok = dispatch("ilife798://accounts/\(account.id)")
        return .result(dialog: ok ? "已切换到 \(account.name)" : "链接未被识别，未切换账号")
    }
}

// MARK: - 刷新

struct RefreshIntent: AppIntent {
    static var title: LocalizedStringResource = "刷新数据"
    static var description = IntentDescription("重新拉取账号、设备与积分数据。")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let ok = dispatch("ilife798://refresh")
        return .result(dialog: ok ? "已开始刷新" : "链接未被识别，没有刷新")
    }
}

// MARK: - 短语注册

/// 注册到系统的快捷指令。
///
/// 只放 8 条：Siri 里短语越多越难叫准，其余动作继续用「打开 URL」——
/// 它们本来就要用户填参数，做成短语反而更麻烦。
struct ILifeShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: RunTasksIntent(),
            phrases: ["用 \(.applicationName) 运行任务"],
            shortTitle: "运行任务",
            systemImageName: "play.circle"
        )
        AppShortcut(
            intent: RunAllTasksIntent(),
            phrases: ["用 \(.applicationName) 运行全部账号"],
            shortTitle: "运行全部账号",
            systemImageName: "play.circle.fill"
        )
        AppShortcut(
            intent: StopTasksIntent(),
            phrases: ["用 \(.applicationName) 停止任务"],
            shortTitle: "停止任务",
            systemImageName: "stop.circle"
        )
        AppShortcut(
            intent: RunDeviceIntent(),
            phrases: ["用 \(.applicationName) 启动设备"],
            shortTitle: "启动设备",
            systemImageName: "power"
        )
        AppShortcut(
            intent: StopDeviceIntent(),
            phrases: ["用 \(.applicationName) 停止设备"],
            shortTitle: "停止设备",
            systemImageName: "poweroff"
        )
        AppShortcut(
            intent: AddAccountIntent(),
            phrases: ["用 \(.applicationName) 添加账号"],
            shortTitle: "添加账号",
            systemImageName: "person.badge.plus"
        )
        AppShortcut(
            intent: SwitchAccountIntent(),
            phrases: ["用 \(.applicationName) 切换账号"],
            shortTitle: "切换账号",
            systemImageName: "person.2"
        )
        AppShortcut(
            intent: RefreshIntent(),
            phrases: ["用 \(.applicationName) 刷新数据"],
            shortTitle: "刷新数据",
            systemImageName: "arrow.clockwise"
        )
    }

    static var shortcutTileColor: ShortcutTileColor = .blue
}
