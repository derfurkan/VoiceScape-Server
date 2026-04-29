package com.voicescape.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PacedSender {
    private static final Logger log = LoggerFactory.getLogger(PacedSender.class);

    private static final long FRAME_INTERVAL_NANOS = ServerConfig.MAX_PACER_WAIT_MS * 1_000_000;
    private static final long CATCHUP_RESET_NANOS = FRAME_INTERVAL_NANOS * 3;
    private static final int MAX_QUEUE_PER_FLOW = ServerConfig.MAX_PACER_QUEUE;
    private static final long TICK_INTERVAL_MS = 5;

    private final Map<String, FlowState> flows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public PacedSender() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VoiceScape-Pacer");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
        for (FlowState state : flows.values()) {
            state.drain();
        }
        flows.clear();
    }

    public void enqueue(String receiverSessionId, DatagramChannel channel, ByteBuf packet, InetSocketAddress dest) {
        long now = System.nanoTime();
        FlowState state = flows.computeIfAbsent(receiverSessionId, k -> new FlowState());
        synchronized (state) {
            if (state.queue.isEmpty() && now >= state.nextSendNanos) {
                try {
                    channel.write(new DatagramPacket(packet, dest));
                } catch (Exception e) {
                    packet.release();
                    return;
                }
                advanceNextSend(state, now);
            } else {
                if (state.queue.size() >= MAX_QUEUE_PER_FLOW) {
                    PendingPacket droppedPacket = state.queue.poll();
                    if (droppedPacket != null)
                        droppedPacket.release();
                }
                state.queue.offer(new PendingPacket(channel, packet, dest));
            }
        }
    }

    public void removeFlow(String receiverSessionId) {
        FlowState state = flows.remove(receiverSessionId);
        if (state != null) state.drain();
    }

    private void tick() {
        try {
            long now = System.nanoTime();
            Set<DatagramChannel> toFlush = null;
            for (FlowState state : flows.values()) {
                synchronized (state) {
                    while (!state.queue.isEmpty() && now >= state.nextSendNanos) {
                        PendingPacket pendingPacket = state.queue.poll();
                        if (pendingPacket == null) break;
                        try {
                            pendingPacket.channel.write(new DatagramPacket(pendingPacket.buf, pendingPacket.dest));
                            if (toFlush == null) toFlush = new HashSet<>();
                            toFlush.add(pendingPacket.channel);
                        } catch (Exception e) {
                            pendingPacket.release();
                        }
                        advanceNextSend(state, now);
                    }
                }
            }
            if (toFlush != null) {
                for (DatagramChannel ch : toFlush) {
                    try {
                        ch.flush();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Pacer tick failed: {}", e.getMessage());
        }
    }

    private static void advanceNextSend(FlowState state, long now) {
        if (state.nextSendNanos == 0) {
            state.nextSendNanos = now + FRAME_INTERVAL_NANOS;
        } else {
            state.nextSendNanos += FRAME_INTERVAL_NANOS;
            if (now - state.nextSendNanos > CATCHUP_RESET_NANOS) {
                state.nextSendNanos = now + FRAME_INTERVAL_NANOS;
            }
        }
    }

    private static class FlowState {
        final Queue<PendingPacket> queue = new ArrayDeque<>();
        long nextSendNanos = 0;

        void drain() {
            synchronized (this) {
                while (!queue.isEmpty()) {
                    PendingPacket pendingPacket = queue.poll();
                    if (pendingPacket != null)
                        pendingPacket.release();
                }
            }
        }
    }

    private record PendingPacket(DatagramChannel channel, ByteBuf buf, InetSocketAddress dest) {
        void release() {
                buf.release();
            }
        }
}
