package skt;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

/**
 * Agent-facing end of the website integration. Only agents with the
 * intake_clerk role (the order_intake agent) can drain web orders.
 */
public class OrderGateway extends Artifact {

    void init() { log("order gateway ready (queue is filled by WebOrderApi)"); }

    private String caller() { return getCurrentOpAgentId().getAgentName(); }

    /** Blocks (without blocking the artifact) until a web order is waiting, then returns it. */
    @OPERATION
    void takeWebOrder(OpFeedbackParam<String> ticket, OpFeedbackParam<String> customer,
                      OpFeedbackParam<String> front, OpFeedbackParam<String> rear,
                      OpFeedbackParam<Integer> torque, OpFeedbackParam<Integer> deadlineMs,
                      OpFeedbackParam<Double> wTime, OpFeedbackParam<Double> wEnergy,
                      OpFeedbackParam<Double> wQuality, OpFeedbackParam<Double> price) {
        String denied = OrgLedger.INSTANCE.check(caller(), OrgLedger.TAKE_WEB_ORDER, -1, "OrderGateway.takeWebOrder");
        if (denied != null) { failed(denied); return; }
        WebOrderApi.WebOrder o;
        while ((o = WebOrderApi.PENDING.poll()) == null) await_time(200);
        ticket.set(o.ticket); customer.set(o.customer); front.set(o.front); rear.set(o.rear);
        torque.set(o.torque); deadlineMs.set(o.deadlineMs);
        wTime.set(o.wTime); wEnergy.set(o.wEnergy); wQuality.set(o.wQuality); price.set(o.price);
    }

    @OPERATION
    void registerTicketAgent(String ticket, String agent) {
        String denied = OrgLedger.INSTANCE.check(caller(), OrgLedger.TAKE_WEB_ORDER, -1, "OrderGateway.registerTicketAgent");
        if (denied != null) { failed(denied); return; }
        WebOrderApi.Ticket t = WebOrderApi.TICKETS.get(ticket);
        if (t != null) t.agent = agent;
    }

    @OPERATION
    void rejectTicket(String ticket, String reason) {
        String denied = OrgLedger.INSTANCE.check(caller(), OrgLedger.TAKE_WEB_ORDER, -1, "OrderGateway.rejectTicket");
        if (denied != null) { failed(denied); return; }
        WebOrderApi.Ticket t = WebOrderApi.TICKETS.get(ticket);
        if (t != null) t.rejected = reason;
    }
}
