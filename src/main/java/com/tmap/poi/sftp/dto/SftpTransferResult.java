package com.tmap.poi.sftp.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SftpTransferResult {
    private String remotePath;
    private boolean success;
    
    // 기본형 long이 아닌 객체형 Long을 사용해야 null 체크가 가능합니다.
    private Long fileSize; 
    private Long transferTimeMs; 
    
    private LocalDateTime finishedAt;
    private String errorMessage;
    private String localPath;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Summary {
        private int totalFiles;
        private int successCount;
        private int failureCount;
        private List<SftpTransferResult> details;

        public String getSummaryText() {
            return String.format("총 %d건 중 %d건 성공 (실패: %d)", totalFiles, successCount, failureCount);
        }
        public boolean isAllSuccess() { return totalFiles > 0 && failureCount == 0; }
    }
}