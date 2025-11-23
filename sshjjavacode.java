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

