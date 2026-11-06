# Changelog

## [v2.2.0] - 2026-11-06

### Added
- **原生每日 GUI 定时任务适配（`workflow.create_daily_gui` / `workflow.get_status`）**：
  - 支持由第三方模型决策每日执行时刻、目标应用与操作指令，组装 `time_fence` + `gui_task_create` 触发任务载荷。
  - 复用 YOYO 原生 `WorkFlowMainChatManager` / `WorkFlowChatHelper` 的 DSL 转换与校验链路，经 `WorkFlowUseCase.addOrUpdateWorkFlow` 写入 `com.hihonor.brain`，并调用 `WorkFlowCardDisplayHelper.showTrialRunCard` 渲染原生定时任务卡片。
  - 新增 `NativeWorkflowCapabilities`：在选用系统默认模型执行真实工作流时自动学习工具能力元数据（`create_trigger_task`、`time_fence`、`gui_task_create`），按当前 YOYO 安装版本校验并剥离旧任务 ID、时间、用户指令与鉴权字段。
  - 每次创建均生成全新的 26 位十进制 `workflowId` 与匹配的 `workflowRaw` 历史结构，并在 DSL 转换后核对 ID 一致性。
  - 支持原生保存回调（`BrainMcpResult.isSuccess()` + `getSucceededIds()`）与只读存储回查（`getWorkFlowListStrictly`）双重确认机制。
- **工作流契约与 DEX 签名校验测试**：
  - 新增 `tests/workflow/TestMetadata.java` 与 `tests/workflow/TestPayload.java` 纯 Java 契约测试（时区、跨日推进、ID 隔离、动态与敏感字段剥离）。
  - 新增 `tests/native-api/CheckWorkflowApi.java`，基于 JADX 原始 `MethodInfo` 核对目标 DEX 的 8 个真实 JVM 方法签名。

### Fixed
- **首轮会话话题 ID 异步落库竞态修复**：
  - 修复首轮文字请求时 `FwConversationTopicManager` 正数话题 ID 尚未完成异步落库导致的 `nativeTopic=false` 与次轮历史丢失问题。
  - 在后台等待真实正数话题 ID（最长 1 秒）的同时监听 UI 话题切换事件，防止误绑切换后的其他话题；无法获取有效 ID 时自动降级为单次隔离调用。
- **加载状态卡片原生化**：
  - 将对话期间的处理提示替换为原生 `AutoLoadingCard`。

---

## [v2.1.0] - 2026-11-04

### Added
- **被动脱敏协议观测器（`NativeTrace`）**：
  - 通过私有目录开关文件 `/data/user/0/com.hihonor.magicvoice/files/yoyo-lsp/native-trace.enabled` 按需启停，仅做只读观测，不修改原生方法入参或返回值。
  - 覆盖 `AiProviderProxy`、`VoiceControlService`、`PlanManager`、`McpNodeTool`、`ActionGroupNodeTool`、`FwContentUiMsgProcessor`、`FwBaseContentPresenter`、`WorkFlowMainChatManager`、`WorkFlowUseCase` 等关键边界。
  - 自动对 `token`、`authorization`、`cookie`、`password`、`apikey`、`secret` 等敏感字段执行 `[REDACTED]` 脱敏，并支持单进程 16 MiB 日志轮转。
- **会话话题隔离与脱敏协议样本**：
  - 按 `FwConversationTopicManager` 正数话题 ID 隔离多轮对话上下文（单进程最多缓存 8 个会话，每会话上限 64 条消息 / 512 KiB）。
  - 新增 `tests/fixtures/native-decoded-qa/` 脱敏应用层协议样本（含推理开始、工具运行、流式增量、最终结果、流结束与任务完成）。

### Fixed
- **默认模型与自定义模型路由严格隔离**：
  - 仅在 `selectedCustom(...)` 判定当前实际选中 Bridge 自定义模型时才将 `ClawConnectManager.getClawWsUrl` 切换至本地网关，避免启用模块后污染系统默认模型路由。

---

## [v2.0.0] - 2026-11-03

### Added
- **独立 LSPosed 模块与原生 Material 设置页（`SettingsActivity`）**：
  - 完全移除旧版 KernelSU / WebUI 依赖，改用纯原生 Android Material 视图构建配置管理界面。
  - 提供 `BridgeSettingsProvider`（`dev.yoyobridge.helper.settings`），仅允许模块自身与 `com.hihonor.magicvoice` UID 调用，状态快照自动剥离 `apiKey` 与 `authorization`。
- **双通道模型桥接与原生手机工具调用**：
  - 支持拦截 `AiProviderProxy.recognizeText` 文字请求并转发至兼容 OpenAI `/v1/chat/completions` 的第三方模型接口，同时保留 `127.0.0.1:38765` OpenClaw WebSocket 网关适配。
  - 在 YOYO 进程内通过 `NativeToolExecutor` 驱动原生 `McpNodeTool`、`gui.create` 与 `task-result-query`，并提供可选的 Root `local.ui`（`RootUiBroker`）回退执行器。
