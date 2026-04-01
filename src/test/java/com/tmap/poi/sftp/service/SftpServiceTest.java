package com.tmap.poi.sftp.service;

import com.tmap.poi.sftp.config.SftpProperties;
import com.tmap.poi.sftp.dto.SftpTransferResult;
import com.tmap.poi.sftp.exception.SftpTransferException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SftpService 단위 테스트
 *
 * <ul>
 *   <li>위치: src/test/java (올바른 테스트 소스셋)</li>
 *   <li>Spring 컨텍스트 없이 Mockito 만으로 실행</li>
 *   <li>실제 SFTP 서버 불필요 → CI/CD 환경에서도 항상 실행 가능</li>
 * </ul>
 *
 * <p>실제 서버 통합 테스트는 {@link SftpServiceIntegrationTest} 참조</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SftpService 단위 테스트")
class SftpServiceTest {

    // ── Mocks ────────────────────────────────────────────────
    @Mock private SshClient        sshClient;
    @Mock private SftpProperties   props;
    @Mock private ConnectFuture    connectFuture;
    @Mock private ClientSession    session;
    @Mock private AuthFuture       authFuture;
    @Mock private SftpClient       sftpClient;
    @Mock private SftpClient.CloseableHandle fileHandle;

    @InjectMocks
    private SftpService sftpService;

    // ── 공통 설정 ─────────────────────────────────────────────
    private static final String HOST       = "223.39.122.217";
    private static final int    PORT       = 40022;
    private static final String USER       = "cptmap";
    private static final String PASSWORD   = "test_password";
    private static final String REMOTE_DIR = "/home/cptmap/data/test";

    private SftpProperties.Retry retry;
    private Path tempDir;
    private Path testFile;

    @BeforeEach
    void setUp() throws Exception {
        // SftpProperties 기본값 설정
        retry = new SftpProperties.Retry();    // maxAttempts=3, backoffDelay=5000
        retry.setMaxAttempts(3);
        retry.setBackoffDelay(100L);           // 테스트에서는 짧게

        lenient().when(props.getHost()).thenReturn(HOST);
        lenient().when(props.getPort()).thenReturn(PORT);
        lenient().when(props.getUsername()).thenReturn(USER);
        lenient().when(props.getPassword()).thenReturn(PASSWORD);
        lenient().when(props.getAuthType()).thenReturn("password");
        lenient().when(props.getConnectTimeout()).thenReturn(30_000L);
        lenient().when(props.getRetry()).thenReturn(retry);

        // 임시 테스트 파일 생성
        tempDir  = Files.createTempDirectory("sftp-unit-test-");
        testFile = tempDir.resolve("poi_data_20260301.csv");
        Files.writeString(testFile, "POI_ID,NAME\n1001107297,여울동\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (testFile != null) Files.deleteIfExists(testFile);
        if (tempDir  != null) Files.deleteIfExists(tempDir);
    }

    // ═══════════════════════════════════════════════════════════
    // testConnection()
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("testConnection()")
    class TestConnectionTests {

        @Test
        @DisplayName("접속 성공 → true 반환")
        void testConnection_success() throws Exception {
            // given
            givenSessionOpens();
            when(session.isOpen()).thenReturn(true);

            // when
            boolean result = sftpService.testConnection();

            // then
            assertThat(result).isTrue();
            verify(session).isOpen();
        }

        @Test
        @DisplayName("접속 실패 (IOException) → false 반환, 예외 전파 없음")
        void testConnection_ioException_returnsFalse() throws Exception {
            // given
            when(sshClient.connect(anyString(), anyString(), anyInt()))
                .thenThrow(new IOException("Connection refused"));

            // when
            boolean result = sftpService.testConnection();

            // then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("세션 isOpen=false → false 반환")
        void testConnection_sessionNotOpen_returnsFalse() throws Exception {
            // given
            givenSessionOpens();
            when(session.isOpen()).thenReturn(false);

            // when
            boolean result = sftpService.testConnection();

            // then
            assertThat(result).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // upload()
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("upload()")
    class UploadTests {

        @Test
        @DisplayName("업로드 성공 → success=true, fileSize > 0")
        void upload_success() throws Exception {
            // given
            givenSessionOpens();
            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory factoryInstance = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(factoryInstance);
                when(factoryInstance.createSftpClient(session)).thenReturn(sftpClient);

                // stat → 디렉터리 이미 존재 (mkdirsRemote 에서 예외 없음)
                when(sftpClient.stat(anyString())).thenReturn(mock(SftpClient.Attributes.class));
                // open → handle 반환
                when(sftpClient.open(anyString(), any())).thenReturn(fileHandle);

                // when
                SftpTransferResult result = sftpService.upload(testFile, REMOTE_DIR);

                // then
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.getFileSize()).isGreaterThan(0);
                assertThat(result.getLocalPath()).isEqualTo(testFile.toString());
                assertThat(result.getRemotePath()).isEqualTo(REMOTE_DIR + "/" + testFile.getFileName());
                assertThat(result.getRetryCount()).isEqualTo(0);
                assertThat(result.getStartedAt()).isNotNull();
                assertThat(result.getFinishedAt()).isNotNull();
            }
        }

        @Test
        @DisplayName("업로드 실패 → success=false, errorMessage 포함")
        void upload_failure() throws Exception {
            // given: 연결 자체가 실패하는 케이스
            when(sshClient.connect(anyString(), anyString(), anyInt()))
                .thenThrow(new IOException("Connection timeout"));

            // when
            SftpTransferResult result = sftpService.upload(testFile, REMOTE_DIR);

            // then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("첫 시도 실패 후 재시도 성공 → retryCount=1")
        void upload_retryOnce_thenSuccess() throws Exception {
            // given: 첫 번째 connect 실패, 두 번째 성공
            when(sshClient.connect(anyString(), anyString(), anyInt()))
                .thenThrow(new IOException("Temporary failure"))   // 1차 실패
                .thenReturn(connectFuture);                         // 2차 성공

            when(connectFuture.verify(anyLong(), any(TimeUnit.class))).thenReturn(connectFuture);
            when(connectFuture.getSession()).thenReturn(session);
            when(session.auth()).thenReturn(authFuture);
            when(authFuture.verify(anyLong(), any(TimeUnit.class))).thenReturn(authFuture);

            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory fi = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(fi);
                when(fi.createSftpClient(session)).thenReturn(sftpClient);
                when(sftpClient.stat(anyString())).thenReturn(mock(SftpClient.Attributes.class));
                when(sftpClient.open(anyString(), any())).thenReturn(fileHandle);

                // when
                SftpTransferResult result = sftpService.upload(testFile, REMOTE_DIR);

                // then
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.getRetryCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("maxAttempts(3) 모두 실패 → success=false, retryCount=2")
        void upload_allAttemptsFail() throws Exception {
            // given: 모든 시도 실패
            when(sshClient.connect(anyString(), anyString(), anyInt()))
                .thenThrow(new IOException("Always fails"));

            // when
            SftpTransferResult result = sftpService.upload(testFile, REMOTE_DIR);

            // then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getRetryCount()).isEqualTo(2);   // 3번 시도 → 재시도 2회
            verify(sshClient, times(3)).connect(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("존재하지 않는 파일 업로드 → success=false")
        void upload_fileNotFound() {
            // given
            Path nonExistent = tempDir.resolve("not_exists.csv");

            // when
            SftpTransferResult result = sftpService.upload(nonExistent, REMOTE_DIR);

            // then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isNotBlank();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // uploadDirectory()
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("uploadDirectory()")
    class UploadDirectoryTests {

        @Test
        @DisplayName("디렉터리 내 파일 2건 → Summary.totalFiles=2, successCount=2")
        void uploadDirectory_twoFiles_allSuccess() throws Exception {
            // given: 임시 파일 2개 생성
            Path file1 = tempDir.resolve("area_20260301.csv");
            Path file2 = tempDir.resolve("poi_20260301.csv");
            Files.writeString(file1, "data1");
            Files.writeString(file2, "data2");

            // 두 파일 업로드 모두 성공하도록 설정
            when(sshClient.connect(anyString(), anyString(), anyInt()))
                .thenReturn(connectFuture);
            when(connectFuture.verify(anyLong(), any(TimeUnit.class))).thenReturn(connectFuture);
            when(connectFuture.getSession()).thenReturn(session);
            when(session.auth()).thenReturn(authFuture);
            when(authFuture.verify(anyLong(), any(TimeUnit.class))).thenReturn(authFuture);

            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory fi = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(fi);
                when(fi.createSftpClient(session)).thenReturn(sftpClient);
                when(sftpClient.stat(anyString())).thenReturn(mock(SftpClient.Attributes.class));
                when(sftpClient.open(anyString(), any())).thenReturn(fileHandle);

                // when
                SftpTransferResult.Summary summary =
                    sftpService.uploadDirectory(tempDir, REMOTE_DIR);

                // then
                // tempDir 에는 setUp() 에서 만든 testFile + file1 + file2 = 3개
                assertThat(summary.getTotalFiles()).isGreaterThanOrEqualTo(2);
                assertThat(summary.getSuccessCount()).isEqualTo(summary.getTotalFiles());
                assertThat(summary.getFailureCount()).isEqualTo(0);
                assertThat(summary.isAllSuccess()).isTrue();
                assertThat(summary.getTotalBytes()).isGreaterThan(0);
            }

            Files.deleteIfExists(file1);
            Files.deleteIfExists(file2);
        }

        @Test
        @DisplayName("빈 디렉터리 → totalFiles=0, allSuccess=true")
        void uploadDirectory_emptyDir_returnsZero() throws Exception {
            // given
            Path emptyDir = Files.createTempDirectory("sftp-empty-");

            // when
            SftpTransferResult.Summary summary =
                sftpService.uploadDirectory(emptyDir, REMOTE_DIR);

            // then
            assertThat(summary.getTotalFiles()).isEqualTo(0);
            assertThat(summary.isAllSuccess()).isTrue();

            Files.deleteIfExists(emptyDir);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // download()
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("download()")
    class DownloadTests {

        @Test
        @DisplayName("다운로드 성공 → success=true, 로컬 파일 생성")
        void download_success() throws Exception {
            // given
            String remotePath = REMOTE_DIR + "/poi_data_20260301.csv";
            Path downloadDir  = tempDir.resolve("download");

            givenSessionOpens();

            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory fi = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(fi);
                when(fi.createSftpClient(session)).thenReturn(sftpClient);
                when(sftpClient.open(anyString(), any())).thenReturn(fileHandle);

                // read: 첫 번째 호출에서 데이터 반환, 두 번째에서 -1 (EOF)
                byte[] data = "POI_ID,NAME\n1001107297,여울동\n".getBytes();
                when(sftpClient.read(eq(fileHandle), eq(0L), any(byte[].class), eq(0), anyInt()))
                    .thenAnswer(inv -> {
                        byte[] buf = inv.getArgument(2);
                        System.arraycopy(data, 0, buf, 0, data.length);
                        return data.length;
                    });
                when(sftpClient.read(eq(fileHandle), eq((long) data.length),
                        any(byte[].class), eq(0), anyInt()))
                    .thenReturn(-1);

                // when
                SftpTransferResult result = sftpService.download(remotePath, downloadDir);

                // then
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.getFileSize()).isEqualTo(data.length);
                assertThat(result.getRemotePath()).isEqualTo(remotePath);
            }
        }

        @Test
        @DisplayName("원격 파일 없음 → success=false")
        void download_remoteFileNotFound() throws Exception {
            // given
            String remotePath = REMOTE_DIR + "/not_exists.csv";
            Path downloadDir  = tempDir.resolve("download2");

            when(sshClient.connect(anyString(), anyString(), anyInt()))
                .thenThrow(new IOException("No such file"));

            // when
            SftpTransferResult result = sftpService.download(remotePath, downloadDir);

            // then
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isNotBlank();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // listRemoteFiles()
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("listRemoteFiles()")
    class ListRemoteFilesTests {

        @Test
        @DisplayName("목록 조회 성공 → . .. 제외한 파일명만 반환")
        void listRemoteFiles_success() throws Exception {
            // given
            givenSessionOpens();

            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory fi = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(fi);
                when(fi.createSftpClient(session)).thenReturn(sftpClient);

                // DirEntry 목록 구성 (. .. 포함)
                SftpClient.DirEntry dot    = makeDirEntry(".");
                SftpClient.DirEntry dotdot = makeDirEntry("..");
                SftpClient.DirEntry file1  = makeDirEntry("poi_20260301.csv");
                SftpClient.DirEntry file2  = makeDirEntry("area_20260301.csv");

                when(sftpClient.readDir(REMOTE_DIR))
                    .thenReturn(Arrays.asList(dot, dotdot, file1, file2));

                // when
                List<String> result = sftpService.listRemoteFiles(REMOTE_DIR);

                // then
                assertThat(result)
                    .hasSize(2)
                    .containsExactlyInAnyOrder("poi_20260301.csv", "area_20260301.csv")
                    .doesNotContain(".", "..");
            }
        }

        @Test
        @DisplayName("빈 디렉터리 → 빈 리스트 반환")
        void listRemoteFiles_emptyDir() throws Exception {
            // given
            givenSessionOpens();

            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory fi = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(fi);
                when(fi.createSftpClient(session)).thenReturn(sftpClient);
                when(sftpClient.readDir(REMOTE_DIR)).thenReturn(List.of());

                // when
                List<String> result = sftpService.listRemoteFiles(REMOTE_DIR);

                // then
                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("IOException 발생 → SftpTransferException 던짐")
        void listRemoteFiles_ioException_throwsSftpTransferException() throws Exception {
            // given
            givenSessionOpens();

            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory fi = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(fi);
                when(fi.createSftpClient(session)).thenReturn(sftpClient);
                when(sftpClient.readDir(anyString()))
                    .thenThrow(new IOException("Permission denied"));

                // when / then
                assertThatThrownBy(() -> sftpService.listRemoteFiles(REMOTE_DIR))
                    .isInstanceOf(SftpTransferException.class)
                    .hasMessageContaining("원격 목록 조회 실패");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // existsRemote()
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("existsRemote()")
    class ExistsRemoteTests {

        @Test
        @DisplayName("파일 존재 → true")
        void existsRemote_fileExists_returnsTrue() throws Exception {
            // given
            String remotePath = REMOTE_DIR + "/poi_20260301.csv";
            givenSessionOpens();

            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory fi = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(fi);
                when(fi.createSftpClient(session)).thenReturn(sftpClient);
                when(sftpClient.stat(remotePath))
                    .thenReturn(mock(SftpClient.Attributes.class));

                // when / then
                assertThat(sftpService.existsRemote(remotePath)).isTrue();
            }
        }

        @Test
        @DisplayName("파일 없음 → false")
        void existsRemote_fileNotFound_returnsFalse() throws Exception {
            // given
            String remotePath = REMOTE_DIR + "/not_exists.csv";
            givenSessionOpens();

            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory fi = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(fi);
                when(fi.createSftpClient(session)).thenReturn(sftpClient);
                when(sftpClient.stat(remotePath))
                    .thenThrow(new IOException("No such file"));

                // when / then
                assertThat(sftpService.existsRemote(remotePath)).isFalse();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // deleteRemote()
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("deleteRemote()")
    class DeleteRemoteTests {

        @Test
        @DisplayName("삭제 성공 → true, remove() 호출 확인")
        void deleteRemote_success() throws Exception {
            // given
            String remotePath = REMOTE_DIR + "/old_file.csv";
            givenSessionOpens();

            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory fi = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(fi);
                when(fi.createSftpClient(session)).thenReturn(sftpClient);
                doNothing().when(sftpClient).remove(remotePath);

                // when
                boolean result = sftpService.deleteRemote(remotePath);

                // then
                assertThat(result).isTrue();
                verify(sftpClient).remove(remotePath);
            }
        }

        @Test
        @DisplayName("삭제 실패 (IOException) → false, 예외 전파 없음")
        void deleteRemote_failure_returnsFalse() throws Exception {
            // given
            String remotePath = REMOTE_DIR + "/locked_file.csv";
            givenSessionOpens();

            try (MockedStatic<SftpClientFactory> factory =
                     mockStatic(SftpClientFactory.class)) {

                SftpClientFactory fi = mock(SftpClientFactory.class);
                factory.when(SftpClientFactory::instance).thenReturn(fi);
                when(fi.createSftpClient(session)).thenReturn(sftpClient);
                doThrow(new IOException("Permission denied"))
                    .when(sftpClient).remove(remotePath);

                // when
                boolean result = sftpService.deleteRemote(remotePath);

                // then
                assertThat(result).isFalse();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SftpTransferResult.Summary 유틸 테스트
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SftpTransferResult.Summary")
    class SummaryTests {

        @Test
        @DisplayName("전체 성공 → isAllSuccess=true")
        void summary_allSuccess() {
            SftpTransferResult.Summary summary = SftpTransferResult.Summary.builder()
                .totalFiles(3)
                .successCount(3)
                .failureCount(0)
                .totalBytes(1024L)
                .elapsedMs(500L)
                .build();

            assertThat(summary.isAllSuccess()).isTrue();
            assertThat(summary.getSummaryText()).contains("3/3 성공");
        }

        @Test
        @DisplayName("일부 실패 → isAllSuccess=false, getSummaryText에 실패 수 포함")
        void summary_partialFailure() {
            SftpTransferResult.Summary summary = SftpTransferResult.Summary.builder()
                .totalFiles(5)
                .successCount(3)
                .failureCount(2)
                .totalBytes(2048L)
                .elapsedMs(1200L)
                .build();

            assertThat(summary.isAllSuccess()).isFalse();
            assertThat(summary.getSummaryText()).contains("2 실패");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Private Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * SSH 세션 open 공통 설정 (비밀번호 인증 흐름)
     */
    private void givenSessionOpens() throws Exception {
        when(sshClient.connect(anyString(), anyString(), anyInt()))
            .thenReturn(connectFuture);
        when(connectFuture.verify(anyLong(), any(TimeUnit.class)))
            .thenReturn(connectFuture);
        when(connectFuture.getSession()).thenReturn(session);
        when(session.auth()).thenReturn(authFuture);
        when(authFuture.verify(anyLong(), any(TimeUnit.class)))
            .thenReturn(authFuture);
    }

    /**
     * DirEntry Mock 생성
     */
    private SftpClient.DirEntry makeDirEntry(String filename) {
        SftpClient.DirEntry entry = mock(SftpClient.DirEntry.class);
        when(entry.getFilename()).thenReturn(filename);
        return entry;
    }
}
