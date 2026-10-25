# PCLA 界面规格：逐项对标 PCL

所有数值都从 PCL 源码抄的，不是"看起来像"。仓库版本 `2.99.0.0`（`git log -1` = 6b9a50d）。

## 色板（`Application.xaml:22-65`）

| PCL 资源名 | 值 | PCLA 里的用途 |
|---|---|---|
| `ColorObject1` | `#343D4A` | 正文色（是的，PCL 的 Color1 是深板岩不是蓝） |
| `ColorObject2` | `#0B5BCB` | 深蓝，标签文字 |
| `ColorObject3` | `#1370F3` | **主色**，按钮/强调 |
| `ColorObject4` | `#4890F5` | 渐变起点（`FormMain.xaml:105` 的 `GradientStop Color=ColorObject4`） |
| `ColorObject5` | `#96C0F9` | 浅蓝 |
| `ColorObject6` | `#D5E6FD` | 选中列表项描边 |
| `ColorObject7` | `#E0EAFD` | 胶囊标签底色 |
| `ColorObject8` | `#EAF2FE` | 浅蓝底 |
| `ColorObjectGray1..8` | `#404040 → #F5F5F5` | 灰阶八级 |
| `ColorBrushRedLight` | `#FF4C4C` | 警告 |

## 尺寸（XAML 原文）

| 元素 | 来源 | 值 |
|---|---|---|
| 标题栏高度 | `FormMain.xaml:100` `Height="48"` | 48dp |
| 窗口圆角 | `FormMain.xaml:91` `RadiusX="6"` | 6dp（卡片也用它） |
| 导航胶囊 | `MyRadioButton.xaml` `CornerRadius="13.5" MinHeight="27" MaxHeight="27"` | 27dp 高、13.5dp 圆角 |
| 胶囊内边距 | 同上 `Margin="8,0,12,0"` | 左右 12dp 起 |
| 按钮圆角 | `MyButton.xaml` `CornerRadius="3"` | 3dp |
| 按钮描边 | 同上 `BorderThickness="1" BorderBrush=ColorBrush1` | 1dp，颜色 = 文字色 |
| 主按钮高度 | `PageLaunchLeft.xaml:65` `Height="54"` | 54dp |
| 正文/按钮字号 | `Application.xaml:122` `FontSize="13"` | 13sp |
| 列表行高 | `MyListItem.xaml` `Height="42"` | 42dp |
| 内容边距 | `PageLaunchRight.xaml` `Margin="25,25,25,10"` | 桌面 25，手机取 14 |

## 布局映射

| PCL | PCLA | 为什么改 |
|---|---|---|
| 标题栏渐变 + 胶囊导航（启动/下载/联机/设置/更多） | 同样渐变 + 胶囊，但只有「体检 / 降级」 | 页签名必须对应真实功能，写个"下载"里面空着就是骗人 |
| 850×500 双栏，左栏 300px | 单列卡片流 | 手机宽 360dp，塞不下 300 的左栏 |
| 左栏：版本选择 → 正版/离线 → 54 高启动键 → 11px 版本名 | 标签 → 大字版本号 → 54 高键（重新探测）→ 11px 理由 | 按钮换成真实动作，不放假按钮 |
| 右侧 `MyCard` 卡片流 | `PclCard`（圆角 6、无边距内缩） | 保留 |
| logo 是白色描边 Path（`FormMain.xaml:117`，`M26,29 v-25 h6 a7,7...`） | 白色粗体 "PCL" 文字 | 真按 Path 数据画收益低，视觉近似即可 |

## 刻意没做的

- 窗口阴影/透明圆角边框（`Border Margin="8"` + `RectangleGeometry`）：那是无边框窗口的装饰，Android 上有系统圆角。
- `MyCard` 的关闭按钮、`MyHint` 的可关闭提示条：当前没用得上的场景。
- 动态取色（Material You）：会让品牌蓝被壁纸染成紫的，PCL 自己也不跟系统主题色。
