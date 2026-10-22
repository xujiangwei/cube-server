/**
 * 白泽 API 文档 — 现代主题注入器
 * ---------------------------------------------------------------------------
 * 在 `apidoc -i src -o output` 之后运行，把主题资源复制进 output/assets，
 * 并在 output/index.html 里挂上 theme.css / theme.js 的引用。
 *
 * 设计取舍：不做 apidoc 自定义模板（-t），因为官方模板的 webpack 打包依赖
 * apidoc 自身 node_modules 的解析路径，一旦模板被复制出安装目录，bundle 就
 * 会构建失败且错误被静默吞掉。这里改用「生成后注入」，对 apidoc 版本升级免疫。
 *
 * 幂等：重复执行会先移除旧的注入标记再重新写入。
 *
 * 用法：node apidoc-theme/apply.mjs [outputDir]
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const OUT_DIR = path.resolve(process.argv[2] || path.join(ROOT, 'output'));

const INDEX = path.join(OUT_DIR, 'index.html');
const ASSETS = path.join(OUT_DIR, 'assets');

const STYLE_SRC = path.join(HERE, 'theme.css');
const SCRIPT_SRC = path.join(HERE, 'theme.js');

const MARK = 'data-apidoc-theme';
const STYLE_TAG = `<link href="assets/theme.css" rel="stylesheet" media="screen, print" ${MARK}="css">`;
const SCRIPT_TAG = `<script src="assets/theme.js" ${MARK}="js"></script>`;

function fail(message) {
  console.error(`[apidoc-theme] ${message}`);
  process.exit(1);
}

function inject(html, tag, closer) {
  if (!html.includes(closer)) {
    fail(`index.html 中找不到 ${closer}，跳过注入。`);
  }
  const at = html.lastIndexOf(closer);
  return `${html.slice(0, at)}  ${tag}\n${html.slice(at)}`;
}

function strip(html) {
  return html
    .replace(new RegExp(`\\s*<link[^>]*${MARK}="css"[^>]*>`, 'g'), '')
    .replace(new RegExp(`\\s*<script[^>]*${MARK}="js"[^>]*><\\/script>`, 'g'), '');
}

function main() {
  if (!fs.existsSync(INDEX)) {
    fail(`未找到 ${INDEX}，请先执行 apidoc -i src -o output`);
  }
  if (!fs.existsSync(STYLE_SRC) || !fs.existsSync(SCRIPT_SRC)) {
    fail('主题源文件缺失（theme.css / theme.js）。');
  }

  fs.mkdirSync(ASSETS, { recursive: true });
  fs.copyFileSync(STYLE_SRC, path.join(ASSETS, 'theme.css'));
  fs.copyFileSync(SCRIPT_SRC, path.join(ASSETS, 'theme.js'));

  const original = fs.readFileSync(INDEX, 'utf8');
  let html = strip(original);
  html = inject(html, STYLE_TAG, '</head>');
  html = inject(html, SCRIPT_TAG, '</body>');

  fs.writeFileSync(INDEX, html);

  const kb = (n) => `${(n / 1024).toFixed(1)} KB`;
  console.log(
    `[apidoc-theme] 已应用现代主题 → ${path.relative(process.cwd(), INDEX)}\n` +
    `[apidoc-theme]   assets/theme.css  ${kb(fs.statSync(STYLE_SRC).size)}\n` +
    `[apidoc-theme]   assets/theme.js   ${kb(fs.statSync(SCRIPT_SRC).size)}`
  );
}

main();
