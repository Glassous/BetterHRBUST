/**
 * 将 vite 构建出的 dist-userscript/app.bundle.js 包装为可直接安装的油猴脚本。
 * 前置步骤:vite build --config vite.config.userscript.mjs(由 npm run build:userscript 一并执行)
 *
 * 包装层职责(与业务代码解耦):
 *  1. 提供 userscript 元数据头;
 *  2. 提供「查看原版教务系统」的油猴菜单开关(应用为只读客户端,
 *     选课等写操作仍需原版页面;用 sessionStorage 记忆,仅对当前标签页生效);
 *  3. 内联应用主体。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const webRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const bundlePath = path.join(webRoot, 'dist-userscript', 'app.bundle.js');
const outPath = path.join(webRoot, 'dist-userscript', 'better-hrbust.user.js');

const pkg = JSON.parse(readFileSync(path.join(webRoot, 'package.json'), 'utf8'));

// CI 注入(本地构建时留空):
//  - USERSCRIPT_DOWNLOAD_URL: 指向 dist 分支 raw 地址,写入 @downloadURL/@updateURL,
//    油猴据此做自动更新检查
//  - USERSCRIPT_VERSION_SUFFIX: 版本追加构建号(如 1.0.0.42),上游不 bump 版本时
//    也能让油猴识别出新构建
const downloadUrl = (process.env.USERSCRIPT_DOWNLOAD_URL || '').trim();
const versionSuffix = (process.env.USERSCRIPT_VERSION_SUFFIX || '').trim();
const version = versionSuffix ? `${pkg.version}.${versionSuffix}` : pkg.version;

let bundle = readFileSync(bundlePath, 'utf8');
// 末尾的 sourcemap 行注释会把随后拼上的闭合代码一并注释掉,先移除
bundle = bundle.replace(/^[ \t]*\/\/[#@] sourceMappingURL=.*$/gm, '').trimEnd();

const updateMeta = downloadUrl
  ? `// @downloadURL ${downloadUrl}\n// @updateURL   ${downloadUrl}\n`
  : '';

const header = `// ==UserScript==
// @name         BetterHRBUST 教务工作台
// @namespace    https://github.com/Glassous/BetterHRBUST
// @version      ${version}
// @description  哈理工教务在线(JWP/URP)现代化客户端:课表/成绩/考试/空教室/GPA 分析,同源直连真实教务数据
// @author       Glassous
// @match        *://jwzx.hrbust.edu.cn/*
// @run-at       document-end
// @noframes
// @grant        GM_registerMenuCommand
// @homepageURL  https://github.com/Glassous/BetterHRBUST
// @license      MIT
${updateMeta}// ==/UserScript==
`;

const output = `${header}
;(function () {
  'use strict';

  var OFF_KEY = 'betterHRBUST:showOriginal';

  function isOff() {
    try { return sessionStorage.getItem(OFF_KEY) === '1'; } catch (e) { return false; }
  }

  if (typeof GM_registerMenuCommand === 'function') {
    GM_registerMenuCommand(
      isOff() ? '启用 BetterHRBUST 界面' : '查看原版教务系统(选课等操作)',
      function () {
        try { sessionStorage.setItem(OFF_KEY, isOff() ? '0' : '1'); } catch (e) { /* 忽略 */ }
        location.reload();
      }
    );
  }

  if (isOff()) return; // 本标签页处于「查看原版」状态,不接管

  /* ===== BetterHRBUST 应用主体(vite 构建产物) ===== */
${bundle}
})();
`;

writeFileSync(outPath, output);

const kb = (n) => `${(n / 1024).toFixed(1)} KB`;
console.log(`[wrap-userscript] ${path.relative(webRoot, outPath)} 已生成(${kb(output.length)}),主体 ${kb(bundle.length)},版本 ${version}${downloadUrl ? `,更新地址 ${downloadUrl}` : ''}`);
