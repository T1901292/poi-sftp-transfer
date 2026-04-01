package com.tmap.poi.sftp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Paths;
import java.time.Duration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SftpClientConfig {
    private final SftpProperties props;

    @Bean(destroyMethod = "stop")
    public SshClient sshClient() {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);

        if ("privateKey".equalsIgnoreCase(props.getAuthType())) {
            client.setKeyIdentityProvider(new FileKeyPairProvider(Paths.get(props.getPrivateKeyPath())));
            if (props.getPrivateKeyPassphrase() != null && !props.getPrivateKeyPassphrase().isEmpty()) {
                client.setFilePasswordProvider(FilePasswordProvider.of(props.getPrivateKeyPassphrase()));
            }
        }

        client.start();
        return client;
    }
}