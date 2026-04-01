package com.tmap.poi;
package com.tmap.poi.sftp.service;

import com.tmap.poi.sftp.config.SftpProperties;
import com.tmap.poi.sftp.dto.SftpTransferResult;
import com.tmap.poi.sftp.exception.SftpTransferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.EnumSet;
/**
 * Apache MINA SSHD 기반 SFTP 전송 서비스
 *
 * <pre>
 * 접속 정보: sftp -P 40022 cptmap@223.39.122.217
 * </pre>
 *
 * <p>주요 기능:</p>
 * <ul>
 *   <li>단건 파일 업로드 / 다운로드</li>
 *   <li>디렉터리 재귀 업로드</li>
 *   <li>원격 디렉터리 목록 조회</li>
 *   <li>파일 존재 여부 확인</li>
 *   <li>재시도 (maxAttempts)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SftpService {

    private final SshClient sshClient;
    private final SftpProperties props;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 단건 파일 업로드 (재시도 포함)
     *
     * @param localFilePath  로컬 파일 경로
     * @param remoteDir      원격 목적지 디렉터리 (파일명 제외)
     * @return 전송 결과
     */
    public SftpTransferResult upload(Path localFilePath, String remoteDir) {
        String remoteFilePath = remoteDir + "/" + localFilePath.getFileName();
        SftpTransferResult.SftpTransferResultBuilder result = SftpTransferResult.builder()
            .localPath(localFilePath.toString())
            .remotePath(remoteFilePath)
            .startedAt(LocalDateTime.now());

        int attempt = 0;
        Exception lastEx = null;

        while (attempt < props.getRetry().getMaxAttempts()) {
            attempt++;
            long start = System.currentTimeMillis();
            try (ClientSession session = openSession();
                 SftpClient sftp = openSftpClient(session)) {

                // 원격 디렉터리 없으면 생성
                mkdirsRemote(sftp, remoteDir);

                // 업로드
                long fileSize = Files.size(localFilePath);
                try (InputStream in = Files.newInputStream(localFilePath);
					SftpClient.CloseableHandle handle = sftp.open(
						remoteFilePath,
						EnumSet.of(
							SftpClient.OpenMode.Write,
							SftpClient.OpenMode.Create,
							SftpClient.OpenMode.Truncate
						)
					) {
		 

                    byte[] buffer = new byte[256 * 1024];  // 256 KB 버퍼
                    long offset = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        sftp.write(handle, offset, buffer, 0, read);
                        offset += read;
                    }
                }

                long elapsed = System.currentTimeMillis() - start;
                log.info("[SFTP] 업로드 완료 [시도:{}/{}] {} → {} ({} bytes, {}ms)",
                    attempt, props.getRetry().getMaxAttempts(),
                    localFilePath.getFileName(), remoteFilePath, fileSize, elapsed);

                return result
                    .success(true)
                    .fileSize(fileSize)
                    .transferTimeMs(elapsed)
                    .retryCount(attempt - 1)
                    .finishedAt(LocalDateTime.now())
                    .build();

            } catch (Exception e) {
                lastEx = e;
                log.warn("[SFTP] 업로드 실패 [시도:{}/{}] {} - {}",
                    attempt, props.getRetry().getMaxAttempts(),
                    localFilePath.getFileName(), e.getMessage());

                if (attempt < props.getRetry().getMaxAttempts()) {
                    sleep(props.getRetry().getBackoffDelay());
                }
            }
        }

        log.error("[SFTP] 업로드 최종 실패: {}", localFilePath, lastEx);
        return result
            .success(false)
            .retryCount(attempt - 1)
            .errorMessage(lastEx != null ? lastEx.getMessage() : "알 수 없는 오류")
            .finishedAt(LocalDateTime.now())
            .build();
    }

    /**
     * 디렉터리 내 파일 일괄 업로드 (재귀)
     *
     * @param localDir  로컬 디렉터리
     * @param remoteDir 원격 목적지 디렉터리
     * @return 집계 결과
     */
    public SftpTransferResult.Summary uploadDirectory(Path localDir, String remoteDir) {
        log.info("[SFTP] 디렉터리 업로드 시작: {} → {}", localDir, remoteDir);
        long startAll = System.currentTimeMillis();

        List<SftpTransferResult> results = new ArrayList<>();

        try {
            Files.walkFileTree(localDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // 로컬 상대 경로 → 원격 경로 계산
                    Path relative    = localDir.relativize(file.getParent());
                    String targetDir = relative.toString().isEmpty()
                        ? remoteDir
                        : remoteDir + "/" + relative.toString().replace('\\', '/');

                    SftpTransferResult r = upload(file, targetDir);
                    results.add(r);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("[SFTP] 파일 접근 실패: {} - {}", file, exc.getMessage());
                    results.add(SftpTransferResult.builder()
                        .localPath(file.toString())
                        .success(false)
                        .errorMessage(exc.getMessage())
                        .startedAt(LocalDateTime.now())
                        .finishedAt(LocalDateTime.now())
                        .build());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("[SFTP] 디렉터리 탐색 오류: {}", localDir, e);
        }

        long elapsed = System.currentTimeMillis() - startAll;
        long totalBytes = results.stream()
            .filter(SftpTransferResult::isSuccess)
            .mapToLong(SftpTransferResult::getFileSize)
            .sum();
        long successCnt = results.stream().filter(SftpTransferResult::isSuccess).count();

        SftpTransferResult.Summary summary = SftpTransferResult.Summary.builder()
            .totalFiles(results.size())
            .successCount((int) successCnt)
            .failureCount((int) (results.size() - successCnt))
            .totalBytes(totalBytes)
            .elapsedMs(elapsed)
            .details(results)
            .build();

        log.info("[SFTP] {}", summary.getSummaryText());
        return summary;
    }

    /**
     * 특정 파일 목록 업로드
     *
     * @param localFiles 업로드할 파일 목록
     * @param remoteDir  원격 목적지 디렉터리
     */
    public SftpTransferResult.Summary uploadFiles(Collection<Path> localFiles, String remoteDir) {
        long startAll = System.currentTimeMillis();
        List<SftpTransferResult> results = new ArrayList<>();

        for (Path file : localFiles) {
            results.add(upload(file, remoteDir));
        }

        long elapsed   = System.currentTimeMillis() - startAll;
        long totalBytes = results.stream().filter(SftpTransferResult::isSuccess)
            .mapToLong(SftpTransferResult::getFileSize).sum();
        long successCnt = results.stream().filter(SftpTransferResult::isSuccess).count();

        return SftpTransferResult.Summary.builder()
            .totalFiles(results.size())
            .successCount((int) successCnt)
            .failureCount((int) (results.size() - successCnt))
            .totalBytes(totalBytes)
            .elapsedMs(elapsed)
            .details(results)
            .build();
    }

    /**
     * 단건 파일 다운로드
     *
     * @param remoteFilePath 원격 파일 경로 (절대경로)
     * @param localDir       로컬 저장 디렉터리
     * @return 전송 결과
     */
    public SftpTransferResult download(String remoteFilePath, Path localDir) {
        String fileName = Paths.get(remoteFilePath).getFileName().toString();
        Path localFilePath = localDir.resolve(fileName);

        SftpTransferResult.SftpTransferResultBuilder result = SftpTransferResult.builder()
            .localPath(localFilePath.toString())
            .remotePath(remoteFilePath)
            .startedAt(LocalDateTime.now());

        int attempt = 0;
        Exception lastEx = null;

        while (attempt < props.getRetry().getMaxAttempts()) {
            attempt++;
            long start = System.currentTimeMillis();
            try (ClientSession session = openSession();
                 SftpClient sftp = openSftpClient(session)) {

                Files.createDirectories(localDir);

                long totalRead = 0;
                try (SftpClient.CloseableHandle handle = sftp.open(
                        remoteFilePath, EnumSet.of(SftpClient.OpenMode.Read));
                     OutputStream out = Files.newOutputStream(localFilePath,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                    byte[] buffer = new byte[256 * 1024];
                    long offset = 0;
                    int read;
                    while (true) {
                        read = sftp.read(handle, offset, buffer, 0, buffer.length);
                        if (read <= 0) break;
                        out.write(buffer, 0, read);
                        offset += read;
                        totalRead += read;
                    }
                }

                long elapsed = System.currentTimeMillis() - start;
                log.info("[SFTP] 다운로드 완료 [시도:{}/{}] {} → {} ({} bytes, {}ms)",
                    attempt, props.getRetry().getMaxAttempts(),
                    remoteFilePath, localFilePath, totalRead, elapsed);

                return result
                    .success(true)
                    .fileSize(totalRead)
                    .transferTimeMs(elapsed)
                    .retryCount(attempt - 1)
                    .finishedAt(LocalDateTime.now())
                    .build();

            } catch (Exception e) {
                lastEx = e;
                log.warn("[SFTP] 다운로드 실패 [시도:{}/{}] {} - {}",
                    attempt, props.getRetry().getMaxAttempts(),
                    remoteFilePath, e.getMessage());
                if (attempt < props.getRetry().getMaxAttempts()) {
                    sleep(props.getRetry().getBackoffDelay());
                }
            }
        }

        return result
            .success(false)
            .retryCount(attempt - 1)
            .errorMessage(lastEx != null ? lastEx.getMessage() : "알 수 없는 오류")
            .finishedAt(LocalDateTime.now())
            .build();
    }

    /**
     * 원격 디렉터리 파일 목록 조회
     *
     * @param remoteDir 원격 디렉터리 경로
     * @return 파일명 목록
     */
    public List<String> listRemoteFiles(String remoteDir) {
        List<String> files = new ArrayList<>();
        try (ClientSession session = openSession();
             SftpClient sftp = openSftpClient(session)) {

            for (SftpClient.DirEntry entry : sftp.readDir(remoteDir)) {
                String name = entry.getFilename();
                if (!".".equals(name) && !"..".equals(name)) {
                    files.add(name);
                }
            }
            log.debug("[SFTP] 원격 목록 조회: {} → {} 건", remoteDir, files.size());

        } catch (IOException e) {
            log.error("[SFTP] 원격 목록 조회 실패: {} - {}", remoteDir, e.getMessage());
            throw new SftpTransferException("원격 목록 조회 실패", remoteDir, false, e);
        }
        return files;
    }

    /**
     * 원격 파일 존재 여부 확인
     */
    public boolean existsRemote(String remoteFilePath) {
        try (ClientSession session = openSession();
             SftpClient sftp = openSftpClient(session)) {

            sftp.stat(remoteFilePath);
            return true;

        } catch (Exception e) {
            // SSH_FX_NO_SUCH_FILE → false
            return false;
        }
    }

    /**
     * 원격 파일 삭제
     */
    public boolean deleteRemote(String remoteFilePath) {
        try (ClientSession session = openSession();
             SftpClient sftp = openSftpClient(session)) {

            sftp.remove(remoteFilePath);
            log.info("[SFTP] 원격 파일 삭제: {}", remoteFilePath);
            return true;

        } catch (IOException e) {
            log.warn("[SFTP] 원격 파일 삭제 실패: {} - {}", remoteFilePath, e.getMessage());
            return false;
        }
    }

    /**
     * 접속 연결 테스트
     * Jenkins / 배치 실행 전 사전 체크용
     */
    public boolean testConnection() {
        try (ClientSession session = openSession()) {
            boolean ok = session.isOpen();
            log.info("[SFTP] 연결 테스트 {} → {}@{}:{} 결과: {}",
                ok ? "성공" : "실패",
                props.getUsername(), props.getHost(), props.getPort(), ok);
            return ok;
        } catch (Exception e) {
            log.error("[SFTP] 연결 테스트 실패: {}", e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * SSH 세션 생성
     * try-with-resources 로 반드시 닫아야 한다.
     */
    private ClientSession openSession() throws IOException {
        ClientSession session = sshClient.connect(
                props.getUsername(), props.getHost(), props.getPort())
            .verify(props.getConnectTimeout(), TimeUnit.MILLISECONDS)
            .getSession();

        // 인증
        if ("privateKey".equalsIgnoreCase(props.getAuthType())) {
            // 개인키 인증 → SshClient 빈 설정에서 KeyIdentityProvider 를 이미 설정함
            log.debug("[SFTP] 개인키 인증 시도");
        } else {
            // 비밀번호 인증
            session.addPasswordIdentity(props.getPassword());
            log.debug("[SFTP] 비밀번호 인증 시도");
        }

        session.auth()
            .verify(props.getConnectTimeout(), TimeUnit.MILLISECONDS);

        log.debug("[SFTP] 세션 연결 성공: {}@{}:{}",
            props.getUsername(), props.getHost(), props.getPort());
        return session;
    }

    /** SftpClient 생성 */
    private SftpClient openSftpClient(ClientSession session) throws IOException {
        return SftpClientFactory.instance().createSftpClient(session);
    }

    /**
     * 원격 디렉터리 재귀 생성 (mkdir -p 동일)
     * 이미 존재하면 그냥 통과.
     */
    private void mkdirsRemote(SftpClient sftp, String remoteDir) throws IOException {
        if (remoteDir == null || remoteDir.isEmpty() || "/".equals(remoteDir)) return;

        String[] parts = remoteDir.split("/");
        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            if (part.isEmpty()) {
                current.append("/");
                continue;
            }
            current.append(part).append("/");
            String path = current.toString();

            try {
                sftp.stat(path);  // 존재하면 통과
            } catch (IOException e) {
                // 없으면 생성
                try {
                    sftp.mkdir(path);
                    log.debug("[SFTP] 원격 디렉터리 생성: {}", path);
                } catch (IOException me) {
                    // 동시 생성 경쟁에서 이미 생성된 경우 무시
                    if (!me.getMessage().contains("File already exists")) {
                        throw me;
                    }
                }
            }
        }
    }

    /** 재시도 대기 */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
