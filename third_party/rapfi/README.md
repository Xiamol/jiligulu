# Rapfi 对应源码与构建资料

引擎固定为 `3c94c2a976f24a0dd1c5517623e9ab6fffe66bd7`，完整上游源码在本目录 ZIP 中，SHA256 为 `06c73fd9385c4ef65787c26e02cf20d8347b010622e33c8d0d8b543ca52ab141`。引擎适用 GNU GPL v3，保留 `AUTHORS`、`COPYING.txt` 和各内嵌库声明。

Android 仅应用 `android-bionic.patch`：Bionic 由 libc 提供 pthread，不链接不存在的独立 libpthread。引擎算法没有修改。NDK、CMake、Ninja 的固定下载地址、校验值、ABI、编译参数和交付二进制 SHA256 见 `release-manifest.json`。

使用 Windows、Python 3，在本目录树根运行：

```powershell
python tools/build_rapfi_android.py --prepare-tools
```

`--cache` 可指定独立构建缓存。公开配方只把原私有机器的默认缓存位置改为用户目录下的 `.cache/RapfiToolchain`，并加入仅适用于该构建脚本的 GPL 声明；编译参数、补丁及引擎输入不变。`release-manifest.json` 的 `recipeSha256` 记录原交付配方，公开可移植脚本自身的校验值见本次发布清单。输出 `app/src/main/jniLibs/<ABI>/librapfi.so` 是独立 PIE 可执行文件，经标准进程协议通信，不是 JNI 链接库。

权重固定来自 `dhbloo/rapfi-networks` 的 `e32ad77a5364363b3e3a02b3f9e8610ade19ea98`，适用 `WEIGHTS-CC0.txt`。具体文件和 SHA256 在 manifest 中，本目录不重复存放模型权重。
