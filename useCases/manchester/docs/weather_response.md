# Weather response in MITO Manchester

MITO generates trips per person and **week**. The weather sensitivities come from a **daily** model:
the NTS 2002–2024 diaries linked to HadUK-Grid weather by local authority and day, estimated with person
fixed-effects Poisson models in `extremeWeather/analysis/mito/weather_tripgen_sensitivities.ipynb`.

The two are reconciled by applying the weather **after the day-of-week choice**. At that point every
trip has a day, so it has that day's weather. Thinning a day's trips by `exp(beta * weather)` scales
that day's expected count exactly as the daily model does. No weekly weather aggregate is needed.

## Two channels

| Channel | Where | What it does | Input file |
|---|---|---|---|
| 1. Cancellation | `modules/WeatherTripAdjustment`, after day-of-week and mode set choice, before discretionary distribution | Keeps each trip with probability `m` (or duplicates it with probability `m - 1` if `m > 1`). `m = exp(...)` depends on purpose, rain, max temperature, gender, age band, occupation, car ownership, rural home and weekend. | `weatherTripGenCoefficients_purposeOnly.csv` (`mode = all` rows only; a file with mode rows is rejected) |
| 2. Mode shift | `calculators/WeatherModeChoiceCalculatorMCR`, wrapping the calibrated calculator of every purpose | Adds the day's shift to the walk / bicycle / public transport utilities, relative to car | `weatherModeUtilityShifts.csv` |

**Why the two channels don't double count.** Trips by mode = all trips × mode share. Channel 1 gives
the change in all trips by purpose. Channel 2 gives the change in the log-odds of each mode against car.
Because the Manchester mode choice is a multinomial logit, that is exactly a constant shift. Together they
reproduce the by-mode NTS response. Don't also add weather to MATSim mode choice.

## Run order (`TravelDemandGeneratorMCR.generateTravelDemand`)

1. Trip generation, mandatory
2. Trip distribution, mandatory
3. Trip generation, discretionary
4. **Day-of-week choice.** It was moved here from after mode choice; it uses only the purpose and number
   of each person's trips.
5. Mode set choice (weekly, not weather-dependent)
6. **Weather channel 1:** cancellation
7. Trip distribution, discretionary
8. Mode choice, with **weather channel 2** inside
9. Time of day, scaling, MATSim plans

Moving the day-of-week choice changes the order of random draws. A baseline run on this branch therefore
differs slightly from earlier baselines, even with weather off. Rerun the baseline before comparing.

## Properties

```properties
# weather of each simulated day; a day not listed (and with no default) is not adjusted
weather.rain.mm.thursday  = 15
weather.tasmax.c.thursday = 8
weather.rain.mm           = 0        # optional default
weather.tasmax.c          = 15       # optional default

weather.tripgen.run          = true
weather.tripgen.coefficients = input/mito/tripGeneration/weatherTripGenCoefficients_purposeOnly.csv

weather.modechoice.run    = true
weather.modechoice.shifts = input/mito/modeChoice/weatherModeUtilityShifts.csv
```

Mode choice calibration (`run.mc.calibration`) and `weather.modechoice.run` together raise an error.
Calibrate on a reference day, otherwise the constants absorb the weather.

## Code change in `mitoCore`

`ModeChoiceCalculator` has a new default method, `calculateUtilities(MitoTrip trip, ...)`, which
`ModeChoice` now calls. By default it ignores the trip, so the other use cases are unchanged. It is the
only way a calculator can see the trip's day. SILO resolves `mitoCore` from Cloudsmith, so a SILO run
picks this up only after `mitoCore` is installed locally or the snapshot is republished.

## Known limitations

- The mode shift is the same for every person and every purpose. Person heterogeneity is in channel 1 only.
- No income or deprivation group yet.
- Trips are thinned one by one, not by tour. NHB origins are drawn from the person's surviving home-based
  trips of the whole week, as before.
- The coefficients are estimated on England without NTS weights. Freezing-day effects rest on few
  (probably snowy) days.
