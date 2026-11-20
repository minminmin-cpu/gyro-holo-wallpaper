/** 通过 WebView 远程调试（CDP）在真机上执行 JS，用于无触摸注入权限时验证交互。
 *
 * 前置：MainActivity 对 debug 包调用了 WebView.setWebContentsDebuggingEnabled(true)。
 * 用法：node android/tests/device_cdp.mjs "<js 表达式>"
 */
import { execFileSync } from "node:child_process";

// adb 路径可用 ADB 环境变量覆盖；默认走 PATH 里的 adb。
const ADB = process.env.ADB || "adb";
const PORT = 9222;
const PKG = "com.example.holowallpaper";

const adb = (args) => execFileSync(ADB, args, { encoding: "utf8" }).trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

try {
  const pid = adb(["shell", "pidof", PKG]);
  if (!pid) throw new Error("进程未运行，请先启动 App");

  adb(["forward", `tcp:${PORT}`, `localabstract:webview_devtools_remote_${pid}`]);
  await sleep(300);

  const targets = await (await fetch(`http://127.0.0.1:${PORT}/json`)).json();
  const page = targets.find((t) => t.type === "page" && /index\.html/.test(t.url)) || targets.find((t) => t.type === "page");
  if (!page) throw new Error("没有找到 WebView target：" + JSON.stringify(targets.map((t) => t.url)));

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  let seq = 0;
  const pending = new Map();
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id);
      pending.delete(m.id);
      m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result);
    }
  };
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });

  await send("Runtime.enable");
  const expr = process.argv[2] || "1";
  const result = await send("Runtime.evaluate", {
    expression: expr,
    returnByValue: true,
    awaitPromise: true,
  });
  const value = result.result ? result.result.value : null;
  console.log(typeof value === "string" ? value : JSON.stringify(value, null, 2));
  if (result.exceptionDetails) {
    console.error("EXCEPTION: " + JSON.stringify(result.exceptionDetails));
    process.exitCode = 1;
  }
  ws.close();
} catch (e) {
  console.error("CDP 失败: " + e.message);
  process.exitCode = 1;
}
