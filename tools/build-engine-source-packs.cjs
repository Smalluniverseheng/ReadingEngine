#!/usr/bin/env node
/**
 * 生成「五产物」各自的出厂源包。
 *
 *   app   (阅读引擎·四合一)  → 全部类型
 *   novel (小说引擎)          → bookSourceType 0(文本) + 3(文件)
 *   comic (漫画引擎)          → bookSourceType 2(图片)
 *   music (音乐引擎)          → bookSourceType 1(音频)
 *   video (视频引擎)          → bookSourceType 4(视频)
 *
 * 产物写到 app/src/<flavor>/assets/defaultData/bookSources.json
 * （AGP 的 flavor sourceSet 会覆盖 main 的同名资产，所以不需要改任何加载代码。）
 *
 * 视频类型额外从头条：`thirdhub-flutter/assets/sources/video_sources.json`
 * 里 272 个**已做过 HTTP 验证**的 TVBox/苹果CMS 采集接口，用统一的 CMS 规则模板
 * 生成 Legado 视频源 —— 原主源包里 type=4 只有 4 条，等于视频引擎出厂即空。
 *
 * 幂等：重复执行结果一致（去重键 = bookSourceUrl 的主机）。
 */
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const MASTER = path.join(ROOT, 'app/src/main/assets/defaultData/bookSources.json')
// 视频站点库（272 个已做过 HTTP 验证的 TVBox/苹果CMS 接口）随仓库走，CI 也能复现。
const VIDEO_SITES = path.join(ROOT, 'tools/data/video_sites.json')
// 视频站点库里的**已死主机**（实测产出，见文件内 note）。站点库本身保留全量，
// 这里只做「生成时剔除」，好处是黑名单可审计、可回滚、也随时能重测刷新。
const VIDEO_DEAD = path.join(ROOT, 'tools/data/video_sites_dead.json')
// 可选的补充文本源（外部采集产物，不入仓；缺了就跳过）
const EXTRA_TEXT = 'D:/ai/deep seek/sources/book_sources_verified.json'

const UA =
  'Mozilla/5.0 (Linux; Android 13; zh-CN; MI 8 Build/QKQ1.190828.002) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36'

const TYPE_RANGES = {
  novel: [0, 3],
  comic: [2],
  music: [1],
  video: [4],
}

/** 苹果CMS 线路列表里挑「集数最多」的那条线路，避免 $$$ 多线混在一起 */
const TOC_JS = [
  '@js:',
  'var m=String(result).match(/vod_play_url":"(.*?)"/);',
  'if(!m){[]}else{',
  '  var raw=String(m[1]).split(\'\\\\/\').join(\'/\');',
  '  var groups=raw.split("$$$");',
  '  var best="";',
  '  for(var g in groups){ if(String(groups[g]).split("#").length>String(best).split("#").length) best=groups[g]; }',
  '  var s=String(best).split("#");',
  '  var txt=[];',
  '  for(var i=0;i<s.length;i++){',
  '    var p=String(s[i]).split("$");',
  '    var nm=p.shift();',
  '    var u=p.join("$");',
  '    if(u) txt.push({text:nm,href:u});',
  '  }',
  '  txt',
  '}',
].join('\n')

const SEARCH_RULE = (base) => ({
  bookList: '$..list[*]',
  name: '$.vod_name',
  author: '主演:{{$.vod_actor}}',
  kind: '{{$.type_name}}\n{{$.vod_year}}\n{{$.vod_remarks}}',
  intro: '$.vod_content',
  coverUrl: '$.vod_pic',
  bookUrl: `${base}?ac=detail&ids={{$.vod_id}}`,
})

function buildExploreUrl(base) {
  // 苹果CMS 的 t= 分类 id 各站不同，用通用 1..24；不存在的分类返回空列表，无害。
  const arr = [
    { title: '🆕 最新更新', url: `${base}?ac=detail&pg={{page}}` },
  ]
  for (let t = 1; t <= 24; t++) {
    arr.push({ title: `分类 ${t}`, url: `${base}?ac=detail&pg={{page}}&t=${t}` })
  }
  return (
    JSON.stringify(
      arr.map((x, i) => ({
        ...x,
        style: i === 0
          ? { layout_flexGrow: 1, layout_flexBasisPercent: 1 }
          : { layout_flexGrow: 1, layout_flexBasisPercent: 0.25 },
      }))
    ) + '\n'
  )
}

/** 把采集接口 URL 归一到「可拼 ac=detail」的基址 */
function normalizeBase(raw) {
  if (!raw) return null
  let u = String(raw).trim()
  if (!/^https?:\/\//.test(u)) return null
  u = u.replace(/\?.*$/, '').replace(/\/+$/, '')
  // /at/xml 是 XML 接口，JSON 规则解析不了，跳过
  if (/\/at\/xml/i.test(u)) return null
  if (!/provide\/vod|api\.php|jm\.php|seaxml|tvbox/i.test(u)) return null
  return u
}

function hostOf(u) {
  try {
    return new URL(u).host.toLowerCase()
  } catch (e) {
    return String(u).toLowerCase()
  }
}

/** 已实测死亡的主机集合。缺文件时退化为空集（一条都不拉黑），不让构建挂掉。 */
function loadDeadHosts() {
  const dead = new Set()
  if (!fs.existsSync(VIDEO_DEAD)) return dead
  for (const d of (JSON.parse(fs.readFileSync(VIDEO_DEAD, 'utf8')).hosts || [])) {
    if (d && d.host) dead.add(String(d.host).toLowerCase())
  }
  return dead
}

function genVideoSources() {
  if (!fs.existsSync(VIDEO_SITES)) {
    console.log('! 缺少视频站点库, 跳过生成:', VIDEO_SITES)
    return []
  }
  const sites = JSON.parse(fs.readFileSync(VIDEO_SITES, 'utf8'))
  const dead = loadDeadHosts()
  let skippedDead = 0
  const seen = new Set()
  const out = []
  for (const s of sites) {
    const base = normalizeBase(s.url)
    if (!base) continue
    const h = hostOf(base)
    // ★ 实测死源不进出厂包。理由：出厂包是拿来直接搜的，而搜索有固定时间预算
    //   （THP 默认 20s）。死源不会只"不产出结果"，它还会占掉一个并发槽直到自己超时，
    //   于是"源越多 = 结果越少"。剔掉死源等于把预算还给活源。
    if (dead.has(h)) { skippedDead++; continue }
    if (seen.has(h)) continue
    seen.add(h)
    out.push({
      bookSourceComment: `自动生成 · 苹果CMS/TVBox 采集接口 · 出处 ThirdHub 视频源验证库 · 验证时间 ${
        s.checkedAt || '-'
      }`,
      bookSourceGroup: '视频·自动生成',
      bookSourceName: `🎬 ${s.name || h}`,
      bookSourceType: 4,
      bookSourceUrl: base,
      customOrder: 900 + out.length,
      enabled: true,
      enabledCookieJar: false,
      enabledExplore: true,
      header: JSON.stringify({ 'User-Agent': UA, Accept: 'application/json, text/plain, */*' }),
      searchUrl: `${base}?ac=detail&wd={{key}}&pg={{page}}`,
      exploreUrl: buildExploreUrl(base),
      ruleSearch: SEARCH_RULE(base),
      ruleExplore: { ...SEARCH_RULE(base) },
      ruleBookInfo: {},
      ruleToc: { chapterList: TOC_JS, chapterName: 'text', chapterUrl: 'href' },
      // 章节 URL 本身就是 m3u8 直链, 内容规则原样返回即可 (THP music/video 分支会再走 AnalyzeUrl)
      ruleContent: { content: '<js>baseUrl</js>' },
      weight: 0,
    })
  }
  if (dead.size) console.log(`剔除实测死源主机: ${skippedDead} 条 (黑名单共 ${dead.size} 个主机)`)
  return out
}

function main() {
  const master = JSON.parse(fs.readFileSync(MASTER, 'utf8'))
  console.log(`主源包: ${master.length} 条`)

  // 1) 补上 verified 里主源包缺的文本源
  const haveUrl = new Set(master.map((s) => s.bookSourceUrl))
  let addedText = 0
  if (fs.existsSync(EXTRA_TEXT)) {
    for (const s of JSON.parse(fs.readFileSync(EXTRA_TEXT, 'utf8'))) {
      if (!s.bookSourceUrl || haveUrl.has(s.bookSourceUrl)) continue
      haveUrl.add(s.bookSourceUrl)
      master.push(s)
      addedText++
    }
    console.log(`补入 verified 文本源: ${addedText} 条`)
  }

  // 2) 生成视频源（按主机去重，不与主源包重复）
  const haveHost = new Set(master.map((s) => hostOf(s.bookSourceUrl)))
  const gen = genVideoSources().filter((s) => !haveHost.has(hostOf(s.bookSourceUrl)))
  console.log(`生成视频源: ${gen.length} 条`)

  // 3) 剔除死主机 —— 对**全量**生效。
  //    只靠 genVideoSources 内部的过滤只能做到"不再新增第二次"，删不掉上一轮
  //    已经落进 main 的那一份（它们现在属于 master，不再算"生成的"）。
  //    所以在这里对合并后的全量统一清一遍，并把清理结果回写 main，
  //    这样"剔除"是**幂等且持久**的，而不是每次构建都重新捡回来。
  const deadHosts = loadDeadHosts()
  const merged = master.concat(gen)
  const all = deadHosts.size
    ? merged.filter((s) => !deadHosts.has(hostOf(s.bookSourceUrl)))
    : merged
  const pruned = merged.length - all.length
  if (pruned) console.log(`从全量剔除死主机源: ${pruned} 条`)

  // 全量超集回写 main —— `app`(四合一) flavor 直接吃它, 无需覆盖文件
  fs.writeFileSync(MASTER, JSON.stringify(all), 'utf8')
  console.log(`回写 main 全量包: ${all.length} 条 (${(fs.statSync(MASTER).size / 1024 / 1024).toFixed(1)}MB)`)

  // 4) 按 flavor 切包（flavor sourceSet 覆盖 main 同名资产）
  const stat = {}
  for (const [flavor, types] of Object.entries(TYPE_RANGES)) {
    const list = all.filter((s) => types.includes(s.bookSourceType ?? 0))
    const dest = path.join(ROOT, 'app/src', flavor, 'assets/defaultData/bookSources.json')
    fs.mkdirSync(path.dirname(dest), { recursive: true })
    fs.writeFileSync(dest, JSON.stringify(list), 'utf8')
    stat[flavor] = { total: list.length, enabled: list.filter((s) => s.enabled).length, file: dest }
  }

  console.log('\n=== 各产物源包 ===')
  for (const [k, v] of Object.entries(stat)) {
    console.log(
      `${k.padEnd(6)} 共 ${String(v.total).padStart(5)} 条 (启用 ${String(v.enabled).padStart(5)})  ` +
        `${(fs.statSync(v.file).size / 1024 / 1024).toFixed(1)}MB  ${path.relative(ROOT, v.file).replace(/\\/g, '/')}`
    )
  }
  console.log(`app    共 ${String(all.length).padStart(5)} 条 (启用 ${String(all.filter((s) => s.enabled).length).padStart(5)})  ` +
    `${(fs.statSync(MASTER).size / 1024 / 1024).toFixed(1)}MB  app/src/main/assets/defaultData/bookSources.json`)
}

main()
