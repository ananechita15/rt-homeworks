package ssatr.tracker;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tine minte ultima pozitie a fiecarui vehicul de pe linia si sensul alese
 * si spune care este cel mai apropiat vehicul CARE INCA VINE spre statie.
 *
 * Distanta se masoara pe traseul liniei (shape), nu in linie dreapta. Un vehicul
 * care a trecut deja de statie (pe traseu se afla dupa ea) nu mai conteaza.
 */
class NearestBus {

    /** Un vehicul scos din calcul daca nu a mai trimis nimic de atat timp. */
    private static final long MAX_SILENCE_MS = 120_000;

    /** Un vehicul mai departe de traseu decat atat nu circula pe aceasta linie/sens. */
    private static final double MAX_OFF_ROUTE_M = 150;

    private record Seen(Model.Vehicle vehicle, long receivedAtMs, double meters) { }

    /** Rezultatul calculului: vehiculul si distanta pe traseu pana la statie. */
    record Result(Model.Vehicle vehicle, double meters) { }

    private final Map<Integer, Seen> latest = new ConcurrentHashMap<>();   // cheia: id vehicul
    private final Model.Line line;
    private final Model.Direction direction;
    private final Model.Stop stop;

    private final List<double[]> shape;      // traseul sensului ales
    private final double[] alongMeters;      // alongMeters[i] = metri de la inceputul traseului pana la punctul i
    private final int stopIndex;             // punctul traseului cel mai apropiat de statie

    NearestBus(Model.Line line, Model.Direction direction, Model.Stop stop) {
        this.line = line;
        this.direction = direction;
        this.stop = stop;
        this.shape = direction.shape();

        if (shape.size() >= 2) {
            alongMeters = new double[shape.size()];
            for (int i = 1; i < shape.size(); i++) {
                alongMeters[i] = alongMeters[i - 1] + Geo.distanceMeters(
                        shape.get(i - 1)[0], shape.get(i - 1)[1], shape.get(i)[0], shape.get(i)[1]);
            }
            stopIndex = snap(stop.lat(), stop.lon()).index();
        } else {
            alongMeters = null;              // fara traseu: ramane distanta in linie dreapta
            stopIndex = -1;
        }
    }

    /** Cel mai apropiat punct al traseului de o pozitie GPS. */
    private record Snap(int index, double offRouteMeters) { }

    private Snap snap(double lat, double lon) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < shape.size(); i++) {
            double d = Geo.distanceMeters(lat, lon, shape.get(i)[0], shape.get(i)[1]);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return new Snap(best, bestDistance);
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

        double meters;
        if (alongMeters == null) {
            meters = Geo.distanceMeters(v.lat(), v.lon(), stop.lat(), stop.lon());
        } else {
            Snap s = snap(v.lat(), v.lon());
            if (s.offRouteMeters() > MAX_OFF_ROUTE_M || s.index() > stopIndex) {
                latest.remove(v.id());                // in afara traseului sau a trecut deja de statie
                return;
            }
            meters = alongMeters[stopIndex] - alongMeters[s.index()];   // distanta pe traseu pana la statie
        }
        latest.put(v.id(), new Seen(v, System.currentTimeMillis(), meters));
    }

    /** Cel mai apropiat vehicul care vine spre statie, sau gol daca nu stim niciunul. */
    Optional<Result> nearest() {
        long now = System.currentTimeMillis();
        Result best = null;
        for (Seen s : latest.values()) {
            if (now - s.receivedAtMs() > MAX_SILENCE_MS) {
                continue;                             // vehicul care a disparut
            }
            if (best == null || s.meters() < best.meters()) {
                best = new Result(s.vehicle(), s.meters());
            }
        }
        return Optional.ofNullable(best);
    }

    /** Cate vehicule care vin spre statie cunoastem acum. */
    int knownVehicles() {
        long now = System.currentTimeMillis();
        return (int) latest.values().stream()
                .filter(s -> now - s.receivedAtMs() <= MAX_SILENCE_MS)
                .count();
    }
}