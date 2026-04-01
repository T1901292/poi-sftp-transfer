package com.tmap.poi.sftp.job;

import com.tmap.poi.sftp.config.SftpProperties;
import com.tmap.poi.sftp.dto.SftpTransferResult;
import com.tmap.poi.sftp.service.SftpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;

@Slf4j
@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
public class SftpTransferJobConfig {

    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;
    private final SftpService sftpService;
    private final SftpProperties props;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ──────────────────────────────────────────
    // 1. Jobs (전송용 vs 조회 전용)
    // ──────────────────────────────────────────

    /** [Job 1] 전송 및 검증: 접속체크 -> 업로드 -> 오늘날짜 목록조회 */
    @Bean
    public Job sftpTransferJob() {
        return jobBuilderFactory.get("sftpTransferJob")
            .incrementer(new RunIdIncrementer())
            .listener(jobExecutionListener())
            .start(connectionCheckStep())
            .next(sftpUploadStep())
            .next(remoteListStep())
            .build();
    }

    /** [Job 2] 조회 전용: 접속체크 -> 오늘날짜 목록조회 */
    @Bean
    public Job sftpListOnlyJob() {
        return jobBuilderFactory.get("sftpListOnlyJob")
            .incrementer(new RunIdIncrementer())
            .listener(jobExecutionListener())
            .start(connectionCheckStep())
            .next(remoteListStep())
            .build();
    }

    // ──────────────────────────────────────────
    // 2. Steps
    // ──────────────────────────────────────────

    @Bean
    public Step connectionCheckStep() {
        return stepBuilderFactory.get("connectionCheckStep")
            .tasklet(connectionCheckTasklet(null, null))
            .build();
    }

    @Bean
    public Step sftpUploadStep() {
        return stepBuilderFactory.get("sftpUploadStep")
            .tasklet(sftpUploadTasklet(null, null, null))
            .build();
    }

    @Bean
    public Step remoteListStep() {
        return stepBuilderFactory.get("remoteListStep")
            .tasklet(remoteListTasklet(null))
            .build();
    }

    // ──────────────────────────────────────────
    // 3. Tasklets (비즈니스 로직)
    // ──────────────────────────────────────────

    @Bean
    @StepScope
    public Tasklet connectionCheckTasklet(
        @Value("#{jobParameters['baseSdt']}") String baseSdt,
        @Value("#{jobParameters['remoteDir']}") String remoteDir) {

        return (contribution, chunkContext) -> {
            log.info("[JOB] === SFTP 접속 체크 시작 (baseSdt={}) ===", baseSdt);
            boolean connected = sftpService.testConnection();
            if (!connected) {
                throw new RuntimeException("SFTP 서버 접속 실패: " + props.getHost());
            }
            log.info("[JOB] 접속 확인 성공");
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    @StepScope
    public Tasklet sftpUploadTasklet(
        @Value("#{jobParameters['localDir']}")  String localDir,
        @Value("#{jobParameters['remoteDir']}") String remoteDir,
        @Value("#{jobParameters['baseSdt']}")   String baseSdt) {

        return (contribution, chunkContext) -> {
            Path localPath = Paths.get(localDir != null ? localDir : props.getLocalBaseDir());
            String remotePath = (remoteDir != null && !remoteDir.isBlank())
                ? remoteDir : props.getRemoteBaseDir() + (baseSdt != null ? "/" + baseSdt : "");

            log.info("[JOB] 전송 프로세스 시작: {} -> {}", localPath, remotePath);

            SftpTransferResult.Summary summary = sftpService.uploadDirectory(localPath, remotePath);

            // 리스트 출력 부분 개선
            log.info("");
            log.info("[UPLOAD RESULT] -----------------------------------------------------------");
            log.info(String.format("%-40s | %-12s | %-10s | %-20s", "전송 파일명", "사이즈(B)", "소요시간", "완료시각"));
            log.info("---------------------------------------------------------------------------");

            summary.getDetails().stream().filter(SftpTransferResult::isSuccess).forEach(r -> {
                String fileName = (r.getRemotePath() != null) ? Paths.get(r.getRemotePath()).getFileName().toString() : "Unknown";
                String finishTime = (r.getFinishedAt() != null) ? r.getFinishedAt().format(TIME_FORMATTER) : "N/A";
                log.info(String.format("%-40s | %12d | %8d ms | %-20s", fileName, r.getFileSize(), r.getTransferTimeMs(), finishTime));
            });
            log.info("---------------------------------------------------------------------------");

            // Summary 저장
            chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putString("transferSummary", summary.getSummaryText());

            if (!summary.isAllSuccess()) {
                log.error("[JOB] !!! 전송 실패 발생 !!!");
                summary.getDetails().stream().filter(r -> !r.isSuccess())
                    .forEach(r -> log.error("실패 파일: {} (사유: {})", r.getLocalPath(), r.getErrorMessage()));
                throw new RuntimeException("SFTP 업로드 일부 실패");
            }

            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    @StepScope
    public Tasklet remoteListTasklet(
        @Value("#{jobParameters['remoteDir']}") String remoteDir) {

        return (contribution, chunkContext) -> {
            String targetDir = (remoteDir != null && !remoteDir.isBlank()) ? remoteDir : props.getRemoteBaseDir();

            log.info("[VIEW] === 원격지 오늘 날짜 파일 리스트 (Target: {}) ===", targetDir);
            SftpTransferResult.Summary summary = sftpService.listRemoteFilesByToday(targetDir);

            log.info("");
            log.info("[REMOTE LIST] -------------------------------------------------------------");
            log.info(String.format("%-45s | %-12s | %-20s", "파일명(Remote)", "사이즈(B)", "최종수정시간"));
            log.info("---------------------------------------------------------------------------");

            if (summary.getDetails().isEmpty()) {
                log.info(" 금일 생성되거나 수정된 파일이 존재하지 않습니다.");
            } else {
                summary.getDetails().forEach(r -> {
                    String fileName = Paths.get(r.getRemotePath()).getFileName().toString();
                    String modTime = (r.getFinishedAt() != null) ? r.getFinishedAt().format(TIME_FORMATTER) : "Unknown";
                    log.info(String.format("%-45s | %12d | %-20s", fileName, r.getFileSize(), modTime));
                });
            }
            log.info("---------------------------------------------------------------------------");
            log.info("[VIEW] 총 {}건의 파일이 확인되었습니다.", summary.getTotalFiles());

            return RepeatStatus.FINISHED;
        };
    }

    // ──────────────────────────────────────────
    // 4. Job Listener
    // ──────────────────────────────────────────

    @Bean
    public JobExecutionListener jobExecutionListener() {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution jobExecution) {
                log.info("[JOB] >>> {} 시작 (ID: {})", jobExecution.getJobInstance().getJobName(), jobExecution.getJobId());
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                String jobName = jobExecution.getJobInstance().getJobName();
                BatchStatus status = jobExecution.getStatus();
                
                log.info("[JOB] ====================================================");
                log.info("[JOB] 종료 Job: {}", jobName);
                log.info("[JOB] 최종 상태: {}", status);

                if ("sftpTransferJob".equals(jobName)) {
                    String summary = jobExecution.getExecutionContext().getString("transferSummary", "데이터 없음");
                    log.info("[JOB] 전송 결과: {}", summary);
                }

                if (status == BatchStatus.FAILED) {
                    jobExecution.getAllFailureExceptions().forEach(e -> log.error("[JOB] 예외 발생: {}", e.getMessage()));
                }
                log.info("[JOB] ====================================================");
            }
        };
    }
}