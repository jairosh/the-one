package routing;

import core.*;
import org.ipn.cic.ndsrg.BloomFilter;
import routing.util.RoutingInfo;
import util.Tuple;

import java.io.InvalidObjectException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * BFGRouting protocol implementation
 */
public class BFGRouter extends ActiveRouter{

    /**
     * Each host has two Bloom filters:
     */
    /**
     * The first Bloom filter contains all the information gathered by the node and its neighbors
     */
    private BloomFilter<Integer> Ft;
    /**
     * The second Bloom filter contains only the identity of this host and it's constant over time
     */
    private BloomFilter<Integer> F_STAR;


    /**
     * This value sets the rate in which the external information decays
     */
    private double degradationInterval;
    /**
     * This value sets the degradation probability if the degradation should be stochastic
     */
    private double degradationProbability;

    /**
     * Depending on the forwarding strategy, this value sets a threshold to decide if a packet should be
     * forwarded or not
     */
    private double forwardThreshold;
    /**
     * This sets the type of criteria to forward a packet to a new contact
     */
    private int    forwardStrategy;

    /**
     * Indicates the first threshold at which a certain node switches from an Epidemic based transmition to a progress-
     * based transmition
     */
    private double firstThreshold;


    /**
     * Bloom filter parameters
     */
    private int bfCounters;      //m
    private int bfHashFunctions; //k
    private int bfMaxCount;      //c

    /**
     * Keeps track of the time when degradation was made, this provides a rudimentary Timer
     */
    private double lastDegradation;
    private double lastBFExchange;

    public static final String BFG_NS = "BFGRouter";
    public static final String SETTINGS_DEG_INTERVAL = "degradationInterval";
    public static final String SETTINGS_DEG_PROBABILITY = "degradationProbability";
    public static final String SETTINGS_FORWARD_THRESHOLD = "forwardingThreshold";
    public static final String SETTINGS_FORWARD_STRATEGY = "forwardStrategy";
    public static final String SETTINGS_THRESHOLD_1 = "firstThreshold";
    public static final String SETTINGS_BF_COUNTERS = "BFCounters";
    public static final String SETTINGS_BF_HASH_FUNCTIONS = "BFHashFunctions";
    public static final String SETTINGS_BF_MAX_COUNT = "BFMaxCount";

    protected double creationTime;

    /**
     * Constructor from settings in configuration files. Invoked by ONE
     * @param s The instance holding all the settings
     */
    public BFGRouter(Settings s){
        super(s);
        Settings bfgSettings = new Settings(BFG_NS);
        degradationInterval = bfgSettings.getDouble(SETTINGS_DEG_INTERVAL, 5.0);
        degradationProbability = bfgSettings.getDouble(SETTINGS_DEG_PROBABILITY, 0.5);
        forwardThreshold = bfgSettings.getDouble(SETTINGS_FORWARD_THRESHOLD, 0.5);
        forwardStrategy = bfgSettings.getInt(SETTINGS_FORWARD_STRATEGY, 1);

        if(forwardStrategy == 5){
            firstThreshold = bfgSettings.getDouble(SETTINGS_THRESHOLD_1, 0.1);
        }

        bfCounters = bfgSettings.getInt(SETTINGS_BF_COUNTERS, 64);
        bfHashFunctions = bfgSettings.getInt(SETTINGS_BF_HASH_FUNCTIONS, 6);
        bfMaxCount = bfgSettings.getInt(SETTINGS_BF_MAX_COUNT, 32);

        this.F_STAR = new BloomFilter<Integer>(bfCounters, bfHashFunctions, bfMaxCount);
        this.Ft = new BloomFilter<Integer>(bfCounters, bfHashFunctions, bfMaxCount);

        lastDegradation = 0.0;
        creationTime = SimClock.getTime();
    }

    /**
     * Copy constructor. Creates a new instance with all the same values
     * @param r The original instance to copy from
     */
    protected BFGRouter(BFGRouter r){
        super(r);
        this.F_STAR = new BloomFilter<Integer>(r.F_STAR);
        this.Ft = new BloomFilter<Integer>(r.Ft);
        this.degradationInterval = r.degradationInterval;
        this.degradationProbability = r.degradationProbability;
        this.forwardThreshold = r.forwardThreshold;
        this.forwardStrategy = r.forwardStrategy;

        this.bfCounters = r.bfCounters;
        this.bfMaxCount = r.bfMaxCount;
        this.bfHashFunctions = r.bfHashFunctions;
        creationTime = SimClock.getTime();
    }


    /**
     * Initializes this router object with the host information. If you need the node's identity for any initialization,
     * that should be made here
      * @param host
     * @param mListeners
     */
    @Override
    public void init(DTNHost host, List<MessageListener> mListeners){
        super.init(host, mListeners);
        initializeBloomFilters();

        if (this.Ft == null) throw new AssertionError();
        if (this.F_STAR == null) throw new AssertionError();
    }

    /**
     * Creates a copy from this instance
     * @return The copy of this instance
     */
    @Override
    public MessageRouter replicate() {
        return new BFGRouter(this);
    }

    /**
     * Adds the necessary data to the filters
     */
    public void initializeBloomFilters(){
        Integer thisHost = new Integer(getHost().getAddress());
        this.Ft.add(thisHost);//Add the identity of the node this router is in
        this.F_STAR.add(thisHost);
    }


    /**
     * Updates the router
     * This method should be called at least once
     */
    @Override
    public void update() {
        super.update();
        degradationTimer();

        if (!canStartTransfer() ||isTransferring()) {
            return; // nothing to transfer or is currently transferring
        }
        //Direct transfer of messages between this node and its connected neighbors
        if (exchangeDeliverableMessages() != null) {
            return; //If a connection started a transfer end this update
        }
        forwardMessages();
    }

    /**
     * Provides information about the internal state of this router, e.g. routing tables or deliverability probabilities
     * @return The Routing information object of this router
     */
    @Override
    public RoutingInfo getRoutingInfo(){
        RoutingInfo top = super.getRoutingInfo(); //Get the information of ActiveRouter and MessageRouter
        RoutingInfo localBF = new RoutingInfo("BloomFilter: " + this.Ft.toString());

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for(Integer i : F_STAR.hashesFor(getHost().getAddress())){
            sb.append(i);
            sb.append(",");
        }
        sb.deleteCharAt(sb.length() -1);
        sb.append("]");
        RoutingInfo myID = new RoutingInfo("myBFid: " + sb.toString());

        top.addMoreInfo(localBF);
        top.addMoreInfo(myID);
        return top;
    }

    /**
     * Called when a connection state in this node changes
     * @param con The connection whose state changed
     */
    @Override
    public void changedConnection(Connection con){
        if(con.isUp()){
            exchangeBloomFilters(con);
        }
    }


    /**
     * Whenever this function is called, checks the current time and compares if at least {degradationInterval} seconds
     * degrades the filter
     */
    private void degradationTimer(){
        double now = SimClock.getTime();
        if(now - lastDegradation >= degradationInterval){
            /**Degrade the filter**/
            this.Ft.stochasticDegrade(degradationProbability);
            try {
                this.Ft.join(F_STAR);
            } catch (InvalidObjectException e) {
                log(e.getMessage());
            }
            lastDegradation = now;
        }
    }

    /**
     * Exchanges periodically the Bloom filters in all available connections. Implemented for static nodes that keep
     * an always-active connection
     */
    private void helloTimer(){
        double now = SimClock.getTime();
        if(now - lastBFExchange >= (degradationInterval + 2.0)){ //Separate the Bloom filter exchange at least one second
            for(Connection con : getConnections()){
                if(con.isUp()){
                    exchangeBloomFilters(con);
                }
            }
        }

    }


    /**
     * Exchange routing information between two nodes in a Connection object
     * @param con
     */
    private void exchangeBloomFilters(Connection con){
        DTNHost neighbor = con.getOtherNode(getHost());
        if(! (neighbor.getRouter() instanceof  BFGRouter)){
            log("Error: This protocol can not communicate with other type of Router");
            return;
        }
        BFGRouter other = (BFGRouter) neighbor.getRouter();
        BloomFilter<Integer> Fit = new BloomFilter<Integer>(other.Ft);
        Fit.stochasticDegrade(degradationProbability);

        try {
            this.Ft.join(Fit);
        } catch (InvalidObjectException e) {
            log(e.getMessage());
        }
    }


    /**
     * Tries to send all the messages to a node that it's a good candidate to forward, ordered by the delivery
     * probability for each destination
     */
    private Tuple<Message, Connection> forwardMessages(){
        List<Tuple<Message, Connection>> messages = new ArrayList<Tuple<Message, Connection>>();
        Collection<Message> msgCollection = getMessageCollection();

        //For each active connection
        for(Connection con : getConnections()){
            DTNHost neighbor = con.getOtherNode(getHost());
            BFGRouter neighborRouter  = (BFGRouter) neighbor.getRouter();

            if(neighborRouter.isTransferring()) continue; //Skip transferring nodes

            //Check all the messages
            for(Message m : msgCollection){
                if(neighborRouter.hasMessage(m.getId())) continue; //Skip messages already on the neighbor

                double Pri = probabilityTo(m.getTo());
                double Prj = probabilityThrough(m.getTo(), neighborRouter.Ft);

                switch(forwardStrategy){
                    case 1:
                        if(Prj >= Pri || Prj == 1.0)
                            messages.add(new Tuple<>(m, con));
                        break;
                    case 2:
                        if(Prj >= Pri + forwardThreshold || Prj == 1.0)
                            messages.add(new Tuple<>(m, con));
                        break;
                    case 3:
                        if(Prj >= (Pri * (1.0 + forwardThreshold)) || Prj == 1.0)
                            messages.add(new Tuple<>(m, con));
                        break;
                    case 4:
                        if(Pri <= forwardThreshold || Prj >= Pri + forwardThreshold || Prj == 1.0)
                            messages.add(new Tuple<>(m, con));
                        break;
                    case 5:
                        //Behaviour based on three levels of proximity to the destination
                        if(0 <= Pri && Pri < firstThreshold ){ //If there's no probabilistic state, do Epidemic
                            messages.add(new Tuple<>(m, con));
                        }else {//Hill-climbing transmission
                            if (Prj >= (Pri * (1.0 + forwardThreshold)) || Prj == 1.0) {
                                messages.add(new Tuple<>(m, con));
                                deleteMessage(m.getId(), true);
                            }
                        }
                        break;
                    default:
                        log("Illegal forwarding strategy");
                        System.exit(1);
                        break;
                }
            }
        }

        return tryMessagesForConnected(messages);
    }

    /**
     * Calculates the probaility to reach {destination} through a particular node given its internal Bloom filter
     * @param destination The destination host we want to reach
     * @param intermediate Ft (Bloom filter) of the intermediate node
     * @return The probability
     */
    private double probabilityThrough(DTNHost destination, BloomFilter<Integer> intermediate){
        double Pr = 1.0;
        for(Integer i : intermediate.hashesFor(destination.getAddress())){
            try {
                Pr *= intermediate.counterAt(i);
            }catch (IndexOutOfBoundsException e){
                log("Error accessing counter " + i + " in filter");
                log("Caused in node " + getHost().toString() + " while calculating for " + destination);
            }
        }
        return Pr /(Math.pow(bfMaxCount, bfHashFunctions));
    }

    /**
     * A simple function wrapper
     * @param destination The destination node
     * @return The probability that this node has to reach the {destination}
     */
    private double probabilityTo(DTNHost destination){
        return probabilityThrough(destination, this.Ft);
    }

    private void log(String msg){
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        String function = stack[2].getMethodName();
        String file = stack[2].getFileName();
        int line = stack[2].getLineNumber();

        String formatString = "%f {%s} %s:%d %s";
        String message = String.format(formatString, SimClock.getTime(), function, file, line, msg);
        System.out.println(message);
    }

    public String serializeInfo(){
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"m\":" + this.bfCounters + ",");
        sb.append("\"k\":" + this.bfHashFunctions + ",");
        sb.append("\"c\":" + this.bfMaxCount + ",");
        sb.append("\"myID\":" + this.F_STAR.hashesFor(getHost().getAddress()) + ",");
        sb.append("\"Ft\":" + this.Ft.toReducedString());
        sb.append("}");
        return sb.toString();
    }
}
