package globalquake.core.earthquake;

import globalquake.core.GlobalQuake;
import globalquake.core.HypocsSettings;
import globalquake.core.Settings;
import globalquake.core.analysis.BetterAnalysis;
import globalquake.core.analysis.Event;
import globalquake.core.earthquake.data.*;
import globalquake.core.earthquake.interval.DepthConfidenceInterval;
import globalquake.core.earthquake.interval.PolygonConfidenceInterval;
import globalquake.core.events.specific.QuakeCreateEvent;
import globalquake.core.events.specific.QuakeRemoveEvent;
import globalquake.core.events.specific.QuakeUpdateEvent;
import globalquake.core.geo.taup.TauPTravelTimeCalculator;
import globalquake.core.intensity.IntensityTable;
import globalquake.core.station.AbstractStation;
import globalquake.core.station.StationState;
import globalquake.utils.GeoUtils;
import globalquake.utils.Point2DGQ;
import globalquake.utils.monitorable.MonitorableCopyOnWriteArrayList;
import gqserver.api.packets.station.InputType;

import org.apache.commons.math3.analysis.function.Log;
import org.tinylog.Logger;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class EarthquakeAnalysis {

    private class HypoRefineResult {
        public final PreliminaryHypocenter bestHypo;
        public final List<PickedEvent> selectedEvents;

        public HypoRefineResult(PreliminaryHypocenter bestHypo, List<PickedEvent> selectedEvents) {
            this.bestHypo = bestHypo;
            this.selectedEvents = selectedEvents;
        }
    }

    public static final double MIN_RATIO = 8.0;

    public static final int QUADRANTS = 26;

    public static final boolean USE_MEDIAN_FOR_ORIGIN = true;
    private static final boolean REMOVE_WEAKEST = false;
    private static final boolean CHECK_QUADRANTS = true;
    private static final boolean CHECK_DISTANT_EVENT_STATIONS = false;
    private static final int DEPTH_ITERS_POLYGONS = 12;
    protected static final double NO_MAGNITUDE = -999.0;
    private static final boolean CHECK_DELTA_P = false;

    private static final boolean ONLY_SELECT_BETTER = true;
    // NOTE: I edit here
    private static final boolean OBVIOUS_CORRECT_CHECK = true;
    private static final double OBVIOUS_CORRECT_MAX_DISTANCE = 500;
    private static final double OBVIOUS_CORRECT_THRESHOLD = 0.28;
    private static final double OBVIOUS_CORRECT_DELETE_THRESHOLD = 0.20;
    private static final double OBVIOUS_CORRECT_RATIO_THRESHOLD = 256.0;
    public static final int OBVIOUS_CORRECT_MIN_TOTAL = 8;

    public static boolean DEPTH_FIX_ALLOWED = true;

    private final List<Earthquake> earthquakes;

    private ClusterAnalysis clusterAnalysis;

    public boolean testing = false;

    public EarthquakeAnalysis() {
        earthquakes = new MonitorableCopyOnWriteArrayList<>();
    }

    public EarthquakeAnalysis(ClusterAnalysis clusterAnalysis, List<Earthquake> earthquakes) {
        this.clusterAnalysis = clusterAnalysis;
        this.earthquakes = earthquakes;
    }

    public List<Earthquake> getEarthquakes() {
        return earthquakes;
    }

    public void run() {
        // Logger.tag("Hypocs").info("Running hypocenter finder");
        if (clusterAnalysis == null) {
            if (GlobalQuake.instance == null) {
                return;
            } else {
                clusterAnalysis = GlobalQuake.instance.getClusterAnalysis();
            }
        }
        clusterAnalysis.getClustersReadLock().lock();
        // Logger.tag("Hypocs").info("Processing clusters for hypocenter finding: " +
        // clusterAnalysis.getClusters());
        try {
            clusterAnalysis.getClusters().parallelStream()
                    .forEach(cluster -> processCluster(cluster, createListOfPickedEvents(cluster), true));
        } finally {
            clusterAnalysis.getClustersReadLock().unlock();
        }
    }

    public void processCluster(Cluster cluster, List<PickedEvent> pickedEvents, boolean useCUDA) {
        if (pickedEvents.isEmpty()) {
            Logger.tag("Hypocs").info("No events for hypocenter finding for cluster %s".formatted(cluster.getUuid()));
            return;
        }

        // Calculation starts only if number of events increases by some %
        if (cluster.getEarthquake() != null) {
            int count = pickedEvents.size();
            if (Settings.reduceRevisions) {
                if (count <= cluster.getEarthquake().nextReportEventCount) {
                    return;
                }
                cluster.getEarthquake().nextReportEventCount = (int) (count * 1.2);
                // Logger.tag("Hypocs").info("Next report will be at " +
                // cluster.getEarthquake().nextReportEventCount + " assigns");
            }
        }

        // if (cluster.lastEpicenterUpdate * (Settings.reduceRevisions ? 1.1 : 1.0) >=
        // cluster.updateCount) {
        // Logger.tag("Hypocs").info("Skipping hypocenter finding for cluster %s, last
        // update was %d, current is %d"
        // .formatted(cluster.getUuid(), cluster.lastEpicenterUpdate,
        // cluster.updateCount));
        // return;
        // }

        cluster.lastEpicenterUpdate = cluster.updateCount;

        preprocess(cluster, pickedEvents, useCUDA);
    }

    private void preprocess(Cluster cluster, List<PickedEvent> pickedEvents, boolean useCUDA) {
        pickedEvents.sort(Comparator.comparing(PickedEvent::maxRatio));

        // if there is no event stronger than MIN_RATIO, abort
        if (pickedEvents.get(pickedEvents.size() - 1).maxRatio() < MIN_RATIO) {
            Logger.tag("Hypocs").info("Not enough strong events for hypocenter finding: %f < %f for cluster %s"
                    .formatted(pickedEvents.get(pickedEvents.size() - 1).maxRatio(), MIN_RATIO, cluster.getUuid()));
            return;
        }

        if (REMOVE_WEAKEST) {
            double ratioPercentileThreshold = pickedEvents.get((int) ((pickedEvents.size() - 1) * 0.35)).maxRatio();

            // remove events that are weaker than the threshold and keep at least 8 events
            while (pickedEvents.get(0).maxRatio() < ratioPercentileThreshold && pickedEvents.size() > 8) {
                pickedEvents.remove(0);
            }
        }

        HypocenterFinderSettings finderSettings = createSettings(useCUDA);

        // if in the end there is less than N events, abort
        if (pickedEvents.size() < finderSettings.minStations()) {
            Logger.tag("Hypocs").info("Not enough events for hypocenter finding: %d < %d for cluster %s"
                    .formatted(pickedEvents.size(), finderSettings.minStations(), cluster.getUuid()));
            return;
        }

        ArrayList<PickedEvent> selectedEvents = new ArrayList<>();
        selectedEvents.add(pickedEvents.get(0));

        // Selects picked events in a way that they are spaced away as much as possible
        findGoodEvents(pickedEvents, selectedEvents);

        findHypocenter(selectedEvents, cluster, finderSettings);
    }

    public static HypocenterFinderSettings createSettings(boolean useCUDA) {
        return new HypocenterFinderSettings(Settings.pWaveInaccuracyThreshold, Settings.hypocenterCorrectThreshold,
                Settings.hypocenterDetectionResolution, Settings.hypocenterDetectionResolutionGPU,
                Settings.minimumStationsForEEW, useCUDA);
    }

    private List<PickedEvent> createListOfPickedEvents(Cluster cluster) {
        List<PickedEvent> result = new ArrayList<>();
        for (Event event : cluster.getAssignedEvents().values()) {
            if (event.isValid() && !event.isSWave()) {
                result.add(new PickedEvent(event.getpWave(), event.getLatFromStation(), event.getLonFromStation(),
                        event.getElevationFromStation(), event.maxRatio));
            }
        }

        return result;
    }

    private void findGoodEvents(List<PickedEvent> events, List<PickedEvent> selectedEvents) {
        while (selectedEvents.size() < Settings.maxEvents) {
            double maxDist = 0;
            PickedEvent furthest = null;
            for (PickedEvent event : events) {
                if (!selectedEvents.contains(event)) {
                    double closest = Double.MAX_VALUE;
                    for (PickedEvent event2 : selectedEvents) {
                        double dist = GeoUtils.greatCircleDistance(event.lat(), event.lon(),
                                event2.lat(), event2.lon());
                        if (dist < closest) {
                            closest = dist;
                        }
                    }
                    if (closest > maxDist) {
                        maxDist = closest;
                        furthest = event;
                    }
                }
            }

            if (furthest == null) {
                break;
            }

            selectedEvents.add(furthest);

            if (selectedEvents.size() == events.size()) {
                break;
            }
        }
    }

    private boolean checkDeltaP(Cluster cluster, Hypocenter bestHypocenter, List<PickedEvent> events) {
        events.sort(Comparator.comparing(PickedEvent::pWave));

        if (cluster.getRootLat() == Cluster.NONE) {
            cluster.calculateRoot(false);
        }

        double distFromRoot = GeoUtils.greatCircleDistance(bestHypocenter.lat, bestHypocenter.lon, cluster.getRootLat(),
                cluster.getRootLon());

        long deltaP = events.get((int) ((events.size() - 1) * 0.75)).pWave()
                - events.get((int) ((events.size() - 1) * 0.25)).pWave();

        long limit = 600 + (long) Math.sqrt(distFromRoot) * 60;

        Logger.tag("Hypocs").debug("deltaP: %d ms, limit for %.1f km is %d ms".formatted(deltaP, distFromRoot, limit));

        return deltaP >= limit;
    }

    public PreliminaryHypocenter runHypocenterFinder(List<PickedEvent> selectedEvents, Cluster cluster,
            HypocenterFinderSettings finderSettings,
            boolean far) {
        if (selectedEvents.isEmpty()) {
            return null;
        }

        if (GQHypocs.isCudaLoaded() && finderSettings.useCUDA()) {
            var result = GQHypocs.findHypocenter(selectedEvents, cluster, 0, finderSettings);

            if (result != null) {
                return result;
            }

            Logger.tag("Hypocs")
                    .error("CUDA hypocenter search has failed! This is likely caused by GPU running out of memory " +
                            "because too many stations were involved in the event, but it might be also different error");
            Logger.tag("Hypocs").warn("Fallback to CPU!");
        }

        Logger.tag("Hypocs").debug("==== Searching hypocenter of cluster #" + cluster.getUuid() + " ====");

        double maxDepth = TauPTravelTimeCalculator.MAX_DEPTH;

        int iterationsDifference = (int) Math.round((finderSettings.resolution() - 40.0) / 14.0);
        double universalMultiplier = getUniversalResolutionMultiplier(finderSettings);
        double pointMultiplier = universalMultiplier * universalMultiplier * 0.33;

        Logger.tag("Hypocs").debug("Universal multiplier is " + universalMultiplier);
        Logger.tag("Hypocs").debug("Point multiplier is " + pointMultiplier);
        Logger.tag("Hypocs").debug("Iterations difference: " + iterationsDifference);

        long timeMillis = System.currentTimeMillis();

        PreliminaryHypocenter bestHypocenter = null;
        Hypocenter previousHypocenter = cluster.getPreviousHypocenter();

        double _lat = cluster.getAnchorLat();
        double _lon = cluster.getAnchorLon();

        if (far && (previousHypocenter == null || previousHypocenter.correctEvents < 24
                || previousHypocenter.getCorrectness() < 0.8)) {
            // phase 1 search far from ANCHOR (it's not very certain)
            bestHypocenter = scanArea(selectedEvents, 90.0 / 360.0 * GeoUtils.EARTH_CIRCUMFERENCE,
                    (int) (40000 * pointMultiplier), _lat, _lon, 6 + iterationsDifference, maxDepth, finderSettings);
            Logger.tag("Hypocs").debug("FAR: " + (System.currentTimeMillis() - timeMillis));
            Logger.tag("Hypocs").debug(bestHypocenter.correctStations + " / " + bestHypocenter.err);
            _lat = bestHypocenter.lat;
            _lon = bestHypocenter.lon;
        }

        if (previousHypocenter == null || previousHypocenter.correctEvents < 42
                || previousHypocenter.getCorrectness() < 0.9) {
            // phase 2A search region near BEST or ANCHOR (it's quite certain)
            timeMillis = System.currentTimeMillis();
            PreliminaryHypocenter hyp = scanArea(selectedEvents, 2500.0, (int) (20000 * pointMultiplier), _lat, _lon,
                    7 + iterationsDifference, maxDepth, finderSettings);
            bestHypocenter = selectBetterHypocenter(hyp, bestHypocenter);
            _lat = bestHypocenter.lat;
            _lon = bestHypocenter.lon;
            Logger.tag("Hypocs").debug("REGIONAL: " + (System.currentTimeMillis() - timeMillis));
            Logger.tag("Hypocs").debug(bestHypocenter.correctStations + " / " + bestHypocenter.err);
        } else {
            // phase 2B search region closer BEST or ANCHOR (it assumes it's almost right)
            timeMillis = System.currentTimeMillis();
            PreliminaryHypocenter hyp = scanArea(selectedEvents, 1000.0, (int) (10000 * pointMultiplier), _lat, _lon,
                    7 + iterationsDifference, maxDepth, finderSettings);
            bestHypocenter = selectBetterHypocenter(hyp, bestHypocenter);
            _lat = bestHypocenter.lat;
            _lon = bestHypocenter.lon;
            Logger.tag("Hypocs").debug("CLOSER: " + (System.currentTimeMillis() - timeMillis));
            Logger.tag("Hypocs").debug(bestHypocenter.correctStations + " / " + bestHypocenter.err);
        }

        // phase 3 find exact area
        timeMillis = System.currentTimeMillis();
        PreliminaryHypocenter hyp = scanArea(selectedEvents, 100.0, (int) (4000 * pointMultiplier), _lat, _lon,
                8 + iterationsDifference, maxDepth, finderSettings);
        bestHypocenter = selectBetterHypocenter(hyp, bestHypocenter);
        Logger.tag("Hypocs").debug("EXACT: " + (System.currentTimeMillis() - timeMillis));
        Logger.tag("Hypocs").debug(bestHypocenter.correctStations + " / " + bestHypocenter.err);

        // phase 4 find exact depth
        timeMillis = System.currentTimeMillis();
        _lat = bestHypocenter.lat;
        _lon = bestHypocenter.lon;
        hyp = scanArea(selectedEvents, 10.0, (int) (4000 * pointMultiplier), _lat, _lon, 10 + iterationsDifference,
                maxDepth, finderSettings);
        bestHypocenter = selectBetterHypocenter(hyp, bestHypocenter);
        Logger.tag("Hypocs").debug("DEPTH: " + (System.currentTimeMillis() - timeMillis));
        Logger.tag("Hypocs").debug(bestHypocenter.correctStations + " / " + bestHypocenter.err);

        Logger.tag("Hypocs").trace(bestHypocenter);

        return bestHypocenter;
    }

    public void findHypocenter(List<PickedEvent> selectedEvents, Cluster cluster,
            HypocenterFinderSettings finderSettings) {
        long startTime = System.currentTimeMillis();

        List<PickedEvent> correctSelectedEvents = new ArrayList<>(selectedEvents);

        PreliminaryHypocenter bestHypocenter = runHypocenterFinder(correctSelectedEvents, cluster, finderSettings,
                true);

        if (bestHypocenter == null) {
            Logger.tag("Hypocs").info("No hypocenter found! for cluster %d".formatted(cluster.id));
            return;
        }

        PreliminaryHypocenter bestHypocenter2 = bestHypocenter;

        int reduceIterations = HypocsSettings.getOrDefaultInt("reduceIterations", 2);
        int reduceLimit = HypocsSettings.getOrDefaultInt("reduceLimit", 12);
        double reduceAmount = HypocsSettings.getOrDefault("reduceAmount", 0.2f);

        for (int it = 0; it < reduceIterations; it++) { // TODO setting?
            if (correctSelectedEvents.size() > reduceLimit) {
                Map<PickedEvent, Long> residuals = calculateResiduals(bestHypocenter, correctSelectedEvents);
                int targetSize = residuals.size() - (int) Math.max(1, (residuals.size() - reduceLimit) * reduceAmount);

                List<Map.Entry<PickedEvent, Long>> list = new ArrayList<>(residuals.entrySet());
                list.sort(Map.Entry.comparingByValue());

                while (list.size() > targetSize) {
                    list.remove(list.size() - 1);
                }

                Logger.tag("Hypocs").debug("Reduced the number of events from %d to the best %d for better accuracy"
                        .formatted(correctSelectedEvents.size(), list.size()));

                correctSelectedEvents = list.stream().map(Map.Entry::getKey).collect(Collectors.toList());

                bestHypocenter2 = runHypocenterFinder(correctSelectedEvents, cluster, finderSettings, false);
            }
        }

        HypoRefineResult hypoRefineResult1 = refineHypocenter(cluster, bestHypocenter2, correctSelectedEvents,
                finderSettings, 6, 1.5);

        HypoRefineResult hypoRefineResult2 = refineHypocenterRandomElimination(cluster, bestHypocenter2,
                correctSelectedEvents, finderSettings, 1.5);

        HypoRefineResult hypoRefineResult3 = refineHypocenterElimination(cluster, bestHypocenter2,
                correctSelectedEvents, finderSettings, 1.5);

        HypoRefineResult bestHypoRefineResult = selectBetterHypoRefined(1.5, hypoRefineResult1,
                hypoRefineResult2, hypoRefineResult3);

        if (bestHypoRefineResult == null) {
            Logger.tag("Hypocs")
                    .info("No better hypocenter found! for cluster %d, may be overfitted or not good enough".formatted(cluster.id));
            return;
        }

        Logger.info("Hypocenter selected for cluster %d: %s".formatted(cluster.id,
                bestHypoRefineResult.bestHypo));
                
        bestHypocenter2 = bestHypoRefineResult.bestHypo;
        correctSelectedEvents = bestHypoRefineResult.selectedEvents;

        List<PickedEvent> reallyCorrectedEvents = filterCorrected(bestHypoRefineResult, 1.5);

        postProcess(selectedEvents, correctSelectedEvents, reallyCorrectedEvents, cluster, bestHypocenter2, finderSettings, startTime);
    }

    private List<PickedEvent> filterCorrected(HypoRefineResult hypoRefineResult, double hardCut) {
        double[] residuals = calculateResidualsListDouble(hypoRefineResult.bestHypo, hypoRefineResult.selectedEvents);
        List<PickedEvent> filtered = new ArrayList<>();
        for (int i = 0; i < residuals.length; i++) {
            if (residuals[i] <= hardCut) {
                filtered.add(hypoRefineResult.selectedEvents.get(i));
            }
        }
        return filtered;
    }

    /** Median Absolute Deviation (robust scatter) */
    private static double mad(double[] xs) {
        double med = median(xs);
        double[] abs = Arrays.stream(xs)
                .map(x -> Math.abs(x - med))
                .toArray();
        return median(abs);
    }

    /** Plain old median */
    private static double median(double[] xs) {
        double[] copy = xs.clone();
        Arrays.sort(copy);
        int n = copy.length;
        return (n % 2 == 0) ? (copy[n / 2 - 1] + copy[n / 2]) / 2.0
                : copy[n / 2];
    }

    private double residualWeight(double residual, double hardCut) {
        double MAX_RESIDUAL = 30 * 1000.0; // 30 seconds
        if (residual <= hardCut) {
            return 0;
        } else {
            if (residual > MAX_RESIDUAL) {
                return 1;
            }
            return (residual - hardCut) / (MAX_RESIDUAL - hardCut);
        }
    }

    private int selectDeletedEvent(double[] residuals, double hardCut) {
        double[] weight = new double[residuals.length];
        double[] cumulativeWeight = new double[weight.length];
        double sumWeight = 0;
        for (int i = 0; i < residuals.length; i++) {
            weight[i] = residualWeight(residuals[i], hardCut);
            if (i == 0) {
                cumulativeWeight[i] = weight[i];
            } else {
                cumulativeWeight[i] = cumulativeWeight[i - 1] + weight[i];
            }
            sumWeight += weight[i];
        }
        if (sumWeight == 0) {
            return -1;
        }
        double random = Math.random() * sumWeight;
        for (int i = 0; i < cumulativeWeight.length; i++) {
            if (random < cumulativeWeight[i]) {
                return i;
            }
        }
        return -1;
    }

    private int selectMostErrorEvent(double[] residuals, double hardCut) {
        int maxIndex = -1;
        double maxResidual = Double.MIN_VALUE;
        for (int i = 0; i < residuals.length; i++) {
            if (residuals[i] > hardCut && residuals[i] > maxResidual) {
                maxResidual = residuals[i];
                maxIndex = i;
            }
        }
        return maxIndex;
    }

    private int residualCompare(double[] residuals1, double[] residuals2, double hardCut) {
        int correct1 = 0;
        int correct2 = 0;
        double errSum1 = 0;
        double errSum2 = 0;

        for (int i = 0; i < residuals1.length; i++) {
            if (residuals1[i] <= hardCut) {
                correct1++;
            }
            errSum1 += residuals1[i] * residuals1[i];
        }

        for (int i = 0; i < residuals2.length; i++) {
            if (residuals2[i] <= hardCut) {
                correct2++;
            }
            errSum2 += residuals2[i] * residuals2[i];
        }

        if (correct1 > correct2) {
            return 1;
        }
        if (correct1 < correct2) {
            return 2;
        }

        // heuristic error calculation
        double rmse1 = Math.sqrt(errSum1 / residuals1.length);
        double rmse2 = Math.sqrt(errSum2 / residuals2.length);

        return rmse1 <= rmse2 ? 1 : 2;

    }

    private double[] calculateResidualsListDouble(PreliminaryHypocenter hypocenter, List<PickedEvent> events) {
        double[] result = new double[events.size()];
        for (int i = 0; i < events.size(); i++) {
            result[i] = (double) (calculateResidual(hypocenter, events.get(i))) / 1000.0;
        }
        return result;
    }

    private double calcRMSE(double[] residuals) {
        double sum = 0;
        for (double residual : residuals) {
            sum += residual * residual;
        }
        return Math.sqrt(sum / residuals.length);
    }

    private int calcGoodEvents(double[] residuals, double hardCut) {
        int goodEvents = 0;
        for (double residual : residuals) {
            if (residual <= hardCut) {
                goodEvents++;
            }
        }
        return goodEvents;
    }

    private HypoRefineResult selectBetterHypoRefined(double hardCut, HypoRefineResult... results) {
        if (results.length == 0) {
            return null;
        }
        double[][] residuals = new double[results.length][];
        double[] rmse = new double[results.length];
        int[] goodEvents = new int[results.length];
        for (int i = 0; i < results.length; i++) {
            residuals[i] = calculateResidualsListDouble(results[i].bestHypo, results[i].selectedEvents);
            rmse[i] = calcRMSE(residuals[i]);
            goodEvents[i] = calcGoodEvents(residuals[i], hardCut);
        }
        Logger.tag("Hypocs").info("=== Hypocenter Refination Comparison ===");
        Logger.tag("Hypocs").info("RMSE: " + Arrays.toString(rmse));
        Logger.tag("Hypocs").info("Good events: " + Arrays.toString(goodEvents));

        List<HypoRefineResult> selectedResults = new ArrayList<>();
        List<double[]> selectedResiduals = new ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            if(!isOverfit(residuals[i], hardCut) && calcGoodEvents(residuals[i], hardCut) >= Settings.minimumStationsForEEW) {
                selectedResults.add(results[i]);
                selectedResiduals.add(residuals[i]);
            } else {
                Logger.tag("Hypocs").info("Refined hypocenter %d is overfit".formatted(i));
            }
        }

        for (int i = 0; i < selectedResiduals.size(); i++) {
            boolean isBest = true;
            for (int j = 0; j < selectedResiduals.size(); j++) {
                if (i == j) {
                    continue;
                }
                int better = residualCompare(selectedResiduals.get(i), selectedResiduals.get(j), hardCut);
                if (better == 2) {
                    isBest = false;
                    break;
                }
            }
            if (isBest) {
                Logger.tag("Hypocs").info("Refined hypocenter %d is the best".formatted(i));
                if( calcGoodEvents(selectedResiduals.get(i), hardCut) < Settings.minimumStationsForEEW ) {
                    Logger.tag("Hypocs").info("Best refined hypocenter %d is not good enough".formatted(i));
                    return null;
                }
                return results[i];
            }
        }
        return null;
    }

    private HypoRefineResult refineHypocenterRandomElimination(Cluster cluster, PreliminaryHypocenter bestHypo,
            List<PickedEvent> events,
            HypocenterFinderSettings finderSettings, double hardCut) {

        PreliminaryHypocenter previousHypo = bestHypo;
        List<PickedEvent> prevEvents = new ArrayList<>(events);
        double[] previousResiduals = calculateResidualsListDouble(previousHypo, prevEvents);
        Logger.tag("Hypocs").info("==== Refining hypocenter(Random Elimination) of cluster #" + cluster.id + " ====");
        Logger.tag("Hypocs").info("Init: origin %d, depth %.1f".formatted(previousHypo.origin,
                previousHypo.depth));
        double[] newResiduals;

        int iter = 0;
        while (prevEvents.size() > Settings.minimumStationsForEEW) {
            Logger.tag("Hypocs").info("Iteration %d: origin %d, depth %.1f".formatted(iter, previousHypo.origin,
                    previousHypo.depth));
            Logger.tag("Hypocs").info("Previous residuals: " + Arrays.toString(previousResiduals));
            int deleteIndex = selectDeletedEvent(previousResiduals, hardCut);
            if (deleteIndex == -1) {
                Logger.tag("Hypocs").info("No event to delete");
                break;
            }
            Logger.tag("Hypocs")
                    .info("Deleting event %d with residual %f".formatted(deleteIndex, previousResiduals[deleteIndex]));

            List<PickedEvent> newEvents = new ArrayList<>(prevEvents);
            newEvents.remove(deleteIndex);
            PreliminaryHypocenter newHypo = runHypocenterFinder(newEvents, cluster, finderSettings, false);

            newResiduals = calculateResidualsListDouble(newHypo, newEvents);
            Logger.tag("Hypocs").info("New residuals: " + Arrays.toString(newResiduals));
            int better = residualCompare(previousResiduals, newResiduals, hardCut);
            if (better == 1) {
                Logger.tag("Hypocs").info("Removing is not better");
                return new HypoRefineResult(previousHypo, prevEvents);
            }
            Logger.tag("Hypocs").info("Removing is better");
            previousHypo = newHypo;
            previousResiduals = newResiduals;
            prevEvents = newEvents;
            iter++;
        }
        return new HypoRefineResult(previousHypo, prevEvents);
    }

    private boolean isOverfit(double[] residuals, double hardCut) {
        int goodEvents = calcGoodEvents(residuals, hardCut);
        if (goodEvents > 4) {
            return false;
        }
        for (double residual : residuals) {
            if (residual > 0.02) {
                return false;
            }
        }
        return true;
    }

    // Remove biggest residual that is over hardCut until it stop being better.
    private HypoRefineResult refineHypocenterElimination(Cluster cluster, PreliminaryHypocenter bestHypo,
            List<PickedEvent> events,
            HypocenterFinderSettings finderSettings, double hardCut) {

        PreliminaryHypocenter previousHypo = bestHypo;
        List<PickedEvent> prevEvents = new ArrayList<>(events);
        double[] previousResiduals = calculateResidualsListDouble(previousHypo, prevEvents);
        Logger.tag("Hypocs").info("==== Refining hypocenter(Elimination) of cluster #" + cluster.id + " ====");
        Logger.tag("Hypocs").info("Init: origin %d, depth %.1f".formatted(previousHypo.origin,
                previousHypo.depth));
        double[] newResiduals;

        int iter = 0;
        while (prevEvents.size() > Settings.minimumStationsForEEW) {
            Logger.tag("Hypocs").info("Iteration %d: origin %d, depth %.1f".formatted(iter, previousHypo.origin,
                    previousHypo.depth));
            Logger.tag("Hypocs").info("Previous residuals: " + Arrays.toString(previousResiduals));
            int deleteIndex = selectMostErrorEvent(previousResiduals, hardCut);
            if (deleteIndex == -1) {
                Logger.tag("Hypocs").info("No event to delete");
                break;
            }
            Logger.tag("Hypocs")
                    .info("Deleting event %d with residual %f".formatted(deleteIndex, previousResiduals[deleteIndex]));

            List<PickedEvent> newEvents = new ArrayList<>(prevEvents);
            newEvents.remove(deleteIndex);
            PreliminaryHypocenter newHypo = runHypocenterFinder(newEvents, cluster, finderSettings, false);

            newResiduals = calculateResidualsListDouble(newHypo, newEvents);
            Logger.tag("Hypocs").info("New residuals: " + Arrays.toString(newResiduals));
            int better = residualCompare(previousResiduals, newResiduals, hardCut);
            if (better == 1) {
                Logger.tag("Hypocs").info("Removing is not better");
                return new HypoRefineResult(previousHypo, prevEvents);
            }
            Logger.tag("Hypocs").info("Removing is better");
            previousHypo = newHypo;
            previousResiduals = newResiduals;
            prevEvents = newEvents;
            iter++;
        }
        return new HypoRefineResult(previousHypo, prevEvents);
    }

    private HypoRefineResult refineHypocenter(Cluster cluster, PreliminaryHypocenter bestHypo, List<PickedEvent> events,
            HypocenterFinderSettings finderSettings, int maxIter, double hardCut) {
        Logger.tag("Hypocs").info("==== Refining hypocenter of cluster #" + cluster.id + " ====");
        List<PickedEvent> current = new ArrayList<>(events);
        PreliminaryHypocenter hypocenter = bestHypo;
        Logger.tag("Hypocs").info("Init: origin %d, depth %.1f".formatted(hypocenter.origin,
                hypocenter.depth));
        for (int iter = 0; iter < maxIter; iter++) {
            Logger.tag("Hypocs").info("Iteration %d: origin %d, depth %.1f".formatted(iter, hypocenter.origin,
                    hypocenter.depth));
            long[] arrivalTimes = new long[current.size()];
            for (int i = 0; i < current.size(); i++) {
                arrivalTimes[i] = current.get(i).pWave();
            }
            Logger.tag("Hypocs").info("Arrival times: " + Arrays.toString(arrivalTimes));
            // 2. Compute residuals
            long[] resLong = calculateResidualsList(hypocenter, current);
            double[] res = new double[resLong.length];
            for (int i = 0; i < resLong.length; i++) {
                res[i] = resLong[i] / 1000.0;
            }
            Logger.tag("Hypocs").info("Residuals: " + Arrays.toString(res));
            // 3. Robust threshold
            double med = median(res);
            double scatter = 1.4826 * mad(res); // 1.4826 turns MAD into σ
            double softCut = med + 2.5 * scatter; // tweak 2.5 as you like

            // Alternative
            // Random remove single bad event ( weighted by residual )

            // 4. Kick outliers
            List<PickedEvent> next = new ArrayList<>();
            for (int i = 0; i < current.size(); i++) {
                // if (res[i] <= hardCut / 1000 && res[i] <= softCut) {
                if (res[i] <= hardCut) {
                    Logger.tag("Hypocs").info("Keeping event %d with residual %f".formatted(i, res[i]));
                    next.add(current.get(i));
                }
            }

            // 5. Done?
            if (next.size() == current.size())
                break; // nothing kicked
            if (next.size() < Settings.minimumStationsForEEW)
                break; // not enough left

            current = next; // iterate again
            hypocenter = runHypocenterFinder(current, cluster, finderSettings, false);
        }
        Logger.tag("Hypocs").info("Final: origin %d, depth %.1f".formatted(hypocenter.origin,
                hypocenter.depth));
        return new HypoRefineResult(hypocenter, current);
    }

    private Map<PickedEvent, Long> calculateResiduals(PreliminaryHypocenter hypocenter, List<PickedEvent> events) {
        Map<PickedEvent, Long> result = new HashMap<>();
        for (PickedEvent event : events) {
            long actualTravel = event.pWave() - hypocenter.origin;

            double distGC = GeoUtils.greatCircleDistance(hypocenter.lat, hypocenter.lon,
                    event.lat(), event.lon());
            double angle = TauPTravelTimeCalculator.toAngle(distGC);
            double expectedTravelPRaw = TauPTravelTimeCalculator.getPWaveTravelTime(hypocenter.depth,
                    angle);

            if (expectedTravelPRaw >= 0) {
                long expectedTravel = (long) ((expectedTravelPRaw
                        + EarthquakeAnalysis.getElevationCorrection(event.elevation())) * 1000);
                result.put(event, Math.abs(expectedTravel - actualTravel));
            }
        }

        return result;
    }

    private long[] calculateResidualsList(PreliminaryHypocenter hypocenter, List<PickedEvent> events) {
        long[] result = new long[events.size()];
        for (int i = 0; i < events.size(); i++) {
            result[i] = calculateResidual(hypocenter, events.get(i));
        }
        return result;
    }

    private long calculateResidual(PreliminaryHypocenter hypocenter, PickedEvent event) {
        long actualTravel = event.pWave() - hypocenter.origin;
        double distGC = GeoUtils.greatCircleDistance(hypocenter.lat, hypocenter.lon,
                event.lat(), event.lon());
        double angle = TauPTravelTimeCalculator.toAngle(distGC);
        double expectedTravelPRaw = TauPTravelTimeCalculator.getPWaveTravelTime(hypocenter.depth,
                angle);
        if (expectedTravelPRaw >= 0) {
            long expectedTravel = (long) ((expectedTravelPRaw
                    + EarthquakeAnalysis.getElevationCorrection(event.elevation())) * 1000);
            // Logger.tag("Hypocs")
            // .info("Event %s: expecct travel (raw=%f -> %d), actual travel %d angle %f
            // depth %f dist %f"
            // .formatted(event, expectedTravelPRaw, expectedTravel, actualTravel, angle,
            // hypocenter.depth,
            // distGC));
            return Math.abs(expectedTravel - actualTravel);
        }
        Logger.tag("Hypocs").info("Negative travel time for event %s".formatted(event));
        return Long.MAX_VALUE;
    }

    private DepthConfidenceInterval calculateDepthConfidenceInterval(List<PickedEvent> selectedEvents,
            PreliminaryHypocenter bestHypocenter, HypocenterFinderSettings finderSettings) {
        double upperBound = bestHypocenter.depth;
        double lowerBound = bestHypocenter.depth;

        PreliminaryHypocenter hypocenterA = new PreliminaryHypocenter();
        HypocenterFinderThreadData threadData = new HypocenterFinderThreadData(selectedEvents.size());
        List<ExactPickedEvent> pickedEvents = createListOfExactPickedEvents(selectedEvents);
        calculateDistances(pickedEvents, bestHypocenter.lat, bestHypocenter.lon);

        for (double depth = 0; depth < TauPTravelTimeCalculator.MAX_DEPTH; depth += 1.0
                / getUniversalResolutionMultiplier(finderSettings)) {
            analyseHypocenter(hypocenterA, bestHypocenter.lat, bestHypocenter.lon, depth, pickedEvents, finderSettings,
                    threadData);
            if (calculateHeuristic(hypocenterA) > calculateHeuristic(bestHypocenter) / CONFIDENCE_LEVEL
                    && depth < bestHypocenter.depth && depth < upperBound) {
                upperBound = depth;
            }

            if (calculateHeuristic(hypocenterA) > calculateHeuristic(bestHypocenter) / CONFIDENCE_LEVEL
                    && depth > bestHypocenter.depth) {
                lowerBound = depth;
            }
        }

        return new DepthConfidenceInterval(upperBound, lowerBound);
    }

    private static final int CONFIDENCE_POLYGON_EDGES = 64;
    private static final double CONFIDENCE_POLYGON_OFFSET = 0;
    private static final double CONFIDENCE_POLYGON_STEP = 10;
    private static final double CONFIDENCE_POLYGON_MIN_STEP = 0.25;
    private static final double CONFIDENCE_POLYGON_MAX_DIST = 5000;

    private static final double CONFIDENCE_LEVEL = 1.2;

    public Earthquake getEarthquake(UUID uuid) {
        for (Earthquake earthquake : getEarthquakes()) {
            if (earthquake.getUuid().equals(uuid)) {
                return earthquake;
            }
        }

        return null;
    }

    record PolygonConfidenceResult(double dist, long minOrigin, long maxOrigin) {
    }

    private PolygonConfidenceInterval calculatePolygonConfidenceInterval(List<PickedEvent> selectedEvents,
            PreliminaryHypocenter bestHypocenter, HypocenterFinderSettings finderSettings, double confidenceThreshold) {
        List<Integer> integerList = IntStream.range(0, CONFIDENCE_POLYGON_EDGES).boxed().toList();
        List<PolygonConfidenceResult> results = (Settings.parallelHypocenterLocations ? integerList.parallelStream()
                : integerList.stream()).map(ray -> {
                    double ang = CONFIDENCE_POLYGON_OFFSET + (ray / (double) CONFIDENCE_POLYGON_EDGES) * 360.0;
                    double dist = CONFIDENCE_POLYGON_STEP;
                    double step = CONFIDENCE_POLYGON_STEP;

                    long minOrigin = Long.MAX_VALUE;
                    long maxOrigin = Long.MIN_VALUE;

                    List<ExactPickedEvent> pickedEvents = createListOfExactPickedEvents(selectedEvents);
                    HypocenterFinderThreadData threadData = new HypocenterFinderThreadData(pickedEvents.size());
                    while (step > CONFIDENCE_POLYGON_MIN_STEP && dist < CONFIDENCE_POLYGON_MAX_DIST) {
                        double[] latLon = GeoUtils.moveOnGlobe(bestHypocenter.lat, bestHypocenter.lon, dist, ang);
                        double lat = latLon[0];
                        double lon = latLon[1];

                        // reset
                        threadData.bestHypocenter.err = Double.MAX_VALUE;
                        threadData.bestHypocenter.correctStations = 0;

                        calculateDistances(pickedEvents, lat, lon);
                        getBestAtDepth(DEPTH_ITERS_POLYGONS, TauPTravelTimeCalculator.MAX_DEPTH, finderSettings, 0, lat,
                                lon, pickedEvents, threadData);

                        double h1 = calculateHeuristic(threadData.bestHypocenter);
                        double h2 = calculateHeuristic(bestHypocenter);
                        boolean stillValid = h1 > h2 / confidenceThreshold;
                        if (stillValid) {
                            dist += step;
                            if (threadData.bestHypocenter.origin > maxOrigin) {
                                maxOrigin = threadData.bestHypocenter.origin;
                            }
                            if (threadData.bestHypocenter.origin < minOrigin) {
                                minOrigin = threadData.bestHypocenter.origin;
                            }
                        } else {
                            step /= 2.0;
                            dist -= step;
                        }
                    }

                    return new PolygonConfidenceResult(dist, minOrigin, maxOrigin);
                }).toList();

        List<Double> lengths = results.stream().map(polygonConfidenceResult -> polygonConfidenceResult.dist).toList();

        long minOrigin = results.stream().map(polygonConfidenceResult -> polygonConfidenceResult.minOrigin)
                .min(Long::compareTo).orElse(0L);
        long maxOrigin = results.stream().map(polygonConfidenceResult -> polygonConfidenceResult.maxOrigin)
                .max(Long::compareTo).orElse(0L);

        return new PolygonConfidenceInterval(CONFIDENCE_POLYGON_EDGES, CONFIDENCE_POLYGON_OFFSET, lengths, minOrigin,
                maxOrigin);
    }

    // calculate correct stations and err
    private void postProcess(List<PickedEvent> selectedEvents, PreliminaryHypocenter bestHypocenterPrelim,
            HypocenterFinderSettings finderSettings) {
        List<ExactPickedEvent> pickedEvents = createListOfExactPickedEvents(selectedEvents);
        HypocenterFinderThreadData threadData = new HypocenterFinderThreadData(pickedEvents.size());
        calculateDistances(pickedEvents, bestHypocenterPrelim.lat, bestHypocenterPrelim.lon);
        getBestAtDepth(DEPTH_ITERS_POLYGONS, TauPTravelTimeCalculator.MAX_DEPTH, finderSettings, 0,
                bestHypocenterPrelim.lat, bestHypocenterPrelim.lon, pickedEvents, threadData);

        bestHypocenterPrelim.correctStations = threadData.bestHypocenter.correctStations;
        bestHypocenterPrelim.err = threadData.bestHypocenter.err;
    }

    private void postProcess(List<PickedEvent> selectedEvents, List<PickedEvent> correctSelectedEvents, List<PickedEvent> reallyCorrectedEvents ,Cluster cluster,
            PreliminaryHypocenter bestHypocenterPrelim, HypocenterFinderSettings finderSettings, long startTime) {
        Logger.tag("Hypocs").debug("Post processing id=" + cluster.getUuid());
        postProcess(correctSelectedEvents, bestHypocenterPrelim, finderSettings);
        Hypocenter bestHypocenter = bestHypocenterPrelim.finish(
                calculateDepthConfidenceInterval(correctSelectedEvents, bestHypocenterPrelim, finderSettings),
                calculatePolygonConfidenceIntervals(correctSelectedEvents, bestHypocenterPrelim, finderSettings));

        if (bestHypocenter.correctEvents == 0 || bestHypocenter.totalErr == Double.MAX_VALUE) {
            Logger.tag("Hypocs").info("Absurd!");
            return;
        }

        bestHypocenter.usedEvents = selectedEvents.size();
        bestHypocenter.reducedEvents = correctSelectedEvents.size();
        bestHypocenter.correctEvents = reallyCorrectedEvents.size();
        bestHypocenter.totalEvents = cluster.getAssignedEvents().size();
        bestHypocenter.correctSelectedEvents = reallyCorrectedEvents;

        calculateActualCorrectEvents(selectedEvents, bestHypocenter);


        bestHypocenter.correctSelectedEvents = reallyCorrectedEvents;
        bestHypocenter.correctEvents = reallyCorrectedEvents.size();

        bestHypocenter.calculateQuality();
        calculateMagnitude(cluster, bestHypocenter, bestHypocenter);

        calculateObviousArrivals(bestHypocenter);

        Logger.tag("Hypocs").debug(bestHypocenter);

        if (!testing && bestHypocenter.magnitude == NO_MAGNITUDE) {
            Logger.tag("Hypocs").info("No magnitude!");
            return;
        }

        if (bestHypocenter.depth > TauPTravelTimeCalculator.MAX_DEPTH - 5.0) {
            Logger.tag("Hypocs")
                    .info("Ignoring too deep quake, it's probably a core wave! %.1fkm".formatted(bestHypocenter.depth));

            if (cluster.getEarthquake() != null) {
                updateMagnitudeOnly(cluster, bestHypocenter);
                Logger.tag("Hypocs").debug("Performed magnitude-only revision anyway");
            }

            return;
        }

        // There has to be at least some difference in the picked pWave times
        if (CHECK_DELTA_P && !checkDeltaP(cluster, bestHypocenter, correctSelectedEvents)) {
            Logger.tag("Hypocs").info("Not Enough Delta-P");

            if (cluster.getEarthquake() != null) {
                updateMagnitudeOnly(cluster, bestHypocenter);
                Logger.tag("Hypocs").debug("Performed magnitude-only revision anyway");
            }

            return;
        }

        if (!checkUncertainty(bestHypocenter, correctSelectedEvents)) {
            Logger.tag("Hypocs").info("Search canceled for cluster %d".formatted(cluster.id));
            Earthquake earthquake1 = cluster.getEarthquake();
            if (earthquake1 != null) {
                updateMagnitudeOnly(cluster, bestHypocenter);
                Logger.tag("Hypocs").debug("Performed magnitude-only revision anyway");
            }

            return;
        }

        double obviousCorrectPct = 1.0;
        if (OBVIOUS_CORRECT_CHECK && bestHypocenter.obviousArrivalsInfo != null
                && bestHypocenter.obviousArrivalsInfo.total() > OBVIOUS_CORRECT_MIN_TOTAL) {
            obviousCorrectPct = bestHypocenter.obviousArrivalsInfo.getPCT();
        }

        double pct = 100 * bestHypocenter.getCorrectness();
        boolean valid = pct >= finderSettings.correctnessThreshold()
                && bestHypocenter.correctEvents >= finderSettings.minStations()
                && obviousCorrectPct >= OBVIOUS_CORRECT_THRESHOLD;
        if (!valid) {
            boolean remove = pct < finderSettings.correctnessThreshold() * 0.75
                    || bestHypocenter.correctEvents < finderSettings.minStations() * 0.75
                    || obviousCorrectPct < OBVIOUS_CORRECT_DELETE_THRESHOLD;
            Earthquake earthquake1 = cluster.getEarthquake();
            if (remove && earthquake1 != null) {
                removeQuake(cluster, earthquake1);
            }
            Logger.tag("Hypocs")
                    .info("Hypocenter not valid, remove = %s, pct=%.2f/%.2f, obvious_correct_pct=%.2f/%.2f was %s"
                            .formatted(remove, pct, finderSettings.correctnessThreshold(), obviousCorrectPct,
                                    OBVIOUS_CORRECT_THRESHOLD, bestHypocenter));
        } else {
            HypocenterCondition result;
            if ((result = checkConditions(selectedEvents, bestHypocenter, cluster.getPreviousHypocenter(), cluster,
                    finderSettings)) == HypocenterCondition.OK) {
                // Logger.tag("Hypocs").info("Hypocenter is valid, updating cluster
                // %s".formatted(cluster.getUuid()));
                updateHypocenter(cluster, bestHypocenter);
            } else if (result != HypocenterCondition.NULL) {
                updateMagnitudeOnly(cluster, bestHypocenter);
                Logger.tag("Hypocs").trace("Performed magnitude-only revision because: %s".formatted(result));
            } else {
                Logger.tag("Hypocs").error("Fatal error: %s".formatted(result));
            }
            // Logger.tag("Hypocs").info("Hypocenter conditions: %s".formatted(result));
        }

        Logger.tag("Hypocs")
                .trace("Hypocenter finding finished in: %d ms".formatted(System.currentTimeMillis() - startTime));
    }

    private void removeQuake(Cluster cluster, Earthquake earthquake1) {
        getEarthquakes().remove(earthquake1);
        if (GlobalQuake.instance != null) {
            GlobalQuake.instance.getEventHandler().fireEvent(new QuakeRemoveEvent(earthquake1));
        }
        cluster.setEarthquake(null);
        cluster.setPreviousHypocenter(null);
        cluster.resetAnchor();
        Logger.tag("Hypocs").info("Quake removed!");
    }

    private void updateMagnitudeOnly(Cluster cluster, Hypocenter bestHypocenter) {
        if (cluster.getEarthquake() != null && cluster.getPreviousHypocenter() != null) {
            // calculate magnitudes, but using the previous hypocenter, that is believed to
            // be more accurate
            calculateMagnitude(cluster, cluster.getPreviousHypocenter(), bestHypocenter);

            if (!testing && bestHypocenter.magnitude == NO_MAGNITUDE) {
                Logger.tag("Hypocs").debug("No magnitude!");
                return;
            }

            cluster.revisionID += 1;

            cluster.getPreviousHypocenter().magnitudeUpdate(bestHypocenter);

            if (GlobalQuake.instance != null) {
                GlobalQuake.instance.getEventHandler()
                        .fireEvent(new QuakeUpdateEvent(cluster.getEarthquake(), cluster.getPreviousHypocenter()));
            }
        }
    }

    private void updateHypocenter(Cluster cluster, Hypocenter bestHypocenter) {
        cluster.updateAnchor(bestHypocenter);

        cluster.revisionID += 1;
        cluster.setPreviousHypocenter(bestHypocenter);

        if (cluster.getEarthquake() == null) {
            Earthquake newEarthquake = new Earthquake(cluster);
            if (!testing) {
                getEarthquakes().add(newEarthquake);
                if (GlobalQuake.instance != null) {
                    GlobalQuake.instance.getEventHandler().fireEvent(new QuakeCreateEvent(newEarthquake));
                }
            }
            cluster.setEarthquake(newEarthquake);
        } else {
            cluster.getEarthquake().update();

            if (GlobalQuake.instance != null) {
                GlobalQuake.instance.getEventHandler()
                        .fireEvent(new QuakeUpdateEvent(cluster.getEarthquake(), cluster.getPreviousHypocenter()));
            }
        }
    }

    private boolean checkUncertainty(Hypocenter bestHypocenter, List<PickedEvent> events) {
        bestHypocenter.depthUncertainty = bestHypocenter.depthConfidenceInterval.maxDepth()
                - bestHypocenter.depthConfidenceInterval.minDepth();
        bestHypocenter.locationUncertainty = bestHypocenter.polygonConfidenceIntervals
                .get(bestHypocenter.polygonConfidenceIntervals.size() - 1)
                .lengths().stream().max(Double::compareTo).orElse(0.0);

        if (bestHypocenter.locationUncertainty > HypocsSettings.getOrDefault("locationUncertaintyLimit", 90.0f)) {
            Logger.tag("Hypocs")
                    .info("Location uncertainty of %.1f is too high!".formatted(bestHypocenter.locationUncertainty));
            return false;
        }

        if (DEPTH_FIX_ALLOWED) {
            if (bestHypocenter.depthUncertainty > 200.0 || bestHypocenter.depthUncertainty > 20.0 &&
                    (bestHypocenter.depthConfidenceInterval.minDepth() <= 10.0
                            && bestHypocenter.depthConfidenceInterval.maxDepth() >= 10.0)) {
                Logger.tag("Hypocs").info("Depth uncertainty of %.1f is too high, defaulting the depth to 10km!"
                        .formatted(bestHypocenter.depthUncertainty));
                fixDepth(bestHypocenter, 10, events);
            }
        }

        return true;
    }

    @SuppressWarnings("SameParameterValue")
    private void fixDepth(Hypocenter bestHypocenter, double depth, List<PickedEvent> events) {
        bestHypocenter.depth = depth;
        bestHypocenter.depthFixed = true;

        List<Long> origins = new ArrayList<>();

        for (PickedEvent event : events) {
            double distGC = GeoUtils.greatCircleDistance(event.lat(), event.lon(), bestHypocenter.lat,
                    bestHypocenter.lon);
            double travelTime = TauPTravelTimeCalculator.getPWaveTravelTime(depth,
                    TauPTravelTimeCalculator.toAngle(distGC));
            if (travelTime < 0) {
                continue;
            }

            travelTime += getElevationCorrection(event.elevation());

            long origin = event.pWave() - ((long) (travelTime * 1000));
            origins.add(origin);
        }

        if (origins.isEmpty()) {
            return;
        }

        origins.sort(Long::compareTo);
        bestHypocenter.origin = origins.get((origins.size() - 1) / 2);

        Logger.tag("Hypocs").debug("Origin time recalculated");
    }

    private List<PolygonConfidenceInterval> calculatePolygonConfidenceIntervals(List<PickedEvent> selectedEvents,
            PreliminaryHypocenter bestHypocenterPrelim, HypocenterFinderSettings finderSettings) {
        List<PolygonConfidenceInterval> result = new ArrayList<>();

        double mul = 80.0 / (selectedEvents.size() * selectedEvents.size() + 8.0) + 0.9;

        result.add(calculatePolygonConfidenceInterval(selectedEvents, bestHypocenterPrelim, finderSettings, 3.0 * mul));
        result.add(calculatePolygonConfidenceInterval(selectedEvents, bestHypocenterPrelim, finderSettings, 2.0 * mul));
        result.add(calculatePolygonConfidenceInterval(selectedEvents, bestHypocenterPrelim, finderSettings, 1.5 * mul));
        result.add(
                calculatePolygonConfidenceInterval(selectedEvents, bestHypocenterPrelim, finderSettings, 1.25 * mul));

        return result;
    }

    private void calculateActualCorrectEvents(List<PickedEvent> selectedEvents, Hypocenter bestHypocenter) {
        int correct = 0;
        for (PickedEvent event : selectedEvents) {
            if (ClusterAnalysis.couldBeArrival(event, bestHypocenter, false, false, false)) {
                correct++;
            }
        }

        bestHypocenter.correctEvents = correct;
    }

    private void calculateObviousArrivals(Hypocenter bestHypocenter) {
        if (GlobalQuake.instance == null) {
            bestHypocenter.obviousArrivalsInfo = new ObviousArrivalsInfo(0, 0);
            return;
        }

        int total = 0;
        int wrong = 0;

        for (AbstractStation station : GlobalQuake.instance.getStationManager().getStations()) {
            double distGC = GeoUtils.greatCircleDistance(bestHypocenter.lat, bestHypocenter.lon, station.getLatitude(),
                    station.getLongitude());

            if (distGC > OBVIOUS_CORRECT_MAX_DISTANCE) {
                continue;
            }

            double angle = TauPTravelTimeCalculator.toAngle(distGC);

            double rawTravelP = TauPTravelTimeCalculator.getPWaveTravelTime(bestHypocenter.depth, angle);
            if (rawTravelP < 0) {
                continue;
            }

            double expectedRatio = IntensityTable.getRatio(bestHypocenter.magnitude, GeoUtils.gcdToGeo(distGC));
            if (expectedRatio < OBVIOUS_CORRECT_RATIO_THRESHOLD) {
                continue;
            }

            long expectedPArrival = bestHypocenter.origin
                    + (long) ((rawTravelP + EarthquakeAnalysis.getElevationCorrection(station.getAlt())) * 1000);

            if (station.getStateAt(expectedPArrival) != StationState.ACTIVE) {
                continue;
            }

            total++;
            if (station.getEventAt(expectedPArrival, 20 * 1000) == null) {
                wrong++;
            }
        }

        bestHypocenter.obviousArrivalsInfo = new ObviousArrivalsInfo(total, wrong);
    }

    public static final double PHI = 1.61803398875;

    private PreliminaryHypocenter scanArea(List<PickedEvent> events, double maxDist, int points, double _lat,
            double _lon, int depthIterations,
            double maxDepth, HypocenterFinderSettings finderSettings) {
        int CPUS = Runtime.getRuntime().availableProcessors();
        double c = maxDist / Math.sqrt(points); // Distance between points
        double one = points / (double) CPUS; // Number of points per CPU

        List<Integer> integerList = IntStream.range(0, CPUS).boxed().toList();
        return (Settings.parallelHypocenterLocations ? integerList.parallelStream() : integerList.stream()).map(
                cpu -> {
                    List<ExactPickedEvent> pickedEvents = createListOfExactPickedEvents(events);
                    HypocenterFinderThreadData threadData = new HypocenterFinderThreadData(pickedEvents.size());

                    int start = (int) (cpu * one);
                    int end = (int) ((cpu + 1) * one);

                    for (int n = start; n < end; n++) {
                        double ang = 360.0 / (PHI * PHI) * n;
                        double dist = Math.sqrt(n) * c;
                        double[] latLon = GeoUtils.moveOnGlobe(_lat, _lon, dist, ang);

                        double lat = latLon[0];
                        double lon = latLon[1];

                        calculateDistances(pickedEvents, lat, lon);
                        getBestAtDepth(depthIterations, maxDepth, finderSettings, 0, lat, lon, pickedEvents,
                                threadData);
                    }
                    return threadData.bestHypocenter;
                }).reduce(EarthquakeAnalysis::selectBetterHypocenter).orElse(null);
    }

    @SuppressWarnings("unused")
    private PreliminaryHypocenter scanAreaOldd(List<PickedEvent> events, double distanceResolution, double maxDist,
            double _lat, double _lon, int depthIterations, double maxDepth, double distHorizontal,
            HypocenterFinderSettings finderSettings) {

        List<Double> distances = new ArrayList<>();
        for (double dist = 0; dist < maxDist; dist += distanceResolution) {
            distances.add(dist);
        }

        return (Settings.parallelHypocenterLocations ? distances.parallelStream() : distances.stream()).map(
                distance -> {
                    List<ExactPickedEvent> pickedEvents = createListOfExactPickedEvents(events);
                    HypocenterFinderThreadData threadData = new HypocenterFinderThreadData(pickedEvents.size());
                    getBestAtDist(distance, distHorizontal, _lat, _lon, pickedEvents, depthIterations, maxDepth,
                            finderSettings, threadData);
                    return threadData.bestHypocenter;
                }).reduce(EarthquakeAnalysis::selectBetterHypocenter).orElse(null);
    }

    private List<ExactPickedEvent> createListOfExactPickedEvents(List<PickedEvent> events) {
        List<ExactPickedEvent> result = new ArrayList<>(events.size());
        for (PickedEvent event : events) {
            result.add(new ExactPickedEvent(event));
        }
        return result;
    }

    private static double calculateHeuristic(PreliminaryHypocenter hypocenter) {
        return (hypocenter.correctStations * hypocenter.correctStations) / (hypocenter.err * hypocenter.err);
    }

    private static PreliminaryHypocenter selectBetterHypocenter(PreliminaryHypocenter hypocenter1,
            PreliminaryHypocenter hypocenter2) {
        if (hypocenter1 == null) {
            return hypocenter2;
        } else if (hypocenter2 == null) {
            return hypocenter1;
        }

        if (hypocenter1.correctStations > (int) (hypocenter2.correctStations)) {
            return hypocenter1;
        } else if (hypocenter2.correctStations > (int) (hypocenter1.correctStations)) {
            return hypocenter2;
        } else {
            return calculateHeuristic(hypocenter1) > calculateHeuristic(hypocenter2) ? hypocenter1 : hypocenter2;
        }
    }

    private void getBestAtDist(double distFromAnchor, double distHorizontal, double _lat, double _lon,
            List<ExactPickedEvent> events, int depthIterations, double depthEnd,
            HypocenterFinderSettings finderSettings, HypocenterFinderThreadData threadData) {
        double depthStart = 0;

        double angularResolution = (distHorizontal * 360) / (5 * distFromAnchor + 10);
        angularResolution /= getUniversalResolutionMultiplier(finderSettings);

        GeoUtils.MoveOnGlobePrecomputed precomputed = new GeoUtils.MoveOnGlobePrecomputed();
        Point2DGQ point2D = new Point2DGQ();
        GeoUtils.precomputeMoveOnGlobe(precomputed, _lat, _lon, distFromAnchor);

        for (double ang = 0; ang < 360; ang += angularResolution) {
            GeoUtils.moveOnGlobe(precomputed, point2D, ang);
            double lat = point2D.x;
            double lon = point2D.y;

            calculateDistances(events, lat, lon);
            getBestAtDepth(depthIterations, depthEnd, finderSettings, depthStart, lat, lon, events, threadData);
        }
    }

    private void getBestAtDepth(int depthIterations, double depthEnd, HypocenterFinderSettings finderSettings,
            double depthStart, double lat, double lon, List<ExactPickedEvent> pickedEvents,
            HypocenterFinderThreadData threadData) {
        double lowerBound = depthStart; // 0
        double upperBound = depthEnd; // 600

        double depthA = lowerBound + (upperBound - lowerBound) * (1 / 3.0);
        double depthB = lowerBound + (upperBound - lowerBound) * (2 / 3.0);

        analyseHypocenter(threadData.hypocenterA, lat, lon, depthA, pickedEvents, finderSettings, threadData);
        analyseHypocenter(threadData.hypocenterB, lat, lon, depthB, pickedEvents, finderSettings, threadData);

        PreliminaryHypocenter upperHypocenter = threadData.hypocenterA;
        PreliminaryHypocenter lowerHypocenter = threadData.hypocenterB;

        for (int iteration = 0; iteration < depthIterations; iteration++) {
            PreliminaryHypocenter better = selectBetterHypocenter(upperHypocenter, lowerHypocenter);
            boolean goUp = better == upperHypocenter;

            PreliminaryHypocenter temp = lowerHypocenter;
            lowerHypocenter = upperHypocenter;
            upperHypocenter = temp;

            if (goUp) {
                upperBound = (upperBound + lowerBound) / 2.0;
                depthA = lowerBound + (upperBound - lowerBound) * (1 / 3.0);

                analyseHypocenter(upperHypocenter, lat, lon, depthA, pickedEvents, finderSettings, threadData);
                threadData.setBest(selectBetterHypocenter(threadData.bestHypocenter, upperHypocenter));
            } else {
                lowerBound = (upperBound + lowerBound) / 2.0;
                depthB = lowerBound + (upperBound - lowerBound) * (2 / 3.0);

                analyseHypocenter(lowerHypocenter, lat, lon, depthB, pickedEvents, finderSettings, threadData);
                threadData.setBest(selectBetterHypocenter(threadData.bestHypocenter, lowerHypocenter));
            }
        }

        // additionally check 0km and 10 km
        analyseHypocenter(threadData.hypocenterA, lat, lon, 0, pickedEvents, finderSettings, threadData);
        threadData.setBest(selectBetterHypocenter(threadData.bestHypocenter, threadData.hypocenterA));
        analyseHypocenter(threadData.hypocenterA, lat, lon, 10, pickedEvents, finderSettings, threadData);
        threadData.setBest(selectBetterHypocenter(threadData.bestHypocenter, threadData.hypocenterA));
    }

    private static final long UNKNOWN_ORIGIN = Long.MIN_VALUE;

    public static void analyseHypocenter(PreliminaryHypocenter hypocenter, double lat, double lon, double depth,
            List<ExactPickedEvent> events, HypocenterFinderSettings finderSettings,
            HypocenterFinderThreadData threadData) {
        int c = 0;

        for (ExactPickedEvent event : events) {
            double travelTime = TauPTravelTimeCalculator.getPWaveTravelTimeFast(depth, event.angle);
            if (travelTime == TauPTravelTimeCalculator.NO_ARRIVAL) {
                threadData.origins[c] = UNKNOWN_ORIGIN;
                c++;
                continue;
            }

            travelTime += getElevationCorrection(event.elevation());

            long origin = event.pWave() - ((long) (travelTime * 1000));
            threadData.origins[c] = origin;
            c++;
        }

        long bestOrigin;
        if (USE_MEDIAN_FOR_ORIGIN) {
            Arrays.sort(threadData.origins);
            bestOrigin = threadData.origins[(threadData.origins.length - 1) / 2];
        } else {
            bestOrigin = threadData.origins[0];
        }

        if (bestOrigin == UNKNOWN_ORIGIN) {
            hypocenter.err = Double.MAX_VALUE;
            hypocenter.correctStations = 0;
            return;
        }

        double err = 0;
        int acc = 0;

        for (long orign : threadData.origins) {
            double _err = Math.abs(orign - bestOrigin);
            if (_err < finderSettings.pWaveInaccuracyThreshold()) {
                acc++;
            } else {
                _err = (_err - finderSettings.pWaveInaccuracyThreshold()) * 0.2
                        + finderSettings.pWaveInaccuracyThreshold();
            }

            _err /= 1000;

            err += _err * _err;
        }

        hypocenter.lat = lat;
        hypocenter.lon = lon;
        hypocenter.depth = depth;
        hypocenter.origin = bestOrigin;
        hypocenter.err = err;
        hypocenter.correctStations = acc;
    }

    public static double getElevationCorrection(double elevation) {
        return elevation / 6000.0;
    }

    private void calculateDistances(List<ExactPickedEvent> pickedEvents, double lat, double lon) {
        for (ExactPickedEvent event : pickedEvents) {
            event.angle = TauPTravelTimeCalculator.toAngle(GeoUtils.greatCircleDistance(event.lat(),
                    event.lon(), lat, lon));
        }
    }

    public static final class ExactPickedEvent extends PickedEvent {
        public double angle;

        public ExactPickedEvent(PickedEvent pickedEvent) {
            super(pickedEvent.pWave(), pickedEvent.lat(), pickedEvent.lon(), pickedEvent.elevation(),
                    pickedEvent.maxRatio());
        }

    }

    private double getUniversalResolutionMultiplier(HypocenterFinderSettings finderSettings) {
        // 30% when 0.0 (min) selected
        // 100% when 40.0 (default) selected
        // 550% when 100 (max) selected
        double x = finderSettings.resolution();
        return ((x * x + 600) / 2200.0);
    }

    private HypocenterCondition checkConditions(List<PickedEvent> events, Hypocenter bestHypocenter,
            Hypocenter previousHypocenter, Cluster cluster, HypocenterFinderSettings finderSettings) {
        if (bestHypocenter == null) {
            return HypocenterCondition.NULL;
        }

        double distFromRoot = GeoUtils.greatCircleDistance(bestHypocenter.lat, bestHypocenter.lon, cluster.getRootLat(),
                cluster.getRootLon());

        if (CHECK_DISTANT_EVENT_STATIONS && distFromRoot > 600 && bestHypocenter.correctEvents < 8) {
            return HypocenterCondition.DISTANT_EVENT_NOT_ENOUGH_STATIONS;
        }

        if (CHECK_DISTANT_EVENT_STATIONS && distFromRoot > 2000 && bestHypocenter.correctEvents < 12) {
            return HypocenterCondition.DISTANT_EVENT_NOT_ENOUGH_STATIONS;
        }

        if (bestHypocenter.correctEvents < finderSettings.minStations()) {
            return HypocenterCondition.NOT_ENOUGH_CORRECT_STATIONS;
        }

        if (CHECK_QUADRANTS) {
            if (checkQuadrants(bestHypocenter, events) < 2.0) {
                return HypocenterCondition.TOO_SHALLOW_ANGLE;
            }
        }

        if (previousHypocenter != null) {
            if (bestHypocenter.quality.getSummary().ordinal() > previousHypocenter.quality.getSummary().ordinal()) {
                return HypocenterCondition.PREVIOUS_WAS_BETTER_QUALITY;
            } else if (bestHypocenter.quality.getSummary().ordinal() < previousHypocenter.quality.getSummary()
                    .ordinal()) {
                return HypocenterCondition.OK;
            }
        }

        PreliminaryHypocenter bestPrelim = toPreliminary(bestHypocenter);

        if (ONLY_SELECT_BETTER && selectBetterHypocenter(toPreliminary(previousHypocenter), bestPrelim) != bestPrelim) {
            return HypocenterCondition.PREVIOUS_WAS_BETTER;
        }

        return HypocenterCondition.OK;
    }

    private PreliminaryHypocenter toPreliminary(Hypocenter previousHypocenter) {
        if (previousHypocenter == null) {
            return null;
        }
        return new PreliminaryHypocenter(previousHypocenter.lat, previousHypocenter.lon, previousHypocenter.depth,
                previousHypocenter.origin, previousHypocenter.totalErr, previousHypocenter.correctEvents);
    }

    private double checkQuadrants(Hypocenter hyp, List<PickedEvent> events) {
        int[] qua = new int[QUADRANTS];
        double good = 0;
        for (PickedEvent event : events) {
            double angle = GeoUtils.calculateAngle(hyp.lat, hyp.lon, event.lat(), event.lon());
            int q = (int) ((angle * QUADRANTS) / 360.0);
            if (qua[q] == 0) {
                good += 1;
            }
            if (qua[q] == 1) {
                good += 0.5;
            }
            qua[q]++;
        }
        return good;
    }

    private void calculateMagnitude(Cluster cluster, Hypocenter hypocenterLocation, Hypocenter hypocenterAssign) {
        hypocenterAssign.magnitude = NO_MAGNITUDE;
        hypocenterAssign.mags = null;

        if (cluster == null) {
            Logger.tag("Hypocs").error("Fatal error: cluster or hypocenter is null!");
            return;
        }
        Collection<Event> goodEvents = cluster.getAssignedEvents().values();
        if (goodEvents.isEmpty()) {
            return;
        }

        assignMagnitude(hypocenterLocation, hypocenterAssign, goodEvents, MagnitudeType.DEFAULT);
        Logger.tag("Hypocs").trace("Mg%.1f".formatted(hypocenterAssign.magnitude));
        if (hypocenterAssign.magnitude > 4.0) {
            assignMagnitude(hypocenterLocation, hypocenterAssign, goodEvents, MagnitudeType.LOW_FREQ);
            Logger.tag("Hypocs").trace("Mgl%.1f".formatted(hypocenterAssign.magnitude));
        }
        if (hypocenterAssign.magnitude > 6.5) {
            double mgl = hypocenterAssign.magnitude;
            assignMagnitude(hypocenterLocation, hypocenterAssign, goodEvents, MagnitudeType.ULTRA_LOW_FREQ);
            Logger.tag("Hypocs").trace("Mgu%.1f".formatted(hypocenterAssign.magnitude));

            double pct = Math.min(1.0, (hypocenterAssign.magnitude - 6.0) * 1.25);
            hypocenterAssign.magnitude = mgl * (1 - pct) + hypocenterAssign.magnitude * pct;
        }
    }

    private static void assignMagnitude(Hypocenter hypocenterLocation, Hypocenter hypocenterAssign,
            Collection<Event> goodEvents, MagnitudeType magnitudeType) {
        ArrayList<MagnitudeReading> mags = new ArrayList<>();
        for (Event event : goodEvents) {
            if (!event.isValid() || event.getMaxVelocity() < 0 || event.getMaxRatio() < 0) {
                continue;
            }
            double distGC = GeoUtils.greatCircleDistance(hypocenterLocation.lat, hypocenterLocation.lon,
                    event.getLatFromStation(), event.getLonFromStation());
            double distGE = GeoUtils.geologicalDistance(hypocenterLocation.lat, hypocenterLocation.lon,
                    -hypocenterLocation.depth, event.getLatFromStation(), event.getLonFromStation(),
                    event.getAnalysis().getStation().getAlt() / 1000.0);
            double sTravelRaw = TauPTravelTimeCalculator.getSWaveTravelTime(hypocenterLocation.depth,
                    TauPTravelTimeCalculator.toAngle(distGC));
            long expectedSArrival = (long) (hypocenterLocation.origin
                    + sTravelRaw
                            * 1000);
            long lastRecord = ((BetterAnalysis) event.getAnalysis()).getLatestLogTime();
            // *0.5 because s wave is stronger
            double mul = sTravelRaw == TauPTravelTimeCalculator.NO_ARRIVAL || lastRecord > expectedSArrival + 8 * 1000
                    ? 0.95
                    : Math.max(1, 3 - distGC / 400.0);

            double maxVelocity = event.getMaxVelocity(magnitudeType);

            InputType inputType = event.getAnalysis().getStation().getInputType();
            boolean accelerometer = inputType == InputType.ACCELERATION;

            double magnitude = event.isUsingRatio()
                    ? IntensityTable.getMagnitudeByRatio(distGE, event.getMaxRatio() * mul)
                    : accelerometer ? IntensityTable.getMagnitudeByAccelerometer(distGE, maxVelocity * mul)
                            : IntensityTable.getMagnitude(distGE, maxVelocity * mul);
            magnitude -= getDepthCorrection(hypocenterLocation.depth);

            long eventAge = lastRecord - event.getpWave();

            mags.add(new MagnitudeReading(magnitude, distGC, eventAge, inputType));
        }

        mags.sort(Comparator.comparing(MagnitudeReading::eventAge));

        int minSize = 4;
        long ageLimit = Math.max(2000, Math.min(16000, mags.get((int) (mags.size() * 0.25 - 1)).eventAge()));

        while (mags.size() > minSize && mags.get(0).eventAge() < ageLimit) {
            mags.remove(0);
        }

        hypocenterAssign.mags = mags;
        hypocenterAssign.magnitude = selectMagnitude(mags);
        hypocenterAssign.magnitudeType = magnitudeType;
    }

    public static double getDepthCorrection(double depth) {
        return Math.log10(depth + 160.0) - Math.log10(160.0);
    }

    protected static double selectMagnitude(List<MagnitudeReading> mags) {
        mags.sort(Comparator.comparing(MagnitudeReading::distance));

        List<MagnitudeReading> magnitudeReadings = new ArrayList<>();
        for (MagnitudeReading magnitudeReading : mags) {
            if (magnitudeReading.distance() > 90 && magnitudeReading.distance() < 2000
                    && magnitudeReading.inputType() == InputType.VELOCITY) {
                magnitudeReadings.add(magnitudeReading);
            }
        }

        int targetSize = 42;
        for (MagnitudeReading magnitudeReading : mags) {
            if (magnitudeReading.distance() > 90 && magnitudeReading.distance() < 2000
                    && magnitudeReading.inputType() == InputType.VELOCITY) {
                continue;
            }

            if (magnitudeReadings.size() < targetSize) {
                magnitudeReadings.add(magnitudeReading);
            } else
                break;
        }

        List<MagnitudeReading> list = new ArrayList<>(magnitudeReadings);

        list.sort(Comparator.comparing(MagnitudeReading::magnitude));

        if (list.isEmpty()) {
            Logger.tag("Hypocs").warn("Magnitude readings list is empty! (%d -> 0)".formatted(mags.size()));
            return NO_MAGNITUDE;
        }

        return list.get((int) ((list.size() - 1) * 0.5)).magnitude();
    }

    public static final int[] STORE_TABLE = {
            3, 3, // M0
            3, 3, // M1
            3, 3, // M2
            5, 6, // M3
            8, 16, // M4
            30, 30, // M5
            30, 40, // M6
            40, 40, // M7+
    };

    /**
     * @param earthquake    the earthquake
     * @param marginSeconds POSITIVE NUMBERS CAUSE THIS FUNCTION TO RETURN TRUE
     *                      SOONER
     * @return if the quake is too old and should be removed
     */
    public static boolean shouldRemove(Earthquake earthquake, int marginSeconds) {
        double store_minutes = STORE_TABLE[Math.max(0,
                Math.min(STORE_TABLE.length - 1,
                        (int) ((earthquake.getMag() + getDepthCorrection(earthquake.getDepth())) * 2.0)))]
                - marginSeconds / 60.0;
        long time_passed = GlobalQuake.instance.currentTimeMillis() - earthquake.getCreatedAt();
        // if exceed 15 minutes, remove it
        if (time_passed > 15 * 60 * 1000) {
            return true;
        }
        return GlobalQuake.instance.currentTimeMillis() - earthquake.getOrigin() > (long) store_minutes * 60 * 1000
                && GlobalQuake.instance.currentTimeMillis() - earthquake.getLastUpdate() > 0.25 * store_minutes * 60
                        * 1000;
    }

    public void second() {
        Iterator<Earthquake> it = earthquakes.iterator();
        List<Earthquake> toBeRemoved = new ArrayList<>();
        while (it.hasNext()) {
            Earthquake earthquake = it.next();
            if (shouldRemove(earthquake, 0)) {
                if (GlobalQuake.instance != null) {
                    GlobalQuake.instance.getArchive().archiveQuakeAndSave(earthquake);
                }
                toBeRemoved.add(earthquake);
            }
        }

        earthquakes.removeAll(toBeRemoved);
    }

    public void destroy() {
    }

}
