# Pikafish 对应源码与许可

历史交付的 ARM64 独立引擎来自上游 `Pikafish-2026-09-06`，对应源码提交 `4c17cee11f888ae1d48a9494f2e2239f019f0a1f`。完整源码 ZIP 的 SHA256 为 `2b762c967fae882aa9a8dfe76d868b4665e658f55dead2940b53ce1548f12e0c`，保留 `AUTHORS` 与 GNU GPL v3 `Copying.txt`。二进制未修改，只为 Android 打包重命名；对应关系见 `release-manifest.json`。源码含 Makefile，构建帮助见上游源码中的说明。

引擎源码的 GPL 与神经网络权重的许可不同。`NNUE-License.md` 禁止未获授权的商业使用，不能用本目录源码许可替代。当前候选版不提供该权重下载；权重的来源与旧版对应信息仍保留，避免误导旧版接收者。本目录没有复制权重。

本目录只涉及第三方引擎与对应材料，不将应用主体或服务端作为该引擎源码的一部分公开。
