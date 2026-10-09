# 果+ · HGModule

面向红果短剧的 Xposed / LSPosed 模块，提供控件精简、清屏播放、画质与倍速设置，以及自动版本适配。

安装包名：`com.hmodule` · 应用名称：**果+**

[源码仓库](https://github.com/wchunlin1006/HGModule) · [发布页面](https://github.com/wchunlin1006/HGModule/releases) · [问题反馈](https://github.com/wchunlin1006/HGModule/issues)

## 功能

| 分类 | 功能 |
| --- | --- |
| 精简控件 | 按顶部入口、播放操作、互动与弹幕、短剧信息、底部导航分组，逐项选择隐藏；开关统一启停 |
| 布局补位 | 保留的互动按钮从底部排列，作者头像与关注入口同步补位；精简热评等信息后收起空白区域；底栏跨标签精简并重排 |
| 清屏播放 | 隐藏播放页全部控件；退出后恢复未精简的控件；可从长按播放抽屉的“清屏播放（果+）”切换 |
| 暂停时退出清屏 | 暂停时临时显示未精简控件，继续播放后恢复清屏；精简选择保持有效 |
| 显示设置 | 控件透明度、隐藏状态栏、隐藏系统导航小白条 |
| 播放设置 | 默认画质（最高 1080P，不可用时按支持列表回退）、默认倍速、双击打开评论区 |
| 手势设置 | 禁用下拉刷新、顶部下滑拦截 |
| 内容与账号 | 已适配的广告与挂件拦截、VIP 状态及图标相关 Hook；效果取决于客户端版本和业务逻辑 |
| 模块界面 | 首页 / 功能 / 设置三页，胶囊悬浮底栏，左右滑动跟随手势；暗色、亮色与跟随系统主题；清除功能配置 |
| 自动适配 | 红果冷启动时自动检查适配配置，下载后缓存；离线使用已有缓存及内置配置 |

## 安装与使用

需要 Android 10（API 29）或以上，以及支持 **libxposed API 102** 的 Xposed / LSPosed 框架。

1. 安装 APK，在框架中启用果+，勾选红果应用作用域。
2. 完全结束红果进程后重新打开；按框架要求重启作用域或系统。
3. 在 LSPosed 的模块页面打开果+，或通过红果设置入口、长按“清屏播放（果+）”进入模块设置。
4. 打开总开关，再配置需要使用的功能。点击“精简控件”设置行进入单项选择，右侧开关控制整体启停。

功能设置持久保存。清除功能配置不会删除版本适配缓存。

## 版本适配

#### 国内版

- 已适配：7.3.2.32、7.3.9.32，其他版本请自测

#### 海外版

- 未专项适配，自测

## 构建与测试

环境：JDK 17、Android SDK 36、Gradle Wrapper 9.5.1、Android Gradle Plugin 8.13.0、Kotlin 2.1.0。

```bash
git clone https://github.com/wchunlin1006/HGModule.git
cd HGModule
./gradlew testDebugUnitTest assembleDebug
```

Windows 使用 `gradlew.bat testDebugUnitTest assembleDebug`。配置 `ANDROID_HOME` 或在 `local.properties` 中设置 SDK 路径。APK 输出在 `app/build/outputs/apk/debug/`。

签名配置参考 `keystore.properties.example`，自行创建 `keystore.properties`。发布构建应配置固定签名，覆盖安装需要保持签名一致。

## 代码结构

```text
app/src/main/kotlin/com/hmodule/
├── MainHook.kt              # Xposed 入口
├── MainActivity.kt          # 模块界面
├── adaptation/              # 版本适配、缓存及校验
├── config/                  # 功能配置与跨进程共享
├── controls/                # 控件策略、显示恢复与布局补位
├── hooks/                   # 红果 Hook 与版本映射
└── ui/                      # 公共样式、配置页面、手势分页
app/src/test/                # 自动回归测试
adaptation/                  # 适配配置
tools/                       # 开发辅助工具
```

## 许可证

[GNU GPL v3](LICENSE)。
