#!/usr/bin/env perl
# アプリのアイコン（adaptive icon の前景とテーマアイコン用の単色の層）を vector drawable で書き出す。
# 網点の座標を計算で決めるので、手で書かずにこのスクリプトで作る。リポジトリの直下で実行する。
# 使い方: perl scripts/gen-launcher-icon.pl
use strict;
# 108dp の層のうち、どのマスクでも欠けない安全域（直径 66dp）に収まる 32〜76 の正方形に描く
my %P = (
  top => [[32,32],[76,32],[76,47],[32,52]],
  bl  => [[32,55],[57,52.2],[51.5,76],[32,76]],
  br  => [[60.3,51.8],[76,50],[76,76],[54.5,76]],
);
sub poly { my $p = shift; "M" . join("L", map { "$_->[0],$_->[1]" } @$p) . "Z" }
sub n { my $v = sprintf("%.2f", shift); $v =~ s/\.?0+$//; $v }
sub tone {
  my ($f) = @_; my $step = 2.6; my $d = "";
  my $row = 0;
  for (my $y = 30; $y <= 78; $y += $step * 0.866, $row++) {
    for (my $x = 30 + ($row % 2) * $step / 2; $x <= 78; $x += $step) {
      my $v = $f->($x, $y); $v = 0 if $v < 0; $v = 1 if $v > 1;
      my $r = $step * 0.5 * sqrt($v);
      next if $r <= 0.12;
      $d .= "M" . n($x - $r) . "," . n($y) . "a" . n($r) . "," . n($r) . " 0 1,0 " . n(2 * $r) . ",0a" . n($r) . "," . n($r) . " 0 1,0 -" . n(2 * $r) . ",0Z";
    }
  }
  $d;
}
my $INK = "#FF141416"; my $MAG = "#FFE0447A";
my $t1 = tone(sub { my ($x, $y) = @_; 0.15 + 0.85 * (($x - 32) / 44) * (1 - ($y - 32) / 30) });
my $t2 = tone(sub { my ($x, $y) = @_; 0.25 + 0.6 * (($y - 50) / 26) });
my $hdr = qq{<?xml version="1.0" encoding="utf-8"?>\n};
my $vec = qq{<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n};
open my $fg, '>', "app/src/main/res/drawable/ic_launcher_foreground.xml";
print $fg $hdr, "<!-- アイコンの案 A1（トーン）。斜めの線で割った 3 つのコマに、マゼンタと墨の網点を貼る。scripts/gen-launcher-icon.pl で生成した。手で直さない -->\n", $vec;
print $fg qq{    <path android:fillColor="#FFFDE9F0" android:pathData="} . poly($P{top}) . qq{" />\n};
print $fg qq{    <group>\n        <clip-path android:pathData="} . poly($P{top}) . qq{" />\n        <path android:fillColor="$MAG" android:pathData="$t1" />\n    </group>\n};
print $fg qq{    <path android:fillColor="$INK" android:pathData="} . poly($P{bl}) . qq{" />\n};
print $fg qq{    <group>\n        <clip-path android:pathData="} . poly($P{br}) . qq{" />\n        <path android:fillColor="$INK" android:pathData="$t2" />\n    </group>\n};
for my $k (qw(top bl br)) {
  print $fg qq{    <path android:pathData="} . poly($P{$k}) . qq{" android:strokeColor="$INK" android:strokeWidth="1.5" android:strokeLineJoin="round" />\n};
}
print $fg "</vector>\n"; close $fg;
open my $mono, '>', "app/src/main/res/drawable/ic_launcher_monochrome.xml";
print $mono $hdr, "<!-- テーマアイコン用の単色の層。網点は単色にすると読めないので、3 つのコマの形だけにする -->\n", $vec;
print $mono qq{    <path android:fillColor="#FFFFFFFF" android:pathData="} . poly($P{$_}) . qq{" />\n} for qw(top bl br);
print $mono "</vector>\n"; close $mono;
