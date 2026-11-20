# モックアップ

- `editor.template.html`: コマ割りの編集画面のモックアップ（claude.ai の Artifact）の元の HTML。
  画像と切り抜き（`{{IMAGES_JSON}}` など）は版権のある画像なので入れていない。計算の式（線の木、端を見せない制限、名前の置き場所の探索など）をアプリへ移すときの参照に使う。
- 保存されたコマ割りと画像の選択は、Artifact の db `editor/current` にある。
- 実機へ送る手順は `.claude/skills/export-mockup/SKILL.md`。新しいモックアップを作るときも、後で送れるように、このファイルに書いた保存の形に合わせる。
