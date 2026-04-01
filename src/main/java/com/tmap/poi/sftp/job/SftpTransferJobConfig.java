package com.tmap.poi.sftp.job;

import com.tmap.poi.sftp.config.SftpProperties;
import com.tmap.poi.sftp.dto.SftpTransferResult;
import com.tmap.poi.sftp.service.SftpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.*;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.*;
import java.util.Objects;

@Slf4j @Configuration @EnableBatchProcessing @RequiredArgsConstructor
public class SftpTransferJobConfig {
    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;
    private final SftpService sftpService;
    private final SftpProperties props;

    @Bean
    public Job sftpTransferJob() {
        return jobBuilderFactory.get("sftpTransferJob")
                .start(sftpUploadStep())
                .build();
    }

    @Bean
    public Step sftpUploadStep() {
        return stepBuilderFactory.get("sftpUploadStep")
                .tasklet(sftpUploadTasklet(null, null))
                .build();
    }

    @Bean @StepScope
    public Tasklet sftpUploadTasklet(
            @Value("#{jobParameters['localDir']}") String localDir,
            @Value("#{jobParameters['remoteDir']}") String remoteDir) {

        return (contribution, chunkContext) -> {
            Path localPath = Paths.get(localDir != null ? localDir : props.getLocalBaseDir());
            String remoteTarget = (remoteDir != null) ? remoteDir : props.getRemoteBaseDir();

            log.info("[JOB] 전송 시작: {} -> {}", localPath, remoteTarget);

            SftpTransferResult.Summary summary = sftpService.uploadDirectory(localPath, remoteTarget);

            log.info("\n[JOB] ================== 전송 완료 파일 리스트 ==================");
            log.info(String.format("%-40s | %-12s | %-10s", "파일명", "사이즈(Byte)", "전송시간"));
            log.info("--------------------------------------------------------------------------");

            if (summary.getDetails() != null) {
                summary.getDetails().stream()
                    .filter(Objects::nonNull)
                    .filter(SftpTransferResult::isSuccess)
                    .forEach(r -> {
                        String fileName = (r.getRemotePath() != null) ? Paths.get(r.getRemotePath()).getFileName().toString() : "Unknown";
                        
                        // [에러 수정 지점] r.getFileSize()를 Object로 취급하여 안전하게 처리
                        Object rawSize = r.getFileSize();
                        Object rawTime = r.getTransferTimeMs();
                        
                        long size = (rawSize instanceof Long) ? (Long) rawSize : 0L;
                        long time = (rawTime instanceof Long) ? (Long) rawTime : 0L;

                        log.info(String.format("%-40s | %12d | %8d ms", fileName, size, time));
                    });
            }
            log.info("==========================================================================");
            log.info("[JOB] 요약: {}", summary.getSummaryText());

            if (!summary.isAllSuccess() && summary.getTotalFiles() > 0) {
                throw new RuntimeException("전송 중 일부 실패 발생");
            }
            return RepeatStatus.FINISHED;
        };
    }
}