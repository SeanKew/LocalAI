# 认知记忆系统（长时记忆 · 本地 RAG · 自学习）

## 数据流

```
用户消息 ─┬─ "记住：…" ──────────────► ExplicitMemory ──► upsert ─┐
          │                                                      ▼
          ├─ composeSystemPrompt ──► 候选(最近+置顶+关键词LIKE) ──► MemoryRetriever 打分/限额
          │        ▲                                              │
          │        └──── Room(ArchivistDB) ◄── upsert/去重/容量清理 ◄┘
          ▼
   MnnLlm.chat(prompt, systemPromptOverride = 基础提示词 + 记忆块)
          │
          └─ 回答顺利结束 ─► ReflectionGate(是否值得反思) ─► 等 2s ─► MnnLlm.complete(反思提示词)
                             ─► ReflectionParser(严格校验+隐私过滤) ─► upsert
```

## 分层

| 层 | 文件 | 依赖 | 测试 |
|----|------|------|------|
| 纯逻辑 | `MemoryText` `MemoryRetriever` `MemoryPrompt` `Reflection` `CognitiveMemoryManager` | 仅 Kotlin + coroutines | JVM 单元测试 |
| 存储抽象 | `MemoryStore` | — | `InMemoryMemoryStore`（测试用） |
| 持久化 | `MemoryEntity` `MemoryDao` `ArchivistDB` `RoomMemoryStore` | Room | CI 编译 / 真机 |
| 集成 | `ChatViewModel` `MnnLlm.chat/complete` `MainActivity` | Android | 真机 |

## 关键设计决策

- **检索是词法的，不是语义的。** 端侧没有嵌入模型。中文用 bigram（丢弃含虚词的），英文用单词。
  升级路径：引入小型嵌入模型后，在 `MemoryRetriever.score` 里换成向量相似度，其余不变。
- **常驻 + 话题两类名额。** 置顶记忆和高重要度偏好总是注入；其余必须词法相关才注入。
  没有相关记忆时系统提示词与原来完全一致（零额外 token）。
- **每轮都会整段重新预填充**（native 每次生成都 reset KV），所以记忆块有严格字符预算（默认 480 字符 / 5 条）。
- **聊天永远优先于学习。** 反思用 `MnnLlm.complete()`（无状态、不碰对话历史），用户一发消息就被取消，
  取消会等 native 真正退出、状态回到 READY 后才返回。
- **宁缺毋滥。** 反思输出必须是 `类型|重要度|内容` 且通过校验（长度、非提问、无隐私/凭据、无控制标记），
  与已有记忆精确重复则强化、近重复（Jaccard ≥ 0.6，同类型）则合并，总量上限 500 条（置顶不清理）。
- **记忆是"背景资料"不是"指令"。** 注入块带明确声明，内容经 `sanitizeForPrompt` 清洗（去换行、拆控制标记、限长）。
- **用户可见可控。** 日志面板显示每次"已记住/已更新"；界面有总开关、自学习开关、记忆管理（查看/置顶/删除/清空）。

## 已知限制

- 1.5B 模型的信息抽取质量有限，会漏记也可能记错 —— 这是需要"管理记忆"界面的原因。
- 反思期间预填充无法被打断，最坏情况下用户追问要多等一次预填充的时间。
- 数据库为明文存储在应用私有目录；`allowBackup=false` 已避免云备份。需要加密可换 SQLCipher。
- schema 变更必须写 Migration，不要使用 `fallbackToDestructiveMigration`（会清空记忆）。
