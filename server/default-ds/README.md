# 默认 DS 服务

公开客户端不携带共享 API Key。未配置个人密钥的客户端通过本服务调用 DeepSeek 官方接口；个人密钥及其它供应商直接使用各自接口，不经过本服务。

将 `worker.mjs` 作为 Worker 入口，并在部署环境中将 `DEEPSEEK_API_KEY` 设置为服务端秘密变量。不要写入源码、客户端配置或构建产物。默认客户端入口为 `/v1/chat/completions`，健康检查为 `/health`。

服务限制模型、请求大小、输出长度及请求频率，固定上游地址，不保存对话或 API 花费记录。上游错误采用通用响应，避免回传凭据。运行 `node --test server/default-ds/worker.test.mjs` 验证请求边界、错误脱敏和频率限制。
