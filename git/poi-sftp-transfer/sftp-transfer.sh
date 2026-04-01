#!/bin/bash
# ============================================================
# POI SFTP 전송 Jenkins 실행 스크립트
# sftp -P 40022 cptmap@223.39.122.217
# ============================================================

set -euo pipefail

# ── 파라미터 ──────────────────────────────────────────────
BASE_SDT="${1:-$(date +%Y%m01)}"          # 기준일자 (기본: 이번달 1일)
MODE="${2:-job}"                           # job | test | list
DB_CONFIG_FILE="$HOME/.m2/db-config.properties"
APP_JAR="/opt/poi/lib/poi-sftp-transfer.jar"
LOG_DIR="/var/log/poi/sftp"
LOG_FILE="$LOG_DIR/sftp_${BASE_SDT}_$(date +%H%M%S).log"

# ── 디렉터리 ──────────────────────────────────────────────
LOCAL_DIR="/data/poi/export/${BASE_SDT}"
REMOTE_DIR="/home/cptmap/data/${BASE_SDT}"

# ── DB 설정 로드 ──────────────────────────────────────────
if [ -f "$DB_CONFIG_FILE" ]; then
    DB_USER=$(grep "jdxbatch.jdbc.poi_stat_real.username"       "$DB_CONFIG_FILE" | cut -d'=' -f2 | xargs)
    DB_PASS=$(grep "jdxbatch.jdbc.poi_stat_real.password.shell" "$DB_CONFIG_FILE" | cut -d'=' -f2 | xargs)
    DB_TNS=$(grep  "jdxbatch.jdbc.poi_stat_real.tnsname"        "$DB_CONFIG_FILE" | cut -d'=' -f2 | xargs)
    SFTP_PASS=$(grep "sftp.cptmap.password"                     "$DB_CONFIG_FILE" | cut -d'=' -f2 | xargs)
else
    echo "[ERROR] DB 설정 파일 없음: $DB_CONFIG_FILE"
    exit 1
fi

# ── 준비 ──────────────────────────────────────────────────
mkdir -p "$LOG_DIR"

echo "============================================================"
echo "[SFTP] 전송 시작: BASE_SDT=${BASE_SDT}, MODE=${MODE}"
echo "[SFTP] 로컬: ${LOCAL_DIR}"
echo "[SFTP] 원격: cptmap@223.39.122.217:40022:${REMOTE_DIR}"
echo "============================================================"

# ── 로컬 디렉터리 존재 확인 ──────────────────────────────
if [ "$MODE" = "job" ] && [ ! -d "$LOCAL_DIR" ]; then
    echo "[ERROR] 로컬 디렉터리 없음: $LOCAL_DIR"
    exit 1
fi

# ── 파일 수 확인 ──────────────────────────────────────────
if [ "$MODE" = "job" ]; then
    FILE_COUNT=$(find "$LOCAL_DIR" -type f | wc -l)
    echo "[SFTP] 전송 대상 파일: ${FILE_COUNT}건"
    if [ "$FILE_COUNT" -eq 0 ]; then
        echo "[WARN] 전송할 파일이 없습니다."
        exit 0
    fi
fi

# ── Java 실행 ──────────────────────────────────────────────
java \
  -Xmx512m \
  -Dfile.encoding=UTF-8 \
  -DSFTP_PASSWORD="${SFTP_PASS}" \
  -DDB_USER="${DB_USER}" \
  -DDB_PASS="${DB_PASS}" \
  -DDB_SERVICE="${DB_TNS}" \
  -jar "${APP_JAR}" \
  --mode="${MODE}" \
  --localDir="${LOCAL_DIR}" \
  --remoteDir="${REMOTE_DIR}" \
  --baseSdt="${BASE_SDT}" \
  2>&1 | tee "$LOG_FILE"

EXIT_CODE=${PIPESTATUS[0]}

echo "============================================================"
if [ $EXIT_CODE -eq 0 ]; then
    echo "[SFTP] 전송 완료 (EXIT: $EXIT_CODE)"
else
    echo "[SFTP] 전송 실패 (EXIT: $EXIT_CODE)"
    echo "[SFTP] 로그: $LOG_FILE"
fi
echo "============================================================"

exit $EXIT_CODE
