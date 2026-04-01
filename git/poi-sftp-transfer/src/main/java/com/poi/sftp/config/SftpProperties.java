package com.tmap.poi;
package com.tmap.poi.sftp.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SFTP 접속 설정 프로퍼티
 * application.yml 의 sftp.* 값을 바인딩
 *
 * <pre>
 *   sftp -P 40022 cptmap@223.39.122.217
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "sftp")
public class SftpProperties {

    /** 원격 호스트 IP */
    private String host = "223.39.122.217";

    /** 포트 (기본 22, 여기서는 40022) */
    private int port = 40022;

    /** 접속 계정 */
    private String username = "cptmap";

    /**
     * 인증 방식
     * <ul>
     *   <li>password  - 비밀번호 인증</li>
     *   <li>privateKey - 개인키 인증</li>
     * </ul>
     */
    private String authType = "password";

    /** 비밀번호 (authType=password 일 때) */
    private String password;

    /** 개인키 파일 경로 (authType=privateKey 일 때) */
    private String privateKeyPath;

    /** 개인키 패스프레이즈 */
    private String privateKeyPassphrase;

    /** 접속 타임아웃 (ms) */
    private long connectTimeout = 30_000L;

    /** 세션 하트비트 주기 (초) */
    private int sessionHeartbeat = 60;

    /** known_hosts 엄격 검증 여부 */
    private boolean strictHostKeyChecking = false;

    /** 원격 기본 디렉터리 */
    private String remoteBaseDir = "/home/cptmap/data";

    /** 로컬 기본 디렉터리 */
    private String localBaseDir = "/data/poi/export";

    /** 재시도 설정 */
    private Retry retry = new Retry();

    @Data
    public static class Retry {
        private int maxAttempts = 3;
        private long backoffDelay = 5_000L;
    }
}
