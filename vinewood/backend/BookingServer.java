import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BookingServer {
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final Path SITE_ROOT = Paths.get(".").toAbsolutePath().normalize();
    private static final Path BOOKINGS_FILE = SITE_ROOT.resolve("data").resolve("bookings.csv");

    private BookingServer() {
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
        server.createContext("/api/", BookingServer::handleApi);
        server.createContext("/", BookingServer::serveSiteFile);
        server.start();
        System.out.println("Winewood backend running at http://localhost:8080");
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/health".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, "{\"status\":\"ok\"}");
            return;
        }
        if (!"/api/bookings".equals(path)) {
            sendJson(exchange, 404, "{\"error\":\"API endpoint not found\"}");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            sendJson(exchange, 405, "{\"error\":\"Use POST to submit a booking\"}");
            return;
        }

        try {
            Map<String, String> form = readForm(exchange.getRequestBody());
            Booking booking = Booking.from(form);
            saveBooking(booking);
            sendJson(exchange, 201, "{\"message\":\"Booking registered\",\"id\":\"" + booking.id + "\"}");
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, "{\"error\":\"" + jsonEscape(exception.getMessage()) + "\"}");
        } catch (IOException exception) {
            sendJson(exchange, 500, "{\"error\":\"Could not save the booking\"}");
        }
    }

    private static Map<String, String> readForm(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int total = 0;
        int count;
        while ((count = input.read(chunk)) != -1) {
            total += count;
            if (total > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("Booking request is too large");
            }
            buffer.write(chunk, 0, count);
        }

        Map<String, String> values = new HashMap<String, String>();
        String body = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], "UTF-8");
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], "UTF-8") : "";
            values.put(key, value);
        }
        return values;
    }

    private static void saveBooking(Booking booking) throws IOException {
        synchronized (BookingServer.class) {
            Files.createDirectories(BOOKINGS_FILE.getParent());
            boolean writeHeader = !Files.exists(BOOKINGS_FILE) || Files.size(BOOKINGS_FILE) == 0;
            StringBuilder row = new StringBuilder();
            if (writeHeader) {
                row.append("id,created_at,checkin,checkout,persons,adults,children,room_type\n");
            }
            row.append(booking.id).append(',')
                    .append(LocalDateTime.now()).append(',')
                    .append(booking.checkin).append(',')
                    .append(booking.checkout).append(',')
                    .append(booking.persons).append(',')
                    .append(booking.adults).append(',')
                    .append(booking.children).append(',')
                    .append(booking.roomType).append('\n');
            Files.write(BOOKINGS_FILE, row.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static void serveSiteFile(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            sendText(exchange, 405, "Method not allowed");
            return;
        }

        String requestedPath = exchange.getRequestURI().getPath();
        if ("/".equals(requestedPath)) {
            requestedPath = "/index.html";
        }
        Path candidate = SITE_ROOT.resolve(requestedPath.substring(1)).normalize();
        if (!candidate.startsWith(SITE_ROOT) || !isPublicFile(candidate)) {
            sendText(exchange, 404, "Not found");
            return;
        }

        Path realRoot = SITE_ROOT.toRealPath();
        Path realFile = candidate.toRealPath();
        if (!realFile.startsWith(realRoot)) {
            sendText(exchange, 404, "Not found");
            return;
        }
        String contentType = contentType(realFile);
        if (contentType == null) {
            sendText(exchange, 404, "Not found");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        byte[] content = Files.readAllBytes(realFile);
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private static boolean isPublicFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String relative = SITE_ROOT.relativize(path).toString().replace('\\', '/');
        return !relative.startsWith(".") && !relative.startsWith("backend/") && contentType(path) != null;
    }

    private static String contentType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".avif")) return "image/avif";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        return null;
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Booking {
        private final String id;
        private final LocalDateTime checkin;
        private final LocalDateTime checkout;
        private final int persons;
        private final int adults;
        private final int children;
        private final String roomType;

        private Booking(LocalDateTime checkin, LocalDateTime checkout, int persons,
                        int adults, int children, String roomType) {
            this.id = UUID.randomUUID().toString();
            this.checkin = checkin;
            this.checkout = checkout;
            this.persons = persons;
            this.adults = adults;
            this.children = children;
            this.roomType = roomType;
        }

        private static Booking from(Map<String, String> form) {
            LocalDateTime checkin = parseDate(form.get("checkin"), "Check-in time is required");
            LocalDateTime checkout = parseDate(form.get("checkout"), "Check-out time is required");
            if (!checkout.isAfter(checkin)) {
                throw new IllegalArgumentException("Check-out must be after check-in");
            }

            int persons = parseCount(form.get("person"), "Number of persons", 1);
            int adults = parseCount(form.get("adults"), "Number of adults", 1);
            int children = parseCount(form.get("children"), "Number of children", 0);
            if (adults + children != persons) {
                throw new IllegalArgumentException("Adults and children must add up to the total number of persons");
            }

            String roomType = form.get("roomtype");
            if (!"delux".equals(roomType) && !"king".equals(roomType)
                    && !"royal".equals(roomType) && !"normal".equals(roomType)) {
                throw new IllegalArgumentException("Choose a valid room type");
            }
            return new Booking(checkin, checkout, persons, adults, children, roomType);
        }

        private static LocalDateTime parseDate(String value, String message) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(message);
            }
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("Enter a valid check-in and check-out time");
            }
        }

        private static int parseCount(String value, String label, int minimum) {
            try {
                int count = Integer.parseInt(value);
                if (count < minimum || count > 20) {
                    throw new IllegalArgumentException(label + " must be between " + minimum + " and 20");
                }
                return count;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Enter a valid " + label.toLowerCase());
            }
        }
    }
}