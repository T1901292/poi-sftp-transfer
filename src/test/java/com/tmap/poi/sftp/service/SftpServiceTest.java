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
import org.junit.jupiter.api.io.TempDir; // @TempDir 사용을 위해 추가
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 불필요한 Stubbing 경고 방지
@DisplayName("SftpService 단위 테스트")
class SftpServiceTest {

    @Mock private SshClient sshClient;
    @Mock private SftpProperties props;
    @Mock private ConnectFuture connectFuture;
    @Mock private ClientSession session;
    @Mock private AuthFuture authFuture;
    @Mock private SftpClient sftpClient;
    @Mock private SftpClient.CloseableHandle fileHandle;

    @InjectMocks
    private SftpService sftpService;

    // JUnit 5에서 권장하는 임시 디렉토리 관리 방식
    @TempDir
    Path tempDir;

    private Path testFile;
    private SftpProperties.Retry retry;

    private static final String HOST = "223.39.122.217";
    private static final int PORT = 40022;
    private static final String USER = "cptmap";
    private static final String REMOTE_DIR = "/home/cptmap/data/test";

    @BeforeEach
    void setUp() throws Exception {
        retry = new SftpProperties.Retry();
        retry.setMaxAttempts(3);
        retry.setBackoffDelay(10L);

        lenient().when(props.getHost()).thenReturn(HOST);
        lenient().when(props.getPort()).thenReturn(PORT);
        lenient().when(props.getUsername()).thenReturn(USER);
        lenient().when(props.getAuthType()).thenReturn("password");
        lenient().when(props.getConnectTimeout()).thenReturn(1000L);
        lenient().when(props.getRetry()).thenReturn(retry);

        testFile = tempDir.resolve("poi_data_20260301.csv");
        Files.writeString(testFile, "POI_ID,NAME\n1001107297,여울동\n");
    }

    // @TempDir 사용 시 AfterEach에서 폴더를 직접 삭제할 필요가 없습니다.

    @Test
    @DisplayName("접속 성공 → true 반환")
    void testConnection_success() throws Exception {
        givenSessionOpens();
        when(session.isOpen()).thenReturn(true);

        boolean result = sftpService.testConnection();

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("업로드 성공 → success=true")
    void upload_success() throws Exception {
        givenSessionOpens();
        
        // Static Mocking 사용 시 try-with-resources 사용
        try (MockedStatic<SftpClientFactory> factory = mockStatic(SftpClientFactory.class)) {
            SftpClientFactory factoryInstance = mock(SftpClientFactory.class);
            factory.when(SftpClientFactory::instance).thenReturn(factoryInstance);
            when(factoryInstance.createSftpClient(session)).thenReturn(sftpClient);

            // SftpService 내부의 mkdirsRemote 대응
            lenient().when(sftpClient.stat(anyString())).thenReturn(mock(SftpClient.Attributes.class));
            when(sftpClient.open(anyString(), any())).thenReturn(fileHandle);

            SftpTransferResult result = sftpService.upload(testFile, REMOTE_DIR);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getFileSize()).isGreaterThan(0);
        }
    }

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
        // 수정: 실제 SummaryText 포맷인 "성공: 3" 이 포함되는지 확인
        assertThat(summary.getSummaryText()).contains("성공: 3");
    }

    @Test
    @DisplayName("일부 실패 → isAllSuccess=false")
    void summary_partialFailure() {
        SftpTransferResult.Summary summary = SftpTransferResult.Summary.builder()
                .totalFiles(5)
                .successCount(3)
                .failureCount(2)
                .totalBytes(2048L)
                .elapsedMs(1200L)
                .build();

        assertThat(summary.isAllSuccess()).isFalse();
        // 수정: 실제 SummaryText 포맷인 "실패: 2" 가 포함되는지 확인
        assertThat(summary.getSummaryText()).contains("실패: 2");
    }

    // ── Helper ────────────────────────────────────────────────
    private void givenSessionOpens() throws Exception {
        when(sshClient.connect(anyString(), anyString(), anyInt())).thenReturn(connectFuture);
        when(connectFuture.verify(anyLong(), any(TimeUnit.class))).thenReturn(connectFuture);
        when(connectFuture.getSession()).thenReturn(session);
        when(session.auth()).thenReturn(authFuture);
        when(authFuture.verify(anyLong(), any(TimeUnit.class))).thenReturn(authFuture);
    }

    private SftpClient.DirEntry makeDirEntry(String filename) {
        SftpClient.DirEntry entry = mock(SftpClient.DirEntry.class);
        when(entry.getFilename()).thenReturn(filename);
        return entry;
    }
}