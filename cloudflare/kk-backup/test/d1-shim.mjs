// Minimal Cloudflare D1 stand-in backed by Node's built-in SQLite, so the tests
// run the real schema and SQL.
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";

export function createD1() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));

  const statement = (sql, values = []) => ({
    bind: (...next) => statement(sql, next),
    async first() {
      return sqlite.prepare(sql).get(...values) ?? null;
    },
    async all() {
      return { results: sqlite.prepare(sql).all(...values) };
    },
    async run() {
      const info = sqlite.prepare(sql).run(...values);
      return { meta: { changes: Number(info.changes), last_row_id: Number(info.lastInsertRowid) } };
    },
  });

  return {
    sqlite,
    prepare: (sql) => statement(sql),
    async batch(statements) {
      return Promise.all(statements.map((s) => s.run()));
    },
  };
}
