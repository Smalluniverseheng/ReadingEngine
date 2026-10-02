#!/usr/bin/env node
// 小说引擎「分享版」出厂源一键剔除（PLAN-4.56-ENGINES.md §一 第 2 件事）
//
// 背景：本引擎是自用版，出厂源包（app/src/main/assets/defaultData/bookSources.json）
//   直接打进 APK，装上就能搜、不用任何配置。**但分享给别人之前必须先把源剔掉**
//   —— 这是规划里写明的"分享前的强制前置"。
//
// 用法：
//   node tools/strip-sources.mjs            # 剔除（把源包置空，自动先备份）
//   node tools/strip-sources.mjs --dry      # 只看会删什么，不落盘
//   node tools/strip-sources.mjs --restore  # 从最近的备份还原
//
// 设计要点：
//   · **不是删文件**。源包路径被代码读，文件消失会走异常分支；置成 `[]` 才是安全的"没有源"。
//   · 落盘前先把原文件整份复制到 `.strip-backup/<时间戳>/`，并回读校验 sha256 一致，
//     校验不过就中止 —— 免得"以为备份了、其实没备"。
//
// 注：本文件是 .mjs（ESM），**不能用 require** —— 会 "ReferenceError: require is not defined
//   in ES module scope" 直接死掉，且因为死在 import 阶段连一行输出都没有。
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PACK = path.join(ROOT, 'app/src/main/assets/defaultData/bookSources.json');
const BACKUP_DIR = path.join(ROOT, '.strip-backup');

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const args = new Set(process.argv.slice(2));
const DRY = args.has('--dry');
const RESTORE = args.has('--restore');

function latestBackup() {
  if (!fs.existsSync(BACKUP_DIR)) return null;
  const dirs = fs.readdirSync(BACKUP_DIR).filter((d) => fs.statSync(path.join(BACKUP_DIR, d)).isDirectory()).sort();
  for (const d of dirs.reverse()) {
    const p = path.join(BACKUP_DIR, d, 'bookSources.json');
    if (fs.existsSync(p)) return p;
  }
  return null;
}

if (RESTORE) {
  const src = latestBackup();
  if (!src) { console.error('没有可还原的备份（' + BACKUP_DIR + ' 下没找到 bookSources.json）'); process.exit(1); }
  fs.mkdirSync(path.dirname(PACK), { recursive: true });
  fs.copyFileSync(src, PACK);
  const back = fs.readFileSync(PACK);
  console.log('已还原 ' + src + ' → ' + PACK);
  console.log('  条数 ' + JSON.parse(back.toString('utf8')).length + '，sha256 ' + sha256(back).slice(0, 16));
  process.exit(0);
}

if (!fs.existsSync(PACK)) { console.error('找不到出厂源包: ' + PACK); process.exit(1); }
const orig = fs.readFileSync(PACK);
const origSha = sha256(orig);
let arr = [];
try { arr = JSON.parse(orig.toString('utf8')); } catch (e) { console.error('源包不是合法 JSON: ' + e.message); process.exit(1); }

const byType = {};
for (const s of arr) { const k = String(s.bookSourceType ?? 0); byType[k] = (byType[k] || 0) + 1; }
console.log('出厂源包 ' + PACK);
console.log('  当前 ' + arr.length + ' 条（启用 ' + arr.filter((s) => s.enabled !== false).length + '），类型分布 ' + JSON.stringify(byType));
console.log('  sha256 ' + origSha.slice(0, 16) + '  size ' + orig.length);

if (DRY) { console.log('\n--dry：以上内容将全部被剔除（不落盘）'); process.exit(0); }

// 1) 备份 + 回读校验
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const dir = path.join(BACKUP_DIR, stamp);
fs.mkdirSync(dir, { recursive: true });
const dst = path.join(dir, 'bookSources.json');
fs.writeFileSync(dst, orig);
const back = fs.readFileSync(dst);
if (sha256(back) !== origSha) { console.error('备份校验失败（sha256 不一致），已中止，源包未改动'); process.exit(1); }
console.log('\n已备份 → ' + dst + '（sha256 已回读校验一致）');

// 2) 置空（保留合法 JSON 数组，不是删文件）
fs.writeFileSync(PACK, '[]\n', 'utf8');
console.log('已剔除：源包置为 ' + fs.readFileSync(PACK, 'utf8').trim() + '（文件仍在，路径不变，运行时即"没有出厂源"）');
console.log('\n下一步：重新出包（gradlew :app:assembleRelease），产出的就是可分享的干净版。');
console.log('想还原：node tools/strip-sources.mjs --restore');
