# AI のエージェント向けの案内

このファイルは、Claude Code・Codex などのエージェントがこのリポジトリを変更するときに最初に読む。`CLAUDE.md` はこのファイルを読み込むだけにしてある。機能の説明は README と `docs/usage.md`、`layout.json` の書き方は `docs/layout-json.md` にあり、ここには変更の進め方だけを書く。

## 手順のスキル

`.claude/skills/` に手順を置いた。Claude Code は説明文から自動で選ぶ。ほかのエージェントでは、該当する `SKILL.md` を読んでから作業する。

| スキル | 使う場面 |
|---|---|
| `fork-setup` | フォークした直後に、パッケージ名・アプリ名・署名鍵・動作を確認する端末を自分用に変える |
| `build-and-install` | ビルドして実機か Waydroid にインストールする。Release を作る |
| `change-feature` | 機能を追加・修正する。どのモジュールに書くか、テスト、確認、コミットの書き方 |
| `make-layout` | 自分の画像でコマ割り（`layout.json`）と人物の切り抜きを作り、端末に転送する |
| `export-mockup` | claude.ai の Artifact で作ったコマ割りのモックアップを端末に送る |
| `edge-to-edge` ほか 3 つ | Android の公式スキルのコピー（出所は `.claude/skills/README.md`） |

## 守ること

- モジュールの依存は `docs/development.md` の「構成」の表の向きだけにする。`:core:model` は Android に依存しない純粋な Kotlin で、計算はなるべくここに書いてテストする。機能のモジュールどうしは互いを参照しない。
- Hilt・Room・DataStore・Coil・Navigation・Material3 を導入しない。理由は `docs/development.md` の「依存を絞った理由」にある。導入したいときは利用者に確認する。
- 版権のある画像をコミットしない。画像は `art/` か `*.private.webp` / `*.private.png` の名前で置く（`.gitignore` にある）。テストとサンプルの画像の名前も、実在の作品やキャラクターの名前にしない。
- 署名鍵とパスワードはリポジトリの外に置き、`local.properties` で場所だけを渡す。
- コードのコメント・README・コミットメッセージは日本語で書く。UI の文言は Android の日本語 UI の慣習（分割、結合、削除、非表示、キャンセルのような漢語とカタカナ語の体言止め）に合わせる。
- 見た目の調整は、数値の基準を先に決め、記録と計算で候補を絞ってから実機で見てもらう（ビジュアライザーなら `tools/vis-replay`）。候補を 1 つずつ実機にインストールして目で選ぶ往復を繰り返さない。

## よく使うコマンド

```sh
nix develop -c ./gradlew :core:model:test :feature:home:testDebugUnitTest :feature:editor:testDebugUnitTest
nix develop -c ./gradlew spotlessApply lint
nix develop -c ./gradlew assembleDebug
scripts/e2e-waydroid.sh
```

Nix の devShell は x86_64-linux だけに対応する。ほかの環境では Android Studio（JDK 21、compileSdk 37、build-tools 36.0.0）で開く。詳しくは `build-and-install` スキル。
