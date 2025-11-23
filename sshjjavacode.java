@Configuration
public class ExecutorConfig {

    @Bean(name = "sshExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(15);     // 15 parallel devices
        executor.setMaxPoolSize(15);
        executor.setQueueCapacity(50);
        executor.initialize();
        return executor;
    }
}

public String runCommand(String deviceHost, String command) throws Exception {

    // Step 1: Connect to Jump Server
    SSHClient jump = new SSHClient();
    jump.addHostKeyVerifier((h, p, key) -> true);
    jump.connect(JUMP_HOST, JUMP_PORT);
    jump.authPassword(JUMP_USER, JUMP_PASS);

    // Step 2: Create port forwarding
    int localPort = jump.forwardLocal(0, deviceHost, 22);

    // Step 3: Connect to device using forwarded port
    SSHClient device = new SSHClient();
    device.addHostKeyVerifier((h, p, key) -> true);
    device.connect("127.0.0.1", localPort);
    device.authPassword(DEVICE_USER, DEVICE_PASS);

    // Execute command
    Session session = device.startSession();
    Session.Command cmd = session.exec(command);
    String output = IOUtils.readFully(cmd.getInputStream()).toString();

    // Cleanup
    cmd.close();
    session.close();
    device.disconnect();
    jump.disconnect();

    return output;
}

@Service
public class CsvExecutionService {

    @Autowired
    private SshService sshService;

    @Autowired
    @Qualifier("sshExecutor")
    private Executor executor;

    /**
     * Read CSV, execute SSH commands in parallel, produce output CSV
     */
    public void processCsv(String inputPath, String outputPath) throws Exception {

        List<Future<DeviceResult>> futures = new ArrayList<>();

        try (Reader reader = new FileReader(inputPath)) {

            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(reader);

            for (CSVRecord record : records) {
                String host = record.get("hostname");
                String command = record.get("command");

                // Submit parallel task
                futures.add(
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                String output = sshService.runCommand(host, command);
                                return new DeviceResult(host, command, output);

                            } catch (Exception e) {
                                return new DeviceResult(host, command, "ERROR: " + e.getMessage());
                            }
                        }, executor)
                );
            }
        }

        // Wait for all tasks
        List<DeviceResult> results = new ArrayList<>();
        for (Future<DeviceResult> f : futures) {
            results.add(f.get());
        }

        // Write results to CSV
        try (CSVPrinter printer = new CSVPrinter(
                new FileWriter(outputPath),
                CSVFormat.DEFAULT.withHeader("hostname", "command", "output")
        )) {
            for (DeviceResult r : results) {
                printer.printRecord(r.getHostname(), r.getCommand(), r.getOutput());
            }
        }
    }
}

@Data
@AllArgsConstructor
public class DeviceResult {
    private String hostname;
    private String command;
    private String output;
}

@RestController
@RequestMapping("/ssh")
public class SshController {

    @Autowired
    private CsvExecutionService csvExecutionService;

    @GetMapping("/run")
    public String run() {
        try {
            csvExecutionService.processCsv(
                "/opt/input/devices.csv",
                "/opt/output/results.csv"
            );
            return "Execution completed. Output saved.";

        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }
}
