/** 用 CDP 精确模拟手机视口，验证多尺寸适配并重新截图。
 *  headless Edge 的 --window-size 会被钳到最小宽度，--screenshot 只裁左上角，
 *  所以必须用 Emulation.setDeviceMetricsOverride 才是可信的移动端验证。
 *  运行: node app-ui/tests/verify_ui.mjs
 */
import { spawn } from "node:child_process";
import { writeFileSync, mkdtempSync, rmSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const APP = dirname(dirname(fileURLToPath(import.meta.url)));
const URL_BASE = "file:///" + join(APP, "index.html").replace(/\\/g, "/").replace(/ /g, "%20");
const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const PORT = 9333;

const CASES = [
  { w: 320, h: 640, page: "create", tag: "ui-small-320" },
  { w: 360, h: 780, page: "create", tag: "ui-360-create" },
  { w: 390, h: 844, page: "create", tag: "ui-create-390" },
  { w: 390, h: 844, page: "preview", tag: "ui-preview-390" },
  { w: 390, h: 844, page: "models", tag: "ui-models-390" },
  { w: 390, h: 844, page: "library", tag: "ui-library-390" },
  { w: 430, h: 932, page: "preview", tag: "ui-large-430" },
  { w: 844, h: 390, page: "preview", tag: "ui-landscape-844" },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const profile = mkdtempSync(join(tmpdir(), "appui-cdp-"));
const shots = join(APP, "tests", "shots");
mkdirSync(shots, { recursive: true });

const child = spawn(EDGE, [
  "--headless=new", "--disable-gpu-sandbox", "--use-gl=angle",
  "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
  "--no-first-run", "--hide-scrollbars", "--remote-allow-origins=*",
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, "about:blank",
], { stdio: "ignore" });

let ws;
try {
  let ready = false;
  for (let i = 0; i < 50; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
      if (r.ok) { ready = true; break; }
    } catch (_) { /* not up yet */ }
    await sleep(200);
  }
  if (!ready) throw new Error("CDP 端口未就绪");

  const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  const page = targets.find((t) => t.type === "page");
  if (!page) throw new Error("没有可用的 page target");

  ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  let seq = 0;
  const pending = new Map();
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id);
      pending.delete(m.id);
      m.error ? reject(new Error(m.error.message)) : resolve(m.result);
    }
  };
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });

  await send("Page.enable");
  await send("Runtime.enable");

  let failed = 0;
  console.log("视口适配验证 (CDP Emulation.setDeviceMetricsOverride)");
  for (const c of CASES) {
    await send("Emulation.setDeviceMetricsOverride", {
      width: c.w, height: c.h, deviceScaleFactor: 1, mobile: true,
    });
    await send("Page.navigate", { url: `${URL_BASE}?debug=1&page=${c.page}` });
    await sleep(620);
    const res = await send("Runtime.evaluate", {
      returnByValue: true,
      expression: `JSON.stringify((()=>{
        const m=document.getElementById('main');
        const tab=document.querySelector('#tabs .tab:last-child');
        const brand=document.querySelector('.badge-free');
        return {vw:innerWidth, vh:innerHeight,
          ovf:m.scrollWidth-m.clientWidth,
          docW:document.documentElement.scrollWidth,
          tabRight:Math.round(tab.getBoundingClientRect().right),
          brandRight:Math.round(brand.getBoundingClientRect().right),
          tier:document.body.dataset.tier||'',
          page:window.__ui?window.__ui.state.page:''};
      })())`,
    });
    const m = JSON.parse(res.result.value);
    const ok = m.vw === c.w && m.ovf <= 0 && m.docW <= c.w + 1 &&
      m.tabRight <= c.w + 1 && m.brandRight <= c.w + 1 && m.page === c.page;
    if (!ok) failed++;
    console.log(`  [${ok ? "PASS" : "FAIL"}] ${c.w}x${c.h} ${c.page.padEnd(8)} vw=${m.vw} ovf=${m.ovf} docW=${m.docW} tabRight=${m.tabRight} brandRight=${m.brandRight}`);

    const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
    writeFileSync(join(shots, c.tag + ".png"), Buffer.from(shot.data, "base64"));
  }

  // 模拟原生桥验证创作门槛与配置迁移，不请求真实服务、不读取用户配置。
  const mock = await send("Page.addScriptToEvaluateOnNewDocument", { source: `
    window.__mock = { providers: [], calls: [] };
    window.HoloAndroid = {
      listProviders: () => JSON.stringify({ok:true, items:window.__mock.providers}),
      listProfiles: () => JSON.stringify({ok:true, items:[]}),
      previewTextures: () => JSON.stringify({ok:false, reason:'测试未装载素材'}),
      generate: (value) => { window.__mock.calls.push(JSON.parse(value)); return JSON.stringify({ok:true,status:'pending'}); },
      saveProvider: (value) => { const p=JSON.parse(value); window.__mock.saved=p; return JSON.stringify({ok:true,id:p.id}); }
    };
  ` });
  await send("Page.navigate", { url: `${URL_BASE}?static=1&page=create` });
  await sleep(400);
  const behavior = await send("Runtime.evaluate", { returnByValue: true, expression: `JSON.stringify((()=>{
    const $=id=>document.getElementById(id), checks=[];
    const check=(name,ok)=>checks.push({name,ok:!!ok});
    check('唯一照片入口',!$('entry-prompt')&&!document.querySelector('[data-entry="prompt"]'));
    check('无预置模型',window.__ui.state.providers.length===0);
    check('仅 Chat 协议',[...$('m-protocol').options].filter(o=>o.value).every(o=>/^OPENAI_CHAT_(TEXT|VISION)$/.test(o.value)));
    window.__onNative.onPhotoPicked(JSON.stringify({ok:true,path:'/mock/photo.jpg',name:'测试照片',size:1234}));
    $('photo-desc').value='冷蓝配色、双线边框、星点装饰';
    $('generate').click();
    check('未选模型拒绝生成',window.__mock.calls.length===0&&$('toast').textContent.includes('模型'));
    window.__mock.providers=[{id:'legacy',name:'旧模型',protocol:'LEGACY',modelId:'custom',caps:[],needsMigration:true}];
    window.__ui.state.providers=window.__mock.providers.map(p=>({...p,model:p.modelId}));
    window.__ui.state.activeProviderId='legacy';
    $('generate').click();
    check('旧配置拒绝调用',window.__mock.calls.length===0&&$('toast').textContent.includes('旧模型协议'));
    window.__mock.providers=[{id:'chat',name:'设计模型',protocol:'OPENAI_CHAT_VISION',modelId:'user-model',caps:['DESIGN_JSON','VISION'],needsMigration:false}];
    window.__ui.state.providers=window.__mock.providers.map(p=>({...p,model:p.modelId}));
    window.__ui.state.activeProviderId='chat';
    $('generate').click();
    const call=window.__mock.calls[0];
    check('照片描述传入桥',call&&call.entry==='photo'&&call.photoPath==='/mock/photo.jpg'&&call.prompt.includes('冷蓝')&&call.providerId==='chat');
    window.__onNative.onGenerateResult(JSON.stringify({ok:false,reason:'设计 JSON 字段不符'}));
    check('失败显示且按钮恢复',!$('generate').disabled&&$('toast').textContent.includes('设计 JSON 字段不符')&&!window.__ui.state.generated);
    $('photo-desc').value=''; $('generate').click();
    check('描述必填',window.__mock.calls.length===1&&$('toast').textContent.includes('设计描述'));
    $('photo-desc').value='照片卡片设计';
    const fields={title:['用户标题',12],subtitle:['用户下方小字',32],collection:['用户典藏',24],edition:['NO. 007',24]};
    for(const [key,[value,limit]] of Object.entries(fields)) {
      const el=$('copy-'+key); el.value=value; el.dispatchEvent(new Event('input'));
      check(key+'长度限制',el.maxLength===limit);
    }
    $('generate').click();
    const custom=window.__mock.calls.at(-1).copy;
    check('用户文案显式优先',custom.priority==='user'&&Object.entries(fields).every(([key,[value]])=>custom[key]===value)&&custom.hideSubtitle===false);
    window.__onNative.onGenerateResult(JSON.stringify({ok:false,reason:'模拟结束'}));
    $('copy-hide-subtitle').checked=true; $('copy-hide-subtitle').dispatchEvent(new Event('change'));
    $('generate').click();
    check('强制清空小字开关入桥',window.__mock.calls.at(-1).copy.hideSubtitle===true&&$('copy-subtitle').disabled);
    window.__onNative.onGenerateResult(JSON.stringify({ok:false,reason:'模拟结束'}));
    $('copy-hide-subtitle').checked=false; $('copy-hide-subtitle').dispatchEvent(new Event('change'));
    check('取消隐藏保留原小字',!$('copy-subtitle').disabled&&$('copy-subtitle').value==='用户下方小字');
    const before=window.__mock.calls.length;
    for(const [key,[value,limit]] of Object.entries(fields)) {
      $('copy-'+key).value='字'.repeat(limit+1); $('generate').click();
      check(key+'超长不请求',window.__mock.calls.length===before&&!$('copy-'+key).validity.valid);
      $('copy-'+key).value=value;
    }
    $('copy-title').value='标题'+String.fromCharCode(127); $('generate').click();
    check('控制字符不请求',window.__mock.calls.length===before&&!$('copy-title').validity.valid);
    $('copy-title').value='<标题>'; $('generate').click();
    check('尖括号不请求',window.__mock.calls.length===before&&!$('copy-title').validity.valid);
    for(const key of Object.keys(fields)) { $('copy-'+key).value=''; $('copy-'+key).dispatchEvent(new Event('input')); }
    $('generate').click();
    check('留空交给模型设计',Object.keys(fields).every(key=>window.__mock.calls.at(-1).copy[key]==='')&&!window.__mock.calls.at(-1).copy.hideSubtitle);
    window.__onNative.onGenerateResult(JSON.stringify({ok:false,reason:'模拟结束'}));
    for(const [key,[value]] of Object.entries(fields)) { $('copy-'+key).value=value; $('copy-'+key).dispatchEvent(new Event('input')); }
    $('copy-hide-subtitle').checked=true; $('copy-hide-subtitle').dispatchEvent(new Event('change'));
    check('草稿不保存照片或密钥',Object.keys(JSON.parse(localStorage.getItem('holo.card-copy-draft.v1'))).sort().join(',')==='copy,prompt');
    check('旧作品文案需重新生成提示',$('copy-help').textContent.includes('重新选择原照片并生成'));
    return checks;
  })())` });
  if (behavior.exceptionDetails) throw new Error(behavior.exceptionDetails.text);
  const behaviorChecks = JSON.parse(behavior.result.value);
  await send("Page.navigate", { url: `${URL_BASE}?static=1&page=create` });
  await sleep(400);
  const restored = await send("Runtime.evaluate", { returnByValue: true, expression: `JSON.stringify((()=>{
    const $=id=>document.getElementById(id);
    const checks=[
      {name:'刷新恢复四项草稿',ok:$('copy-title').value==='用户标题'&&$('copy-subtitle').value==='用户下方小字'&&$('copy-collection').value==='用户典藏'&&$('copy-edition').value==='NO. 007'},
      {name:'刷新恢复清空小字开关',ok:$('copy-hide-subtitle').checked&&$('copy-subtitle').disabled},
      {name:'刷新恢复设计描述但不恢复照片',ok:$('photo-desc').value==='照片卡片设计'&&!window.__ui.state.photoPath&&!window.__ui.state.fileName}
    ];
    localStorage.removeItem('holo.card-copy-draft.v1');
    return checks;
  })())` });
  if (restored.exceptionDetails) throw new Error(restored.exceptionDetails.text);
  behaviorChecks.push(...JSON.parse(restored.result.value));
  for (const c of behaviorChecks) {
    if (!c.ok) failed++;
    console.log('  ['+(c.ok?'PASS':'FAIL')+'] '+c.name);
  }
  await send("Page.removeScriptToEvaluateOnNewDocument", {identifier:mock.identifier});

  // 性能采样：headless SwiftShader 软件渲染，仅作相对参考（真机 GPU 更高）
  await send("Emulation.setDeviceMetricsOverride", { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await send("Page.navigate", { url: `${URL_BASE}?debug=1&page=preview` });
  await sleep(2400);
  const perf = await send("Runtime.evaluate", { returnByValue: true, expression: "JSON.stringify(window.__ui.getMetrics())" });
  console.log("  性能采样 (SwiftShader 软件渲染): " + perf.result.value);

  // 降级路径验证
  await send("Emulation.setDeviceMetricsOverride", { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await send("Page.navigate", { url: `${URL_BASE}?static=1&debug=1&page=preview` });
  await sleep(500);
  const st = await send("Runtime.evaluate", { returnByValue: true, expression: "JSON.stringify({tier:window.__ui.tier, particles:window.__ui.getMetrics().particles})" });
  const s = JSON.parse(st.result.value);
  const staticOk = s.tier === "none" && s.particles === 0;
  if (!staticOk) failed++;
  console.log(`  [${staticOk ? "PASS" : "FAIL"}] 减少动效降级 tier=${s.tier} particles=${s.particles}`);
  const shot = await send("Page.captureScreenshot", { format: "png" });
  writeFileSync(join(shots, "ui-static-390.png"), Buffer.from(shot.data, "base64"));

  const total = CASES.length + 1 + behaviorChecks.length;
  console.log(`\n结果: ${total - failed}/${total} 通过；截图 -> ${shots}`);
  if (failed) process.exitCode = 1;
} catch (e) {
  console.error("验证失败:", e.message);
  process.exitCode = 1;
} finally {
  if (ws) ws.close();
  child.kill();
  await sleep(300);
  rmSync(profile, { recursive: true, force: true });
}
