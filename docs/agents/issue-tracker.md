# Issue tracker: Local Markdown

用户已在任务拆分审阅后确认采用本地 Markdown 跟踪。本项目没有已配置的远程 issue tracker；本地发布不代表远程 issue 已创建。

## 约定

- 功能目录：`.scratch/social-governance-risk/`，索引为其中的 `README.md`。
- 一项任务一个文件，保存在 `issues/<NN>-<slug>.md`，编号从 01 起，前置任务先编号。
- 来源规格保留在文档目录，以索引中的链接访问，不复制出第二份权威规格、不改写父规格。
- 任务使用 `What to build`、`Blocked by`、`Status` 及验收标准；Blocked by 列出直接前置的编号与标题。
- 已批准待执行任务状态为 `ready-for-agent`；只有前置全为 `done` 才能开工。
- 任务完成须有验收证据，状态改为 `done`。实现讨论追加在相应文件的 `## Comments` 下。
- 发布任务指新增本地逐项文件；读取任务指读取对应文件。不自动发送外部消息或创建远程 issue。
- 不把任务状态当作应用功能或测试已经完成的证据。

