package de.tum.bgu.msm.modules.modeChoice;

import de.tum.bgu.msm.data.*;
import de.tum.bgu.msm.data.travelTimes.TravelTimes;
import de.tum.bgu.msm.modules.Module;
import de.tum.bgu.msm.resources.Resources;
import de.tum.bgu.msm.util.ErrorTerms;
import de.tum.bgu.msm.util.LogitTools;
import de.tum.bgu.msm.util.MitoUtil;
import de.tum.bgu.msm.util.concurrent.ConcurrentExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static de.tum.bgu.msm.resources.Properties.*;

public class ModeChoice extends Module {

    private final static Logger logger = LogManager.getLogger(ModeChoice.class);

    private boolean finishedSamplingPersonErrorTerms = false;

    private final Map<Purpose, ModeChoiceCalculator> modeChoiceCalculatorByPurpose = new EnumMap<>(Purpose.class);

    public ModeChoice(DataSet dataSet, List<Purpose> purposes) {
        super(dataSet, purposes);
        boolean includeAV = Resources.instance.getBoolean(AUTONOMOUS_VEHICLE_CHOICE, false);
        //TODO: move AV related codes to useCases/munich/scenarios
        //AV option is deactivated for now, since it uses outdate mode choice calculators.
    }

    public void registerModeChoiceCalculator(Purpose purpose, ModeChoiceCalculator modeChoiceCalculator) {
        final ModeChoiceCalculator prev = modeChoiceCalculatorByPurpose.put(purpose, modeChoiceCalculator);
        if (prev != null) {
            logger.info("Overwrote mode choice calculator for purpose " + purpose + " with " + modeChoiceCalculator.getClass());
        }
    }

    @Override
    public void run() {
        if (modeChoiceCalculatorByPurpose.isEmpty()){
            throw new RuntimeException("It is mandatory to define mode choice calculators. Look at TravelDemandGeneratorXXX.java");
        }
        if(Resources.instance.getBoolean(MC_STATIC_PERSON_ERROR_TERMS,false)) {
            samplePersonErrorTerms();
        }
        logger.info(" Calculating mode choice probabilities for each trip");
        modeChoiceByPurpose();
        printModeShares();
    }

    private void samplePersonErrorTerms() {
        if(finishedSamplingPersonErrorTerms) {
            logger.warn("Person-level error terms already sampled, will not resample. This message should only show during mode choice calibration!");
        } else {
            Purpose refPurpose = Purpose.valueOf(Resources.instance.getString(MC_STATIC_PERSON_ERROR_TERMS_NEST_STRUCTURE));
            ModeChoiceCalculator refCalculator = modeChoiceCalculatorByPurpose.get(refPurpose);
            logger.info("Sampling person-level error terms based on choices and nesting structure for " + refPurpose + ".");
            ErrorTerms<Mode> errorTermSampler = new ErrorTerms<>(Mode.class,refCalculator.getChoiceSet(),refCalculator.getNests(),MitoUtil.getRandomObject().nextInt());
            for (MitoHousehold household : dataSet.getModelledHouseholds().values()) {
                for(MitoPerson person : household.getPersons().values()) {
                    person.setErrorTerms(errorTermSampler.sampleErrorTerms());
                }
            }
            finishedSamplingPersonErrorTerms = true;
        }
    }

    private void modeChoiceByPurpose() {
        ConcurrentExecutor<Void> executor = ConcurrentExecutor.fixedPoolService(Purpose.values().length);
        for (Purpose purpose : purposes) {
            executor.addTaskToQueue(new ModeChoiceByPurpose(purpose, dataSet, modeChoiceCalculatorByPurpose.get(purpose)));
        }
        executor.execute();
    }

    private void printModeShares() {

        //filter valid trips by purpose
        Map<Purpose, List<MitoTrip>> tripsByPurpose = dataSet.getTrips().values().stream()
                .filter(trip -> trip.getTripMode() != null)
                .collect(Collectors.groupingBy(MitoTrip::getTripPurpose));


        tripsByPurpose.forEach((purpose, trips) -> {
            final long totalTrips = trips.size();
            trips.parallelStream()
                    //group number of trips by mode
                    .collect(Collectors.groupingBy(MitoTrip::getTripMode, Collectors.counting()))
                    //calculate and add share to data set table
                    .forEach((mode, count) ->
                            dataSet.addModeShareForPurpose(purpose, mode, (double) count / totalTrips));
        });

        for (Purpose purpose : Purpose.values()) {
            logger.info("#################################################");
            logger.info("Mode shares for purpose " + purpose + ":");
            for (Mode mode : Mode.values()) {
                Double share = dataSet.getModeShareForPurpose(purpose, mode);
                if (share != null) {
                    logger.info(mode + " = " + share * 100 + "%");
                }
            }
        }

        // mode share of intrazonal trips
        //filter intrazonal trips
        logger.info("#################################################");
        logger.info("Intrazonal trip mode shares :");
        Map<Mode,Double> intrazonalModeShare = new HashMap<>();
        List<MitoTrip> intrazonalTrips = dataSet.getTrips().values().stream()
                .filter(trip -> trip.getTripOrigin().getZoneId() == trip.getTripDestination().getZoneId())
                .collect(Collectors.toList());

        final long totalIntrazonalTrips = intrazonalTrips.size();
        intrazonalTrips.stream()
                // Group number of persons by mode set
                .collect(Collectors.groupingBy(MitoTrip::getTripMode, Collectors.counting()))
                //calculate and add share to data set table
                .forEach((mode, count) ->
                        intrazonalModeShare.put(mode, (double) count / totalIntrazonalTrips));

        for (Mode mode : Mode.values()) {
            Double share = intrazonalModeShare.get(mode);
            if (share != null) {
                logger.info(mode + " = " + share * 100 + "%");
            }
        }
    }

    static class ModeChoiceByPurpose implements Callable<Void> {

        private final Purpose purpose;
        private final DataSet dataSet;
        private final TravelTimes travelTimes;
        private final ModeChoiceCalculator modeChoiceCalculator;
        private int countTripsSkipped;
        private ErrorTerms<Mode> errorTermsSampler = null;

        ModeChoiceByPurpose(Purpose purpose, DataSet dataSet, ModeChoiceCalculator modeChoiceCalculator) {
            this.purpose = purpose;
            this.dataSet = dataSet;
            this.travelTimes = dataSet.getTravelTimes();
            this.modeChoiceCalculator = modeChoiceCalculator;
            if(!Resources.instance.getBoolean(MC_STATIC_PERSON_ERROR_TERMS, false)) {
                this.errorTermsSampler = new ErrorTerms<>(Mode.class,
                        modeChoiceCalculator.getChoiceSet(),
                        modeChoiceCalculator.getNests(),
                        MitoUtil.getRandomObject().nextInt());
            }
        }

        @Override
        public Void call() {
            countTripsSkipped = 0;
            try {
                for (MitoHousehold household : dataSet.getModelledHouseholds().values()) {
                    for (MitoTrip trip : household.getTripsForPurpose(purpose)) {
                        chooseMode(trip, calculateTripUtilities(household, trip));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            logger.info(countTripsSkipped + " trips skipped for " + purpose);
            return null;
        }

        private EnumMap<Mode, Double> calculateTripUtilities(MitoHousehold household, MitoTrip trip) {
            if (trip.getTripOrigin() == null || trip.getTripDestination() == null) {
                countTripsSkipped++;
                return null;
            }

            final int originId = trip.getTripOrigin().getZoneId();

            final int destinationId = trip.getTripDestination().getZoneId();
            final MitoZone origin = dataSet.getZones().get(originId);
            final MitoZone destination = dataSet.getZones().get(destinationId);
            final double travelDistanceAuto = dataSet.getTravelDistancesAuto().getTravelDistance(originId,
                    destinationId);
            final double travelDistanceNMT = dataSet.getTravelDistancesNMT().getTravelDistance(originId,
                    destinationId);
            return modeChoiceCalculator.calculateUtilities(trip, purpose, household, trip.getPerson(), origin, destination, travelTimes, travelDistanceAuto,
                    travelDistanceNMT, dataSet.getPeakHour());
        }

        private void chooseMode(MitoTrip trip, EnumMap<Mode, Double> utilities) {
            if (utilities == null) {
                countTripsSkipped++;
                return;
            }

            //found Nan when there is no transit!!
            utilities.replaceAll((mode, utility) -> utility.isNaN() ? Double.NEGATIVE_INFINITY : utility);

            Map<Mode,Double> errorTerms = trip.getPerson().getErrorTerms();
            if(errorTerms == null) {
                errorTerms = errorTermsSampler.sampleErrorTerms();
            }

            double sum = MitoUtil.getSum(utilities.values());
            if (Double.isFinite(sum)) {
                final Mode select = LogitTools.getHighest(utilities,errorTerms);
                trip.setTripMode(select);
            } else {
                logger.error("Infinite utilities for trip " + trip.getId());
                trip.setTripMode(null);
            }
        }
    }
}
