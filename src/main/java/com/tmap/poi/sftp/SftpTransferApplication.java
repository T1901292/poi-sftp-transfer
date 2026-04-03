package com.tmap.poi.sftp;

import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
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
    	    // try-with-resources로 컨텍스트 관리
    	    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SftpTransferApplication.class)) {
    	        
    	        String jobName = getArg(args, "spring.batch.job.names");
    	        if (jobName == null) jobName = "sftpTransferJob";

    	        JobLauncher jobLauncher = context.getBean(JobLauncher.class);
    	        Job job = context.getBean(jobName, Job.class);

    	        JobParameters params = new JobParametersBuilder()
    	                .addString("localDir", getArg(args, "localDir"))
    	                .addString("remoteDir", getArg(args, "remoteDir"))
    	                .addLong("time", System.currentTimeMillis())
    	                .toJobParameters();

    	        // 1. Job 실행 및 결과 수신
    	        JobExecution execution = jobLauncher.run(job, params);

    	        // 2. 실행 상태 확인 (가장 중요)
    	        if (execution.getStatus() != BatchStatus.COMPLETED) {
    	            log.error("[MAIN] Job 실행 실패! 최종 상태: {}", execution.getStatus());
    	            // 젠킨스에게 실패를 알리기 위해 0이 아닌 종료 코드 반환
    	            System.exit(1); 
    	        } else {
    	            log.info("[MAIN] Job 실행 완료");
    	            System.exit(0);
    	        }

    	    } catch (Exception e) {
    	        log.error("[MAIN] 실행 중 예외 발생", e);
    	        System.exit(1); // 예외 발생 시 실패 처리
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