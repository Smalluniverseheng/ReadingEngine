#!/usr/bin/env node
/**
 * 给五产物补齐 google-services.json 的 client 条目。
 *
 * 为什么必须有这一步：`com.google.gms.google-services` 插件在
 * **JSON 里找不到当前 variant 的包名时会直接让 process<Variant>GoogleServices 失败**：
 *   No matching client found for package name 'com.legado.app.novel.release'
 * 而四个独立产物（.novel/.comic/.music/.video × release/debug）都是新包名，
 * 不补就会全量构建失败（本机实测撞到过）。
 *
 * 做法：以 `com.legado.app.release` 那条为模板克隆，只改包名与新生成的 mobilesdk_app_id，
 * 并丢掉 client_type=1 的 Android OAuth 条目（它是绑定「包名 + 签名指纹」注册的，
 * 克隆过来只会是个查不到的假 client）。
 *
 * ★注意：这些 app id 没有在 Firebase 控制台真实注册过，所以四个独立产物的
 *   Firebase Analytics / Performance **不会上报**（静默失败，不影响功能）。
 *   四合一 app flavor 用的是原有 client，统计照常。
 *   要真上报，需要去 Firebase 控制台为这 4 个包名各建一个 Android 应用并重新下载 JSON。
 *
 * 幂等：已存在的包名跳过。
 */
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')

const FILE = path.resolve(__dirname, '../app/google-services.json')
const PROJECT_NUMBER = '620835636521'

const NEW_PACKAGES = [
  'com.legado.app.novel.release',
  'com.legado.app.novel.debug',
  'com.legado.app.comic.release',
  'com.legado.app.comic.debug',
  'com.legado.app.music.release',
  'com.legado.app.music.debug',
  'com.legado.app.video.release',
  'com.legado.app.video.debug',
]

function appId() {
  // Firebase android app id 形状: 1:<project_number>:android:<22 位 hex>
  const hex = crypto.randomBytes(11).toString('hex')
  return `1:${PROJECT_NUMBER}:android:${hex}`
}

function main() {
  const raw = fs.readFileSync(FILE, 'utf8')
  const j = JSON.parse(raw)
  const have = new Set(j.client.map((c) => c.client_info.android_client_info.package_name))

  const tpl = j.client.find(
    (c) => c.client_info.android_client_info.package_name === 'com.legado.app.release'
  )
  if (!tpl) {
    console.error('! 模板 client (com.legado.app.release) 不存在, 无法克隆')
    process.exit(1)
  }

  let added = 0
  for (const pkg of NEW_PACKAGES) {
    if (have.has(pkg)) continue
    const c = JSON.parse(JSON.stringify(tpl))
    c.client_info.mobilesdk_app_id = appId()
    c.client_info.android_client_info.package_name = pkg
    // 丢掉包名/签名绑定的 Android OAuth client
    c.oauth_client = (c.oauth_client || []).filter((o) => o.client_type !== 1)
    j.client.push(c)
    added++
  }

  if (!added) {
    console.log('已是最新, 无新增 client')
    return
  }
  fs.writeFileSync(FILE, JSON.stringify(j, null, 2) + '\n', 'utf8')
  console.log(`新增 ${added} 个 client, 当前共 ${j.client.length} 个:`)
  for (const c of j.client) console.log('  · ' + c.client_info.android_client_info.package_name)
}

main()
