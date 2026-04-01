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

/**
 * SFTP 전송 Spring Batch Job 설정
 *
 * <h3>Jenkins 실행 예시</h3>
 * <pre>
 * java -jar poi-sftp-transfer.jar \
 *   --spring.batch.job.names=sftpTransferJob \
 *   --localDir=/data/poi/export/20260301 \
 *   --remoteDir=/home/cptmap/data/20260301 \
 *   --SFTP_PASSWORD=secret
 * </pre>
 *
 * <h3>Job Parameter</h3>
 * <ul>
 *   <li>localDir  - 전송할 로컬 디렉터리 경로 (필수)</li>
 *   <li>remoteDir - 원격 목적지 디렉터리 (없으면 sftp.remote-base-dir 사용)</li>
 *   <li>baseSdt   - 기준일자 (로그/추적용, 예: 20260301)</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
public class SftpTransferJobConfig {

    private final JobBuilderFactory  jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;
    private final SftpService        sftpService;
    private final SftpProperties     props;

    // ──────────────────────────────────────────
    // Job
    // ──────────────────────────────────────────

    @Bean
    public Job sftpTransferJob() {
        return jobBuilderFactory.get("sftpTransferJob")
            .incrementer(new RunIdIncrementer())
            .listener(jobExecutionListener())
            .start(connectionCheckStep())
            .next(sftpUploadStep())
            .build();
    }

    // ──────────────────────────────────────────
    // Step 1: 접속 확인
    // ──────────────────────────────────────────

    @Bean
    public Step connectionCheckStep() {
        return stepBuilderFactory.get("connectionCheckStep")
            .tasklet(connectionCheckTasklet(null, null))
            .build();
    }

    @Bean
    @StepScope
    public Tasklet connectionCheckTasklet(
        @Value("#{jobParameters['baseSdt']}") String baseSdt,
        @Value("#{jobParameters['remoteDir']}") String remoteDir) {

        return (contribution, chunkContext) -> {
            log.info("[JOB] === SFTP 전송 Job 시작 === baseSdt={}", baseSdt);
            log.info("[JOB] 접속 대상: sftp -P {} {}@{}",
                props.getPort(), props.getUsername(), props.getHost());

            boolean connected = sftpService.testConnection();
            if (!connected) {
                throw new RuntimeException(
                    String.format("SFTP 서버 접속 실패: %s@%s:%d",
                        props.getUsername(), props.getHost(), props.getPort())
                );
            }
            log.info("[JOB] 접속 확인 완료");
            return RepeatStatus.FINISHED;
        };
    }

    // ──────────────────────────────────────────
    // Step 2: 파일 업로드
    // ──────────────────────────────────────────

    @Bean
    public Step sftpUploadStep() {
        return stepBuilderFactory.get("sftpUploadStep")
            .tasklet(sftpUploadTasklet(null, null, null))
            .build();
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
                ? remoteDir
                : props.getRemoteBaseDir() + (baseSdt != null ? "/" + baseSdt : "");

            log.info("[JOB] 전송 프로세스 시작: {} → {}", localPath, remotePath);

            // 1. 업로드 실행
            SftpTransferResult.Summary summary = sftpService.uploadDirectory(localPath, remotePath);

            // 2. [추가] 전송 파일 리스트 상세 조회 로그
            log.info("");
            log.info("[JOB] ================== 전송 파일 상세 리스트 ==================");
            log.info(String.format("%-40s | %-12s | %-10s | %-20s", "파일명", "사이즈(Byte)", "소요시간", "전송완료시간"));
            log.info("--------------------------------------------------------------------------");

            summary.getDetails().stream()
                .filter(SftpTransferResult::isSuccess)
                .forEach(r -> {
                    String fileName = Paths.get(r.getLocalPath()).getFileName().toString();
                    // 시간 포맷팅 (예: 2026-03-01 15:30:01)
                    String finishTime = r.getFinishedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    
                    log.info(String.format("%-40s | %12d | %8d ms | %-20s", 
                        fileName, 
                        r.getFileSize(), 
                        r.getTransferTimeMs(), 
                        finishTime));
                });

            log.info("==========================================================================");
            log.info("");

            // 3. 결과 요약 및 상태 저장
            chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putString("transferSummary", summary.getSummaryText());

            if (!summary.isAllSuccess()) {
                // 실패한 파일이 있을 경우 별도 리스트 출력
                log.error("[JOB] !!! 전송 실패 파일 목록 !!!");
                summary.getDetails().stream()
                    .filter(r -> !r.isSuccess())
                    .forEach(r -> log.error("실패: {} (사유: {})", r.getLocalPath(), r.getErrorMessage()));

                throw new RuntimeException("일부 파일 전송 실패");
            }

            return RepeatStatus.FINISHED;
        };
    }

    // ──────────────────────────────────────────
    // Job Listener
    // ──────────────────────────────────────────

    @Bean
    public JobExecutionListener jobExecutionListener() {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution jobExecution) {
                log.info("[JOB] ====================================================");
                log.info("[JOB] sftpTransferJob 시작 - JobId: {}", jobExecution.getJobId());
                log.info("[JOB] 파라미터: {}", jobExecution.getJobParameters());
                log.info("[JOB] ====================================================");
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                BatchStatus status = jobExecution.getStatus();
                String summary = jobExecution.getExecutionContext()
                    .getString("transferSummary", "집계 없음");

                log.info("[JOB] ====================================================");
                log.info("[JOB] sftpTransferJob 종료 - 상태: {}", status);
                log.info("[JOB] 결과: {}", summary);
                if (status == BatchStatus.FAILED) {
                    jobExecution.getAllFailureExceptions()
                        .forEach(e -> log.error("[JOB] 오류: {}", e.getMessage()));
                }
                log.info("[JOB] ====================================================");
            }
        };
    }
}
