# 棋桌中继部署

旧版 PeerJS 包含的免费 TURN 服务在 2024 年已经停用。房间码出现在信令服务上，不代表手机流量网络已经建立数据通道。仅更改重试次数无法解决运营商的对称 NAT。

本目录提供 coturn + 短期凭证接口。当前工程没有已部署的中继，也没有可用的部署账户；因此默认资源只有 STUN，遇到无法直连的网络会给出明确错误。**加入界面能正确结束等待不等于已经完成流量跨网验收。**

1. 准备一台有公网 IPv4 的 Linux 主机，配置 `turn.example.com` DNS 和有效证书。复制 `.env.example` 为 `.env`，填写实际地址及随机 `TURN_SECRET`。证书放入 `certs/fullchain.pem` 与 `certs/privkey.pem`。
2. 放行 3478 TCP/UDP、443 TCP、49160–49200 UDP。443 为 TURN TLS 专用端口，不与同机 HTTPS 网站共用。执行 `docker compose up -d`。
3. 将 `credentials-worker.mjs` 部署为 Cloudflare Worker，绑定 `TURN_HOST` 和仅服务器可见的 `TURN_SECRET`，为 `/ice` 设置每 IP 每分钟 20 次请求的限速规则。客户端只会得到限时 TURN 用户名和凭证，不会得到共享密钥。
4. 更新 `app/src/main/assets/chess-online/relay-config.json` 的 `credentialEndpoint` 为实际 HTTPS `/ice` 地址。不要在资源中写管理员 API Key 或共享密钥。接口返回标准 `iceServers` 数组；请求 8 秒截止，失败仍可尝试直连。
5. 运行 `python tools/chess_turn_probe.py --credential-url https://实际地址/ice`。它检验 HTTPS、TURN TLS 认证和分配，输出不包含密码。再在两个不同运营商/手机流量网络上各建、加入一次象棋与五子棋房间，落子、后台两分钟回来、悔棋、认输、重开。TURN 分配通过只代表服务器可用，不能代替此实际跨网对局验收。

连线中断保留同一访客身份，协议对每条操作编号并确认，恢复时只重放未确认的操作；不会重新随机棋色或重复落子。临时后台会通知对方暂停棋桌，最多保留 5 分钟恢复窗口；显式离开仍关闭房间。进程被系统杀掉、已关闭房间的跨进程续局目前不支持。

参考：[PeerJS 停用公告](https://github.com/orgs/peers/discussions/1172)、[coturn 部署文档](https://github.com/coturn/coturn/tree/master/docker/coturn)、[coturn TURN REST 凭证](https://github.com/coturn/coturn/blob/master/README.turnserver)。
