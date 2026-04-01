package com.tmap.poi.sftp.service;

import com.tmap.poi.sftp.dto.SftpTransferResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SftpService 실서버 통합 테스트
 *
 * <p><b>기본적으로 건너뜁니다.</b> 실서버가 필요할 때만 실행:</p>
 * <pre>
 *   # 환경변수 + 시스템 프로퍼티로 활성화
 *   SFTP_PASSWORD=your_pw mvn test \
 *     -Dtest=SftpServiceIntegrationTest \
 *     -DrunIntegrationTest=true
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SftpService 실서버 통합 테스트 (sftp -P 40022 cptmap@223.39.122.217)")
class SftpServiceIntegrationTest {

    @Autowired
    private SftpService sftpService;

    private static final String REMOTE_TEST_DIR = "/home/cptmap/data/junit-test";

    private static Path tempDir;
    private static Path testFile;

    @BeforeAll
    static void setUpAll() throws IOException {
        tempDir  = Files.createTempDirectory("sftp-it-");
        testFile = tempDir.resolve("poi_integration_test.csv");
        Files.writeString(testFile,
            "BASE_SDT,LEGAL_CODE,ADDR3_CD_NAME\n" +
            "20260301,4159711500,여울동\n"
        );
    }

    @AfterAll
    static void tearDownAll() throws IOException {
        if (testFile != null) Files.deleteIfExists(testFile);
        if (tempDir  != null) Files.deleteIfExists(tempDir);
    }

    // ── Skip guard ───────────────────────────────────────────

    private static void skipUnlessEnabled() {
        boolean enabled = Boolean.parseBoolean(
            System.getProperty("runIntegrationTest", "false")
        );
        Assumptions.assumeTrue(enabled,
            "runIntegrationTest=true 가 아니면 통합 테스트를 건너뜁니다. " +
            "(-DrunIntegrationTest=true 로 활성화)"
        );
    }

    // ── Tests ────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("[IT-1] SFTP 서버 접속 확인")
    void it_testConnection() {
        skipUnlessEnabled();

        boolean ok = sftpService.testConnection();

        assertThat(ok)
            .as("sftp -P 40022 cptmap@223.39.122.217 접속 성공해야 함")
            .isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("[IT-2] 파일 업로드 성공")
    void it_upload() {
        skipUnlessEnabled();

        SftpTransferResult result = sftpService.upload(testFile, REMOTE_TEST_DIR);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFileSize()).isGreaterThan(0);
        System.out.printf("[IT] 업로드: %s → %s (%d bytes, %dms)%n",
            result.getLocalPath(), result.getRemotePath(),
            result.getFileSize(), result.getTransferTimeMs());
    }

    @Test
    @Order(3)
    @DisplayName("[IT-3] 원격 파일 목록 조회")
    void it_listRemoteFiles() {
        skipUnlessEnabled();

        List<String> files = sftpService.listRemoteFiles(REMOTE_TEST_DIR);

        assertThat(files).isNotNull();
        System.out.println("[IT] 원격 파일 목록: " + files);
    }

    @Test
    @Order(4)
    @DisplayName("[IT-4] 원격 파일 존재 확인")
    void it_existsRemote() {
        skipUnlessEnabled();

        String remotePath = REMOTE_TEST_DIR + "/" + testFile.getFileName();
        boolean exists    = sftpService.existsRemote(remotePath);

        assertThat(exists).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("[IT-5] 파일 다운로드")
    void it_download() throws IOException {
        skipUnlessEnabled();

        String remotePath = REMOTE_TEST_DIR + "/" + testFile.getFileName();
        Path   downloadDir = tempDir.resolve("download");

        SftpTransferResult result = sftpService.download(remotePath, downloadDir);

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(downloadDir.resolve(testFile.getFileName()))).isTrue();
        System.out.printf("[IT] 다운로드: %s (%d bytes, %dms)%n",
            result.getLocalPath(), result.getFileSize(), result.getTransferTimeMs());
    }

    @Test
    @Order(6)
    @DisplayName("[IT-6] 원격 파일 삭제")
    void it_deleteRemote() {
        skipUnlessEnabled();

        String remotePath = REMOTE_TEST_DIR + "/" + testFile.getFileName();
        boolean deleted   = sftpService.deleteRemote(remotePath);

        assertThat(deleted).isTrue();
        assertThat(sftpService.existsRemote(remotePath)).isFalse();
    }
}
