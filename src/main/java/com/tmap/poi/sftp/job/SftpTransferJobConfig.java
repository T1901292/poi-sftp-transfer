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
            // 경로가 파라미터로 안 오면 설정파일(props)의 기본값 사용
            Path localPath = Paths.get(localDir != null ? localDir : props.getLocalBaseDir());
            String remoteTarget = (remoteDir != null) ? remoteDir : props.getRemoteBaseDir();

            log.info("[JOB] 전송 시작: {} -> {}", localPath, remoteTarget);

            // 전송 실행
            SftpTransferResult.Summary summary = sftpService.uploadDirectory(localPath, remoteTarget);

            // 결과 리스트 출력
            log.info("\n[JOB] ================== 전송 완료 파일 리스트 ==================");
            log.info(String.format("%-40s | %-12s | %-10s", "파일명", "사이즈(Byte)", "전송시간"));
            log.info("--------------------------------------------------------------------------");

            if (summary.getDetails() != null && !summary.getDetails().isEmpty()) {
                summary.getDetails().stream()
                    .filter(Objects::nonNull)
                    .filter(SftpTransferResult::isSuccess)
                    .forEach(r -> {
                        String fileName = (r.getRemotePath() != null) ? Paths.get(r.getRemotePath()).getFileName().toString() : "Unknown";
                        long size = (r.getFileSize() != null) ? r.getFileSize() : 0L;
                        long time = (r.getTransferTimeMs() != null) ? r.getTransferTimeMs() : 0L;
                        log.info(String.format("%-40s | %12d | %8d ms", fileName, size, time));
                    });
            } else {
                log.info(" 전송할 파일이 없거나 전송 결과가 존재하지 않습니다.");
            }
            log.info("==========================================================================");
            log.info("[JOB] 요약: {}", summary.getSummaryText());

            if (!summary.isAllSuccess() && summary.getTotalFiles() > 0) {
                throw new RuntimeException("전송 중 실패 파일이 발생했습니다.");
            }

            return RepeatStatus.FINISHED;
        };
    }
}