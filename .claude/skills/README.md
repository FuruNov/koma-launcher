# 公式の Android スキル

github.com/android/skills（Apache-2.0、LICENSE.txt）の commit c729425 から、次の 4 つを写した。

- edge-to-edge（system/）
- testing-setup（testing/）
- android-intent-security（security/）
- jetpack-compose-performance（jetpack-compose/）

上流は Gradle と Compose を前提にしている。testing-setup が勧める Hilt はこのリポジトリでは使わない（理由は README）。

# このリポジトリのスキル

フォークした人が AI のエージェントに作業を頼むときにも使えるように書いてある。一覧と使い分けはリポジトリ直下の `AGENTS.md` にある。

- fork-setup: フォークした直後に、パッケージ名・アプリ名・署名の鍵・目標の端末を自分用に変える
- build-and-install: ビルドして実機か Waydroid に入れる。Release を作る
- change-feature: 機能を追加・修正するときの書く場所、テスト、確認、コミットの書き方
- make-layout: 自分の画像でコマ割りと人物の切り抜きを作り、端末に入れる
- export-mockup: Artifact で作ったコマ割りのモックアップを、実機か Waydroid に送る

LICENSE.txt は上の 4 つの公式スキルに適用する。このリポジトリのスキルは、リポジトリ直下の LICENSE（MIT）に従う。
