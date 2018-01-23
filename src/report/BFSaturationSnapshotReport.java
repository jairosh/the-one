package report;

import core.DTNHost;
import core.UpdateListener;
import routing.BFGMPRouter;

import java.util.List;

public class BFSaturationSnapshotReport extends SnapshotReport implements UpdateListener {

    public BFSaturationSnapshotReport() {
        super();
    }


    @Override
    protected void writeSnapshot(DTNHost host) {
        write(bloomFilterSaturationAt(host));
    }


    public String bloomFilterSaturationAt(DTNHost host) {
        if (!(host.getRouter() instanceof BFGMPRouter))
            throw new AssertionError("This report cannot deal with other Routing protocols than BFGRouter");

        StringBuilder sb = new StringBuilder();
        sb.append(host + " ");
        BFGMPRouter router = (BFGMPRouter) host.getRouter();
        sb.append(router.getFilterSaturation());
        return sb.toString();
    }
}
