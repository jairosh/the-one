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
	/**
	 * Meeting probability set maximum size -setting id ({@value}).
	 * The maximum amount of meeting probabilities to store.  */
	public static final String PROB_SET_MAX_SIZE_S = "probSetMaxSize";
	/** Default value for the meeting probability set maximum size ({@value}).*/
	public static final int DEFAULT_PROB_SET_MAX_SIZE = 50;
	private static int probSetMaxSize;

	/** probabilities of meeting hosts */
	private MeetingProbabilitySet probs;
	/** meeting probabilities of all hosts from this host's point of view
	 * mapped using host's network address */
	private Map<Integer, MeetingProbabilitySet> allProbs;
	/** the cost-to-node calculator */
	private MaxPropDijkstra dijkstra;
	/** IDs of the messages that are known to have reached the final dst */
	private Set<String> ackedMessageIds;
	/** mapping of the current costs for all messages. This should be set to
	 * null always when the costs should be updated (a host is met or a new
	 * message is received) */
	private Map<Integer, Double> costsForMessages;
	/** From host of the last cost calculation */
	private DTNHost lastCostFrom;

	/** Map of which messages have been sent to which hosts from this host */
	private Map<DTNHost, Set<String>> sentMessages;

	/** Over how many samples the "average number of bytes transferred per
	 * transfer opportunity" is taken */
	public static int BYTES_TRANSFERRED_AVG_SAMPLES = 10;
	private int[] avgSamples;
	private int nextSampleIndex = 0;
	/** current value for the "avg number of bytes transferred per transfer
	 * opportunity"  */
	private int avgTransferredBytes = 0;

	/** The alpha parameter string*/
	public static final String ALPHA_S = "alpha";

	/** The alpha variable, default = 1;*/
	private double alpha;

	/** The default value for alpha */
	public static final double DEFAULT_ALPHA = 1.0;


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
    private boolean pureMaxPROP;

    public static final String DEG_INTERVAL = "degradationInterval";
    public static final String BF_COUNTERS = "BFCounters";
    public static final String BF_HASH_FUNCTIONS = "BFHashFunctions";
    public static final String BF_MAX_COUNT = "BFMaxCount";
    public static final String ZONE_THRESHOLD = "zoneThreshold";
    public static final String PURE_MAXPROP = "PureMaxPROP";


	/**
	 * Constructor. Creates a new prototype router based on the settings in
	 * the given Settings object.
	 * @param settings The settings object
	 */
	public BFGMPRouter(Settings settings) {
		super(settings);
		Settings routerSettings = new Settings(SETTINGS_NS);
		if (routerSettings.contains(ALPHA_S)) {
			alpha = routerSettings.getDouble(ALPHA_S);
		} else {
			alpha = DEFAULT_ALPHA;
		}

		if (routerSettings.contains(PROB_SET_MAX_SIZE_S)) {
			probSetMaxSize = routerSettings.getInt(PROB_SET_MAX_SIZE_S);
		} else {
			probSetMaxSize = DEFAULT_PROB_SET_MAX_SIZE;
		}

		//Bloom filters configuration
        degradationInterval = routerSettings.getDouble(DEG_INTERVAL, 300.0);
        bfCounters = routerSettings.getInt(BF_COUNTERS, 64);
        bfHashFunctions = routerSettings.getInt(BF_HASH_FUNCTIONS, 6);
        bfMaxCount = routerSettings.getInt(BF_MAX_COUNT, 32);
        pureMaxPROP = routerSettings.getBoolean(PURE_MAXPROP, false);
        zoneThreshold = routerSettings.getDouble(ZONE_THRESHOLD, 0.5);

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
		this.alpha = r.alpha;
		this.probs = new MeetingProbabilitySet(probSetMaxSize, this.alpha);
		this.allProbs = new HashMap<Integer, MeetingProbabilitySet>();
		this.dijkstra = new MaxPropDijkstra(this.allProbs);
		this.ackedMessageIds = new HashSet<String>();
		this.avgSamples = new int[BYTES_TRANSFERRED_AVG_SAMPLES];
		this.sentMessages = new HashMap<DTNHost, Set<String>>();

		this.degradationInterval = r.degradationInterval;
		this.bfCounters = r.bfCounters;
		this.bfHashFunctions = r.bfHashFunctions;
		this.bfMaxCount = r.bfMaxCount;
		this.pureMaxPROP = r.pureMaxPROP;
		this.zoneThreshold = r.zoneThreshold;
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
			this.costsForMessages = null; // invalidate old cost estimates

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

				if( pureMaxPROP ) {

				    /* update both meeting probabilities */
                    probs.updateMeetingProbFor(otherHost.getAddress());
                    otherRouter.probs.updateMeetingProbFor(getHost().getAddress());

				    /* exchange the transitive probabilities */
                    this.updateTransitiveProbs(otherRouter.allProbs);
                    otherRouter.updateTransitiveProbs(this.allProbs);
                    this.allProbs.put(otherHost.getAddress(),
                            otherRouter.probs.replicate());
                    otherRouter.allProbs.put(getHost().getAddress(),
                            this.probs.replicate());
                } else {
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
		}
		else {
			/* connection went down, update transferred bytes average */
			updateTransferredBytesAvg(con.getTotalBytesTransferred());
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
	 * Updates transitive probability values by replacing the current
	 * MeetingProbabilitySets with the values from the given mapping
	 * if the given sets have more recent updates.
	 * @param p Mapping of the values of the other host
	 */
	private void updateTransitiveProbs(Map<Integer, MeetingProbabilitySet> p) {
		for (Map.Entry<Integer, MeetingProbabilitySet> e : p.entrySet()) {
			MeetingProbabilitySet myMps = this.allProbs.get(e.getKey());
			if (myMps == null ||
					e.getValue().getLastUpdateTime() > myMps.getLastUpdateTime() ) {
				this.allProbs.put(e.getKey(), e.getValue().replicate());
			}
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
		this.costsForMessages = null; // new message -> invalidate costs
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
	 * Updates the average estimate of the number of bytes transferred per
	 * transfer opportunity.
	 * @param newValue The new value to add to the estimate
	 */
	private void updateTransferredBytesAvg(int newValue) {
		int realCount = 0;
		int sum = 0;

		this.avgSamples[this.nextSampleIndex++] = newValue;
		if(this.nextSampleIndex >= BYTES_TRANSFERRED_AVG_SAMPLES) {
			this.nextSampleIndex = 0;
		}

		for (int i=0; i < BYTES_TRANSFERRED_AVG_SAMPLES; i++) {
			if (this.avgSamples[i] > 0) { // only values above zero count
				realCount++;
				sum += this.avgSamples[i];
			}
		}

		if (realCount > 0) {
			this.avgTransferredBytes = sum / realCount;
		}
		else { // no samples or all samples are zero
			this.avgTransferredBytes = 0;
		}
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

		if(pureMaxPROP) {
            Collections.sort(validMessages,
                    new MessageComparator(this.calcThreshold()));
        }else{
		    Collections.sort(validMessages, new BloomFilterMessageComparator());
        }

		return validMessages.get(validMessages.size()-1); // return last message
	}

	@Override
	public void update() {
		super.update();

		if (!pureMaxPROP){
		    degradationTimer();
        }

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
	 * Returns the message delivery cost between two hosts from this host's
	 * point of view. If there is no path between "from" and "to" host,
	 * Double.MAX_VALUE is returned. Paths are calculated only to hosts
	 * that this host has messages to.
	 * @param from The host where a message is coming from
	 * @param to The host where a message would be destined to
	 * @return The cost of the cheapest path to the destination or
	 * Double.MAX_VALUE if such a path doesn't exist
	 */
	public double getCost(DTNHost from, DTNHost to) {
		/* check if the cached values are OK */
            if (this.costsForMessages == null || lastCostFrom != from) {
			/* cached costs are invalid -> calculate new costs */
                this.allProbs.put(getHost().getAddress(), this.probs);
                int fromIndex = from.getAddress();

			/* calculate paths only to nodes we have messages to
			 * (optimization) */
                Set<Integer> toSet = new HashSet<Integer>();
                for (Message m : getMessageCollection()) {
                    toSet.add(m.getTo().getAddress());
                }

                this.costsForMessages = dijkstra.getCosts(fromIndex, toSet);
                this.lastCostFrom = from; // store source host for caching checks
            }

            if (costsForMessages.containsKey(to.getAddress())) {
                return costsForMessages.get(to.getAddress());
            } else {
			/* there's no known path to the given host */
                return Double.MAX_VALUE;
            }
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
				/* message was a good candidate for sending */
				messages.add(new Tuple<Message, Connection>(m,con));
			}
		}

		if (messages.size() == 0) {
			return null;
		}

		if(pureMaxPROP) {
		    /* sort the message-connection tuples according to the criteria
		     * defined in MaxPropTupleComparator */
            Collections.sort(messages, new TupleComparator(calcThreshold()));
        }else{
		    Collections.sort(messages, new BloomFilterQueueComparator());
        }

        if (messages.size() >= 5) {
            printTupleList(messages);
        }
		return tryMessagesForConnected(messages);
	}

	/**
	 * Calculates and returns the current threshold value for the buffer's split
	 * based on the average number of bytes transferred per transfer opportunity
	 * and the hop counts of the messages in the buffer. Method is public only
	 * to make testing easier.
	 * @return current threshold value (hop count) for the buffer's split
	 */
	public int calcThreshold() {
		/* b, x and p refer to respective variables in the paper's equations */
		long b = this.getBufferSize();
		long x = this.avgTransferredBytes;
		long p;

		if (x == 0) {
			/* can't calc the threshold because there's no transfer data */
			return 0;
		}

		/* calculates the portion (bytes) of the buffer selected for priority */
		if (x < b/2) {
			p = x;
		}
		else if (b/2 <= x && x < b) {
			p = Math.min(x, b-x);
		}
		else {
			return 0; // no need for the threshold
		}

		/* creates a copy of the messages list, sorted by hop count */
		ArrayList<Message> msgs = new ArrayList<Message>();
		msgs.addAll(getMessageCollection());
		if (msgs.size() == 0) {
			return 0; // no messages -> no need for threshold
		}
		/* anonymous comparator class for hop count comparison */
		Comparator<Message> hopCountComparator = new Comparator<Message>() {
			public int compare(Message m1, Message m2) {
				return m1.getHopCount() - m2.getHopCount();
			}
		};
		Collections.sort(msgs, hopCountComparator);

		/* finds the first message that is beyond the calculated portion */
		int i=0;
		for (int n=msgs.size(); i<n && p>0; i++) {
			p -= msgs.get(i).getSize();
		}

		i--; // the last round moved i one index too far
		if (i < 0) {
			return 0;
		}

		/* now i points to the first packet that exceeds portion p;
		 * the threshold is that packet's hop count + 1 (so that packet and
		 * perhaps some more are included in the priority part) */
		return msgs.get(i).getHopCount() + 1;
	}

	/**
	 * Message comparator for the MaxProp routing module.
	 * Messages that have a hop count smaller than the given
	 * threshold are given priority and they are ordered by their hop count.
	 * Other messages are ordered by their delivery cost.
	 */
	private class MessageComparator implements Comparator<Message> {
		private int threshold;
		private DTNHost from1;
		private DTNHost from2;

		/**
		 * Constructor. Assumes that the host where all the costs are calculated
		 * from is this router's host.
		 * @param treshold Messages with the hop count smaller than this
		 * value are transferred first (and ordered by the hop count)
		 */
		public MessageComparator(int treshold) {
			this.threshold = treshold;
			this.from1 = this.from2 = getHost();
		}

		/**
		 * Constructor.
		 * @param threshold Messages with the hop count smaller than this
		 * value are transferred first (and ordered by the hop count)
		 * @param from1 The host where the cost of msg1 is calculated from
		 * @param from2 The host where the cost of msg2 is calculated from
		 */
		public MessageComparator(int threshold, DTNHost from1, DTNHost from2) {
			this.threshold = threshold;
			this.from1 = from1;
			this.from2 = from2;
		}

		/**
		 * Compares two messages and returns -1 if the first given message
		 * should be first in order, 1 if the second message should be first
		 * or 0 if message order can't be decided. If both messages' hop count
		 * is less than the threshold, messages are compared by their hop count
		 * (smaller is first). If only other's hop count is below the threshold,
		 * that comes first. If both messages are below the threshold, the one
		 * with smaller cost (determined by
		 * {@link BFGMPRouter#getCost(DTNHost, DTNHost)}) is first.
		 */
		public int compare(Message msg1, Message msg2) {
			double p1, p2;
			int hopc1 = msg1.getHopCount();
			int hopc2 = msg2.getHopCount();

			if (msg1 == msg2) {
				return 0;
			}

			/* if one message's hop count is above and the other one's below the
			 * threshold, the one below should be sent first */
			if (hopc1 < threshold && hopc2 >= threshold) {
				return -1; // message1 should be first
			}
			else if (hopc2 < threshold && hopc1 >= threshold) {
				return 1; // message2 -"-
			}

			/* if both are below the threshold, one with lower hop count should
			 * be sent first */
			if (hopc1 < threshold && hopc2 < threshold) {
				return hopc1 - hopc2;
			}

			/* both messages have more than threshold hops -> cost of the
			 * message path is used for ordering */
			p1 = getCost(from1, msg1.getTo());
			p2 = getCost(from2, msg2.getTo());

			/* the one with lower cost should be sent first */
			if (p1-p2 == 0) {
				/* if costs are equal, hop count breaks ties. If even hop counts
				   are equal, the queue ordering is used  */
				if (hopc1 == hopc2) {
					return compareByQueueMode(msg1, msg2);
				} else {
					return hopc1 - hopc2;
				}
			} else if (p1-p2 < 0) {
				return -1; // msg1 had the smaller cost
			} else {
				return 1; // msg2 had the smaller cost
			}
		}
	}

	/**
	 * Message-Connection tuple comparator for the MaxProp routing
	 * module. Uses {@link MessageComparator} on the messages of the tuples
	 * setting the "from" host for that message to be the one in the connection
	 * tuple (i.e., path is calculated starting from the host on the other end
	 * of the connection).
	 */
	private class TupleComparator implements Comparator <Tuple<Message, Connection>>  {
		private int threshold;

		public TupleComparator(int threshold) {
			this.threshold = threshold;
		}

		/**
		 * Compares two message-connection tuples using the
		 * {@link MessageComparator#compare(Message, Message)}.
		 */
		public int compare(Tuple<Message, Connection> tuple1,
						   Tuple<Message, Connection> tuple2) {
			MessageComparator comp;
			DTNHost from1 = tuple1.getValue().getOtherNode(getHost());
			DTNHost from2 = tuple2.getValue().getOtherNode(getHost());

			comp = new MessageComparator(threshold, from1, from2);
			return comp.compare(tuple1.getKey(), tuple2.getKey());
		}
	}




	@Override
	public RoutingInfo getRoutingInfo() {
		RoutingInfo top = super.getRoutingInfo();
		RoutingInfo ri = new RoutingInfo(probs.getAllProbs().size() +
				" meeting probabilities");

		/* show meeting probabilities for this host */
		for (Map.Entry<Integer, Double> e : probs.getAllProbs().entrySet()) {
			Integer host = e.getKey();
			Double value = e.getValue();
			ri.addMoreInfo(new RoutingInfo(String.format("host %d : %.6f",
					host, value)));
		}

		top.addMoreInfo(ri);
		top.addMoreInfo(ri);
		top.addMoreInfo(new RoutingInfo("Avg transferred bytes: " +
				this.avgTransferredBytes));

		return top;
	}

	@Override
	public MessageRouter replicate() {
		BFGMPRouter r = new BFGMPRouter(this);
		return r;
	}


	public void printTupleList(List<Tuple<Message, Connection>> list){
        DecimalFormat dFormat = new DecimalFormat("0.000");
	    System.out.println("----------------------Node " + getHost().toString() + "@" + SimClock.getTime() + "---------------------");
	    System.out.println("MSG\tDST\tPri\tPrj\thops1\thops2");
	    for( Tuple<Message, Connection> entry : list){
	        Message m = entry.getKey();
	        Connection c = entry.getValue();
            StringBuffer sb = new StringBuffer();
            sb.append(m.getId()).append("\t");
            sb.append(c.getOtherNode(getHost())).append("\t");
            double pr = bloomFilterDeliveryProbability(getHost(), m.getTo());
            sb.append(dFormat.format(pr)).append("\t");
            pr = bloomFilterDeliveryProbability(c.getOtherNode(getHost()), m.getTo());
            sb.append(dFormat.format(pr)).append("\t");
            sb.append(m.getHopCount());
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
	        double pri, prj;
	        int hopCount1 = m1.getHopCount();
	        int hopCount2 = m2.getHopCount();

            //If the messages have the same hop count, then compare by delivery probability
            pri = bloomFilterDeliveryProbability(from1, m1.getTo());
            prj = bloomFilterDeliveryProbability(from2, m2.getTo());

            if(prj > pri){
                return 1;
            }else if(prj == pri){
                if(hopCount2 < hopCount1){
                    return 1;
                }else if(hopCount1 == hopCount2){
                    return compareByQueueMode(m1, m2);
                }
            }
            return -1;
        }
    }


    private class BloomFilterQueueComparator implements Comparator<Tuple<Message, Connection>>{

		private Long test;

        @Override
        public int compare(Tuple<Message, Connection> tuple1, Tuple<Message, Connection> tuple2) {
            BloomFilterMessageComparator comp;
            DTNHost from1 = tuple1.getValue().getOtherNode(getHost());
            DTNHost from2 = tuple2.getValue().getOtherNode(getHost());

            comp = new BloomFilterMessageComparator(from1, from2);
            Message m1 = tuple1.getKey();
            Message m2 = tuple2.getKey();
            return comp.compare(tuple1.getKey(), tuple2.getKey());
        }
    }
}
