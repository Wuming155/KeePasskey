# 标签（Tags）互操作 fixture（ISSUE-P2-282）

- **文件**：`pykeepass-comma-tags.kdbx`
- **生成方式**（2026-09-23，pykeepass 4.2.0）：

  ```python
  from pykeepass import PyKeePass, create_database
  create_database(path, password='tags-fixture-2026')
  pk = PyKeePass(path, password='tags-fixture-2026')
  e = pk.add_entry(pk.root_group, title='CommaTagsEntry', username='user', password='pass')
  e.tags = ['alpha', 'beta', 'gamma']
  pk.save()
  ```

- **口令**：`tags-fixture-2026`
- **形态证据**：`keepassxc-cli export -f xml` 显示 `<Tags>alpha,beta,gamma</Tags>`（逗号连接，
  KeePassXC / pykeepass 写侧形态）——本 fixture 即「逗号库」样本。
- **期望值**（由 `KdbxTagsFixtureRoundtripTest` 锁定）：
  1. 本仓读侧解析出 **3 个**标签（`alpha` / `beta` / `gamma`，此前只按 `;` 切会读成 1 个）；
  2. 本仓回写后 XML 为 `<Tags>alpha;beta;gamma</Tags>`（官方裸 `;` 存储形态）；
  3. 整库加密往返后标签集合不变。
