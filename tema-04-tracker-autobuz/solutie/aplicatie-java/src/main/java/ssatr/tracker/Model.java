package ssatr.tracker;

import java.util.List;

/** Datele folosite de aplicatie: linii, sensuri, statii, vehicule. */
final class Model {

    record Line(int routeId, String number, String name, String type) { }

    record Stop(int id, String name, double lat, double lon) { }

    /** Un sens al liniei: statiile in ordinea parcurgerii si traseul (shape) ca puncte {lat, lon}. */
    record Direction(String tripId, String headsign, List<Stop> stops, List<double[]> shape) { }

    /** O pozitie raportata de un vehicul. routeId e -1 si tripId e "" daca lipsesc din mesaj. */
    record Vehicle(int id, String label, int routeId, String tripId,
                   double lat, double lon, double speed, long ageSeconds) { }

    private Model() { }
}