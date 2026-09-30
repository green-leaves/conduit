package io.lightning.conduit.node.fx;

import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.EventDispatcher;
import io.lightning.conduit.dispatcher.ListenerRegistry;
import io.lightning.conduit.node.Node1;
import io.lightning.conduit.node.Node2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests a node that is simultaneously a consumer (Node) and a producer (EventDispatcher).
 *
 * Pipeline under test:
 *
 *   tickFeed ──► [NormalizerNode : Node1<Tick> & EventDispatcher<Rate>]
 *                          │
 *                          ▼ (Rate)
 *               [MatcherNode : Node2<Rate, Order> & EventDispatcher<Trade>] ◄── orderFeed
 *                          │
 *                          ▼ (Trade)
 *               [TradeRecorder : Node1<Trade>]
 */
public class OMSPipelineTest {

    // -----------------------------------------------------------------------
    // Domain model
    // -----------------------------------------------------------------------

    record Tick(String symbol, double price) {}

    record Rate(String symbol, double bid, double ask) {
        double mid() { return (bid + ask) / 2.0; }
    }

    enum Side { BUY, SELL }

    record Order(String symbol, Side side, double quantity, double limitPrice) {}

    record Trade(String symbol, Side side, double quantity, double executedPrice) {}

    // -----------------------------------------------------------------------
    // NormalizerNode: Node1<Tick> + EventDispatcher<Rate>
    //
    // Receives raw price ticks, applies a fixed spread, and dispatches Rate.
    // Any downstream node can subscribe to this node as a Dispatcher<Rate>.
    // -----------------------------------------------------------------------

    static class NormalizerNode extends Node1<Tick> implements EventDispatcher<Rate> {

        private final ListenerRegistry<Rate> listeners = new ListenerRegistry<>();
        private final double spreadFraction; // e.g. 0.001 = 0.1%

        NormalizerNode(double spreadFraction) {
            this.spreadFraction = spreadFraction;
        }

        @Override
        public ListenerRegistry<Rate> listeners() {
            return listeners;
        }

        @Override
        protected void onEvent1(Tick tick) {
            double half  = tick.price() * spreadFraction / 2.0;
            double bid   = tick.price() - half;
            double ask   = tick.price() + half;
            dispatch(new Rate(tick.symbol(), bid, ask));
        }
    }

    // -----------------------------------------------------------------------
    // MatcherNode: Node2<Rate, Order> + EventDispatcher<Trade>
    //
    // Stores the latest rate per symbol. When an Order arrives it attempts an
    // immediate fill:
    //   BUY  filled if order.limitPrice >= current ask  (willing to pay enough)
    //   SELL filled if order.limitPrice <= current bid  (willing to accept low enough)
    // Dispatches a Trade on a successful fill.
    // -----------------------------------------------------------------------

    static class MatcherNode extends Node2<Rate, Order> implements EventDispatcher<Trade> {

        private final ListenerRegistry<Trade> listeners = new ListenerRegistry<>();

        // last known rate (null until first Rate event)
        private Rate currentRate;

        @Override
        public ListenerRegistry<Trade> listeners() {
            return listeners;
        }

        @Override
        protected void onEvent1(Rate rate) {
            currentRate = rate;
        }

        @Override
        protected void onEvent2(Order order) {
            if (currentRate == null) return;
            if (!currentRate.symbol().equals(order.symbol())) return;

            boolean filled = switch (order.side()) {
                case BUY  -> order.limitPrice() >= currentRate.ask();
                case SELL -> order.limitPrice() <= currentRate.bid();
            };

            if (filled) {
                double execPrice = order.side() == Side.BUY
                        ? currentRate.ask()
                        : currentRate.bid();
                dispatch(new Trade(order.symbol(), order.side(), order.quantity(), execPrice));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test fixtures
    // -----------------------------------------------------------------------

    private static final String SYMBOL = "EUR/USD";
    private static final double SPREAD = 0.001; // 0.1%

    private EventDispatcher<Tick>  tickFeed;
    private EventDispatcher<Order> orderFeed;
    private NormalizerNode         normalizer;
    private MatcherNode            matcher;

    @BeforeEach
    void setUp() {
        tickFeed   = EventDispatcher.create();
        orderFeed  = EventDispatcher.create();
        normalizer = new NormalizerNode(SPREAD);
        matcher    = new MatcherNode();
    }

    // -----------------------------------------------------------------------
    // Helper: wire the pipeline and start all nodes
    // -----------------------------------------------------------------------

    private void startPipeline() {
        normalizer.subscribe1(tickFeed).start();
        // normalizer itself acts as the Rate dispatcher for matcher
        matcher.subscribe1(normalizer).subscribe2(orderFeed).start();
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void testNormalizerDispatchesRateOnTickReceived() {
        List<Rate> rates = new ArrayList<>();
        normalizer.register(rates::add);
        normalizer.subscribe1(tickFeed).start();

        tickFeed.dispatch(new Tick(SYMBOL, 1.1000));

        assertEquals(1, rates.size());
        Rate rate = rates.get(0);
        assertEquals(SYMBOL, rate.symbol());
        assertEquals(1.1000 * (1 - SPREAD / 2.0), rate.bid(),  1e-9);
        assertEquals(1.1000 * (1 + SPREAD / 2.0), rate.ask(),  1e-9);
    }

    @Test
    void testNormalizerMidPriceEqualsTickPrice() {
        List<Rate> rates = new ArrayList<>();
        normalizer.register(rates::add);
        normalizer.subscribe1(tickFeed).start();

        tickFeed.dispatch(new Tick(SYMBOL, 1.2345));

        assertEquals(1.2345, rates.get(0).mid(), 1e-9);
    }

    @Test
    void testNormalizerCanBeSubscribedByMultipleDownstreamNodes() {
        List<Rate> consumerA = new ArrayList<>();
        List<Rate> consumerB = new ArrayList<>();
        normalizer.register(consumerA::add);
        normalizer.register(consumerB::add);
        normalizer.subscribe1(tickFeed).start();

        tickFeed.dispatch(new Tick(SYMBOL, 1.1000));

        assertEquals(1, consumerA.size());
        assertEquals(1, consumerB.size());
        assertEquals(consumerA.get(0), consumerB.get(0));
    }

    @Test
    void testMatcherFillsBuyOrderWhenLimitPriceExceedsAsk() {
        List<Trade> trades = new ArrayList<>();
        matcher.register(trades::add);
        startPipeline();

        tickFeed.dispatch(new Tick(SYMBOL, 1.1000));         // publishes Rate
        orderFeed.dispatch(new Order(SYMBOL, Side.BUY, 100_000, 1.2000)); // limit well above ask

        assertEquals(1, trades.size());
        Trade trade = trades.get(0);
        assertEquals(SYMBOL,   trade.symbol());
        assertEquals(Side.BUY, trade.side());
        assertEquals(100_000,  trade.quantity(), 1e-9);
        // executed at ask price
        double expectedAsk = 1.1000 * (1 + SPREAD / 2.0);
        assertEquals(expectedAsk, trade.executedPrice(), 1e-9);
    }

    @Test
    void testMatcherFillsSellOrderWhenLimitPriceBelowBid() {
        List<Trade> trades = new ArrayList<>();
        matcher.register(trades::add);
        startPipeline();

        tickFeed.dispatch(new Tick(SYMBOL, 1.1000));
        orderFeed.dispatch(new Order(SYMBOL, Side.SELL, 50_000, 1.0000)); // limit well below bid

        assertEquals(1, trades.size());
        assertEquals(Side.SELL, trades.get(0).side());
        double expectedBid = 1.1000 * (1 - SPREAD / 2.0);
        assertEquals(expectedBid, trades.get(0).executedPrice(), 1e-9);
    }

    @Test
    void testMatcherDoesNotFillBuyOrderWhenLimitPriceBelowAsk() {
        List<Trade> trades = new ArrayList<>();
        matcher.register(trades::add);
        startPipeline();

        tickFeed.dispatch(new Tick(SYMBOL, 1.1000));
        double ask = 1.1000 * (1 + SPREAD / 2.0);
        orderFeed.dispatch(new Order(SYMBOL, Side.BUY, 100_000, ask - 0.0010)); // below ask

        assertTrue(trades.isEmpty(), "order should not fill when limit is below ask");
    }

    @Test
    void testMatcherDoesNotFillOrderWithNoRateYet() {
        List<Trade> trades = new ArrayList<>();
        matcher.register(trades::add);
        startPipeline();

        // dispatch order BEFORE any tick arrives — no rate known yet
        orderFeed.dispatch(new Order(SYMBOL, Side.BUY, 100_000, 1.5000));

        assertTrue(trades.isEmpty(), "order should not fill without a known rate");
    }

    @Test
    void testMatcherIgnoresOrderForDifferentSymbol() {
        List<Trade> trades = new ArrayList<>();
        matcher.register(trades::add);
        startPipeline();

        tickFeed.dispatch(new Tick(SYMBOL, 1.1000));
        orderFeed.dispatch(new Order("GBP/USD", Side.BUY, 100_000, 1.5000));

        assertTrue(trades.isEmpty(), "order for different symbol should not fill");
    }

    @Test
    void testMatcherRateUpdateAffectsSubsequentOrders() {
        List<Trade> trades = new ArrayList<>();
        matcher.register(trades::add);
        startPipeline();

        // First tick: rate too high for a buy at 1.08
        tickFeed.dispatch(new Tick(SYMBOL, 1.1000));
        orderFeed.dispatch(new Order(SYMBOL, Side.BUY, 100_000, 1.08));
        assertEquals(0, trades.size(), "should not fill at 1.1000 ask with limit 1.08");

        // Rate drops — order at 1.08 now clears the ask
        tickFeed.dispatch(new Tick(SYMBOL, 1.0700));
        orderFeed.dispatch(new Order(SYMBOL, Side.BUY, 100_000, 1.08));
        assertEquals(1, trades.size(), "should fill at 1.0700 ask with limit 1.08");
    }

    @Test
    void testFullPipelineEndToEnd() {
        // Wire a TradeRecorder as a plain Node1<Trade> at the end of the pipeline
        List<Trade> recorded = new ArrayList<>();
        Node1<Trade> recorder = new Node1<>() {
            @Override protected void onEvent1(Trade trade) { recorded.add(trade); }
        };

        startPipeline();
        recorder.subscribe1(matcher).start();

        // Simulate a market session: two ticks and two orders
        tickFeed.dispatch(new Tick(SYMBOL, 1.1000));
        orderFeed.dispatch(new Order(SYMBOL, Side.BUY,  100_000, 1.20));  // fills
        orderFeed.dispatch(new Order(SYMBOL, Side.SELL, 100_000, 0.90));  // fills
        orderFeed.dispatch(new Order(SYMBOL, Side.BUY,  100_000, 1.09));  // limit below ask — no fill

        tickFeed.dispatch(new Tick(SYMBOL, 1.0800));
        orderFeed.dispatch(new Order(SYMBOL, Side.BUY, 50_000, 1.09));    // fills at new lower ask

        assertEquals(3, recorded.size());
        assertEquals(Side.BUY,  recorded.get(0).side());
        assertEquals(Side.SELL, recorded.get(1).side());
        assertEquals(Side.BUY,  recorded.get(2).side());
        assertEquals(50_000, recorded.get(2).quantity(), 1e-9);
    }

    @Test
    void testUnregisterStopsDeliveryToThatListener() {
        List<Rate> rates = new ArrayList<>();
        Dispatcher.Listener<Rate> listener = rates::add;

        normalizer.register(listener);
        normalizer.subscribe1(tickFeed).start();

        tickFeed.dispatch(new Tick(SYMBOL, 1.1000));
        assertEquals(1, rates.size());

        normalizer.unregister(listener);
        tickFeed.dispatch(new Tick(SYMBOL, 1.2000));
        assertEquals(1, rates.size(), "no new events after unregister");
    }

    @Test
    void testMultipleTicksProduceMultipleRates() {
        List<Rate> rates = new ArrayList<>();
        normalizer.register(rates::add);
        normalizer.subscribe1(tickFeed).start();

        tickFeed.dispatch(new Tick(SYMBOL, 1.1000));
        tickFeed.dispatch(new Tick(SYMBOL, 1.1100));
        tickFeed.dispatch(new Tick(SYMBOL, 1.1200));

        assertEquals(3, rates.size());
        assertEquals(1.1000 * (1 - SPREAD / 2.0), rates.get(0).bid(), 1e-9);
        assertEquals(1.1100 * (1 - SPREAD / 2.0), rates.get(1).bid(), 1e-9);
        assertEquals(1.1200 * (1 - SPREAD / 2.0), rates.get(2).bid(), 1e-9);
    }
}
