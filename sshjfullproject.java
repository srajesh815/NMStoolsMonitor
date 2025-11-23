Spring Boot — HPNA (Jump Server on port 8022) CSV Executor (SSHJ)

This repository provides a complete Spring Boot solution that:

Reads an input CSV with two columns: hostname,command

Connects to HPNA jump server (port 8022) with password auth using SSHJ

Creates a local port-forward to each device and SSHes into the device

Executes the given command on the device

Runs up to 15 device executions in parallel

Writes an output CSV with three columns: hostname,command,output

Exposes REST endpoints to trigger processing (file upload or providing file paths)



---

Files included (single-file listing for copy/paste)

--- pom.xml ---

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>hpna-sshj-csv</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>17</java.version>
        <spring.boot.version>3.1.4</spring.boot.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring.boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot web + file upload support -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- For reactive WebClient if needed later -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- SSHJ library -->
        <dependency>
            <groupId>com.hierynomus</groupId>
            <artifactId>sshj</artifactId>
            <version>0.38.0</version>
        </dependency>

        <!-- Apache Commons CSV -->
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-csv</artifactId>
            <version>1.10.0</version>
        </dependency>

        <!-- Commons IO for IOUtils (optional) -->
        <dependency>
            <groupId>commons-io</groupId>
            <artifactId>commons-io</artifactId>
            <version>2.13.0</version>
        </dependency>

        <!-- Lombok (optional, used in DTO) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Logging -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-logging</artifactId>
        </dependency>

        <!-- Testing (optional) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>

--- src/main/resources/application.yml ---

server:
  port: 8080

hpna:
  host: hpna.example.com
  port: 8022
  username: hpna_user
  password: hpna_password

device:
  username: device_user
  password: device_password

csv:
  max-parallel: 15
  input-temp-dir: /tmp/hpna-input
  output-temp-dir: /tmp/hpna-output

ssh:
  connect-timeout-ms: 15000
  command-timeout-sec: 60

--- src/main/java/com/example/hpna/HpnaSshCsvApplication.java ---

package com.example.hpna;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HpnaSshCsvApplication {
    public static void main(String[] args) {
        SpringApplication.run(HpnaSshCsvApplication.class, args);
    }
}

--- src/main/java/com/example/hpna/config/ExecutorConfig.java ---

package com.example.hpna.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class ExecutorConfig {

    @Value("${csv.max-parallel:15}")
    private int maxParallel;

    @Bean(name = "sshExecutor")
    public Executor sshExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxParallel);
        executor.setMaxPoolSize(maxParallel);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ssh-exec-");
        executor.initialize();
        return executor;
    }
}

--- src/main/java/com/example/hpna/dto/DeviceResult.java ---

package com.example.hpna.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceResult {
    private String hostname;
    private String command;
    private String output;
}

--- src/main/java/com/example/hpna/service/SshService.java ---

package com.example.hpna.service;

import com.example.hpna.dto.DeviceResult;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Service
public class SshService {

    private static final Logger log = LoggerFactory.getLogger(SshService.class);

    @Value("${hpna.host}")
    private String hpnaHost;

    @Value("${hpna.port}")
    private int hpnaPort;

    @Value("${hpna.username}")
    private String hpnaUser;

    @Value("${hpna.password}")
    private String hpnaPass;

    @Value("${device.username}")
    private String deviceUser;

    @Value("${device.password}")
    private String devicePass;

    @Value("${ssh.connect-timeout-ms:15000}")
    private int connectTimeoutMs;

    @Value("${ssh.command-timeout-sec:60}")
    private int commandTimeoutSec;

    /**
     * Connects to HPNA jump server using password, creates a local port forward and
     * connects to the device via forwarded port. Executes `command` and returns output.
     */
    public String runCommandViaHpna(String deviceHost, String command) throws Exception {
        SSHClient jumpClient = new SSHClient();
        SSHClient deviceClient = new SSHClient();

        jumpClient.addHostKeyVerifier((h, p, k) -> true);
        deviceClient.addHostKeyVerifier((h, p, k) -> true);

        // Result collected from both stdout and stderr
        StringBuilder resultBuilder = new StringBuilder();
        int localPort = -1;

        try {
            log.debug("Connecting to HPNA {}:{} as {}", hpnaHost, hpnaPort, hpnaUser);
            jumpClient.connect(hpnaHost, hpnaPort);
            jumpClient.getConnection().getTransport().setHeartbeatInterval(0); // optional
            jumpClient.authPassword(hpnaUser, hpnaPass);

            // create local port forward (random available port)
            localPort = jumpClient.forwardLocal(0, deviceHost, 22);
            log.debug("Created local forward on port {} to {}:22 via HPNA", localPort, deviceHost);

            // connect to forwarded port (localhost:localPort)
            deviceClient.connect("127.0.0.1", localPort);
            deviceClient.authPassword(deviceUser, devicePass);

            try (Session session = deviceClient.startSession()) {
                log.debug("Executing command on device {}: {}", deviceHost, command);
                Session.Command cmd = session.exec(command);

                InputStream stdout = cmd.getInputStream();
                InputStream stderr = cmd.getErrorStream();

                // Wait for command to finish or until timeout
                boolean finished = cmd.join(commandTimeoutSec, TimeUnit.SECONDS);

                String out = IOUtils.toString(stdout, StandardCharsets.UTF_8);
                String err = IOUtils.toString(stderr, StandardCharsets.UTF_8);

                if (out != null && !out.isBlank()) {
                    resultBuilder.append(out.trim());
                }
                if (err != null && !err.isBlank()) {
                    if (resultBuilder.length() > 0) resultBuilder.append("\n");
                    resultBuilder.append("ERROR: ").append(err.trim());
                }

                if (!finished) {
                    resultBuilder.append("\nWARNING: command timed out after ").append(commandTimeoutSec).append(" seconds");
                    log.warn("Command did not finish within {}s on device {}", commandTimeoutSec, deviceHost);
                }

                cmd.close();
            }

            return resultBuilder.toString();

        } finally {
            try {
                if (deviceClient != null && deviceClient.isConnected()) deviceClient.disconnect();
            } catch (Exception ex) {
                log.warn("Error disconnecting device client", ex);
            }
            try {
                // cancel local forward by disconnecting jump client (port auto-closed on disconnect)
                if (jumpClient != null && jumpClient.isConnected()) jumpClient.disconnect();
            } catch (Exception ex) {
                log.warn("Error disconnecting jump client", ex);
            }
        }
    }
}

--- src/main/java/com/example/hpna/service/CsvExecutionService.java ---

package com.example.hpna.service;

import com.example.hpna.dto.DeviceResult;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Service
public class CsvExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CsvExecutionService.class);

    @Autowired
    private SshService sshService;

    @Autowired
    @Qualifier("sshExecutor")
    private Executor sshExecutor;

    @Value("${csv.input-temp-dir:/tmp/hpna-input}")
    private String inputTempDir;

    @Value("${csv.output-temp-dir:/tmp/hpna-output}")
    private String outputTempDir;

    /**
     * Process CSV located at inputPath and write output to outputPath
     */
    public Path processCsv(Path inputPath, Path outputPath) throws Exception {
        Files.createDirectories(outputPath.getParent());

        List<CompletableFuture<DeviceResult>> futureList = new ArrayList<>();

        try (Reader in = new FileReader(inputPath.toFile())) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(in);

            for (CSVRecord r : records) {
                final String host = r.get("hostname").trim();
                final String command = r.get("command").trim();

                CompletableFuture<DeviceResult> fut = CompletableFuture.supplyAsync(() -> {
                    try {
                        String output = sshService.runCommandViaHpna(host, command);
                        return new DeviceResult(host, command, output == null ? "" : output);
                    } catch (Exception e) {
                        log.error("Error running command for {}", host, e);
                        return new DeviceResult(host, command, "ERROR: " + e.getMessage());
                    }
                }, sshExecutor);

                futureList.add(fut);
            }
        }

        // Wait for all to finish
        CompletableFuture<Void> all = CompletableFuture.allOf(futureList.toArray(new CompletableFuture[0]));
        try {
            all.join();
        } catch (CompletionException ce) {
            log.warn("One or more tasks failed", ce);
        }

        // Collect results in order of submission
        List<DeviceResult> results = new ArrayList<>();
        for (CompletableFuture<DeviceResult> f : futureList) {
            try {
                results.add(f.join());
            } catch (Exception ex) {
                log.warn("Task join error", ex);
            }
        }

        // Write output CSV
        try (FileWriter writer = new FileWriter(outputPath.toFile());
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("hostname", "command", "output"))) {
            for (DeviceResult r : results) {
                printer.printRecord(r.getHostname(), r.getCommand(), r.getOutput());
            }
            printer.flush();
        }

        return outputPath;
    }
}

--- src/main/java/com/example/hpna/controller/ApiController.java ---

package com.example.hpna.controller;

import com.example.hpna.service.CsvExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    @Autowired
    private CsvExecutionService csvExecutionService;

    @Value("${csv.input-temp-dir:/tmp/hpna-input}")
    private String inputTempDir;

    @Value("${csv.output-temp-dir:/tmp/hpna-output}")
    private String outputTempDir;

    /**
     * Upload CSV (multipart) and process it. Returns the output CSV as download.
     * Input CSV must have header: hostname,command
     */
    @PostMapping(value = "/upload-and-run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> uploadAndRun(@RequestParam("file") MultipartFile file) throws Exception {
        Files.createDirectories(Paths.get(inputTempDir));
        Files.createDirectories(Paths.get(outputTempDir));

        Path inPath = Paths.get(inputTempDir).resolve(System.currentTimeMillis() + "-input.csv");
        Path outPath = Paths.get(outputTempDir).resolve(System.currentTimeMillis() + "-output.csv");

        Files.copy(file.getInputStream(), inPath);

        log.info("Saved uploaded CSV to {}", inPath);

        csvExecutionService.processCsv(inPath, outPath);

        byte[] bytes = Files.readAllBytes(outPath);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=results.csv")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    /**
     * For simple local files (paths) - process existing input file and write out.
     */
    @PostMapping("/process-local")
    public ResponseEntity<String> processLocal(@RequestParam String inputPath, @RequestParam String outputPath) throws Exception {
        Path in = Paths.get(inputPath);
        Path out = Paths.get(outputPath);
        csvExecutionService.processCsv(in, out);
        return ResponseEntity.ok("Processed. Output: " + out.toAbsolutePath());
    }
}

--- README.md ---

# HPNA SSHJ CSV Executor (Spring Boot)

## Overview
This Spring Boot application reads a CSV with `hostname,command` lines, connects to each device by first authenticating to HPNA jump server (port 8022) using SSH password, port-forwards to the device and executes the command. Results are written to `hostname,command,output` CSV.

## Endpoints
- `POST /api/upload-and-run` — multipart file upload (CSV). Returns output CSV as download.
- `POST /api/process-local?inputPath=...&outputPath=...` — use local paths.

## Notes / Security
- This example stores HPNA and device credentials in `application.yml` for simplicity. For production, use a secret manager or environment variables.
- Host key verification is disabled (`addHostKeyVerifier((...) -> true)`) for demo. Replace with proper host key checks.
- Commands have a timeout (configurable). Adjust `ssh.command-timeout-sec` as needed.

## Build

mvn clean package java -jar target/hpna-sshj-csv-0.0.1-SNAPSHOT.jar




---

If you'd like I can:

Provide a Dockerfile and docker-compose

Replace password auth with key-based auth

Add improved host key verification

Add retries/backoff per-device

Add logging per-host to file



---

End of code bundle.