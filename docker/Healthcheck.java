import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Uses only the runtime JRE; never prints credentials or response details. */
public final class Healthcheck {
    public static void main(String[] args) {
        HttpURLConnection connection = null;
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/actuator/health").toURL().openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(3000);
            if (connection.getResponseCode() != 200) System.exit(1);
            try (var input = connection.getInputStream()) {
                String body = new String(input.readNBytes(4096), StandardCharsets.UTF_8);
                if (!body.replaceAll("\\s+", "").contains("\"status\":\"UP\"")) System.exit(1);
            }
        } catch (Exception failure) {
            System.exit(1);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
