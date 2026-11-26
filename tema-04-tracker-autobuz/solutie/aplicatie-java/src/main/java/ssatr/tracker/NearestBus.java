package ssatr.tracker;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tine minte ultima pozitie a fiecarui vehicul de pe linia si sensul alese
 * si spune care este cel mai apropiat de statie.
 */
class NearestBus {

    /** Un vehicul scos din calcul daca nu a mai trimis nimic de atat timp. */
    private static final long MAX_SILENCE_MS = 120_000;

    private record Seen(Model.Vehicle vehicle, long receivedAtMs) { }

    /** Rezultatul calculului: vehiculul si distanta pana la statie. */
    record Result(Model.Vehicle vehicle, double meters) { }

    private final Map<Integer, Seen> latest = new ConcurrentHashMap<>();   // cheia: id vehicul
    private final Model.Line line;
    private final Model.Direction direction;
    private final Model.Stop stop;

    NearestBus(Model.Line line, Model.Direction direction, Model.Stop stop) {
        this.line = line;
        this.direction = direction;
        this.stop = stop;
    }

    /** Apelata (din threadul RabbitMQ) la fiecare pozitie primita. */
    void onVehicle(Model.Vehicle v) {
        if (v.routeId() != line.routeId()) {
            return;                                   // alta linie
        }
        if (!v.tripId().isEmpty() && !v.tripId().equals(direction.tripId())) {
            return;                                   // celalalt sens
        }
        if (Double.isNaN(v.lat()) || Double.isNaN(v.lon())) {
            return;                                   // pozitie lipsa
        }
        latest.put(v.id(), new Seen(v, System.currentTimeMillis()));
    }

    /** Cel mai apropiat vehicul, sau gol daca nu stim niciunul. */
    Optional<Result> nearest() {
        long now = System.currentTimeMillis();
        Result best = null;
        for (Seen s : latest.values()) {
            if (now - s.receivedAtMs() > MAX_SILENCE_MS) {
                continue;                             // vehicul care a disparut
            }
            double meters = Geo.distanceMeters(
                    s.vehicle().lat(), s.vehicle().lon(), stop.lat(), stop.lon());
            if (best == null || meters < best.meters()) {
                best = new Result(s.vehicle(), meters);
            }
        }
        return Optional.ofNullable(best);
    }

    /** Cate vehicule cunoastem acum. */
    int knownVehicles() {
        long now = System.currentTimeMillis();
        return (int) latest.values().stream()
                .filter(s -> now - s.receivedAtMs() <= MAX_SILENCE_MS)
                .count();
    }
}