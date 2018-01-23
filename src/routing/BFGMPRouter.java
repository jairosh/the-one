/*
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details.
 */
package routing;

import java.io.InvalidObjectException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import core.*;
import org.ipn.cic.ndsrg.BloomFilter;
import routing.maxprop.MaxPropDijkstra;
import routing.maxprop.MeetingProbabilitySet;
import routing.util.RoutingInfo;
import util.Tuple;

public class BFGMPRouter extends ActiveRouter {
	/** Router's setting namespace ({@value})*/
	public static final String SETTINGS_NS = "BFGMPRouter";

	/** IDs of the messages that are known to have reached the final dst */
	private Set<String> ackedMessageIds;
	/** mapping of the current costs for all messages. This should be set to
	 * null always when the costs should be updated (a host is met or a new
	 * message is received) */

	/** Map of which messages have been sent to which hosts from this host */
	private Map<DTNHost, Set<String>> sentMessages;


    /**
     * ************************************************************************
     */
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
     * Zone Threshold
     */
    private double zoneThreshold;
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

    /**
     * Stores the hashes for each node, due to FNV Hash being too expensive
     */
    private HashMap<DTNHost, List<Integer>> identityCache;

    /**
     * Sigmoid weight parameters
     */
    private Double weightAlpha;
    private Double weightBeta;
    private Double weightDelta;
    private Double weightGamma;


    public static final String DEG_INTERVAL = "degradationInterval";
    public static final String BF_COUNTERS = "BFCounters";
    public static final String BF_HASH_FUNCTIONS = "BFHashFunctions";
    public static final String BF_MAX_COUNT = "BFMaxCount";
    public static final String ZONE_THRESHOLD = "zoneThreshold";
    public static final String WEIGHT_ALPHA = "weightAlpha";
    public static final String WEIGHT_BETA = "weightBeta";
    public static final String WEIGHT_GAMMA = "weightGamma";
    public static final String WEIGHT_DELTA = "weightDelta";


	/**
	 * Constructor. Creates a new prototype router based on the settings in
	 * the given Settings object.
	 * @param settings The settings object
	 */
	public BFGMPRouter(Settings settings) {
		super(settings);
		Settings routerSettings = new Settings(SETTINGS_NS);

		//Bloom filters configuration
        degradationInterval = routerSettings.getDouble(DEG_INTERVAL, 300.0);
        bfCounters = routerSettings.getInt(BF_COUNTERS, 64);
        bfHashFunctions = routerSettings.getInt(BF_HASH_FUNCTIONS, 6);
        bfMaxCount = routerSettings.getInt(BF_MAX_COUNT, 32);
        zoneThreshold = routerSettings.getDouble(ZONE_THRESHOLD, 0.5);

        //Sigmoid parameters
        weightAlpha = routerSettings.getDouble(WEIGHT_ALPHA);
        weightBeta = routerSettings.getDouble(WEIGHT_BETA);
        weightDelta = routerSettings.getDouble(WEIGHT_DELTA);
        weightGamma = routerSettings.getDouble(WEIGHT_GAMMA);

        this.F_STAR = new BloomFilter<Integer>(bfCounters, bfHashFunctions, bfMaxCount);
        this.Ft = new BloomFilter<Integer>(bfCounters, bfHashFunctions, bfMaxCount);

        lastDegradation = 0.0;
        identityCache = new HashMap<DTNHost, List<Integer>>();

    }

	/**
	 * Copy constructor. Creates a new router based on the given prototype.
	 * @param r The router prototype where setting values are copied from
	 */
	protected BFGMPRouter(BFGMPRouter r) {
		super(r);
		this.ackedMessageIds = new HashSet<String>();
		this.sentMessages = new HashMap<DTNHost, Set<String>>();

		this.degradationInterval = r.degradationInterval;
		this.bfCounters = r.bfCounters;
		this.bfHashFunctions = r.bfHashFunctions;
		this.bfMaxCount = r.bfMaxCount;
		this.zoneThreshold = r.zoneThreshold;

		this.weightAlpha = r.weightAlpha;
		this.weightBeta = r.weightBeta;
		this.weightDelta = r.weightDelta;
		this.weightGamma = r.weightGamma;

		this.F_STAR = new BloomFilter<Integer>(r.F_STAR);
		this.Ft = new BloomFilter<Integer>(r.Ft);
		this.lastDegradation = r.lastDegradation;
		this.identityCache = new HashMap<DTNHost, List<Integer>>(r.identityCache);
	}

	@Override
    public void init(DTNHost host, List<MessageListener> messageListeners){
        super.init(host, messageListeners);
        Integer thisHost = getHost().getAddress();
        this.Ft.add(thisHost);//Add the identity of the node this router is in
        this.F_STAR.add(thisHost);

        if (this.Ft == null) throw new AssertionError();
        if (this.F_STAR == null) throw new AssertionError();
    }

	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);

		if (con.isUp()) { // new connection

			if (con.isInitiator(getHost())) {
				/* initiator performs all the actions on behalf of the
				 * other node too (so that the meeting probs are updated
				 * for both before exchanging them) */
				DTNHost otherHost = con.getOtherNode(getHost());
				MessageRouter mRouter = otherHost.getRouter();

				assert mRouter instanceof BFGMPRouter : "BFGMP only works  with other routers of same type";
				BFGMPRouter otherRouter = (BFGMPRouter)mRouter;

				/* exchange ACKed message data */
				this.ackedMessageIds.addAll(otherRouter.ackedMessageIds);
				otherRouter.ackedMessageIds.addAll(this.ackedMessageIds);
				deleteAckedMessages();
				otherRouter.deleteAckedMessages();


                //Bloom filter exchange
                //Create a copy of each node's filter
                BloomFilter<Integer> Fjt = new BloomFilter<Integer>(otherRouter.Ft);
                BloomFilter<Integer> Fit = new BloomFilter<Integer>(this.Ft);
                //Degrade the information in that filters
                Fjt.deterministicDegradation();
                Fit.deterministicDegradation();

                try {
                    //Incorporate the information into each other's filters
                    this.Ft.join(Fjt);
                    otherRouter.Ft.join(Fit);
                } catch (InvalidObjectException e) {
                    System.out.println(e.getMessage());
                }
			}
		}
		//else {
			/* connection went down, update transferred bytes average */

		//}
	}

    /**
     * Whenever this function is called, checks the current time and compares if at least {degradationInterval} seconds
     * degrades the filter
     */
    private void degradationTimer(){
        double now = SimClock.getTime();
        if(now - lastDegradation >= degradationInterval){
            /**Degrade the filter**/
            this.Ft.deterministicDegradation();
            try {
                this.Ft.join(F_STAR);
            } catch (InvalidObjectException e) {
                System.out.println(e.getMessage());
            }
            lastDegradation = now;
        }
    }


	/**
	 * Deletes the messages from the message buffer that are known to be ACKed
	 */
	private void deleteAckedMessages() {
		for (String id : this.ackedMessageIds) {
			if (this.hasMessage(id) && !isSending(id)) {
				this.deleteMessage(id, false);
			}
		}
	}

	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message m = super.messageTransferred(id, from);
		/* was this node the final recipient of the message? */
		if (isDeliveredMessage(m)) {
			this.ackedMessageIds.add(id);
		}
		return m;
	}

	/**
	 * Method is called just before a transfer is finalized
	 * at {@link ActiveRouter#update()}. MaxProp makes book keeping of the
	 * delivered messages so their IDs are stored.
	 * @param con The connection whose transfer was finalized
	 */
	@Override
	protected void transferDone(Connection con) {
		Message m = con.getMessage();
		String id = m.getId();
		DTNHost recipient = con.getOtherNode(getHost());
		Set<String> sentMsgIds = this.sentMessages.get(recipient);

		/* was the message delivered to the final recipient? */
		if (m.getTo() == recipient) {
			this.ackedMessageIds.add(m.getId()); // yes, add to ACKed messages
			this.deleteMessage(m.getId(), false); // delete from buffer
		}

		/* update the map of where each message is already sent */
		if (sentMsgIds == null) {
			sentMsgIds = new HashSet<String>();
			this.sentMessages.put(recipient, sentMsgIds);
		}
		sentMsgIds.add(id);
	}



	/**
	 * Returns the next message that should be dropped, according to MaxProp's
	 * message ordering scheme (see MaxPropTupleComparator).
	 * @param excludeMsgBeingSent If true, excludes message(s) that are
	 * being sent from the next-to-be-dropped check (i.e., if next message to
	 * drop is being sent, the following message is returned)
	 * @return The oldest message or null if no message could be returned
	 * (no messages in buffer or all messages in buffer are being sent and
	 * exludeMsgBeingSent is true)
	 */
	@Override
	protected Message getNextMessageToRemove(boolean excludeMsgBeingSent) {
		Collection<Message> messages = this.getMessageCollection();
		List<Message> validMessages = new ArrayList<Message>();

		for (Message m : messages) {
			if (excludeMsgBeingSent && isSending(m.getId())) {
				continue; // skip the message(s) that router is sending
			}
			validMessages.add(m);
		}

		Collections.sort(validMessages, new BloomFilterMessageComparator());


		return validMessages.get(validMessages.size()-1); // return last message
	}

	@Override
	public void update() {
		super.update();

		degradationTimer();

		if (!canStartTransfer() ||isTransferring()) {
			return; // nothing to transfer or is currently transferring
		}

		// try messages that could be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return;
		}

		tryOtherMessages();
	}


    /**
     * Calculate the delivery probability of one host to reach another host, based on the internal Bloom filter
     * @param from The origin node, the probability depends on the Bloom filter of this node
     * @param to The destination node, only the identity of the node is used
     * @return The delivery probability at the origin node
     */
	public double bloomFilterDeliveryProbability(DTNHost from, DTNHost to){
	    MessageRouter mr = from.getRouter();
	    assert mr instanceof BFGMPRouter : "Incorrect router module";
        BloomFilter<Integer> Fit = new BloomFilter<Integer>(((BFGMPRouter) mr).Ft);

        double Pr = 1.0;
        List<Integer> theHashes = identityCache.get(to);
        if( theHashes == null ){
            theHashes = Fit.hashesFor(to.getAddress());
            this.identityCache.put(to, theHashes);
        }
        for(Integer i : theHashes){
            try {
                Pr *= Fit.counterAt(i);
            }catch (IndexOutOfBoundsException e){
                System.out.println("Error accessing counter " + i + " in filter");
                System.out.println("Caused in node " + getHost().toString() + " while calculating for " + to);
            }
        }
        return Pr /(Math.pow(bfMaxCount, bfHashFunctions));
    }

	/**
	 * Tries to send all other messages to all connected hosts ordered by
	 * hop counts and their delivery probability
	 * @return The return value of {@link #tryMessagesForConnected(List)}
	 */
	private Tuple<Message, Connection> tryOtherMessages() {
		List<Tuple<Message, Connection>> messages =
				new ArrayList<Tuple<Message, Connection>>();

		Collection<Message> msgCollection = getMessageCollection();

		/* for all connected hosts that are not transferring at the moment,
		 * collect all the messages that could be sent */
		for (Connection con : getConnections()) {
			DTNHost other = con.getOtherNode(getHost());
			BFGMPRouter othRouter = (BFGMPRouter)other.getRouter();
			Set<String> sentMsgIds = this.sentMessages.get(other);

			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}

			for (Message m : msgCollection) {
				/* skip messages that the other host has or that have
				 * passed the other host */
				if (othRouter.hasMessage(m.getId()) ||
						m.getHops().contains(other)) {
					continue;
				}
				/* skip message if this host has already sent it to the other
				   host (regardless of if the other host still has it) */
				if (sentMsgIds != null && sentMsgIds.contains(m.getId())) {
					continue;
				}
				/* Calculate if the message is worth of transmitting */
				Double rho = rho(getFilterSaturation());
                Double Pr_neighbor = bloomFilterDeliveryProbability(other, m.getTo());
                Double Pr_local = bloomFilterDeliveryProbability(getHost(), m.getTo());
                if(Pr_neighbor - rho > Pr_local){
                    messages.add(new Tuple<Message, Connection>(m, con));
                }

			}
		}

		if (messages.size() == 0) {
			return null;
		}

		Collections.sort(messages, new BloomFilterQueueComparator());
		//printTupleList(messages);

		return tryMessagesForConnected(messages);
	}



	@Override
	public RoutingInfo getRoutingInfo() {
		RoutingInfo top = super.getRoutingInfo();
		RoutingInfo ri = new RoutingInfo("Saturation: " + getFilterSaturation());

		top.addMoreInfo(ri);

		return top;
	}

	@Override
	public MessageRouter replicate() {
		BFGMPRouter r = new BFGMPRouter(this);
		return r;
	}


	public void printTupleList(List<Tuple<Message, Connection>> list){
	    if(list.size() == 0 )
	        return;

        DecimalFormat dFormat = new DecimalFormat("0.000");
	    System.out.println("----------------------Node " + getHost().toString() + "@" + SimClock.getTime() + "---------------------");
	    System.out.println(String.format("%-8s%-8s%-8s%-8s%-8s%-8s", "MSG", "NHop", "Pri", "Prj", "Δ", "hops"));
	    //System.out.println("MSG\tNHop\tPri\t\tPrj\t\tΔ\thops1\thops2");
	    for( Tuple<Message, Connection> entry : list){
	        Message m = entry.getKey();
	        Connection c = entry.getValue();
            StringBuffer sb = new StringBuffer();

            sb.append(String.format("%-8s%-8s", m.getId(), c.getOtherNode(getHost())));

            double pr = bloomFilterDeliveryProbability(getHost(), m.getTo());
            sb.append(String.format("%-8s", dFormat.format(pr)));

            pr = bloomFilterDeliveryProbability(c.getOtherNode(getHost()), m.getTo());
            sb.append(String.format("%-8s", dFormat.format(pr)));

            double d1 = delta(m, c.getOtherNode(getHost()));
            sb.append(String.format("%-8s", dFormat.format(d1)));

            sb.append(String.format("%-8s", m.getHopCount()));
            System.out.println(sb.toString());
        }
        System.out.println("=========================================================================");
    }


    private class BloomFilterMessageComparator implements Comparator<Message>{

	    private DTNHost from1;
	    private DTNHost from2;

	    public BloomFilterMessageComparator(DTNHost from1, DTNHost from2){
	        this.from1 = from1;
	        this.from2 = from2;
        }

        public BloomFilterMessageComparator(){
	        this.from1 = this.from2 = getHost();
        }

        /**
         * Compares two messages for overall priority based on the delivery probability of the nodes (if supplied)
         * @param m1
         * @param m2
         * @return Returns -1 if m1<m2, zero if m1=m2, or 1 if m1>m2.
         */
        @Override
        public int compare(Message m1, Message m2) {
        	final double lambda = 0.4;
	        double pri, prj;
	        int hopCount1 = m1.getHopCount();
	        int hopCount2 = m2.getHopCount();

            //If the messages have the same hop count, then compare by delivery probability
            pri = bloomFilterDeliveryProbability(from1, m1.getTo());
            prj = bloomFilterDeliveryProbability(from2, m2.getTo());

            //double delta1 = delta(m1, from1);
            //double delta2 = delta(m2, from2);

            double beta1 = (lambda*pri) + (1-lambda)*(Math.exp(-hopCount1));
			double beta2 = (lambda*prj) + (1-lambda)*(Math.exp(-hopCount2));

            if(beta2 > beta1){
                return 1;
            }else if(beta2 == beta1){
                //if(hopCount2 < hopCount1){
				if(prj > pri) {
					return 1;
				}else if(hopCount2 < hopCount1){
					return 1;
                }else if(hopCount1 == hopCount2){
                    return compareByQueueMode(m1, m2);
                }
            }
            return -1;
        }
    }


    private class BloomFilterQueueComparator implements Comparator<Tuple<Message, Connection>>{
        @Override
        public int compare(Tuple<Message, Connection> tuple1, Tuple<Message, Connection> tuple2) {
            BloomFilterMessageComparator comp;
            DTNHost from1 = tuple1.getValue().getOtherNode(getHost());
            DTNHost from2 = tuple2.getValue().getOtherNode(getHost());

            comp = new BloomFilterMessageComparator(from1, from2);
            Message m1 = tuple1.getKey();
            Message m2 = tuple2.getKey();
            return comp.compare(m1, m2);
        }
    }

	/**
	 * Calculates the delta for the delivery probability for a particular message, between this node and a neighbor
	 * @param m The message
	 * @param neighbor
	 * @return The delta, contained between [-1, 1]
	 */
    private double delta(Message m, DTNHost neighbor){
		DTNHost to = m.getTo();
		double pri = bloomFilterDeliveryProbability(getHost(), to);
		double prj = bloomFilterDeliveryProbability(neighbor, to);
		return (prj-pri);
	}

	public Double getFilterSaturation(){
    	Double sum = 0.0;
    	for(int i=0; i<this.bfCounters; i++){
    		sum += this.Ft.counterAt(i);
		}
		return sum / (1.0*bfMaxCount*bfCounters);
	}

	public Double rho(Double saturation){
        Double weight = (weightAlpha * Math.tanh(weightBeta * (saturation - weightDelta))) - weightGamma;
        return weight;
    }
}
