#!/usr/bin/env node
/**
 * modlens 视觉桥：把 modlens CLI 包装成一个本机 HTTP 服务，供 Android 应用（模拟器/真机）调用。
 *
 * 协议（与 app 的 ai/ModlensPayload.kt 对应）：
 *   POST /analyze  {"image_base64":"...","mime":"image/jpeg","prompt":"..."}
 *     → 200 {"text":"识别出的文字","provider":"...","raw":{...modlens 原始 JSON...}}
 *     → 502 {"error":"可读错误信息"}
 *   GET  /health   → {"ok":true,"provider":"..."}
 *
 * 用法：
 *   # 1) 先配置 modlens 的视觉 provider（任选其一）
 *   npx @liustack/modlens config set gemini-api.apiKey <key>
 *   # 或安装 Antigravity CLI 并 agy 登录；或配置 openai.apiKey / anthropic.apiKey ...
 *   # 2) 启动桥（默认 127.0.0.1:8765）
 *   node tools/modlens-bridge/server.mjs
 *   # 3) Android 模拟器里访问宿主机用 10.0.2.2:8765（真机用局域网 IP），
 *   #    在 App「设置 → 视觉识别」选择 modlens 视觉桥并保存。
 *
 * 环境变量：
 *   PORT=8765              监听端口
 *   HOST=127.0.0.1         监听地址（模拟器建议 0.0.0.0 或保持默认，10.0.2.2 会转到宿主机 loopback）
 *   MODLENS_BIN=npx        执行命令
 *   MODLENS_PREFIX=-y,@liustack/modlens   命令前缀（逗号分隔）
 *   MODLENS_PROVIDER=      传给 modlens 的 -p 参数（可选）
 *   MODLENS_TIMEOUT=180000 modlens 超时（毫秒）
 */

import http from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const PORT = Number(process.env.PORT || 8765);
const HOST = process.env.HOST || '127.0.0.1';
const MODLENS_BIN = process.env.MODLENS_BIN || 'npx';
const MODLENS_PREFIX = (process.env.MODLENS_PREFIX || '-y,@liustack/modlens').split(',').filter(Boolean);
const PROVIDER = process.env.MODLENS_PROVIDER || '';
const MODLENS_TIMEOUT = Number(process.env.MODLENS_TIMEOUT || 180000);
const MAX_BODY_BYTES = 30 * 1024 * 1024;

function runModlens(imagePath, prompt) {
  const args = [...MODLENS_PREFIX, 'analyze', '-i', imagePath, '--timeout', String(MODLENS_TIMEOUT)];
  if (prompt) args.push('--prompt', prompt);
  if (PROVIDER) args.push('-p', PROVIDER);
  return new Promise((resolve, reject) => {
    const child = spawn(MODLENS_BIN, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', (error) => reject(error));
    child.on('close', (code) => {
      if (code === 0) {
        resolve(stdout);
      } else {
        const detail = (stderr || stdout).trim().split('\n').slice(-3).join(' ');
        reject(new Error(detail || `modlens 退出码 ${code}`));
      }
    });
  });
}

/** 从 modlens 结构化 JSON 中尽力抽取文字：优先 ocr.full_text，其次 regions[].text。 */
function extractText(node, collected, depth = 0) {
  if (!node || typeof node !== 'object' || depth > 6) return;
  if (typeof node.full_text === 'string' && node.full_text.trim()) {
    collected.push(node.full_text);
    return;
  }
  if (typeof node.text === 'string' && node.text.trim()) {
    collected.push(node.text);
  }
  if (Array.isArray(node.regions)) {
    for (const region of node.regions) extractText(region, collected, depth + 1);
  }
}

function textFromEvidence(evidence) {
  const collected = [];
  extractText(evidence?.ocr, collected);
  if (collected.length === 0) extractText(evidence, collected);
  const unique = [...new Set(collected.map((item) => item.trim()).filter(Boolean))];
  return unique.join('\n');
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('图片过大（超过 30MB 限制）'));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

function sendJson(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  response.end(body);
}

function extensionFor(mime) {
  if (mime.includes('png')) return 'png';
  if (mime.includes('webp')) return 'webp';
  return 'jpg';
}

const server = http.createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    sendJson(response, 200, { ok: true, provider: PROVIDER || 'modlens 默认 provider' });
    return;
  }
  if (request.method !== 'POST' || request.url !== '/analyze') {
    sendJson(response, 404, { error: '只支持 POST /analyze 与 GET /health' });
    return;
  }

  let workdir;
  try {
    const raw = await readBody(request);
    const payload = JSON.parse(raw);
    const base64 = String(payload.image_base64 || '');
    if (!base64) throw new Error('缺少 image_base64 字段');
    const mime = String(payload.mime || 'image/jpeg');
    const prompt = String(payload.prompt || '');
    const bytes = Buffer.from(base64, 'base64');

    workdir = await mkdtemp(path.join(tmpdir(), 'modlens-bridge-'));
    const imagePath = path.join(workdir, `upload.${extensionFor(mime)}`);
    await writeFile(imagePath, bytes);

    const stdout = await runModlens(imagePath, prompt);
    let evidence;
    try {
      evidence = JSON.parse(stdout);
    } catch {
      // modlens 可能在 JSON 前后打印提示信息，截取首尾花括号再试一次
      const start = stdout.indexOf('{');
      const end = stdout.lastIndexOf('}');
      evidence = JSON.parse(stdout.slice(start, end + 1));
    }
    const text = textFromEvidence(evidence);
    if (!text) {
      sendJson(response, 502, { error: 'modlens 没有从图片中识别出文字' });
      return;
    }
    sendJson(response, 200, {
      text,
      provider: evidence?.provider || PROVIDER || null,
      raw: evidence,
    });
  } catch (error) {
    sendJson(response, 502, { error: error?.message || String(error) });
  } finally {
    if (workdir) await rm(workdir, { recursive: true, force: true }).catch(() => {});
  }
});

server.listen(PORT, HOST, () => {
  console.log(`modlens 视觉桥已启动：http://${HOST}:${PORT}`);
  console.log('  GET  /health   健康检查');
  console.log('  POST /analyze  图片 → 文字（调用 modlens analyze）');
  if (!PROVIDER) {
    console.log('提示：未设置 MODLENS_PROVIDER，使用 modlens 默认 provider；先用 modlens doctor 确认 provider 已配置。');
  }
});
