package com.tmap.poi.sftp.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SftpTransferResult {
    private String localPath;
    private String remotePath;
    private boolean success;
    private long fileSize;
    private long transferTimeMs;
    private int retryCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Summary {
        private int totalFiles;
        private int successCount;
        private int failureCount;
        private long totalBytes;
        private long elapsedMs;
        private List<SftpTransferResult> details;

        public boolean isAllSuccess() {
            return totalFiles > 0 && failureCount == 0;
        }

        public String getSummaryText() {
            return String.format("전송 요약: [총 %d건] 성공: %d, 실패: %d | 총 %,d bytes | %dms",
                totalFiles, successCount, failureCount, totalBytes, elapsedMs);
        }
    }
}