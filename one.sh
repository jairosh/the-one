#! /bin/sh
PROFILING_OPTS="-Dcom.sun.management.jmxremote=true -Dcom.sun.management.jmxremote.port=3614 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
FLIGHT_REC_OPTS="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder"
#$PROFILING_OPTS $FLIGHT_REC_OPTS
java -Xmx8G -Djava.util.Arrays.useLegacyMergeSort=true -cp target:lib/ECLA.jar:lib/DTNConsoleConnection.jar:lib/bloomfilter-counters-0.0.2.jar:lib/fnv.jar:lib/murmur.jar core.DTNSim $*
