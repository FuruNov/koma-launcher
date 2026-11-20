# 公式の Android スキル

github.com/android/skills（Apache-2.0、LICENSE.txt）の commit c729425 から、次の 4 つを写した。

- edge-to-edge（system/）
- testing-setup（testing/）
- android-intent-security（security/）
- jetpack-compose-performance（jetpack-compose/）

上流は Gradle と Compose を前提にしている。testing-setup が勧める Hilt はこのリポジトリでは使わない（理由は README）。

# このリポジトリのスキル

- export-mockup: Artifact で作ったコマ割りのモックアップを、実機か Waydroid に送る手順
