# YOYO 原生提示词来源与端云职责核查

针对“为什么第三方模型桥接未直接复用 YOYO 原版系统提示词”这一问题，我们对目标 APK（`com.hihonor.magicvoice` `90.10.35.036`）的字节码及运行时 `startDialog` 通信载荷进行了静态与动态交叉核查。

## 1. 核心结论

1. **客户端内存在的提示词模板（`PlanModelPromptBuilder`）属于端侧 VLM 工具规划器，而非云端主对话 System Prompt**：
   - 在 `classes40.dex` 中，`com.hihonor.aipluginengine.nlu.ondeviceai.business.aimm.TaskPlanModel` 实例化了 `PlanModelPromptBuilder("/data/res/vlm/template.json")`（若本地覆盖文件不存在，则回退至内置常量 `PromptTemplateConstant.DEFAULT_PROMPT_CONFIG`）。
   - `TaskPlanModel` 读取 `AssistantMessage` 的 AIMM 会话历史与候选工具描述，组装提示词后调用 `AimmVLMService.getVLMInferenceResult(prompt, "type_intent-tools-arrange", callback)`，超时上限为 3 秒，输出格式是以 `DSL:<|im_end|>` 结尾的受限工具编排 DSL，并解析为 `DslBean` 列表。
2. **云端主对话（`startDialog`）不在客户端拼装完整 System Prompt**：
   - 运行时观测到的 `startDialog` WebSocket 封包仅上传用户输入文本、`contexts`（设备与界面上下文）、`events` 和 `session` 元数据，不包含 `system` / `prompt` / `messages` 形式的完整大模型提示词。
   - 主对话的系统提示词组装、多阶段规划与安全策略均在荣耀云端内部完成，客户端无法从请求封包中直接提取并转发给第三方模型。
3. **Bridge 采用独立兼容提示词的原因**：
   - `Bridge.AgentSession.systemPrompt()` 面向标准 OpenAI Chat Completions 接口与 Function Calling（Tool Use）协议，负责指导第三方模型在自然语言回复、`McpNodeTool`、`gui.create` / `task-result-query` 以及 `workflow.create_daily_gui` 之间做出决策；而 APK 内置的 `PromptTemplateConstant` 仅适用于特定 LoRA（`type_intent-tools-arrange`）的端侧 DSL 语法生成，二者的模型任务与输出协议完全不同。

## 2. 证据分级汇总

| 核查项 | 证据等级 | 详细说明 |
| --- | --- | --- |
| `TaskPlanModel` -> `PlanModelPromptBuilder` 调用链 | **已确认（APK 静态字节码）** | `TaskPlanModel.getRawDslResult()` 调用 `buildPrompt()` 后传入 `AimmVLMService.getVLMInferenceResult()`，解析返回值为 `DslBean`。 |
| `PromptTemplateConstant.DEFAULT_PROMPT_CONFIG` 语义 | **已确认（模板内容）** | 角色映射为“历史编排/系统答复”，要求在候选工具集中组织任务并输出受限 DSL，以 `DSL:<|im_end|>` 结束。 |
| `/data/res/vlm/template.json` 本地覆盖文件 | **已确认（真机只读检查）** | 测试设备默认不存在该外部覆盖文件，运行时回退至 DEX 内置模板常量。 |
| `startDialog` 云端主对话请求结构 | **已确认（运行时观测）** | 发送帧包含 `contexts`、`events`、`session`、`endpoint`，不含客户端预组装的完整 System Prompt。 |
| `agent_local*.json` 中的 `prompt` 字段 | **待验证（仅配置定义）** | 仅确认为本地业务配置 JSON 中的字段名，未发现其作为主对话 System Prompt 传入云端 LLM 的调用路径。 |
