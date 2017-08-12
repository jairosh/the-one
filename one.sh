#! /bin/sh
java -Xmx8G -cp target:lib/ECLA.jar:lib/DTNConsoleConnection.jar:lib/bloomfilter-counters-0.0.2.jar:lib/fnv.jar:lib/murmur.jar core.DTNSim $*
