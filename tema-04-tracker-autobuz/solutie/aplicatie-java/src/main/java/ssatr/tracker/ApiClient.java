package ssatr.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Clientul API-ului REST CTP Cluj (linii, trasee, statii). Cheia vine din CTP_KEY. */
class ApiClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String key = Config.env("CTP_KEY");

    private JsonObject get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(Config.API_BASE + path))
                .header("X-API-Key", key)
                .GET()
                .build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("API a raspuns " + response.statusCode() + ": " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /** Toate liniile din /api/lines. */
    List<Model.Line> lines() throws IOException, InterruptedException {
        JsonArray array = get("/api/lines").getAsJsonArray("lines");
        List<Model.Line> result = new ArrayList<>();
        for (JsonElement e : array) {
            JsonObject o = e.getAsJsonObject();
            result.add(new Model.Line(
                    o.get("route_id").getAsInt(),
                    text(o, "line"),
                    text(o, "name"),
                    text(o, "type")));
        }
        return result;
    }

    /** Sensurile unei linii, cu statiile in ordinea parcurgerii, din /api/line?route_id=N. */
    List<Model.Direction> directions(int routeId) throws IOException, InterruptedException {
        JsonArray array = get("/api/line?route_id=" + routeId).getAsJsonArray("directions");
        List<Model.Direction> result = new ArrayList<>();
        for (JsonElement e : array) {
            JsonObject o = e.getAsJsonObject();
            List<Model.Stop> stops = new ArrayList<>();
            for (JsonElement s : o.getAsJsonArray("stops")) {
                JsonObject so = s.getAsJsonObject();
                stops.add(new Model.Stop(
                        so.get("id").getAsInt(),
                        text(so, "name"),
                        so.get("lat").getAsDouble(),
                        so.get("lon").getAsDouble()));
            }
            // traseul: lista de puncte [lat, lon], in ordinea parcurgerii
            List<double[]> shape = new ArrayList<>();
            JsonElement shapeElement = o.get("shape");
            if (shapeElement != null && shapeElement.isJsonArray()) {
                for (JsonElement p : shapeElement.getAsJsonArray()) {
                    JsonArray point = p.getAsJsonArray();
                    shape.add(new double[] {point.get(0).getAsDouble(), point.get(1).getAsDouble()});
                }
            }
            result.add(new Model.Direction(text(o, "trip_id"), text(o, "headsign"), stops, shape));
        }
        return result;
    }

    /** Un camp text care poate lipsi sau fi null in date reale. */
    private static String text(JsonObject o, String field) {
        JsonElement v = o.get(field);
        return (v == null || v.isJsonNull()) ? "" : v.getAsString();
    }
}