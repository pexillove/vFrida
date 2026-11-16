# 关于 vFrida

vFrida 是一个跑在 Android 上的应用分身沙箱，内置一套类 Frida 的脚本运行时。它把目标应用装进自己的进程空间里运行，再用一个命令行客户端把 JavaScript 送进宿主或某个分身，拿回日志和 RPC 结果。

- 宿主包名：`com.android.vfrida`
- 引擎包名：`com.android.bb`
- 命令行：`vfrida`、`vfrida-ps`（脚本在 `tools/`，加进 PATH 后可直接调用）
- 运行架构：仅 **arm64-v8a**
- 开源地址：<https://github.com/vFrida/vFrida>
- 许可：Apache License 2.0

## 能做什么

- **应用分身**：同一份 APK 可以开多份，用 `userId` 区分，0 是第一份；分身不是系统里的普通安装，而是跑在宿主包内。
- **虚拟系统服务**：包管理、活动栈、通知、账户、位置、权限等系统服务在沙箱内有一份虚拟实现，分身看到的是自己的数据目录、包列表和进程视图。
- **IO 重定向**：文件路径按前缀树重定向到分身私有目录，`/sdcard` 与物理根有单独策略，隐藏 Root 始终开启。
- **同 uid 伪装**：分身进程的 cmdline、进程计数、磁盘目录按宿主 uid 的口径对齐，避免被目标应用的同 uid 检查发现。
- **类 Frida 脚本**：QuickJS 执行 JavaScript，提供 `Interceptor`、`Stalker`、`Java`、`Memory`、`Module`、`Process`、`console` 与 `rpc.exports`。
- **命令行注入**：`-f` 拉起并注入、`-n` 按进程名挂接、`-p` 按 pid 挂接、`--list` 列出已注册进程、`--call` 调用 RPC。

## 进程模型

| 进程 | 作用 |
| --- | --- |
| `com.android.vfrida` | 宿主界面主进程，负责装包、启动分身，本身也是一个 agent |
| `com.android.vfrida:black` | 虚拟系统进程，监听 TCP `127.0.0.1:2408`，承载 Binder 服务与分身拉起 |
| `com.android.vfrida:pN` | 分身/桩进程，伪造 cmdline，脚本与日志经 Binder 往返 |

开发机只连 `:black` 的环回端口，不直接连分身。USB 模式下客户端会自己执行 `adb forward tcp:0 tcp:2408`。

## 模块

| 模块 | 说明 |
| --- | --- |
| `app` | 宿主 UI：应用列表、分身管理、设置、虚拟定位 |
| `core` | 沙箱引擎 `com.android.bb`：Binder 虚拟服务、包管理、JNI 与 C++ 运行时（QuickJS、Frida Gum、Capstone、xdl） |
| `black` | 替换进分身的 framework 类（`black.*`），承接文件、线程、WebView 等系统调用 |
| `api` | 编译期使用的 Android framework 隐藏 API 桩 |
| `reflection` | 隐藏 API 反射封装与注解 |
| `compiler` | 注解处理器，为反射生成代理代码 |

## 构建

```bash
./gradlew :app:assembleDebug     # 产物 app/build/outputs/apk/debug/
./gradlew :app:assembleRelease
```

- compileSdk 35、minSdk 21、targetSdk 28
- JDK 21、NDK 25.2.9519653
- APK 只打包 `arm64-v8a`；其它 ABI 没有 JS 运行时，RPC 会返回 `js runtime is not in vfrida`

## 限制

- 只支持 arm64，非 arm64 构建不带脚本运行时。
- 分身共享宿主 uid，依赖虚拟化而非独立安装；与系统级多开/工作资料不能混用。
- 目标应用如果做深入的完整性校验，仍可能识别出沙箱环境。
- 签名依赖本地 `app/vfrida-release.jks`（未入库）。

## 文档

- `README.md` / `README.zh-CN.md`：安装与命令行用法
- `docs/vfrida-cli.html`：进程、通道、帧、拉起与挂接的细节
- `docs/vapp-runtime.html`：分身运行时与虚拟系统服务
- `docs/io-redirect.html`：文件路径重定向
- `docs/same-uid.html`：同 uid 下的进程与目录伪装
- `docs/service-hijack.html`、`docs/svc-hijack.html`：系统服务劫持
