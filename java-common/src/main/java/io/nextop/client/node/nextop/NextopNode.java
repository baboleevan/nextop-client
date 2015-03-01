package io.nextop.client.node.nextop;

import io.nextop.Id;
import io.nextop.Message;
import io.nextop.client.*;
import io.nextop.client.node.AbstractMessageControlNode;
import io.nextop.client.node.Head;
import io.nextop.client.node.http.HttpNode;
import io.nextop.client.retry.SendStrategy;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Nextop is symmetric protocol, so the client and server both use an instance
 * of this class to communicate. The difference between instances is the
 * Wire.Factory, which is responsible for a secure connection.
 * The nextop protocol is optimized to pipeline messages up/down. There is a tradeoff
 * in ordering in the case the endpoint crashes. Assuming a reliable endpoint, order is maintained.
 * */
public class NextopNode extends AbstractMessageControlNode {

    public static final class Config {

    }


    final Config config;

    @Nullable
    Wire.Factory wireFactory;

    @Nullable
    volatile Wire.Adapter wireAdapter = null;

    boolean active;

    @Nullable
    ControlLooper controlLooper = null;


    final SharedTransferState sts;



    public NextopNode(Config config) {
        this.config = config;

        sts = new SharedTransferState(this);

    }

    /** @param wireFactory can be an instance of MessageControlNode */
    public void setWireFactory(Wire.Factory wireFactory) {
        this.wireFactory = wireFactory;
    }


    public void setWireAdapter(Wire.Adapter wireAdapter) {
        this.wireAdapter = wireAdapter;
    }





    /////// NODE ///////


    @Override
    protected void initDownstream(Bundle savedState) {
        if (wireFactory instanceof MessageControlNode) {
            ((MessageControlNode) wireFactory).init(this, savedState);
        }
    }

    @Override
    protected void initSelf(@Nullable Bundle savedState) {
        // ready to receive
        upstream.onActive(true);
    }

    @Override
    public void onActive(boolean active) {
        if (active && wireFactory instanceof MessageControlNode) {
            ((MessageControlNode) wireFactory).onActive(active);
        }

        if (this.active != active) {
            this.active = active;

            if (active) {
                assert null == controlLooper;

                controlLooper = new ControlLooper();
                controlLooper.start();
            } else {
                assert null != controlLooper;

                controlLooper.interrupt();
                controlLooper = null;
            }
        }

        if (!active && wireFactory instanceof MessageControlNode) {
            ((MessageControlNode) wireFactory).onActive(active);
        }
    }

    @Override
    public void onMessageControl(MessageControl mc) {
        assert MessageControl.Direction.SEND.equals(mc.dir);

        assert active;
        if (active) {
            MessageControlState mcs = getMessageControlState();
            if (!mcs.onActiveMessageControl(mc, upstream)) {
                switch (mc.type) {
                    case MESSAGE:
                        mcs.add(mc.message);
                        break;
                    default:
                        // ignore
                        break;
                }
            }
        }
    }






    final class ControlLooper extends Thread {


        @Override
        public void run() {

            @Nullable SharedWireState sws = null;


            while (active) {
                try {
                    if (null == sws || !sws.active) {
                        Wire wire;
                        try {
                            wire = wireFactory.create(null != sws ? sws.wire : null);
                        } catch (NoSuchElementException e) {
                            sws = null;
                            continue;
                        }

                        Wire.Adapter wireAdapter = NextopNode.this.wireAdapter;
                        if (null != wireAdapter) {
                            wire = wireAdapter.adapt(wire);
                        }

                        try {
                            syncTransferState(wire);
                        } catch (IOException e) {
                            // FIXME log
                            continue;
                        }

                        sws = new SharedWireState(wire);
                        WriteLooper writeLooper = new WriteLooper(sws);
                        ReadLooper readLooper = new ReadLooper(sws);
                        sws.writeLooper = writeLooper;
                        sws.readLooper = readLooper;
                        writeLooper.start();
                        readLooper.start();

                    } // else it was just an interruption

                    try {
                        sws.awaitEnd();
                    } catch (InterruptedException e) {
                        continue;
                    }

                } catch (Exception e) {
                    // FIXME log
                    continue;
                }
            }

            if (null != sws) {
                sws.end();
            }
        }


        // FIXME see notes in SharedTransferState
        void syncTransferState(Wire wire) throws IOException {
            sts.membar();

            // each side sends SharedTransferState (id->transferred chunks)
            // each side removes parts of the shared transfer state that the other side does not have

            // FIXME

        }
    }

    /* nextop framed format:
     * [byte type][next bytes depend on type] */

    static final class SharedWireState {
        final Wire wire;
        volatile boolean active;

        WriteLooper writeLooper;
        ReadLooper readLooper;

        SharedWireState(Wire wire) {
            this.wire = wire;
        }


        void end() {
            // interrupt writer, reader
            active = false;
            writeLooper.interrupt();
            readLooper.interrupt();

        }

        void awaitEnd() throws InterruptedException {

        }
    }

    final class WriteLooper extends Thread {
        final SharedWireState sws;
        final MessageControlState mcs = getMessageControlState();

        byte[] controlBuffer = new byte[1024];

        WriteLooper(SharedWireState sws) {
            this.sws = sws;
        }


        @Override
        public void run() {
            sts.membar();

            // take top
            // write
            // every chunkQ, check if there if a more important, before writing the next chunk
            // if so put back

            @Nullable MessageControlState.Entry entry = null;

            try {

                top:
                while (sws.active) {
                    if (null == entry) {
                        try {
                            entry = mcs.takeFirstAvailable(NextopNode.this, Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            continue;
                        }
                    }

                    @Nullable MessageWriteState writeState = sts.writeStates.get(entry.id);
                    if (null == writeState) {
                        // FIXME create it
                    }

                    final int n = writeState.chunkOffsets.length;

                    // F_MESSAGE_START [id][total length][total chunks]
                    {
                        int c = 0;
                        controlBuffer[c] = F_MESSAGE_START;
                        c += 1;
                        Id.toBytes(entry.id, controlBuffer, c);
                        c += 32;
                        putint(controlBuffer, c, writeState.bytes.length);
                        c += 4;
                        putint(controlBuffer, c, n);
                        c += 4;
                        sws.wire.write(controlBuffer, 0, c, 0);
                    }

                    for (int i = 0; i < n; ++i) {
                        if (!writeState.chunkWrites[i]) {
                            if (null != entry.end) {
                                // ended
                                entry = null;
                                continue top;
                            }

                            // write it
                            int start = writeState.chunkOffsets[i];
                            int end = i + 1 < n ? writeState.chunkOffsets[i + 1] : writeState.bytes.length;


                            // F_MESSAGE_CHUNK [chunk index][chunk offset][chunk length][data]
                            {
                                int c = 0;
                                controlBuffer[c] = F_MESSAGE_CHUNK;
                                c += 1;
                                putint(controlBuffer, c, i);
                                c += 4;
                                putint(controlBuffer, c, start);
                                c += 4;
                                putint(controlBuffer, c, end - start);
                                c += 4;
                                sws.wire.write(controlBuffer, 0, c, 0);
                            }
                            sws.wire.write(writeState.bytes, start, end, 0);


                            writeState.chunkWrites[i] = true;


                            @Nullable MessageControlState.Entry preemptEntry = mcs.takeFirstAvailable(entry.id, NextopNode.id);
                            if (null != preemptEntry) {
                                mcs.release(entry.id, NextopNode.this);
                                entry = preemptEntry;
                                continue top;
                            }
                        }
                    }

                    // F_MESSAGE_END [id]
                    {
                        int c = 0;
                        controlBuffer[c] = F_MESSAGE_END;
                        c += 1;
                        Id.toBytes(entry.id, controlBuffer, c);
                        c += 32;
                        sws.wire.write(controlBuffer, 0, c, 0);
                    }


                    // done with entry, transfer to pending ack
                    mcs.remove(entry.id, MessageControlState.End.COMPLETED);
                    sts.writePendingAck.add(entry.message);
                    entry = null;
                }
            } catch (IOException e) {
                // fatal
                sws.end();
            }

            if (null != entry) {
                mcs.release(entry.id, NextopNode.this);
                entry = null;
            }

            sts.membar();
        }
    }

    final class ReadLooper extends Thread {
        final SharedWireState sws;


        ReadLooper(SharedWireState sws) {
            this.sws = sws;
        }


        @Override
        public void run() {

            // FIXME
            // as soon as get a COMPLETE, send an ACK (this is not resilient to crash, but works for now to keep the client buffer limited)
            // on F_MESSAGE_COMPLETE or F_MESSAGE_CHUNK, if there is a verification error, send back a NACK
            // if read NACK, move message from pendingWrite back to mcs
        }
    }



    // FIXME relied on new threads being a membar. all this state is shared across 1+1 (writer+reader) threads in sequence
    static final class SharedTransferState {

        // when a message is remove from the shared mcs on write, it goes here
        // these message are pendinging ack
        // sync state established which of these are still valid. if any not valid, the client immediately retransmits at the front of the line
        // the nextop node holds these even if the node goes active->false. the protocol is set up that on reconnect they will get sent.
        //    even if a billing outage, getting these sent is an exception - they will always get sent even if the account is in bad standing etc.
        MessageControlState writePendingAck;

        MessageControlState readPendingAck;

        /** single-thread */
        Map<Id, MessageWriteState> writeStates;

        /** single-thread */
        Map<Id, MessageReadState> readStates;


        SharedTransferState(MessageContext context) {
            writePendingAck = new MessageControlState(context);
            readPendingAck = new MessageControlState(context);

            writeStates = new HashMap<Id, MessageWriteState>(32);
            readStates = new HashMap<Id, MessageReadState>(32);
        }


        synchronized void membar() {

        }
    }

    static final class MessageWriteState {
        final Id id;

        final byte[] bytes;
        // [0] is the start of the first chunk
        final int[] chunkOffsets;
        final boolean[] chunkWrites;


        MessageWriteState(Id id, byte[] bytes, int[] chunkOffsets) {
            this.id = id;
            this.bytes = bytes;
            this.chunkOffsets = chunkOffsets;
            // init all false
            chunkWrites = new boolean[chunkOffsets.length];
        }
    }

    static final class MessageReadState {
        final Id id;

        final byte[] bytes;
        // [0] is the start of the first chunk
        final int[] chunkOffsets;
        final boolean[] chunkReads;


        MessageReadState(Id id, int length, int chunkCount) {
            if (length < chunkCount) {
                throw new IllegalArgumentException();
            }
            this.id = id;
            bytes = new byte[length];
            chunkOffsets = new int[chunkCount];
            chunkReads = new boolean[chunkCount];
        }

        // FIXME on insert a chunk, mark the index of the following
        // FIXME if an insert conflicts with a previous known, NACK the message
    }





    /////// NEXTOP PROTOCOL ///////

    // FIXME be able to transfer MessageControl not just message
    /** [id][total length][total chunks] */
    public static final byte F_MESSAGE_START = 0x01;
    /** [chunk index][chunk offset][chunk length][data] */
    public static final byte F_MESSAGE_CHUNK = 0x02;
    /** [id] */
    public static final byte F_MESSAGE_END = 0x03;

    // FIXME currently the server sends this immediately, but it should sent it on COMPLETE of a message
    /** [id] */
    static final byte F_ACK = 0x04;
    /** [id] */
    static final byte F_NACK = 0x05;




    // TODO work out a more robust fallback
    // big assumption for ordering: nextop endpoint will not crash
    // compromise: maintain order and never lose a message if this is true
    // if not true, at least never lose a message (but order will be lost)

    // two phase dev:
    // (current) phase 1: just get it working, buggy in some cases, no reordering, etc
    // phase 2: correctness (never lose), reordering, etc, focus on perf






    // shared transfer state:
    // id -> bytes, sent index in bytes

    // socket control flow:
    // - retake timeout (use take state, time since last take, elapsed)
    // - create wire (socket) (on timeout, go to [0])
    // - initial handshakes
    // - initial state sync (sync the shared transfer state)
    // - start loopers
    // - when any loopers fails, shut down all, go to [0]
    //

    // WriteLooper
    // take off the top of mcs and write
    // have a parallel thread that peeks at the next
    // every yieldQ write, surface progress, check if there is a more urgent message
    // if so, shelve the current and switch

    // ReadLooper



    // wire format:
    // [type][length]
    // types:
    // - message start [ID]
    // - message data [bytes]
    // - message end [MD5]
    // - (verify error) (ack) (on ack, delete from shared transfer state)


}
