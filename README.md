# 阅读引擎

一个跑在设备本地的**书源解析引擎**。装上、导入书源，它就在后台常驻运行，
同一局域网内的阅读前端（手机 / 平板 / 桌面 / 网页）可以直接调用它来搜索、取目录、取正文。

- **纯本地**：不需要账号，不需要登录，不连接任何云端服务，不向任何服务器上报信息。
- **后台常驻**：开机自启，无需手动打开 App。
- **局域网即插即用**：周期性发送 THP/1 广播，前端可自动发现本机；也可手动填写引擎地址。
- **统一输出**：把各书源五花八门的返回，归一化成同一种结构再交给前端。
- **内容类型**：小说、漫画、听书（音频）、视频。

支持的内容与格式取决于你导入的书源；书源越多，可搜索到的内容越全。

## 使用

1. 安装 APK，打开「阅读引擎」。
2. 「书源管理」导入书源（本地文件 / 网络导入 / 二维码均可）。
3. 保持本机联网（同一 Wi-Fi），前端即可自动发现并调用本引擎。

引擎面板里能看到实时状态、局域网地址与协议版本，也可以一键复制引擎地址。

## 协议（THP/1）

引擎在局域网内提供 HTTP 服务，端口 `1234`，无鉴权。

### THP/1 规范端点（推荐）

`module` ∈ `novel` / `comic` / `music` / `video`。响应统一为
`{ok:true, data:…, meta:{…}}`，出错为 `{ok:false, error:{code, message}, meta:{…}}`。
**失败判断的唯一依据是 `ok:false`**；`meta` 携带分页三件套 `cursor` / `hasMore` / `total`。
调用方若带 `X-TH-Request-Id` 请求头，引擎会原样回显。

| 端点 | 参数 | data |
| --- | --- | --- |
| `GET /thp/meta` | — | `{protocol, instanceId, role, name, version, vendor, caps, auth, remote, endpoints, deprecated, ext}` |
| `POST /thp/m/{module}/search` | `{q, limit, cursor}` | `[{id, name, author, coverUrl, intro, ref}]` |
| `POST /thp/m/{module}/toc` | `{id, cursor}` | `[{id, name, index}]` |
| `POST /thp/m/{module}/content` | `{id, chapterId}` | novel→`{text}` · comic→`{images:[]}` · music/video→`{url, header, variants}` |

以上三个也可用 `GET` 版调用：`?q=` / `?id=` / `?id=&chapterId=`。

约定：`limit` 默认 **20**、上限 **100**（超限自动截断不报错）；搜索在 **7 秒**预算内返回，
超时则返回已收集到的部分结果（聚合搜索允许部分返回）。

### 兼容端点（旧草稿，保留以支持老客户端）

| 端点 | 说明 |
| --- | --- |
| `GET /thp/search?type=novel&q=关键词` | 全源并发搜索 |
| `GET /thp/chapters?type=novel&id=书URL` | 目录 |
| `GET /thp/content?type=novel&id=书URL&chapter=章节URL` | 正文 |
| `GET /thp/discover?type=novel` | 发现页：按书源分组返回分类标签 |
| `GET /thp/explore?type=novel&source=源URL&url=分类URL&page=1` | 发现列表 |

自动发现：引擎每 30 秒向 UDP `19527` 广播一次，亮屏/解锁后立即补发一次；停止服务时发下线报文。

```
THP/1 HELLO <port> <instanceId> engine <caps> <name>      # 每 30 秒
THP/1 BYE <instanceId>                                    # 停止服务时
```

`caps` 采用 THP caps 注册表写法（`m:novel,m:comic,m:music,m:video,post-query`）——
与 `/thp/meta` 返回的 `caps` 保持一致。`instanceId` 为标准 UUID，重启不变。

## 源码与许可

本工程基于 [Legado](https://github.com/legado-with-MD3/legado) 改造，遵循 **GPL-3.0** 许可（见 [LICENSE](LICENSE)）。
改造部分：内嵌 THP 局域网服务与发现广播、引擎面板、以及把书源解析结果归一化输出的接口层。

感谢上游及其依赖：

> org.jsoup:jsoup · cn.wanghaomiao:JsoupXpath · com.jayway.jsonpath:json-path · com.github.gedoor:rhino-android ·
> com.squareup.okhttp3:okhttp · com.github.bumptech.glide:glide · org.nanohttpd:nanohttpd ·
> org.nanohttpd:nanohttpd-websocket · cn.bingoogolapple:bga-qrcode-zxing · com.jaredrummler:colorpicker ·
> org.apache.commons:commons-text · io.noties.markwon:core · io.noties.markwon:image-glide · com.hankcs:hanlp ·
> com.positiondev.epublib:epublib-core · com.github.Moriafly:LyricViewX · io.github.rosemoe:editor

## 构建

```bash
./gradlew :app:assembleAppRelease -ParmOnly=true
```

签名参数通过 `-PRELEASE_STORE_FILE` / `-PRELEASE_STORE_PASSWORD` / `-PRELEASE_KEY_ALIAS` / `-PRELEASE_KEY_PASSWORD` 传入，
CI 中由仓库 Secret（`ENGINE_KEY_STORE` / `ENGINE_STORE_PASSWORD` / `ENGINE_KEY_PASSWORD`）提供。
推送 `engine-v*` 标签或手动触发 `engine-build` 工作流即可出包。
