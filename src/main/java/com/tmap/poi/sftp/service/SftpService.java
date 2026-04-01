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
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
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
            log.error("전송 중 오류 발생", e);
        }
        return SftpTransferResult.Summary.builder().details(results).totalFiles(results.size())
                .successCount((int)results.stream().filter(r->r.isSuccess()).count())
                .failureCount((int)results.stream().filter(r->!r.isSuccess()).count()).build();
    }

    private SftpTransferResult uploadFile(SftpClient sftp, Path file, String remoteDir) {
        // 상세 업로드 구현 (생략 - 기존 로직과 동일)
        return SftpTransferResult.builder().success(true).build(); 
    }

    public boolean testConnection() {
        try (ClientSession session = openSession()) { return session.isOpen(); }
        catch (Exception e) { return false; }
    }

    /*private ClientSession openSession() throws IOException {
        ClientSession session = sshClient.connect(props.getUsername(), props.getHost(), props.getPort())
                .verify(props.getConnectTimeout(), TimeUnit.MILLISECONDS).getSession();
        if (!"privateKey".equals(props.getAuthType())) session.addPasswordIdentity(props.getPassword());
        session.auth().verify(props.getConnectTimeout(), TimeUnit.MILLISECONDS);
        return session;
    }*/
    
    private ClientSession openSession() throws IOException {
        // [DEBUG] 접속 정보 및 비밀번호 전체 출력
        //log.info("[DEBUG] SFTP Connection Attempt");
        //log.info("[DEBUG] Host: {}:{}", props.getHost(), props.getPort());
        //log.info("[DEBUG] User: {}", props.getUsername());
        //log.info("[DEBUG] AuthType: {}", props.getAuthType());
        //log.info("[DEBUG] FULL PASSWORD: [{}]", props.getPassword()); // 비밀번호 전체 출력 (확인 후 삭제 필수)

        ClientSession session = sshClient.connect(props.getUsername(), props.getHost(), props.getPort())
                .verify(props.getConnectTimeout(), TimeUnit.MILLISECONDS).getSession();

        if (!"privateKey".equalsIgnoreCase(props.getAuthType())) {
            // 비밀번호 추가
            session.addPasswordIdentity(props.getPassword());
        }

        session.auth().verify(props.getConnectTimeout(), TimeUnit.MILLISECONDS);
        return session;
    }

    private SftpClient openSftpClient(ClientSession session) throws IOException {
        return SftpClientFactory.instance().createSftpClient(session);
    }
}