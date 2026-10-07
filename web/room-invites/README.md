# 棋桌邀请页

静态支持页，仅把经过校验的棋种和房间码交给 Android App。没有房间服务器、分析脚本或用户资料收集。

生产地址：`https://xiamol.github.io/jiligulu/join/?game=xiangqi&code=ABCD2026`。GitHub Pages 使用独立 `room-invites` 内容分支，不依赖 App 主分支构建。页面按钮在 Android 浏览器通过显式 `intent://` 打开 `com.jiligulu.app`；其它客户端可能要求在系统浏览器打开。App 接收 HTTPS 和 `jiligulu://` 两种邀请，仍会核对房间是否存在、是否可连接。

双方须安装支持新握手与邀请的版本。邀请页可打开不代表流量跨网已通过，中继部署见 `server/chess-relay/README.md`。
