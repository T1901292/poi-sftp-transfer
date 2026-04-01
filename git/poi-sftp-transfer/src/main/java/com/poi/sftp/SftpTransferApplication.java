package com.tmap.poi;
package com.tmap.poi.sftp;

import com.tmap.poi.sftp.service.SftpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * POI SFTP 전송 애플리케이션
 *
 * <h3>실행 모드</h3>
 *
 * <b>1. 배치 Job 실행 (Jenkins 스케줄링)</b>
 * <pre>
 * java -jar poi-sftp-transfer.jar \
 *   --mode=job \
 *   --localDir=/data/poi/export/20260301 \
 *   --remoteDir=/home/cptmap/data/20260301 \
 *   --baseSdt=20260301 \
 *   --SFTP_PASSWORD=your_password
 * </pre>
 *
 * <b>2. 연결 테스트만</b>
 * <pre>
 * java -jar poi-sftp-transfer.jar --mode=test --SFTP_PASSWORD=your_password
 * </pre>
 */
@Slf4j
@SpringBootApplication
@RequiredArgsConstructor
public class SftpTransferApplication implements ApplicationRunner {

    private final JobLauncher jobLauncher;
    private final Job         sftpTransferJob;
    private final SftpService sftpService;

    public static void main(String[] args) {
        SpringApplication.run(SftpTransferApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String mode = getArg(args, "mode", "job");

        switch (mode) {
            case "test":
                // 연결 테스트
                boolean ok = sftpService.testConnection();
                log.info("[APP] 연결 테스트 결과: {}", ok ? "성공" : "실패");
                System.exit(ok ? 0 : 1);
                break;

            case "list":
                // 원격 파일 목록 조회
                String listDir = getArg(args, "remoteDir", "/home/cptmap/data");
                sftpService.listRemoteFiles(listDir)
                    .forEach(f -> log.info("[APP] 원격 파일: {}", f));
                break;

            case "job":
            default:
                // Spring Batch Job 실행
                String localDir  = getArg(args, "localDir",  "");
                String remoteDir = getArg(args, "remoteDir", "");
                String baseSdt   = getArg(args, "baseSdt",   "");

                if (localDir.isEmpty()) {
                    log.error("[APP] --localDir 파라미터가 필요합니다.");
                    System.exit(1);
                }

                JobParameters jobParameters = new JobParametersBuilder()
                    .addString("localDir",  localDir)
                    .addString("remoteDir", remoteDir)
                    .addString("baseSdt",   baseSdt)
                    .addLong("runAt", System.currentTimeMillis())  // 매번 새 실행 보장
                    .toJobParameters();

                jobLauncher.run(sftpTransferJob, jobParameters);
                break;
        }
    }

    private String getArg(ApplicationArguments args, String name, String defaultVal) {
        if (args.containsOption(name)) {
            return args.getOptionValues(name).get(0);
        }
        return defaultVal;
    }
}
