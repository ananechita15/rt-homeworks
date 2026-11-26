package ssatr.tracker;

/** Calcule geografice. */
final class Geo {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private Geo() { }

    /** Distanta in metri, in linie dreapta, intre doua puncte GPS (formula haversine). */
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }
}