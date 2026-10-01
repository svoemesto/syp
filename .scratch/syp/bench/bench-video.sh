#!/usr/bin/env bash
# Замер бюджета видео-части пайплайна SYP на реальном файле из архива.
# Дата: 2026-10-01. Машина: nsa-i9, 36 ядер. GPU в песочнице агента НЕ видна
# (bwrap фильтрует /dev/nvidia*) — замеры чисто CPU/IO.
# Вывод в /dev/null: диск не расходуется.

export LC_ALL=C
VID="${1:-/disks/HDD_16Tb_Clouds/GOT/GOT.S01/GOT.S01E01.BDRip.1080p.mkv}"
OUT="${2:-/home/nsa/syp/.scratch/syp/bench/bench-video.log}"

mkdir -p "$(dirname "$OUT")"

elapsed() { awk -v s="$1" -v e="$2" 'BEGIN{printf "%.1f", e-s}'; }

{
  echo "=== Бюджет видео-части SYP (замер 2026-10-01) ==="
  echo "Файл:        $VID"
  echo "ffmpeg:      $(ffmpeg -version 2>/dev/null | head -1)"
  echo "Ядра:        $(nproc)"
  echo "Длительность:$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$VID") с"
  echo "Разрешение:  $(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "$VID")"
  echo

  run() {
    local title="$1"; shift
    local s e rc
    s=$(date +%s.%N); "$@" > /dev/null 2>/tmp/bench-err.txt; rc=$?; e=$(date +%s.%N)
    printf '%-46s %8s с  rc=%d\n' "$title" "$(elapsed "$s" "$e")" "$rc"
    [ "$rc" -ne 0 ] && head -2 /tmp/bench-err.txt | sed 's/^/      /'
    return 0
  }

  echo "--- 1. Декодирование (вход пайплайна) ---"
  run "весь файл в null" ffmpeg -v quiet -i "$VID" -f null -

  echo
  echo "--- 2. Детекция границ (scdet) ---"
  run "scdet t=8, весь файл" ffmpeg -v quiet -i "$VID" -vf "scdet=t=8" -f null -

  echo
  echo "--- 3. Подготовка кадров к анализу ---"
  run "scale=135:75 (весь файл)" ffmpeg -v quiet -i "$VID" -vf "scale=135:75" -f null -
  run "scale=640:360 (весь файл)" ffmpeg -v quiet -i "$VID" -vf "scale=640:360" -f null -
  run "scale=1280:720 (весь файл)" ffmpeg -v quiet -i "$VID" -vf "scale=1280:720" -f null -

  echo
  echo "--- 4. Покадровое извлечение (первые 3000 кадров → экстраполяция) ---"
  tmp=$(mktemp -d)
  s=$(date +%s.%N)
  ffmpeg -v quiet -i "$VID" -frames:v 3000 -qscale:v 2 "$tmp/f_%06d.jpg"
  e=$(date +%s.%N)
  cnt=$(ls "$tmp" 2>/dev/null | wc -l)
  t=$(elapsed "$s" "$e")
  printf '%-46s %8s с  (%d файлов)\n' "extract 3000 jpeg 1080p" "$t" "$cnt"
  awk -v t="$t" -v n="$cnt" 'BEGIN{ if (n>0) { rate=n/t; printf "    → %.0f кадров/с → полная серия (82834 кадра) ≈ %.1f мин\n", rate, 82834/rate/60 } }'
  rm -rf "$tmp"

  echo
  echo "--- 5. Нарезка сцены (симуляция фрагмента 30 с) ---"
  run "вырезать 30 с, stream-copy" ffmpeg -v quiet -ss 600 -i "$VID" -t 30 -c copy /dev/null -y
  run "вырезать 30 с, перекодирование x264 veryfast" \
      ffmpeg -v quiet -ss 600 -i "$VID" -t 30 -c:v libx264 -preset veryfast -crf 20 -c:a aac /dev/null -y

  echo
  echo "--- 6. Склейка (concat, 50 фрагментов) ---"
  tmp2=$(mktemp -d)
  for i in $(seq 1 50); do
    printf "file '%s'\n" "$tmp2/p$i.mkv" >> "$tmp2/list.txt"
    ffmpeg -v quiet -ss $((600 + i)) -i "$VID" -t 1 -c copy "$tmp2/p$i.mkv" -y
  done
  s=$(date +%s.%N)
  ffmpeg -v quiet -f concat -safe 0 -i "$tmp2/list.txt" -c copy /dev/null -y; rc=$?; e=$(date +%s.%N)
  printf '%-46s %8s с  rc=%d\n' "concat 50 фрагментов, stream-copy" "$(elapsed "$s" "$e")" "$rc"
  rm -rf "$tmp2"

  echo
  echo "=== Итоги считаются в отчёте research/ ==="
} 2>&1 | tee "$OUT"