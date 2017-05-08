package report;

import core.Coord;
import core.DTNHost;
import core.Settings;
import core.UpdateListener;
import routing.BFGRouter;

import java.util.HashSet;
import java.util.List;

/**
 * Created by jairo on 16/04/17.
 */
public class BloomFilterSnapshotReport extends Report implements UpdateListener {

    /**
     * Name of the configuration parameter to seek for the interval in which this will generate snapshots
     */
    private static final String INTERvAL = "interval";

    /**
     * Name of the configuration parameter holding the node list over which the report will be generated
     */
    private static final String NODES = "nodes";

    protected int interval;
    protected double lastUpdate;

    /** Networks addresses (integers) of the nodes which are reported */
    protected HashSet<Integer> reportedNodes;


    public BloomFilterSnapshotReport(){
        Settings settings = getSettings();
        interval = settings.getInt(INTERvAL);
        lastUpdate = 0.0;
        if(settings.contains(NODES)){
            this.reportedNodes = new HashSet<Integer>();
            for( Integer i : settings.getCsvInts(NODES)){
                reportedNodes.add(i);
            }
        }else{
            this.reportedNodes = null;
        }
    }


    @Override
    public void updated(List<DTNHost> hosts) {
        double simTime = getSimTime();
        if (isWarmup()) {
            return; /* warmup period is on */
        }
		/* one snapshot once every granularity seconds */
        if (simTime - lastUpdate >= interval) {
            for( DTNHost h : hosts ){
                if (this.reportedNodes != null &&
                        !this.reportedNodes.contains(h.getAddress())) {
                    continue; /* ignore this node */
                }

                write(serializeSnapshot(h));
            }
            this.lastUpdate = simTime - simTime % interval;
        }
    }


    /**
     * Creates a JSON formatted entity containing the relevant information of the snapshot
     * @param host The current DTNHost to be reported
     * @return A string with the JSON entity
     */
    private String serializeSnapshot(DTNHost host){
        if (!(host.getRouter() instanceof BFGRouter))
            throw new AssertionError("This report cannot deal with other Routing protocols than BFGRouter");

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"time\":" + getSimTime() + ",");
        sb.append("\"id\":" + host.getAddress() + "," );
        Coord pos = host.getLocation();
        sb.append("\"x\":" + pos.getX() + ", \"y\":" + pos.getY() + ",");
        BFGRouter router = (BFGRouter) host.getRouter();
        sb.append("\"router\":" + router.serializeInfo());
        sb.append("}");
        return sb.toString();
    }
}
