# YOYO Bridge LSPosed 2.0

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
