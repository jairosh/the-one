package report;

import core.ConnectionListener;
import core.DTNHost;
import core.SimClock;
import util.Tuple;

import java.util.HashMap;

public class FullContactReport extends Report implements ConnectionListener {
    private HashMap<String, Double> initialTimes;

    public FullContactReport(){
        init();
        this.initialTimes = new HashMap<>();
    }


    @Override
    public void hostsConnected(DTNHost host1, DTNHost host2) {
        String pair = getConnectionString(host1, host2);
        if(isWarmup()){
            addWarmupID(pair);
            return;
        }

        initialTimes.put(pair, SimClock.getTime());
    }

    @Override
    public void hostsDisconnected(DTNHost host1, DTNHost host2) {
        String pair = getConnectionString(host1, host2);

        if(isWarmup() || isWarmupID(pair)){
            removeWarmupID(pair);
            return;
        }

        if(!initialTimes.containsKey(pair) && !initialTimes.containsKey(getConnectionString(host2, host1))){
            return;
        }
        Double iniTime = initialTimes.get(pair);
        if(iniTime == null)
            iniTime = initialTimes.get(getConnectionString(host2, host1));

        Double timeForThisConnection = SimClock.getTime() - iniTime;

        newEvent();
        write(getConnectionString(host1, host2) + " " + timeForThisConnection);

        initialTimes.remove(pair);
        initialTimes.remove(getConnectionString(host2, host1));
    }

    private String getConnectionString(DTNHost h1, DTNHost h2){
        return h1.getAddress() + " " + h2.getAddress();
    }
}
