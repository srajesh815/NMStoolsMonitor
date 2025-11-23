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

@Service
public class SshService {

    private static final String JUMP_HOST = "hpna.jumpserver.com";
    private static final int JUMP_PORT = 8022;
    private static final String JUMP_USER = "jumpuser";
    private static final String JUMP_PASS = "jump_pass";

    private static final String DEVICE_USER = "admin";
    private static final String DEVICE_PASS = "password123";

    /**
     * Execute command on a network device through jump server
     */
    public String runCommand(String deviceHost, String command) throws Exception {

        SSHClient ssh = new SSHClient();

        // Proxy using jump server
        ProxyCommand proxy = new ProxyCommand(
                "ssh -W " + deviceHost + ":22 -p " + JUMP_PORT + " " + JUMP_USER + "@" + JUMP_HOST
        );
        ssh.setProxy(proxy);

        ssh.addHostKeyVerifier((h, p, key) -> true); // skip key checking
        ssh.connect(deviceHost);
        ssh.authPassword(DEVICE_USER, DEVICE_PASS);

        Session session = ssh.startSession();
        Session.Command cmd = session.exec(command);

        String output = IOUtils.readFully(cmd.getInputStream()).toString();

        cmd.close();
        session.close();
        ssh.disconnect();

        return output;
    }
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
