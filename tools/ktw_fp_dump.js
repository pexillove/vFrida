// frida_dump_ktw.js — vfrida 版：只 hook KTW 网络发送入口，dump 加密指纹
//
// 注意：vfrida 的 JS 运行时不是标准 Frida：
//   - 没有 Java.perform()，脚本顶层直接 Java.use
//   - hook 里调用原实现用 Java.original()，不要用 overload.call(this, ...)
//
// hook 点（稳定锚点，不依赖 acsdk 混淆名，通吃 9.76.8 / 9.85.x）：
//   okhttp3.OkHttpClient.newCall(okhttp3.Request)
// acsdk 的混淆类/方法名每个版本都变（如 9.76.8 是 lllI111I111.ll1lIll1II11(String,IlllI1Il1Ill,long)，
// 9.85.3 是 l1lIIl11l111l.ll1lIll1II11(String,I111I1I1IlIl,String,II1Il1lI1l)），
// 上报域名/路径也变过（tfs-fp.vip.com/data/report → tfs-ktw.vip.com/deviceprofile/v1）。
// 真正不变的是：OkHttp 是第三方库、类名不混淆；指纹 body 恒为 {"data":"<Base64>"} 且体积 >10KB
// （事件上报是 {"content":...}、配置请求是 {"param":...}，都可区分）。故以 body 特征过滤。
//
// 用法（对原版 9.85.3）：
//   1. 主机启动在线解密服务：python3 vip_decode_server.py
//   2. 把设备 8765 端口转发到主机：adb reverse tcp:8765 tcp:8765
//   3. vfrida -U -f com.achievo.vipshop -l frida_dump_ktw.js
//
// 捕获后直接把 {"data":...} POST 给解密服务（裸 Socket，绕开明文 HTTP 策略），
// 服务解密后回传明文指纹并落盘 ktw_fp_<时间>.json（?save=1）。
// 发送失败时回退成本地文件 dump（与 patched APK 一致，追加写）：
//   /sdcard/Download/ktw_fp_dump.txt 等三处，之后可用 python3 vip_decode_fp.py 拉取解密

var KTW_FILE = "ktw_fp_dump.txt";
var DECRYPT_HOST = "127.0.0.1"; // 经 adb reverse 到达主机
var DECRYPT_PORT = 8765;
var DECRYPT_PATH = "/api/decrypt?save=1";
var netHookInstalled = false;

function log(msg) {
  console.log("[ktw-dump] " + msg);
}

function warn(msg) {
  console.log("[ktw-dump][!] " + msg);
}

// 等价 com.dump.Tools.dumpToFile / writeFile（追加写）
function dumpToFile(content, filename) {
  if (content === null || content === undefined || content.length === 0) return;

  var wrapped = "\n===DUMP_START===\n" + content + "\n===DUMP_END===\n";
  var paths = ["/sdcard/Download/" + filename];

  try {
    var ActivityThread = Java.use("android.app.ActivityThread");
    var app = ActivityThread.currentApplication();
    if (app !== null && app !== undefined) {
      var ext = app.getExternalFilesDir(null);
      if (ext !== null && ext !== undefined) {
        paths.push(String(ext.getAbsolutePath()) + "/" + filename);
      }
      var files = app.getFilesDir();
      if (files !== null && files !== undefined) {
        paths.push(String(files.getAbsolutePath()) + "/" + filename);
      }
    }
  } catch (e) {
    warn("get app dirs failed: " + e);
  }

  // FileWriter 有多个 2 参构造，$new 会 ambiguous；用显式 JNI 签名构造
  for (var i = 0; i < paths.length; i++) {
    var p = paths[i];
    var writer = null;
    try {
      // 确保父目录存在（如 /sdcard/Download 可能被删）；File 构造同样用显式签名
      var slash = p.lastIndexOf("/");
      if (slash > 0) {
        var dir = Java._new("java.io.File", "(Ljava/lang/String;)V", [p.substring(0, slash)]);
        if (dir !== null) {
          Java._invoke("java.io.File", "mkdirs", "()Z", dir, [], 0);
        }
      }
      writer = Java._new("java.io.FileWriter", "(Ljava/lang/String;Z)V", [p, true]); // append
      if (writer === null) throw new Error("Java._new returned null");
      Java._invoke("java.io.FileWriter", "write", "(Ljava/lang/String;)V", writer, [wrapped], 0);
      Java._invoke("java.io.FileWriter", "close", "()V", writer, [], 0);
      writer = null;
      log("dumped -> " + p + " (" + content.length + " chars)");
    } catch (e) {
      warn("write failed " + p + ": " + e);
      try {
        if (writer !== null) {
          Java._invoke("java.io.FileWriter", "close", "()V", writer, [], 0);
        }
      } catch (_) {}
    }
  }
}

// ---- 直发解密服务（裸 Socket 手写 HTTP/1.1）----
// 注意：本函数在 KTW 发送的后台线程里同步执行，会略延迟原始发送；
// 千万不要在主线程调用（NetworkOnMainThreadException）。

function strBytes(s) {
  var strH = Java._new("java.lang.String", "(Ljava/lang/String;)V", [s]);
  if (strH === null) throw new Error("String _new null");
  return Java._invoke("java.lang.String", "getBytes", "(Ljava/lang/String;)[B", strH, ["UTF-8"], 0);
}

function socketPostJson(host, port, path, jsonStr) {
  var sock = Java._new("java.net.Socket", "(Ljava/lang/String;I)V", [host, port]);
  if (sock === null) throw new Error("socket connect failed: " + host + ":" + port);
  try {
    Java._invoke("java.net.Socket", "setSoTimeout", "(I)V", sock, [20000], 0);
    var req = "POST " + path + " HTTP/1.1\r\n" +
      "Host: " + host + ":" + port + "\r\n" +
      "Content-Type: application/json\r\n" +
      "Content-Length: " + jsonStr.length + "\r\n" +
      "Connection: close\r\n\r\n" + jsonStr;
    var out = Java._invoke("java.net.Socket", "getOutputStream", "()Ljava/io/OutputStream;", sock, [], 0);
    Java._invoke("java.io.OutputStream", "write", "([B)V", out, [strBytes(req)], 0);
    Java._invoke("java.io.OutputStream", "flush", "()V", out, [], 0);

    var input = Java._invoke("java.net.Socket", "getInputStream", "()Ljava/io/InputStream;", sock, [], 0);
    var scanner = Java._new("java.util.Scanner", "(Ljava/io/InputStream;)V", [input]);
    if (scanner === null) throw new Error("Scanner _new null");
    Java._invoke("java.util.Scanner", "useDelimiter", "(Ljava/lang/String;)Ljava/util/Scanner;", scanner, ["\\A"], 0);
    var has = Java._invoke("java.util.Scanner", "hasNext", "()Z", scanner, [], 0);
    return has ? String(Java._invoke("java.util.Scanner", "next", "()Ljava/lang/String;", scanner, [], 0)) : "";
  } finally {
    try {
      Java._invoke("java.net.Socket", "close", "()V", sock, [], 0);
    } catch (_) {}
  }
}

// 把捕获的 {"data":...} 发给服务解密，成功打印关键字段；失败抛错由调用方回退落盘
function sendToDecryptServer(body) {
  var resp = socketPostJson(DECRYPT_HOST, DECRYPT_PORT, DECRYPT_PATH, body);
  var sep = resp.indexOf("\r\n\r\n");
  var statusLine = sep >= 0 ? resp.substring(0, resp.indexOf("\r\n")) : "";
  var respBody = sep >= 0 ? resp.substring(sep + 4) : resp;
  if (statusLine.indexOf(" 200") < 0) {
    throw new Error("server " + statusLine + ": " + respBody.substring(0, 200));
  }
  var data = JSON.parse(respBody);
  if (!data.ok || !data.results || data.results.length === 0) {
    throw new Error("decrypt failed: " + respBody.substring(0, 200));
  }
  var fp = data.results[data.results.length - 1];
  log("decrypted by server: a8=" + fp.a8 + " a41=" + fp.a41 +
      " a131=" + fp.a131 + " fields=" + Object.keys(fp).length +
      (data.saved ? " saved=" + data.saved : ""));
}

function installNetHook() {
  if (netHookInstalled) return true;
  try {
    var OkHttpClient = Java.use("okhttp3.OkHttpClient");
    OkHttpClient.newCall.overload("okhttp3.Request").implementation = function (request) {
      try {
        var reqBody = request.body();
        if (reqBody !== null && reqBody !== undefined) {
          // 先按长度粗筛（指纹 body >10KB），避免每个请求都读 body
          var len = -1;
          try { len = Number(reqBody.contentLength()); } catch (_) {}
          if (len < 0 || len > 5000) {
            // 把 RequestBody 写到新 Buffer 里读出，不影响原始请求
            var Buffer = Java.use("okio.Buffer");
            var buf = Buffer.$new();
            reqBody.writeTo(buf);
            var text = String(buf.readUtf8());
            if (text.indexOf('"data":"') >= 0) {
              log("ktw report captured, len=" + text.length + " url=" + String(request.url()));
              try {
                sendToDecryptServer(text);
              } catch (e) {
                warn("send to decrypt server failed (" + e + "), fallback to file dump");
                dumpToFile(text, KTW_FILE);
              }
            }
          }
        }
      } catch (e) {
        warn("okhttp hook failed: " + e);
      }
      return Java.original();
    };
    netHookInstalled = true;
    log("hooked okhttp: OkHttpClient.newCall(Request), filter=body {\"data\":...} & len>5000");
    return true;
  } catch (e) {
    warn("hook okhttp failed: " + e);
    return false;
  }
}

// spawn 时类可能还没加载，先试一次；失败则等 Application.onCreate 后再装
if (!installNetHook()) {
  try {
    var Application = Java.use("android.app.Application");
    Application.onCreate.implementation = function () {
      var ret = Java.original();
      installNetHook();
      return ret;
    };
    log("deferred: will install net hook on Application.onCreate");
  } catch (e) {
    warn("hook Application failed: " + e);
  }
}

log("ready. 解密服务: http://" + DECRYPT_HOST + ":" + DECRYPT_PORT + DECRYPT_PATH +
    "（需先启动 vip_decode_server.py 并 adb reverse tcp:" + DECRYPT_PORT + " tcp:" + DECRYPT_PORT + "）");
