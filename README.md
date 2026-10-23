# 信息互通（PairSync）

两台安卓手机通过对象存储（S3 协议）异步互通：**通知、短信、电量/充电状态、位置**。
消息以 AES-256-GCM 端到端加密后写入信箱前缀，对端轮询拉取并还原为系统通知，云上只见密文。

默认适配 **中国科技云 数据胶囊**（`s3.cstcloud.cn`，SigV4，Path-Style，免费 20GB 不限请求次数），
也可换任何 S3 兼容服务或后续扩展 WebDAV。

## 工作原理

- 每个 App 既是"发送端"也是"接收端"，装两台配好同一份配置即可。
- 发送：`NotificationListenerService` 捕获通知 → 加密 → PUT 到 `msg/{时间戳}-{uuid}.enc`；短信由 `SMS_RECEIVED` 广播捕获；电量/位置每分钟 PUT 到 `dev/{设备ID}.json.enc` 心跳。
- 接收：前台服务按可配置间隔（默认 10s）LIST `msg/` → GET → 解密 → 以系统通知还原 → DELETE 确认。收到 `status` 时在日志里显示对端电量与位置。

## 编译

用 Android Studio（Ladybug/2024.2 以上）打开项目根目录，同步后直接 Run。仓库已配置阿里云 Maven 镜像。

## 首次配置

1. 手机 A：数据胶囊控制台创建 AccessKey，填入 UI（Endpoint `https://s3.cstcloud.cn`、Bucket、AK、SK）。
2. 点【生成新密钥】得到 32 字节共享密钥，自动填入加密密钥框。
3. 【导出配置（复制）】→ 手机 B【导入配置（粘贴）】（微信/短信/文件中转均可，注意会带 SK，用完可改密）。
4. 手机 B 的"设备名称"改成与 A 不同（如"小米14" / "pixel8"）。
5. 两台分别：申请权限（短信/通知/定位），开启【通知监听】，忽略电池优化，然后【启动同步】。

## 注意事项

- **密钥安全**：聊天中贴出的 AKID 建议之后在控制台轮换一次；SK 只应存在 App 内。
- **省电策略**：国产 ROM 会杀后台，需把 App 加入自启动/后台白名单，否则轮询服务可能被冻结。
- **位置**：只有授予"始终允许"（后台定位）后，状态心跳才会带位置；位置取最近一次系统缓存。
- **通知还原**：还原成 App 自己的通知卡（带来源设备名与应用名的标题），无法点开原应用详情内容，属接收端透传展示。
- **短信**：默认只转发上行（收到的短信）并在对端以通知展示；历史批量补同步为 v0.2 计划。
- Android 14 上前台服务走 `remoteMessaging` 类型（消息中转豁免更宽松）；Android 15 同。
- 数据请求开销：每轮询周期 ≈ 1 LIST + (N 条未确认消息的 GET/PUT/DELETE)。数据胶囊不限次数，若换 OSS 需注意请求计费，建议把间隔调到 30s+。

## 目录结构

```
app/src/main/java/com/pairsync/app/
├─ PairSyncApp.kt          Application 初始化
├─ MainActivity.kt         配置/启动/权限 UI
├─ core/
│  ├─ Config.kt            EncryptedSharedPreferences 配置
│  ├─ Crypto.kt            AES-256-GCM 信封加解密
│  ├─ MsgCodec.kt          消息信封 JSON 编解码
│  ├─ S3Client.kt          OkHttp + 手写 SigV4（list/get/put/delete）
│  ├─ Mailbox.kt           信箱收发与确认
│  ├─ SyncEngine.kt        轮询/状态/还原循环
│  └─ Log.kt               环形日志 + 文件
├─ service/
│  ├─ SyncService.kt       前台服务（dataSync|remoteMessaging）
│  └─ BootReceiver.kt      开机自启
└─ capture/
   ├─ NotifCapture.kt      通知捕获
   └─ SmsCapture.kt        短信捕获
```

## 路线

- v0.2：历史短信批量同步、对端定位地图、按包名转发白名单、WebDAV 信箱适配器（坚果云）
