# 第七轮：项目评估与优化报告

> 本轮为 `docs/PROJECT_REVIEW_AND_OPTIMIZATION.md` 的发布副本（项目惯例：新一轮放 `reports/roundN-*.md`）。
> 全文见 **`docs/PROJECT_REVIEW_AND_OPTIMIZATION.md`**（评审模板交付文件）。

## 本轮结论（速览）

| 项 | 结果 |
|---|---|
| 修复 | **4 个 P1/P2 可靠性/可用性缺陷 + 4 个 P2/P3 一致性与性能项**（详见 `PROJECT_REVIEW_AND_OPTIMIZATION.md` §3/§4） |
| 关键修复 | ① 删除实例前 `stopAndWait()` 等进程退出（防删不干净残留）② WebView 失败面板不再被 `onPageFinished` 盖掉 ③ 安装超时可读文案 ④ 通知不再误报"运行中" ⑤ 实例/日志页去 Material（§2.5 收尾）⑥ 日志环剪 O(n²)→O(n) ⑦ 删未引用文案 |
| 验证 | 编译 BUILD SUCCESSFUL ／ 单测 23/23 ／ 脚本一致性 18/18 ／ §2.5.5 验收命令 1 通过 |
| 未做 | 打包（约定）、真机 e2e（缺 proot/rootfs + 无设备）、在线 Gradle 单测（Google Maven 不可达） |
| 未决 | **targetSdk 34 vs 28**（真机 W^X，见原文 §11.1）、CI 重写、死代码清理、`MaterialAlertDialogBuilder`→`FCLAlertDialog` 分批替换 |

相关文件：`docs/PROJECT_REVIEW_AND_OPTIMIZATION.md`（完整报告）、`docs/CHANGELOG.md`、`docs/LESSONS.md` §5/§6。
