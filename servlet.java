// AggregatorServlet.java  (Java 11+)
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class AggregatorServlet extends HttpServlet {
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    // configure these with actual endpoints & auth
    private static final String HPNA_API = "https://hpna.example.com/api/status?host=";
    private static final String NNM_API  = "https://nnm.example.com/api/deviceStatus?host=";
    private static final String SEVONE_API= "https://sevone.example.com/api/check?host=";

    private static final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String host = req.getParameter("host");
        resp.setContentType("application/json");
        resp.setCharacterEncoding("utf-8");

        if (host == null || host.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"error\":\"missing host parameter\"}");
            return;
        }

        try {
            // Prepare three async requests
            HttpRequest r1 = HttpRequest.newBuilder(URI.create(HPNA_API + URI.create(host).toString()))
                .timeout(Duration.ofSeconds(6))
                .header("Accept","application/json")
                .GET().build();

            HttpRequest r2 = HttpRequest.newBuilder(URI.create(NNM_API + URI.create(host).toString()))
                .timeout(Duration.ofSeconds(6))
                .header("Accept","application/json")
                .GET().build();

            HttpRequest r3 = HttpRequest.newBuilder(URI.create(SEVONE_API + URI.create(host).toString()))
                .timeout(Duration.ofSeconds(6))
                .header("Accept","application/json")
                .GET().build();

            CompletableFuture<HttpResponse<String>> f1 = client.sendAsync(r1, HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> f2 = client.sendAsync(r2, HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> f3 = client.sendAsync(r3, HttpResponse.BodyHandlers.ofString());

            // Wait for all (with overall timeout)
            CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2, f3);
            all.get(8, TimeUnit.SECONDS);

            String hpnaStatus = parseHpnaResponse(f1.join());
            String nnmStatus  = parseNnmResponse(f2.join());
            String sevoneStatus= parseSevoneResponse(f3.join());

            JsonObject out = new JsonObject();
            out.addProperty("host", host);
            out.addProperty("hpna", hpnaStatus);
            out.addProperty("nnm", nnmStatus);
            out.addProperty("sevone", sevoneStatus);

            // optional meta
            JsonObject meta = new JsonObject();
            meta.addProperty("hpna_checked_at", java.time.Instant.now().toString());
            out.add("meta", meta);

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(gson.toJson(out));

        } catch (TimeoutException te) {
            resp.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
            resp.getWriter().write("{\"error\":\"timeout contacting NMS tools\"}");
        } catch (Exception ex) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write("{\"error\":\""+ ex.getMessage() +"\"}");
        }
    }

    // TODO: implement parsing according to each tool's API response schema
    private String parseHpnaResponse(HttpResponse<String> r) {
        if (r.statusCode() != 200) return "UNKNOWN";
        // sample parsing logic, adapt to real API
        try {
            var json = gson.fromJson(r.body(), JsonObject.class);
            String state = json.has("status") ? json.get("status").getAsString() : null;
            if (state == null) return "UNKNOWN";
            if (state.equalsIgnoreCase("up") || state.equalsIgnoreCase("reachable")) return "UP";
            return "DOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private String parseNnmResponse(HttpResponse<String> r) {
        if (r.statusCode() != 200) return "UNKNOWN";
        try {
            var json = gson.fromJson(r.body(), JsonObject.class);
            String state = json.has("deviceState") ? json.get("deviceState").getAsString() : null;
            if (state == null) return "UNKNOWN";
            if (state.equalsIgnoreCase("up") || state.equalsIgnoreCase("managed")) return "UP";
            return "DOWN";
        } catch (Exception e) { return "UNKNOWN"; }
    }

    private String parseSevoneResponse(HttpResponse<String> r) {
        if (r.statusCode() != 200) return "UNKNOWN";
        try {
            var json = gson.fromJson(r.body(), JsonObject.class);
            String state = json.has("status") ? json.get("status").getAsString() : null;
            if (state == null) return "UNKNOWN";
            if (state.equalsIgnoreCase("ok") || state.equalsIgnoreCase("up")) return "UP";
            return "DOWN";
        } catch (Exception e) { return "UNKNOWN"; }
    }
}