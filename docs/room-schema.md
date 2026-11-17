# Room schema 导出与迁移约定（ArchivistDB）

## 为什么
记忆库是用户慢慢积累的数据，升级后丢失无法找回。Room 导出的每个版本的表结构 JSON 是以后写
`@AutoMigration` 和迁移测试的前提，所以必须随代码提交。

## 现在的配置
- `@Database(exportSchema = true)`（`ArchivistDB.kt`）。
- `app/build.gradle.kts` 通过 KSP 参数 `room.schemaLocation` 把 JSON 导出到
  `app/schemas/com.seankew.localai.memory.ArchivistDB/<version>.json`。
  没有使用 Room Gradle 插件：插件会按构建变体写出 `schemas/debug/...`、`schemas/release/...` 两份，路径不固定。
- CI 在构建后运行 `scripts/check-room-schema.sh`：JSON 必须已生成、已提交、版本历史完整，否则构建失败。
  无论成功与否，CI 都会上传 `LocalAI-Room-Schemas` 产物。

## 首次接入（一次性）
1. 合并本改动后，第一次 CI 会在守卫这一步**按预期失败**（v1 的 JSON 还没提交）。
2. 下载产物 `LocalAI-Room-Schemas`，把里面的 `com.seankew.localai.memory.ArchivistDB/1.json`
   放到仓库的 `app/schemas/com.seankew.localai.memory.ArchivistDB/1.json` 并提交，CI 转绿。
3. **提交前先核对 identityHash**：已安装过旧版本的设备上，数据库里记录的 hash 必须与 `1.json` 中的 `identityHash` 一致。
   ```bash
   # debug 构建可用 run-as（先把 -wal/-shm 一起拷出，避免 hash 还在 WAL 里）
   for f in archivist.db archivist.db-wal archivist.db-shm; do
     adb exec-out run-as com.seankew.localai cat databases/$f > $f 2>/dev/null || true
   done
   sqlite3 archivist.db "SELECT id, identity_hash FROM room_master_table;"
   grep identityHash app/schemas/com.seankew.localai.memory.ArchivistDB/1.json
   ```
   不一致说明实体在已发布版本之后被改过：先别提交，把两个 hash 和实体改动发给维护者确认。

## 以后改表结构的流程
1. 改实体 / 索引，同时把 `@Database(version = N)` 升到 N+1。
2. 只加列、加表、加索引 → `@AutoMigration(from = N, to = N+1)`；其余 → 手写 `Migration`，注册到 `Room.databaseBuilder(...).addMigrations(...)`。
3. 构建一次，把新生成的 `N+1.json` 提交（CI 守卫会检查）。
4. 写迁移测试（需要 `androidTest`，见后续计划）。
5. 永远不要用 `fallbackToDestructiveMigration`。

## 数据库打不开时
记忆库不可用不会让应用崩溃：`CognitiveMemoryManager.observeAll()` 会兜住异常并发出空列表，
`storeError` 带着原因；界面显示「记忆库不可用」并禁用「管理记忆」。下次重新订阅（回到前台）会重试。
