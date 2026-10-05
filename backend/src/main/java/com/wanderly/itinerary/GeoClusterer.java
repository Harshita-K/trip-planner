package com.wanderly.itinerary;

import com.wanderly.common.GeoPoint;
import com.wanderly.places.Place;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * k-means on coordinates, used to split candidate places into one geographic group per day so
 * each day stays in one part of the city.
 *
 * <p>Initialisation is deterministic farthest-point (the k-means++ idea without randomness):
 * first centroid is the first place (callers pass places best-first), each next centroid is
 * the place farthest from all chosen ones. Same input -> same plan, which keeps tests stable.
 */
final class GeoClusterer {

    private static final int MAX_ITERATIONS = 50;

    private GeoClusterer() {
    }

    static List<List<Place>> kMeans(List<Place> places, int k) {
        if (places.isEmpty() || k <= 0) {
            return List.of();
        }
        if (k == 1) {
            return List.of(new ArrayList<>(places));
        }
        int n = places.size();
        List<GeoPoint> centroids = initialCentroids(places, Math.min(k, n));
        int[] assignment = new int[n];
        Arrays.fill(assignment, -1);

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int nearest = nearestCentroid(places.get(i).point(), centroids);
                if (assignment[i] != nearest) {
                    assignment[i] = nearest;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
            for (int c = 0; c < centroids.size(); c++) {
                double lat = 0;
                double lng = 0;
                int count = 0;
                for (int i = 0; i < n; i++) {
                    if (assignment[i] == c) {
                        lat += places.get(i).lat();
                        lng += places.get(i).lng();
                        count++;
                    }
                }
                if (count > 0) {   // an empty cluster keeps its previous centroid
                    centroids.set(c, new GeoPoint(lat / count, lng / count));
                }
            }
        }

        List<List<Place>> clusters = new ArrayList<>();
        for (int c = 0; c < centroids.size(); c++) {
            clusters.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            clusters.get(assignment[i]).add(places.get(i));
        }
        return clusters;
    }

    private static List<GeoPoint> initialCentroids(List<Place> places, int k) {
        List<GeoPoint> centroids = new ArrayList<>();
        centroids.add(places.get(0).point());
        while (centroids.size() < k) {
            Place farthest = places.get(0);
            double best = -1;
            for (Place p : places) {
                double d = distanceToNearest(p.point(), centroids);
                if (d > best) {
                    best = d;
                    farthest = p;
                }
            }
            centroids.add(farthest.point());
        }
        return centroids;
    }

    private static int nearestCentroid(GeoPoint point, List<GeoPoint> centroids) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int c = 0; c < centroids.size(); c++) {
            double d = point.distanceKm(centroids.get(c));
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        return best;
    }

    private static double distanceToNearest(GeoPoint point, List<GeoPoint> centroids) {
        double best = Double.MAX_VALUE;
        for (GeoPoint c : centroids) {
            best = Math.min(best, point.distanceKm(c));
        }
        return best;
    }
}
