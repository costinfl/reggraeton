import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves the demo shop used by reggraeton's end-to-end check. JDK only, no dependencies:
 *
 * <pre>java samples/demo-app/DemoServer.java [port] [site-directory]</pre>
 */
public class DemoServer {

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path site = (args.length > 1 ? Path.of(args[1]) : Path.of("samples/demo-app/site")).toAbsolutePath().normalize();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> serve(exchange, site));
        server.start();
        System.out.println("Demo shop on http://localhost:" + port + "/login.html (serving " + site + ")");
    }

    private static void serve(HttpExchange exchange, Path site) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/login.html";
        }
        Path file = site.resolve(path.substring(1)).normalize();
        if (!file.startsWith(site) || !Files.isRegularFile(file)) {
            byte[] body = "Not found".getBytes();
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1);
        exchange.getResponseHeaders().add("Content-Type", TYPES.getOrDefault(extension, "application/octet-stream"));
        byte[] body = Files.readAllBytes(file);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
