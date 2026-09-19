# 打包工具
把 JDK 自带的 `jpackage` 包装成一个表单：填几个路径，点一下，就能把一堆 class/jar 打成免安装目录或者 Windows 安装包（exe / msi）。说白了就是把原来那个要一步步输入框填参数的 `PackageTool` 搬进减速带里，顺手加上了实时日志和外部预设文件。

## 功能
- **表单化打包**：类型、名称、输入目录、主 Jar、输出目录、图标、版本号，对应 `jpackage` 的
  `--type` / `--name` / `--input` / `--main-jar` / `--dest` / `--icon` / `--app-version`。
- **高级参数**：`--main-class`、`--java-options`（多个用空格分隔，会拆成多个 `--java-options`）、
  `--vendor`、`--description`，以及只在 exe / msi 下生效的 `--win-console`、`--win-menu`、
  `--win-shortcut`、`--win-dir-chooser`。
- **jpackage 自动探测**：jpackage 属于 **JDK**（精简运行时里没有），而且常常不在 `PATH` 上。
  插件会依次查找你手填的路径、`JAVA_HOME`、当前运行时、`PATH`，以及 `C:\Program Files\Java\jdk-*`
  等常见安装位置，取版本最高的一个；点「检测 jpackage」可以重新探测并列出全部候选。
- **实时日志**：打包在后台虚拟线程里执行，jpackage 的输出逐行回显在下方日志区（最多保留 2000 行），
  可以随时「取消」强制结束进程。
- **预设存外部文件**：所有打包数据保存在一个普通文本文件里（默认
  `<减速带数据目录>/PackageTool/package.info`），支持「载入预设…」「保存预设」「另存为…」，
  也可以用记事本直接改。**不占用宿主的配置项**。
- **复制命令**：把当前表单等价的 `jpackage` 命令行复制到剪贴板，方便在终端里手动跑或改。

## 打包前会替你检查
- jpackage 是否存在、类型是否合法、名称/输入目录/Jar/输出目录是否为空、输入目录与图标文件是否真实存在；
- 版本号是否以数字开头（jpackage 的硬性要求）；
- `--main-jar` 是否误填成了带路径的形式、是否在 app-image 下勾了 Windows 安装包选项（这两条只提示，不阻断）。

## 设置项说明
本插件不在减速带的配置里存任何数据，打包数据全部写在预设文件里。文件格式与原版 `PackageTool`
的 `package.info` **完全兼容**：前 7 行依次是

```
类型             (--type)
名称             (--name)
输入目录         (--input)
Jar 文件         (--main-jar)
输出目录         (--dest)
图标文件         (--icon，可空行)
版本             (--app-version，可空行)
```

第 8 行起是本插件扩展的高级参数，形如 `键=值`（`jpackage`、`mainClass`、`javaOptions`、`vendor`、
`description`、`winConsole`、`winMenu`、`winShortcut`、`winDirChooser`），`#` 开头是注释，
不认识的键会被忽略。原版工具只读前 7 行，所以两边写出的文件可以互相读取。

## 注意
- 仅支持 **Windows**（生成 exe / msi 需要 Windows；`info.json` 里已声明 `support_platform: windows`）。
- 打包耗时取决于输入规模，`app-image` 一般几十秒；打包期间请不要关掉设置页（关掉页面进程会继续跑完）。
- 输出目录如果已经存在同名产物，jpackage 会自己报错，日志里能看到原因。
- 仅用于个人学习交流。
