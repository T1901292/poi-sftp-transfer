package com.tmap.poi.sftp.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Data
@Component
public class SftpProperties {
    @Value("${sftp.host}") private String host;
    @Value("${sftp.port:22}") private int port;
    @Value("${sftp.username}") private String username;
    @Value("${sftp.auth-type:password}") private String authType;
    @Value("${sftp.password:}") private String password;
    @Value("${sftp.private-key-path:}") private String privateKeyPath;
    @Value("${sftp.private-key-passphrase:}") private String privateKeyPassphrase;
    @Value("${sftp.connect-timeout:30000}") private long connectTimeout;
    @Value("${sftp.session-heartbeat:60}") private int sessionHeartbeat;
    @Value("${sftp.strict-host-key-checking:false}") private boolean strictHostKeyChecking;
    @Value("${sftp.remote-base-dir}") private String remoteBaseDir;
    @Value("${sftp.local-base-dir}") private String localBaseDir;

    private Retry retry = new Retry();

    @Data
    public static class Retry {
        @Value("${sftp.retry.max-attempts:3}") private int maxAttempts;
        @Value("${sftp.retry.backoff-delay:5000}") private long backoffDelay;
    }
}