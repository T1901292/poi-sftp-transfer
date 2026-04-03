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
        
        if (!Files.exists(localDir)) {
            log.error("로컬 디렉토리가 존재하지 않습니다: {}", localDir);
            return SftpTransferResult.Summary.builder().totalFiles(0).details(results).build();
        }

        try (ClientSession session = openSession(); SftpClient sftp = openSftpClient(session)) {
            // 원격지 디렉토리 생성 (없으면 생성)
            mkdirsRemote(sftp, remoteDir);

            // 파일 전송
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(localDir)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        results.add(uploadFile(sftp, file, remoteDir));
                    }
                }
            }
        } catch (IOException e) {
            log.error("SFTP 작업 중 오류 발생", e);
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
            log.error("파일 업로드 실패: {}", file.getFileName(), e);
            return SftpTransferResult.builder().remotePath(remotePath).success(false).errorMessage(e.getMessage()).build();
        }
    }

    private void mkdirsRemote(SftpClient sftp, String path) throws IOException {
        String[] folders = path.split("/");
        StringBuilder current = new StringBuilder();
        for (String folder : folders) {
            if (folder.isEmpty()) continue;
            current.append("/").append(folder);
            try {
                sftp.stat(current.toString());
            } catch (IOException e) {
                sftp.mkdir(current.toString());
            }
        }
    }

    private ClientSession openSession() throws IOException {
    	//log.info("[DEBUG] SFTP Connection Attempt");
        //log.info("[DEBUG] Host: {}:{}", props.getHost(), props.getPort());
        //log.info("[DEBUG] User: {}", props.getUsername());
        //log.info("[DEBUG] AuthType: {}", props.getAuthType());
        //log.info("[DEBUG] FULL PASSWORD: [{}]", props.getPassword()); 
        
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