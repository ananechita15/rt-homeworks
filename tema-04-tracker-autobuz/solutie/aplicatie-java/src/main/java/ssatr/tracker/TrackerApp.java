package ssatr.tracker;

import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Alege linia/sensul/statia (API REST), urmareste vehiculele (RabbitMQ) si vorbeste cu placa (MQTT). */
public class TrackerApp {

    public static void main(String[] args) throws Exception {
        ApiClient api = new ApiClient();
        Scanner in = new Scanner(System.in);

        Model.Line line = chooseLine(api.lines(), in);
        List<Model.Direction> directions = api.directions(line.routeId());
        Model.Direction direction = chooseDirection(directions, in);
        Model.Stop stop = chooseStop(direction, in);

        System.out.println();
        System.out.println("Ai ales: linia " + line.number() + " (route_id " + line.routeId() + "), sensul "
                + direction.tripId() + " spre " + direction.headsign() + ", statia " + stop.name()
                + " (id " + stop.id() + ", " + stop.lat() + ", " + stop.lon() + ")");

        NearestBus nearest = new NearestBus(line, direction, stop);
        CountDownLatch finished = new CountDownLatch(1);          // se opreste la BOARDED sau la Enter
        ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor();

        try (BoardLink board = new BoardLink(() -> {
            System.out.println(">>> Te-ai urcat in autobuz (BOOT apasat pe placa). Gata!");
            finished.countDown();
        });
             VehicleListener listener = new VehicleListener(nearest::onVehicle)) {

            // la fiecare 5 secunde: afisam si trimitem placii cel mai apropiat autobuz
            sender.scheduleAtFixedRate(() -> report(nearest, stop, board), 5, 5, TimeUnit.SECONDS);

            System.out.println("Urmaresc autobuzul. Apasa Enter pentru oprire sau BOOT pe placa cand urci.");
            Thread enterWatcher = new Thread(() -> {
                in.nextLine();
                finished.countDown();
            });
            enterWatcher.setDaemon(true);
            enterWatcher.start();
            finished.await();
        } finally {
            sender.shutdownNow();
        }
    }

    private static void report(NearestBus nearest, Model.Stop stop, BoardLink board) {
        try {
            doReport(nearest, stop, board);
        } catch (RuntimeException e) {
            // o exceptie ar opri definitiv executarea periodica; o afisam si continuam
            System.err.println("[raport] eroare: " + e);
        }
    }

    private static void doReport(NearestBus nearest, Model.Stop stop, BoardLink board) {
        Optional<NearestBus.Result> best = nearest.nearest();
        if (best.isEmpty()) {
            System.out.println("Inca nu stiu niciun autobuz pe aceasta linie.");
            board.sendNoBus();
            return;
        }
        NearestBus.Result r = best.get();
        long meters = Math.round(r.meters());
        System.out.printf("Cel mai apropiat: vehicul %s, %d m de statia %s (din %d vehicule cunoscute)%n",
                r.vehicle().label(), meters, stop.name(), nearest.knownVehicles());
        board.sendDistance(meters);
    }

    private static Model.Line chooseLine(List<Model.Line> lines, Scanner in) {
        while (true) {
            System.out.print("Numarul liniei (ex: 21): ");
            String number = in.nextLine().trim();
            List<Model.Line> found = lines.stream()
                    .filter(l -> l.number().equalsIgnoreCase(number))
                    .toList();
            if (found.isEmpty()) {
                System.out.println("Nu exista linia " + number);
                continue;
            }
            // numarul liniei nu e unic (exista doua linii 12), de aceea se alege dupa route_id
            for (int i = 0; i < found.size(); i++) {
                Model.Line l = found.get(i);
                System.out.println("  " + (i + 1) + ") " + l.number() + "  " + l.name()
                        + "  [" + l.type() + ", route_id " + l.routeId() + "]");
            }
            return found.get(readIndex(in, found.size()));
        }
    }

    private static Model.Direction chooseDirection(List<Model.Direction> directions, Scanner in) {
        System.out.println("Sensuri:");
        for (int i = 0; i < directions.size(); i++) {
            Model.Direction d = directions.get(i);
            System.out.println("  " + (i + 1) + ") " + d.tripId() + " spre " + d.headsign());
        }
        return directions.get(readIndex(in, directions.size()));
    }

    private static Model.Stop chooseStop(Model.Direction direction, Scanner in) {
        System.out.println("Statii:");
        List<Model.Stop> stops = direction.stops();
        for (int i = 0; i < stops.size(); i++) {
            System.out.println("  " + (i + 1) + ") " + stops.get(i).name());
        }
        return stops.get(readIndex(in, stops.size()));
    }

    /** Citeste un numar intre 1 si max si il intoarce ca index (de la 0). */
    private static int readIndex(Scanner in, int max) {
        while (true) {
            System.out.print("Alege (1-" + max + "): ");
            try {
                int n = Integer.parseInt(in.nextLine().trim());
                if (n >= 1 && n <= max) {
                    return n - 1;
                }
            } catch (NumberFormatException ignored) {
                // cade mai jos si cere din nou
            }
            System.out.println("Numar invalid.");
        }
    }
}