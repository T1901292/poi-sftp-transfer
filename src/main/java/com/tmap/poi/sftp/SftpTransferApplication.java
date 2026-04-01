package com.tmap.poi.sftp;

import com.tmap.poi.sftp.service.SftpService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@ComponentScan(basePackages = "com.tmap.poi.sftp")
@PropertySource("classpath:application.properties")
public class SftpTransferApplication {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SftpTransferApplication.class)) {
            JobLauncher jobLauncher = context.getBean(JobLauncher.class);
            Job job = context.getBean(Job.class);

            JobParameters params = new JobParametersBuilder()
                    .addString("localDir", getArg(args, "localDir"))
                    .addLong("time", System.currentTimeMillis())
                    .toJobParameters();

            jobLauncher.run(job, params);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String getArg(String[] args, String key) {
        for (String arg : args) {
            if (arg.startsWith("--" + key + "=")) return arg.split("=")[1];
        }
        return null;
    }
}