// WorkBuddy desktop-owned CLI -> ACP HTTP -> SSH stdio. No credential extraction.
// Desktop sidecar v6 is a private, version-dependent interface.
'use strict';
const net = require('net'), http = require('http'), fs = require('fs');
const path = require('path'), os = require('os'), crypto = require('crypto');
const readline = require('readline');
const launch = __COMMAND__, write = __WRITE__;
const sha = (s, n) => crypto.createHash('sha1').update(s).digest('hex').slice(0, n);
const config = process.env.WORKBUDDY_CONFIG_DIR || process.env.CODEBUDDY_CONFIG_DIR || path.join(os.homedir(), '.workbuddy');
const owned = 'orca-pocket-' + crypto.randomUUID();
const password = crypto.randomBytes(32).toString('base64url');
let control, runtime, endpoint, connectionId, closing = false, sequence = 0;
const waiting = new Map(), requests = new Set();
function emit(message) { process.stdout.write(JSON.stringify(message) + '\n'); }
function fail(id, message) { emit({jsonrpc:'2.0', id:id ?? null, error:{code:-32603, message}}); }
function rpc(method, params, timeout = 35000) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { waiting.delete(id); reject(Error('WorkBuddy 托管入口请求超时')); }, timeout);
    waiting.set(id, {resolve, reject, timer});
    control.write(JSON.stringify({jsonrpc:'2.0', id, method, params}) + '\n');
  });
}
async function connectControl() {
  // sshd may omit TMPDIR while the GUI uses Darwin's per-user temporary directory.
  const temps = new Set([os.tmpdir().trim()]);
  try {temps.add(require('child_process').execFileSync('/usr/bin/getconf', ['DARWIN_USER_TEMP_DIR'], {encoding:'utf8', timeout:3000}).trim());} catch {}
  const paths = new Set();
  for (const temp of temps) {
    if (!path.isAbsolute(temp)) continue;
    const root = path.join(temp, 'wb-' + sha(String(process.getuid()), 6), sha(config, 12));
    let stat; try {stat = fs.statSync(root);} catch {continue;}
    if (stat.uid !== process.getuid() || (stat.mode & 0o077)) throw Error('WorkBuddy 运行目录权限不符合预期');
    for (const p of fs.readdirSync(root)) if (/^sidecar-[a-f0-9]+\.sock$/.test(p)) paths.add(path.join(root, p));
  }
  if (paths.size !== 1) throw Error('未找到唯一的 WorkBuddy 桌面托管入口，请保持桌面应用运行');
  control = net.createConnection([...paths][0]);
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { control.destroy(); reject(Error('WorkBuddy 托管入口连接超时')); }, 5000);
    control.once('connect', () => {clearTimeout(timer); resolve();});
    control.once('error', () => {clearTimeout(timer); reject(Error('WorkBuddy 托管入口连接失败'));});
  });
  control.on('error', () => {});
  control.on('close', () => {
    for (const p of waiting.values()) {clearTimeout(p.timer); p.reject(Error('WorkBuddy 托管连接断开'));}
    waiting.clear();
    if (!closing) {fail(null, 'WorkBuddy 桌面托管连接断开，任务状态未知'); shutdown(1);}
  });
  readline.createInterface({input:control}).on('line', line => {
    let msg; try {msg = JSON.parse(line);} catch {return;}
    const p = waiting.get(msg.id); if (!p) return;
    waiting.delete(msg.id); clearTimeout(p.timer);
    if (msg.error) p.reject(Error('WorkBuddy 托管入口拒绝请求（' + msg.error.code + '）'));
    else p.resolve(msg.result);
  });
  const ping = await rpc('sidecar.ping', {});
  if (ping.version !== 6) throw Error('WorkBuddy 托管接口版本尚未适配');
}
// Match SSH's per-event budget and allow the phone's <=6MB inline image payloads.
const MAX_EVENT_BYTES = 8_000_000;
function protocolError(message) { const error = Error(message); error.publicMessage = message; return error; }
function transportError(error, phase) {
  if(error.publicMessage) return error;
  const code = typeof error.code === 'string' && /^[A-Z0-9_]+$/.test(error.code) ? '（'+error.code+'）' : '';
  return Error('WorkBuddy ACP '+phase+'连接断开'+code+'，任务状态未知；不会自动重发');
}
function post(route, body, onMessage) {
  return new Promise((resolve, reject) => {
    const headers = {'Content-Type':'application/json', 'x-codebuddy-request':'1', Authorization:'Bearer ' + password};
    if (connectionId) headers['acp-connection-id'] = connectionId;
    if (onMessage) headers.Accept = 'application/json, text/event-stream';
    const request = http.request({hostname:'127.0.0.1', port:endpoint.port, path:route, method:'POST', headers}, response => {
      // Permission replies and notifications are acknowledged without a response body.
      if (onMessage && response.statusCode === 202 && (!body.method || !Object.prototype.hasOwnProperty.call(body, 'id'))) {
        response.resume(); response.on('end', resolve); response.on('error', reject); return;
      }
      if (response.statusCode !== 200) {response.resume(); reject(Error('WorkBuddy ACP 网关拒绝请求（HTTP ' + response.statusCode + '）')); return;}
      let buffer = '', size = 0;
      const line = value => {
        if(Buffer.byteLength(value)>MAX_EVENT_BYTES)throw protocolError('WorkBuddy ACP 单条消息超过 8MB，任务状态未知；不会自动重发');
        value = value.trim(); if (value.startsWith('data:')) value = value.slice(5).trim();
        if (!value || value.startsWith('event:') || value.startsWith(':')) return;
        let msg; try {msg = JSON.parse(value);} catch {return;}
        onMessage(msg);
      };
      response.setEncoding('utf8');
      response.on('data', chunk => {
        try {
          buffer += chunk; size += Buffer.byteLength(chunk);
          if (onMessage) {
            let at; while ((at = buffer.indexOf('\n')) >= 0) {line(buffer.slice(0, at)); buffer = buffer.slice(at + 1);}
            if (Buffer.byteLength(buffer) > MAX_EVENT_BYTES) throw protocolError('WorkBuddy ACP 单条消息超过 8MB，任务状态未知；不会自动重发');
          } else if (size > 65536) throw protocolError('WorkBuddy ACP 连接响应过大');
        } catch(error) {request.destroy(error);}
      });
      response.on('end', () => {
        try {if (onMessage) {if (buffer.trim()) line(buffer); resolve();} else resolve(JSON.parse(buffer));}
        catch(error) {reject(error.publicMessage ? error : Error('WorkBuddy ACP 连接响应无效'));}
      });
      response.on('error', error => reject(transportError(error, '回复')));
    });
    requests.add(request); request.on('close', () => requests.delete(request));
    request.on('error', error => reject(transportError(error, '请求')));
    request.setTimeout(route === '/api/v1/acp/connect' ? 10000 : 900000, () => request.destroy(protocolError('WorkBuddy ACP 等待超时，任务状态未知；不会自动重发')));
    request.end(JSON.stringify(body));
  });
}
async function startup() {
  if (process.platform !== 'darwin' || launch.length !== 2 || !launch[1].endsWith('/WorkBuddy.app/Contents/Resources/app.asar.unpacked/cli/bin/codebuddy')) throw Error('此托管启动适配器仅验收 macOS 内置 WorkBuddy CLI');
  await connectControl();
  if (closing) throw Error('WorkBuddy 托管启动已取消');
  const args = [launch[1], '--serve', '--permission-mode', 'default'];
  if (!write) args.push('--tools', 'Read,Glob,Grep');
  const session = await rpc('session.create', {sessionId:owned, command:launch[0], args, cwd:process.cwd(), port:0,
    env:{CODEBUDDY_FORCE_HEADLESS_BUNDLE:'1', CODEBUDDY_GATEWAY_AUTH:'password', CODEBUDDY_GATEWAY_PASSWORD:password}});
  runtime = session.runtimeId; endpoint = new URL(session.acpEndpoint);
  if (!runtime || endpoint.protocol !== 'http:' || endpoint.hostname !== '127.0.0.1' || !endpoint.port) throw Error('WorkBuddy 托管端点不符合预期');
  const connected = await post('/api/v1/acp/connect', {});
  if (typeof connected.connectionId !== 'string' || !connected.connectionId) throw Error('WorkBuddy ACP 连接未确认');
  connectionId = connected.connectionId;
}
async function shutdown(code = 0) {
  if (closing) return; closing = true;
  // Startup may still be creating our child when SSH disconnects.
  await ready.catch(() => {});
  for (const r of requests) r.destroy();
  try {
    if (control && !control.destroyed) {
      // Match both our unique session and runtime. Never touch existing tasks.
      if (!runtime) {
        const sessions = await rpc('session.list', {}, 4000);
        const found = sessions.find(s => (s.sessionId || s.id) === owned);
        runtime = found && found.runtimeId;
      }
      if (runtime) await rpc('session.kill', {sessionId:owned, expectedRuntimeId:runtime}, 5000);
    }
  } catch {process.stderr.write('WorkBuddy 测试会话清理未确认，请检查桌面任务状态。\n'); code = 1;}
  if (control) control.destroy();
  process.exit(code);
}
const ready = startup();
// Mark startup rejection handled immediately; relay it against the first request.
ready.catch(() => {});
readline.createInterface({input:process.stdin}).on('line', async line => {
  let message;
  try {message = JSON.parse(line);} catch {fail(null, '无效的 ACP JSON'); return;}
  if (closing) return;
  try {await ready; await post('/api/v1/acp', message, emit);}
  catch (error) {fail(message.id, error.message); await shutdown(1);}
}).on('close', () => shutdown());
for (const signal of ['SIGHUP','SIGINT','SIGTERM']) process.on(signal, () => shutdown());
