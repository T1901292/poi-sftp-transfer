package com.tmap.poi;
package com.tmap.poi.sftp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import org.apache.sshd.common.session.SessionHeartbeatController;
import java.time.Duration;

/**
 * Apache MINA SSHD 클라이언트 설정
 *
 * <p>JSch 대비 장점:</p>
 * <ul>
 *   <li>CVE 취약점 없음 (JSch 는 다수의 CVE 보고됨)</li>
 *   <li>NIO 기반 비동기 처리</li>
 *   <li>Ed25519 / ECDSA 키 지원</li>
 *   <li>Apache 재단 유지보수 (활발한 업데이트)</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SftpClientConfig {

    private final SftpProperties props;

    /**
     * SshClient 싱글톤 Bean
     * 애플리케이션 전체에서 하나의 SshClient 를 공유하고
     * 전송 시마다 SshClient 로부터 ClientSession 을 생성한다.
     */
    @Bean(destroyMethod = "stop")
    public SshClient sshClient() {
        SshClient client = SshClient.setUpDefaultClient();

        // ── 호스트키 검증 설정 ──────────────────────────────
        if (!props.isStrictHostKeyChecking()) {
            // 개발/운영 내부망 환경: 호스트키 검증 생략
            client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
            log.warn("[SFTP] strictHostKeyChecking=false → 호스트키 검증 생략 (보안 주의)");
        } else {
            // 운영 권장: ~/.ssh/known_hosts 기반 검증
            client.setServerKeyVerifier(
                new KnownHostsServerKeyVerifier(
                    RejectAllServerKeyVerifier.INSTANCE,
                    Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts")
                )
            );
        }

        // ── 개인키 인증 설정 ────────────────────────────────
        if ("privateKey".equalsIgnoreCase(props.getAuthType())) {
            String keyPath = props.getPrivateKeyPath();
            log.info("[SFTP] 개인키 인증 설정: {}", keyPath);
            FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(
                Paths.get(keyPath)
            );
            // 패스프레이즈가 있을 경우
            if (props.getPrivateKeyPassphrase() != null && !props.getPrivateKeyPassphrase().isEmpty()) {
                keyPairProvider.setPasswordFinder(
                    (session, resourceKey, retryIndex) -> props.getPrivateKeyPassphrase().toCharArray()
                );
            }
            client.setKeyIdentityProvider(keyPairProvider);
        }

        // ── 하트비트 (세션 끊김 방지) ────────────────────────

		client.setSessionHeartbeat(
			SessionHeartbeatController.HeartbeatType.IGNORE,
			Duration.ofSeconds(props.getSessionHeartbeat())
		);

        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.start();

        log.info("[SFTP] SshClient 시작 완료 → {}@{}:{} (authType={})",
            props.getUsername(), props.getHost(), props.getPort(), props.getAuthType());

        return client;
    }
}
