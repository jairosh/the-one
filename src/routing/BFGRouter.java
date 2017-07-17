package routing;

import core.*;
import org.ipn.cic.ndsrg.BloomFilter;
import routing.util.RoutingInfo;
import util.Tuple;

import javax.swing.text.html.HTMLDocument;
import java.io.InvalidObjectException;
import java.util.*;

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
    private double zoneThreshold;


    /**
     * Bloom filter parameters
     */
    private int bfCounters;      //m
    private int bfHashFunctions; //k
    private int bfMaxCount;      //c

    /**
     * Indicates how many copies disseminate the original node when there's no probabilistic state
     */
    private int initialCopies;

    /**
     * Stores a list of how many copies has to be redistributed
     */
    private HashMap<String, Integer> copiesLeft;

    /**
     * Keeps track of the time when degradation was made, this provides a rudimentary Timer
     */
    private double lastDegradation;

    //List of messages that can be dropped once the transfer finishes
    private List<String> toBeDropped;

    public static final String BFG_NS = "BFGRouter";
    public static final String SETTINGS_DEG_INTERVAL = "degradationInterval";
    public static final String SETTINGS_DEG_PROBABILITY = "degradationProbability";
    public static final String SETTINGS_FORWARD_THRESHOLD = "forwardingThreshold";
    public static final String SETTINGS_FORWARD_STRATEGY = "forwardStrategy";
    public static final String SETTINGS_ZONE_THRESHOLD = "zoneThreshold";
    public static final String SETTINGS_BF_COUNTERS = "BFCounters";
    public static final String SETTINGS_BF_HASH_FUNCTIONS = "BFHashFunctions";
    public static final String SETTINGS_BF_MAX_COUNT = "BFMaxCount";
    public static final String SETTINGS_NUMBER_OF_COPIES = "initialCopies";

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
        forwardStrategy = bfgSettings.getInt(SETTINGS_FORWARD_STRATEGY, 1);

        if(forwardStrategy == 5){
            zoneThreshold = bfgSettings.getDouble(SETTINGS_ZONE_THRESHOLD, 0.1);
            this.initialCopies = bfgSettings.getInt(SETTINGS_NUMBER_OF_COPIES, 8);
            this.copiesLeft = new HashMap<>();
        }

        if(forwardStrategy != 5){
            forwardThreshold = bfgSettings.getDouble(SETTINGS_FORWARD_THRESHOLD, 0.5);
        }

        bfCounters = bfgSettings.getInt(SETTINGS_BF_COUNTERS, 64);
        bfHashFunctions = bfgSettings.getInt(SETTINGS_BF_HASH_FUNCTIONS, 6);
        bfMaxCount = bfgSettings.getInt(SETTINGS_BF_MAX_COUNT, 32);

        this.F_STAR = new BloomFilter<Integer>(bfCounters, bfHashFunctions, bfMaxCount);
        this.Ft = new BloomFilter<Integer>(bfCounters, bfHashFunctions, bfMaxCount);

        lastDegradation = 0.0;
        creationTime = SimClock.getTime();
        toBeDropped = new ArrayList<>();
        printParameters();
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
        if(this.forwardStrategy == 5) {
            this.zoneThreshold = r.zoneThreshold;
            this.initialCopies = r.initialCopies;
            this.copiesLeft = new HashMap<>();
        }

        this.lastDegradation = r.lastDegradation;

        this.bfCounters = r.bfCounters;
        this.bfMaxCount = r.bfMaxCount;
        this.bfHashFunctions = r.bfHashFunctions;
        toBeDropped = new ArrayList<>();
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
            //One node in the connection does all the work
            if(con.isInitiator(getHost())){
                DTNHost neighbor = con.getOtherNode(getHost());
                if(! (neighbor.getRouter() instanceof  BFGRouter)){
                    log("Error: This protocol can not communicate with other type of Router");
                    return;
                }

                BFGRouter neighborRouter = (BFGRouter) neighbor.getRouter();
                //Create a copy of each node's filter
                BloomFilter<Integer> Fjt = new BloomFilter<Integer>(neighborRouter.Ft);
                BloomFilter<Integer> Fit = new BloomFilter<Integer>(this.Ft);
                //Degrade the information in that filters
                Fjt.stochasticDegrade(degradationProbability);
                Fit.stochasticDegrade(degradationProbability);

                try {
                    //Incorporate the information into each other's filters
                    this.Ft.join(Fjt);
                    neighborRouter.Ft.join(Fit);
                } catch (InvalidObjectException e) {
                    log(e.getMessage());
                }
            }
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

        //For each message
        for(Message m : msgCollection){
            //Check all the connections to send it (may be dropped)
            for(Connection con : getConnections()){
                DTNHost neighbor = con.getOtherNode(getHost());
                BFGRouter neighborRouter  = (BFGRouter) neighbor.getRouter();

                if(neighborRouter.isTransferring()) continue; //Skip transferring nodes
                if(neighborRouter.hasMessage(m.getId())) {
                    continue; //Skip messages already on the neighbor
                }

                double Pri = probabilityTo(m.getTo());
                double Prj = neighborRouter.probabilityTo(m.getTo());

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
                        if(Prj > 0){
                            if(Prj >= (Pri * (1.0 + forwardThreshold)) || Prj == 1.0) {
                                messages.add(new Tuple<>(m, con));
                            }
                        }
                        break;
                    case 4:
                        if(Pri <= forwardThreshold || Prj >= Pri + forwardThreshold || Prj == 1.0)
                            messages.add(new Tuple<>(m, con));
                        break;
                    case 6:
                        //Store if this node is the origin of the current packet
                        boolean isOrigin = m.getFrom().equals(getHost());
                        if(isOrigin){
                            if(Pri < this.zoneThreshold){
                                Integer copies = this.initialCopies;
                                if(this.copiesLeft.containsKey(m.getId())){
                                    copies = this.copiesLeft.get(m.getId());
                                    if(copies == 0)
                                        break;
                                }
                                //Disseminate up to ${initialCopies} copies of the message
                                messages.add(new Tuple<>(m,con));
                                copiesLeft.put(m.getId(), copies-1);
                            }else{
                                //There's already probabilistic state towards destination, transfer only to a better node
                                if(Prj > Pri) {
                                    messages.add(new Tuple<>(m, con));
                                    toBeDropped.add(m.getId());
                                }
                            }
                        }else{
                            //Intermediate nodes
                            if(Prj > Pri) {
                                messages.add(new Tuple<>(m, con));
                                toBeDropped.add(m.getId());
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

        /*There's no messages to be forwarded*/
        if(messages.size() == 0){
            return null;
        }

        /*Sort the Message/Connection pairs by their deliver probability*/
        //Collections.sort(messages, new MessageConnectionComparator());
        return tryMessagesForConnected(messages);
    }

    @Override
    protected void transferDone(Connection con){
        if(this.forwardStrategy == 5){
            Message msg = con.getMessage();
            if(toBeDropped.contains(msg.getId())){
                this.deleteMessage(msg.getId(), true);
                toBeDropped.remove(msg.getId());
            }
        }
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
     * Compares two neighbors of this node,  L and J are by definition, better carriers for a packet than this node,
     * this decides who gets bigger priority according to their delivery probability
     */
    private class MessageConnectionComparator implements Comparator<Tuple<Message, Connection>>{
        @Override
        public int compare(Tuple<Message, Connection> L, Tuple<Message, Connection> J) {
            // delivery probability of tuple1's message with tuple1's connection
            BFGRouter routerL = (BFGRouter)L.getValue().getOtherNode(getHost()).getRouter();
            BFGRouter routerJ = (BFGRouter)J.getValue().getOtherNode(getHost()).getRouter();

            double PrJ = routerJ.probabilityTo(J.getKey().getTo());
            double PrL = routerL.probabilityTo(L.getKey().getTo());

            if(PrJ - PrL == 0){
                return compareByQueueMode(L.getKey(), J.getKey());
            }else if (PrJ-PrL < 0) {
                return -1;
            }else{
                return 1;
            }
        }
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

        String formatString = "%f [%s] {%s} %s:%d %s";
        String message = String.format(formatString, SimClock.getTime(), getHost().toString(), function, file, line, msg);
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

    protected void printParameters(){
        StringBuffer sb = new StringBuffer();
        sb.append("Degradation interval: " + this.degradationInterval + "\n");
        sb.append("Degradation probability: " + this.degradationProbability + "\n");
        sb.append("Forwarding Threshold: " + this.forwardThreshold + "\n");
        sb.append("Forward strategy: " + this.forwardStrategy + "\n");
        if(this.forwardStrategy == 5) {
            sb.append("Zone threshold: " + this.zoneThreshold + "\n");
            sb.append("Initial copies: " + this.initialCopies + "\n");
        }
        sb.append("Bloom Filter params: [m=" + this.bfCounters + ", k=" + this.bfHashFunctions + ", c=" +this.bfMaxCount + "]\n");
        System.out.print(sb.toString());
    }
}
