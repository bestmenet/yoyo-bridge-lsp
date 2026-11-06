# YOYO Next 原生协议边界与架构分析

本文档总结了针对荣耀 YOYO Next（测试基线版本：`com.hihonor.magicvoice` `90.10.35.036` / `901035036`，MagicOS 11 / Android 17）的静态字节码分析与运行时被动观测结论，明确“模型决策”“手机工具执行”“定时工作流编排”与“特殊 UI 卡片渲染”在客户端与荣耀云端之间的真实边界。

## 1. 主对话文字请求链路

### 1.1 静态与运行时调用链

当用户在 YOYO 主对话界面发送文字消息时，客户端内部经历以下调用链：

```text
AiProviderProxy.recognizeText(Intent)
  └─> VoiceControlService.recognizeText(Intent)
        └─> VoiceKitSdk.startRecognize(Intent)
              └─> VoiceKitSdkImpl.startRecognize(Intent)
                    └─> RecognizeAbilityProxy.startNluAnalyze(String, Intent)
                          └─> CloudAbilityProxy.startNluAnalyze(String, Intent)
                                └─> HiVoiceRecognizer.startTextRecognize(Bundle)
                                      └─> HonorVoiceWsClient.startTextRecognize(Bundle)
                                            └─> sendTextStreamEventRequest(Bundle, VoiceAccessAuthResp)
                                                  └─> sendDataWhenOpen(String, Bundle)
                                                        └─> WebSocketClient.send(String)
```

- `CloudAbilityProxy.startNluAnalyze` 将会话标识（`sessionId`、`interactionId`、`dialogId`、`checkId`）与输入文本/上下文组装成 `DialogRequestParam`。
- `HonorVoiceWsClient.sendDataWhenOpen` 将请求封装为 `startDialog` WebSocket 文本帧发送至云端，后续通过 `eventNotify` 回传上下文刷新与工具执行结果。
- 响应侧由 `HonorVoiceWsClient$1.onMessage(WebSocket, String)` 进入 `dealOnMessage(WebSocket, Bundle, String)`：
  - 根据 `msgType` 分流 ASR、NLU、`node*` 消息（经 `WsMessageRouter` 转发至 `NodeWsMessageCallback.onNodeMessage`）以及通用对话管理（DM）消息（经 `CommonDmResultHandler.handleDmResult` 转换为 `VoiceKitMessage`）。
  - 最终通过 `VoiceControlService$KitBaseRecognizeListener.onPartialResult` / `onResult` 交付应用层，驱动 `PlanManager.addKitMsg`、`FwContentUiMsgProcessor.handleLLMStreamOut` 与卡片 Presenter。

### 1.2 核心边界结论

- **主对话并非纯 LLM 推理透传接口**：客户端发出的 `startDialog` 请求信封包含 `contexts`、`events`、`session`、`endpoint` 等语音/多模态对话服务字段；云端返回的是包含 `AgentState`、`ToolCallHint`、`MmqaResultAH.commonQA` 流块、`IntentService.Summary` 与 `FinishTask` 的复合编排事件流。
- **客户端内未暴露独立的云端“仅模型推理”接口**：在 `VoiceControlService` 主对话链路中，服务端同时承担了意图分流、任务规划、工具编排与流式回答封装，因此无法仅靠替换某一个出站 HTTP URL 就无损接管所有云端编排行为，而必须由 Bridge 将第三方模型的 `chat.completions` 决策翻译为客户端可执行的工具调用与卡片事件。

---

## 2. 手机工具调用的三条分流路径

在 YOYO 客户端内部，手机工具与系统操作并非单一机制，而是分为三条不同路径：

| 路径类型 | 典型指令 / 标识 | 客户端核心处理类 | 机制说明 |
| --- | --- | --- | --- |
| **Client Domain Action** | `Tool.systemControl.*`<br>`Tool.yoyoTask.yoyotask_process` | `ActionGroupNodeTool.execute`<br>`PlatformAppMgr.SDK.submitIntentionAction`<br>`WorkFlowActionGroup.startWorkCreateForMain` | 由 `@Action(name=..., nameSpace=...)` 注解的方法处理系统控制或拉起子业务状态机。 |
| **MCP 工具调用** | 应用/系统级 MCP 工具 | `McpNodeTool.execute`<br>`HonorMcpSyncAgentClient` | 通过 `executeToolAsync` / `executeToolSync` 调用本机 MCP 工具，由 `NodeToolCallback` 异步回传结果。 |
| **GUI 自动化与 OpenClaw Node** | `gui.create`<br>`task-result-query`<br>`nodeInvokeRequest` | `GuiHandler`<br>`TaskResultQueryHandler`<br>`WsMessageRouter` | 创建异步屏幕操控任务并轮询执行状态，或通过网关 WebSocket 接收 `node.invoke.request`。 |

---

## 3. 原生定时任务（YOYO 工作流）链路与 2.2 适配方案

### 3.1 原生定时任务创建链路

YOYO 原生定时任务（非系统闹钟）具有独立的工作流协议与持久化通道：

1. 主对话下发 `Tool.yoyoTask.yoyotask_process` 指令，触发 `WorkFlowActionGroup.startWorkCreateForMain(MainChatPayload)`。
2. 客户端进入 `WorkFlowMainChatManager`，通过工作流专用 WebSocket 通道接收 `Tool.yoyoTask / create_trigger_task` 事件（内含 `time_fence` 触发器与 `gui_task_create` 动作）。
3. 客户端调用原生转换器将任务结构转换为 DSL，并执行 `validateGroupTasks` 校验。
4. 客户端通过 `WorkFlowUseCase.addOrUpdateWorkFlow` 经由 `MCPClientManager` 向 `com.hihonor.brain` 提交持久化保存，回调返回 `BrainMcpResult`（真实 JVM 方法为 `isSuccess()` 与 `getSucceededIds()`）。
5. 保存成功后，客户端在主界面通过 `WorkFlowCardDisplayHelper.showTrialRunCard` 渲染包含“条件、操作、自动执行、编辑、执行一次”的原生定时任务卡片；到点后由系统后台自动唤醒目标应用并执行 GUI 任务。

### 3.2 Bridge 2.2 的适配边界

- **第三方模型负责决策**：解析用户自然语言意图，提取每日执行时刻（`hour`、`minute`）、目标应用（`app_name` / `package_name`）与任务描述（`task_query`），调用 `workflow.create_daily_gui`。
- **能力元数据动态自学习**：`NativeWorkflowCapabilities` 从默认模型真实工作流响应中提取 `create_trigger_task`、`time_fence`、`gui_task_create` 的静态插件标识与版本元数据，自动剥离 `membershipInfo`、`workflowId`、`workflowRaw`、`start_datetime`、`skillDesc` 等个人或动态字段，并与当前安装的 `com.hihonor.magicvoice` 版本号绑定。
- **复用原生保存与卡片渲染**：`NativeWorkflowExecutor` 为每次请求生成全新的 26 位十进制 `workflowId` 与匹配的 `workflowRaw` 历史，调用原生 DSL 转换、校验、`WorkFlowUseCase.addOrUpdateWorkFlow` 保存及 `WorkFlowCardDisplayHelper.showTrialRunCard` 原生卡片渲染，并通过原生回调或 `getWorkFlowListStrictly` 只读回查验证落库结果。
- **当前依赖说明**：DSL 转换环节仍调用 YOYO 原生工作流通道，后续到点 GUI 操控由 YOYO 原生执行器完成。

---

## 4. 关键观测点速查表

| 阶段 | 类名与方法签名 | 用途 |
| --- | --- | --- |
| 文本入口拦截 | `com.hihonor.magicvoice.phonebase.sdkframe.sdk.AiProviderProxy#recognizeText(Intent)` | Bridge 自定义模型拦截入口与会话初始化。 |
| 云端请求构造 | `com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.abilityconnector.recognizer.cloud.CloudAbilityProxy#startNluAnalyze(String, Intent)` | 观察进入语音 SDK 前的文本与会话参数。 |
| WS 发送边界 | `com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.abilityconnector.recognizer.cloud.recognizer.HonorVoiceWsClient#sendDataWhenOpen(String, Bundle)` | 观察合并后的 `startDialog` 请求帧。 |
| WS 响应分流 | `com.hihonor.aipluginengine.voiceservice.biz.voicecontrol.abilityconnector.recognizer.cloud.recognizer.HonorVoiceWsClient#dealOnMessage(WebSocket, Bundle, String)` | 区分 ASR、NLU、`node*` 与 DM 响应。 |
| 应用层消息交付 | `com.hihonor.magicvoice.phonebase.plan.PlanManager#addKitMsg(VoiceKitMessage)` | 观察 `AgentState`、`ToolCallHint`、`commonQA`、`Summary` 与 `FinishTask`。 |
| 话题 ID 管理 | `com.hihonor.magicvoice.frameworkui.topic.FwConversationTopicManager` | 获取当前会话正数话题 ID，隔离多轮上下文。 |
| 工作流保存与回查 | `com.hihonor.magicvoice.yoyotask.shared.workflow.WorkFlowUseCase#addOrUpdateWorkFlow` / `getWorkFlowListStrictly` | 原生定时任务持久化与只读状态核实。 |
| 工作流卡片渲染 | `com.hihonor.magicvoice.yoyotask.shared.chat.WorkFlowCardDisplayHelper#showTrialRunCard` | 在聊天流中展示原生定时任务卡片。 |
