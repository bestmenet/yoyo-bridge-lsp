# yobg-cli

`yobg-cli` 把荣耀手机的 YOYO 原生工具发布为 MCP Streamable HTTP 服务。CLI 可以运行在手机的 Termux 中，也可以运行在通过 USB 连接手机的电脑上。服务地址使用运行 CLI 的设备的 IP。

## 配对

1. 从含有本功能的源码构建并安装 YOYO Bridge APK，启用 LSPosed 的 YOYO 作用域。升级已安装的 APK 时沿用原签名。
2. 在模块设置页点 **启用 Phone Use**，通过 **查看 CLI 配对密钥** 获取密钥。打开 YOYO，确认设置页显示 Phone Use 监听就绪。
3. 在 Node.js 22+ 环境中，从仓库根目录安装 CLI 依赖。将手机配对密钥输入到 `read`，再为 MCP 客户端生成独立的访问密钥。

   ```sh
   npm ci --prefix cli
   read -r -s YOBG_PHONE_TOKEN
   export YOBG_PHONE_TOKEN
   export YOBG_MCP_TOKEN="$(openssl rand -hex 32)"
   ```

## 局域网服务

以运行 CLI 的设备 IP `192.168.1.20` 为例。为该地址准备 HTTPS 证书与私钥，并让客户端信任签发证书的 CA。以下命令在电脑上运行，需要已授权的 USB 调试连接和 PATH 中的 `adb`。

```sh
node cli/yobg-cli.mjs serve --adb --host 192.168.1.20 --port 8765 \
  --tls-cert lan-cert.pem --tls-key lan-key.pem
```

在 Termux 直接运行时，省略 `--adb`，将 `--host` 设为手机的 Wi-Fi IP。连接多个 Android 设备时用 `--serial SERIAL` 选择目标。CLI 正常退出时会撤销它创建的 ADB 转发。

在支持自定义请求头的 MCP 客户端中添加以下配置，格式按客户端要求调整。把占位符替换为 `YOBG_MCP_TOKEN` 的值。

```json
{
  "mcpServers": {
    "yoyo-phone": {
      "type": "http",
      "url": "https://192.168.1.20:8765/mcp",
      "headers": {
        "Authorization": "Bearer <YOBG_MCP_TOKEN>"
      }
    }
  }
}
```

省略 `--host` 时，服务仅在 `127.0.0.1:8765` 提供本机访问。局域网监听须使用具体的私有 IPv4 地址及 TLS 证书。MCP 认证使用预配置的 Bearer 密钥，客户端须支持配置 `Authorization` 请求头。

## 工具

| 名称 | 参数 | 行为 |
| --- | --- | --- |
| `gui.create` | `app_name`、`task_query` | 用 YOYO 原生 GUI 执行器在目标应用中创建异步操作，返回原生结果中的 `task_id`。 |
| `task-result-query` | `task_id` | 查询该操作的结果。若 `is_final` 为 `false`，稍后继续查询同一任务。 |

用 `node cli/yobg-cli.mjs tools --adb` 查看手机端工具目录。如果 YOYO 私有目录中的 `mcp_tools.json` 提供了原生工具的 `inputSchema`，可以在启动服务时用 `--native-tool ALIAS=PACKAGE:NAME` 逐项发布。将 `PACKAGE` 设为空可匹配目录中 `appPkg` 为空的系统工具。目录在手机监听启动时读取，CLI 在配对时读取可发布的工具。目录更新后，重启 YOYO 再重启 CLI。

关闭模块设置中的 Phone Use 会撤销手机配对密钥并关闭手机端监听。重新启用后，CLI 需要使用新的配对密钥重启。已交给 YOYO 的原生任务可能继续执行。停掉 CLI 可以立即关闭局域网 MCP 入口。
