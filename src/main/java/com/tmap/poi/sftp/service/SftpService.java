package com.tmap.poi.sftp.service;

import com.tmap.poi.sftp.config.SftpProperties;
import com.tmap.poi.sftp.dto.SftpTransferResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j @Service @RequiredArgsConstructor
public class SftpService {
    private final SshClient sshClient;
    private final SftpProperties props;

    public SftpTransferResult.Summary uploadDirectory(Path localDir, String remoteDir) {
        List<SftpTransferResult> results = new ArrayList<>();
        try (ClientSession session = openSession(); SftpClient sftp = openSftpClient(session)) {
            Files.walk(localDir).filter(Files::isRegularFile).forEach(file -> {
                results.add(uploadFile(sftp, file, remoteDir));
            });
        } catch (IOException e) {
            log.error("디렉토리 탐색 오류", e);
        }
        return SftpTransferResult.Summary.builder()
                .details(results).totalFiles(results.size())
                .successCount((int)results.stream().filter(SftpTransferResult::isSuccess).count())
                .failureCount((int)results.stream().filter(r -> !r.isSuccess()).count()).build();
    }

    private SftpTransferResult uploadFile(SftpClient sftp, Path file, String remoteDir) {
        String remotePath = remoteDir + "/" + file.getFileName();
        long start = System.currentTimeMillis();
        try (InputStream in = Files.newInputStream(file);
             SftpClient.CloseableHandle handle = sftp.open(remotePath, EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate))) {
            
            byte[] buffer = new byte[256 * 1024];
            long offset = 0; int read;
            while ((read = in.read(buffer)) != -1) {
                sftp.write(handle, offset, buffer, 0, read);
                offset += read;
            }
            return SftpTransferResult.builder().remotePath(remotePath).fileSize(Files.size(file))
                    .transferTimeMs(System.currentTimeMillis() - start).success(true).finishedAt(LocalDateTime.now()).build();
        } catch (Exception e) {
            return SftpTransferResult.builder().remotePath(remotePath).success(false).errorMessage(e.getMessage()).localPath(file.toString()).build();
        }
    }

    public boolean testConnection() {
        try (ClientSession session = openSession()) { return session.isOpen(); } catch (Exception e) { return false; }
    }

    private ClientSession openSession() throws IOException {
        ClientSession session = sshClient.connect(props.getUsername(), props.getHost(), props.getPort())
                .verify(props.getConnectTimeout(), TimeUnit.MILLISECONDS).getSession();
        if (!"privateKey".equalsIgnoreCase(props.getAuthType())) session.addPasswordIdentity(props.getPassword());
        session.auth().verify(props.getConnectTimeout(), TimeUnit.MILLISECONDS);
        return session;
    }

    private SftpClient openSftpClient(ClientSession session) throws IOException {
        return SftpClientFactory.instance().createSftpClient(session);
    }
}