package com.tmap.poi.sftp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Slf4j
@Configuration
@ComponentScan(basePackages = "com.tmap.poi.sftp")
@PropertySource("classpath:application.properties")
public class SftpTransferApplication {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SftpTransferApplication.class)) {
            
            // 1. 실행할 Job 이름 결정 (전달받은 아규먼트 우선, 없으면 기본값)
            String jobName = getArg(args, "spring.batch.job.names");
            if (jobName == null || jobName.isEmpty()) {
                jobName = "sftpTransferJob"; // 기본 Job
            }
            
            log.info("[MAIN] 실행할 Job 이름: {}", jobName);

            // 2. 이름으로 특정 Job Bean 가져오기 (NoUniqueBeanDefinitionException 방지)
            JobLauncher jobLauncher = context.getBean(JobLauncher.class);
            Job job = context.getBean(jobName, Job.class); 

            // 3. 파라미터 구성
            JobParameters params = new JobParametersBuilder()
                    .addString("localDir", getArg(args, "localDir"))
                    .addString("remoteDir", getArg(args, "remoteDir"))
                    .addString("baseSdt", getArg(args, "baseSdt"))
                    .addLong("runTime", System.currentTimeMillis())
                    .toJobParameters();

            // 4. 실행
            jobLauncher.run(job, params);
            
        } catch (Exception e) {
            log.error("[MAIN] 치명적 오류 발생", e);
            System.exit(1);
        }
    }

    /**
     * --key=value 형태의 인자 추출
     */
    private static String getArg(String[] args, String key) {
        for (String arg : args) {
            if (arg.startsWith("--" + key + "=")) {
                return arg.split("=", 2)[1].replace("\"", ""); // 따옴표 제거 포함
            }
        }
        return null;
    }
}