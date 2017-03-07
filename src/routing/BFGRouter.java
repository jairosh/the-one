package routing;

import core.Connection;
import core.DTNHost;
import core.Settings;
import org.ipn.cic.ndsrg.BloomFilter;


public class BFGRouter extends ActiveRouter{

    private BloomFilter<DTNHost> Ft;
    private BloomFilter<DTNHost> F_STAR;

    private double degradationInterval;
    private double degradationProbability;
    private int    expectedNrOfItems;

    public static final String BFG_NS = "BFGRouter";
    public static final String SETTINGS_DEG_INTERVAL = "DegradationInterval";
    public static final String SETTINGS_DEG_PROBABILITY = "DegradationProbability";

    public BFGRouter(Settings s){
        super(s);
        Settings bfgSettings = new Settings(BFG_NS);
        degradationInterval = bfgSettings.getDouble(SETTINGS_DEG_INTERVAL, 5.0);
        degradationProbability = bfgSettings.getDouble(SETTINGS_DEG_PROBABILITY, 0.5);
    }

    protected BFGRouter(BFGRouter r){
        super(r);
    }

    @Override
    public MessageRouter replicate() {
        return null;
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
    }

    @Override
    public void update() {
        super.update();
    }


}
