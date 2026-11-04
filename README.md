# YOYO Bridge LSPosed 2.1.0（协议观测与会话修复）

独立 LSPosed APK。管理页使用 Android 原生 Material Views，不含 WebView 或 WebUI。桥接网关由模块注入 YOYO 主进程，在 Application.attach 后启动，随 YOYO 进程退出。

适配目标：荣耀 PPG-AN00、MagicOS 11 / Android 17、YOYO 90.10.35.036。后续 YOYO 版本需重新核对类名、协议与工具参数。

## 使用

安装 APK，在 LSPosed 启用模块，作用域选择 com.hihonor.magicvoice。打开桌面“YOYO Bridge”，填写完整 Chat Completions endpoint、模型 ID 和 API key，保存并应用。连接测试通过后点击“激活 Next Pro”。重开 YOYO，在 PRO 菜单确认“自定义模型”。首次启用或修改连接配置后只需重开 YOYO 进程，无须整机重启。

密钥仅保存在管理 APK 和 YOYO 各自私密目录中，不内置到源码或安装包，不在界面回显。Provider 仅允许管理 APK 自身 UID 与 YOYO UID 访问。控制广播要求模块签名权限。

## 原理

LSPosed 保留 yoyo_bridge 模型入口，并在 PRO 选择本模块时拦截 AiProviderProxy 的实际文字请求。原生聊天界面显示用户输入和模型回复，自定义模型调用配置中的 Chat Completions 接口。工具由同一个 YOYO 进程直接执行。另保留本机 127.0.0.1:38765 的 OpenClaw WebSocket 协议适配，以支持原生网关入口。

文字请求的 MCP 工具通过原 McpNodeTool 执行；网关请求使用 nodeInvokeRequest/nodeInvokeResult。原生 GUI 任务单独暴露 gui.create（app_name、task_query）和 task-result-query（task_id），使用 YOYO 原有 GUI 执行器。模型必须查询异步任务最终结果，才能判断完成。

原项目的云端 GUI 门控、本地应用支持列表和关闭状态 hook 随模块保留。这些修改影响 YOYO 本地检查，不能保证荣耀服务端接受任意任务。

## 构建与测试

运行 python3 build_lsp.py。需要 JDK、Android SDK android.jar、aapt2、zipalign、apksigner；工具路径可用环境变量覆盖。脚本下载并校验固定版本 R8/JSON JAR。Xposed API stub 不会打入 APK。

签名 keystore 在 build/helper/helper.keystore，缺失时生成本地开发证书；覆盖安装需要复用原证书。

运行 BRIDGE_JSON_JAR="$PWD/build/tools/json.jar" python3 tests/test_backend.py。自动测试验证本地协议 fixture；真机模型、GUI 执行和微信状态的结果见验收报告。

## 本次协议调查范围

2.1.0 按 YOYO 原生 UI 的 FwConversationTopicManager 正数话题 ID 隔离第三方模型历史。未建立有效话题 ID 时使用单次隔离调用，避免负数哨兵串话。在当前进程中最多保留 8 个会话，每会话 64 条消息和 512 KiB 历史；进程重启不恢复该缓存，也尚未导入之前由荣耀模型产生的历史。模型、端点、密钥或执行配置变化会重建对应会话。

启用本机执行器不会隐藏 gui.create / task-result-query。原生工具优先，本机 UI 操作只作可选回退。YOYO 原生定时任务必须经过实际工作流工具与保存链路，不能用闹钟代替。网关 URL 只在实际选中 Bridge 自定义模型时切到本地。

可选协议记录：在 YOYO 私有 files/yoyo-lsp/ 下创建 native-trace.enabled 标记启用，移除即停止写入。每进程一个 native-trace-<pid>.private.jsonl，16 MiB 轮转保留一份旧文件。原始日志可能包含个人上下文，只留在私有目录，禁止随 APK 或源码分享。正式使用前关闭记录。记录器只观察，不修改原生方法参数和结果。

本版本尚未实现完整的荣耀主对话、工作流和 DSL 服务替代；原文本桥接入口仍存在，不能把本版本称为与官方完全无差别。配套协议边界报告说明当前证据与未验证部分。

源码包不包含 API key、设备配置、抓取日志、原生 APK、签名私钥或第三方框架副本。覆盖现有安装需继续使用原有本地签名证书。

`tests/fixtures/native-decoded-qa/` 保存 6 个由真实运行消息派生的脱敏协议样本，覆盖推理开始、工具状态、首块文本、最终结果、流结束及任务完成；provenance.json 记录原始证据文件摘要及行号。这些是解码后应用消息，不是原始网络抓包，也不是已实现的协议翻译器。
