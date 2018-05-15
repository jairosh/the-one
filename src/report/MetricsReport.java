package report;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reimplementation of MessageStatsReport due to inflexibility of output format.
 * Metrics added: Physical distance between origin/destination
 * Metrics removed: rtt_avg, rtt_med, response_prob
 */
public class MetricsReport extends Report implements MessageListener{

    private Map<String, Double> distances_at_creation;
    private Map<String, Double> distances_of_delivered;
    private Map<String, Double> speeds;
    private Map<String, Double> creationTimes;
    private List<Double> latencies;
    private List<Integer> hopCounts;
    private List<Double> msgBufferTime;

    private int nrofDropped;
    private int nrofRemoved;
    private int nrofStarted;
    private int nrofAborted;
    private int nrofRelayed;
    private int nrofCreated;
    private int nrofDelivered;



    public MetricsReport(){
        init();

    }

    @Override
    public void init(){
        super.init();
        this.creationTimes = new HashMap<String, Double>();
        this.latencies = new ArrayList<Double>();
        this.msgBufferTime = new ArrayList<Double>();
        this.hopCounts = new ArrayList<Integer>();
        this.distances_at_creation = new HashMap<>();
        this.distances_of_delivered = new HashMap<>();
        this.speeds = new HashMap<>();

        this.nrofDropped = 0;
        this.nrofRemoved = 0;
        this.nrofStarted = 0;
        this.nrofAborted = 0;
        this.nrofRelayed = 0;
        this.nrofCreated = 0;
        this.nrofDelivered = 0;
    }


    public void messageDeleted(Message m, DTNHost where, boolean dropped) {
        if (isWarmupID(m.getId())) {
            return;
        }

        if (dropped) {
            this.nrofDropped++;
        }
        else {
            this.nrofRemoved++;
        }

        this.msgBufferTime.add(getSimTime() - m.getReceiveTime());
    }


    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
        if (isWarmupID(m.getId())) {
            return;
        }

        this.nrofAborted++;
    }


    public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean finalTarget) {
        if (isWarmupID(m.getId())) {
            return;
        }

        this.nrofRelayed++;
        if (finalTarget) {
            double messageTime = getSimTime() - this.creationTimes.get(m.getId());
            this.latencies.add(messageTime);
            this.nrofDelivered++;
            this.hopCounts.add(m.getHops().size() - 1);
            speeds.put(m.getId(), distances_at_creation.get(m.getId()) / messageTime);
            distances_of_delivered.put(m.getId(), distances_at_creation.get(m.getId()));

        }
    }


    public void newMessage(Message m) {
        if (isWarmup()) {
            addWarmupID(m.getId());
            return;
        }

        this.creationTimes.put(m.getId(), getSimTime());
        this.nrofCreated++;
        this.distances_at_creation.put(m.getId(), getMessageDistance(m));
    }


    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
        if (isWarmupID(m.getId())) {
            return;
        }

        this.nrofStarted++;
    }


    public void writeReportHeader(){
        write("Message stats for scenario " + getScenarioName() + "\nsim_time: " + format(getSimTime()));
    }



    @Override
    public void done(){
        StringBuilder sb = new StringBuilder();

        writeReportHeader();

        double deliveryProb = 0; // delivery probability
        double responseProb = 0; // request-response success probability
        double overHead = Double.NaN;	// overhead ratio

        if (this.nrofCreated > 0) {
            deliveryProb = (1.0 * this.nrofDelivered) / this.nrofCreated;
        }
        if (this.nrofDelivered > 0) {
            overHead = (1.0 * (this.nrofRelayed - this.nrofDelivered)) /
                    this.nrofDelivered;
        }

        sb.append("created: ").append(this.nrofCreated);
        sb.append("\nstarted: " + this.nrofStarted);
        sb.append("\nrelayed: " + this.nrofRelayed);
        sb.append("\naborted: " + this.nrofAborted );
        sb.append("\ndropped: " + this.nrofDropped );
        sb.append("\nremoved: " + this.nrofRemoved );
        sb.append("\ndelivered: " + this.nrofDelivered );
        sb.append("\ndelivery_prob: " + format(deliveryProb) );
        sb.append("\nresponse_prob: " + format(responseProb) );
        sb.append("\noverhead_ratio: " + format(overHead) );
        sb.append("\nlatency_avg: " + getAverage(this.latencies) );
        sb.append("\nlatency_med: " + getMedian(this.latencies) );
        sb.append("\nhopcount_avg: " + getIntAverage(this.hopCounts) );
        sb.append("\nhopcount_med: " + getIntMedian(this.hopCounts) );
        sb.append("\nbuffertime_avg: " + getAverage(this.msgBufferTime) );
        sb.append("\nbuffertime_med: " + getMedian(this.msgBufferTime) );
        sb.append("\ndistance_avg: ").append(getAverage(distances_at_creation));
        sb.append("\nd_dist_avg: ").append(getAverage(distances_of_delivered));
        sb.append("\nspeed_avg: ").append(getAverage(speeds));
        write(sb.toString());
        super.done();

        Settings settings = getSettings();
        String outDir = settings.getSetting("reportDir");
        if (!outDir.endsWith("/")) {
            outDir += "/";	// make sure dir ends with directory delimiter
        }
        writeDistanceDistribution(outDir + getScenarioName() + "_DistanceDistribution.csv");
    }


    private Double getMessageDistance(Message m){
        DTNHost origin = m.getFrom();
        DTNHost dest = m.getTo();

        Double x1 = origin.getLocation().getX();
        Double y1 = origin.getLocation().getY();
        Double x2 = dest.getLocation().getX();
        Double y2 = dest.getLocation().getY();

        return Math.sqrt(Math.pow((x1-x2), 2.0) + Math.pow(y1-y2, 2.0));
    }

    private Double getAverage(Map<String, Double> values){
        Double accum = 0.0;
        for(Map.Entry<String, Double> entry : values.entrySet()){
            accum += entry.getValue();
        }
        return accum / values.size();
    }

    private void writeDistanceDistribution(String filename){
        try {
            PrintWriter reportFile = new PrintWriter(new FileWriter(filename));
            reportFile.println("id, delivered, distance, speed");
            for (Map.Entry<String, Double> entry : this.distances_at_creation.entrySet()) {
                boolean delivered = this.distances_of_delivered.containsKey(entry.getKey());
                StringBuilder sb = new StringBuilder();
                sb.append(entry.getKey()).append(", ");
                sb.append(delivered).append(", ");
                sb.append(entry.getValue()).append(", ");
                sb.append(speeds.get(entry.getKey()));
                reportFile.println(sb.toString());
            }
            reportFile.close();
        }catch(IOException ex){
            System.err.print(ex);
        }

    }
}
