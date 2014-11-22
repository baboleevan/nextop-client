package io.nextop.client.android;


import android.net.Uri;
import android.support.annotation.Nullable;
import rx.Observable;
import rx.Subscriber;

import java.io.IOException;

public interface NxClient {
    // AUTH

    void auth(@Nullable String apiKey);

    /* permissions in Nextop are done per key. A key is analogous to a Unix group.
     * Each client can have zero or more keys, granting it the
     * union of the permissions associated with the keys. */
    void grantKey(String key);
    void revokeKey(String key);



    // MESSAGING

    Sender<NxMessage> sender(Uri p);
    // receive is always load balanced
    // the same path is sent to the same client, for consistency on same objects
    Receiver<NxMessage> receiver(Uri p);
    // always auto ack when observer completes
//    void ack(Uri id);
    // seems like a rare case
    void cancel(Uri id);


    // CACHES

    /* Each client has two LRU caches:
     * - disk network cache, for all cacheable messages.
     * - memory image cache, for decoded images.
     * (based on Volley, which has the best network performance on Android) */

    CacheController getNetworkCache();
    CacheController getImageCache();

    // INTROSPECTION

    Observable<MessageStatus> listMessages();
    Observable<MessageStatus> messageStatus(Uri id);
//    Observable<Message> get(Uri id);

    Observable<NxMetrics> metrics(Uri p);

    // a bind is when a message ID is connected to a path
    // the bind is open/subscribed as long at both sides are connected.
    // each side can time out or have a subscription->0 transition.
    // in those cases, the bind is closed
    Observable<Bind> listBinds(Uri p);
    Observable<ReceivePath> listReceivePaths();



    // TODO admin. create keys, set permissions per key









    // SYSTEM EVENTS

    void onConnectivityEvent(ConnectivityEvent e);
//    void onConditionEvent(ConditionEvent e);


    // TRANSPORT

    void setTransportFactory(@Nullable TransportFactory tf);







    final class Sender<M extends NxMessage> {
        public Receiver send(M m) {
            // FIXME
            return null;
        }

        public Receiver send() {
            // FIXME
            return null;
        }

        public Sender<NxImageMessage> encodeImages(EncoderConfig c) {
            // FIXME
            return null;
        }


        // also does some rotation, etc, for front facing camera images
        public static final class EncoderConfig {
            int maxWidth;
            int maxHeight;
            float quality;
        }
    }


    final class Receiver<M extends NxMessage> extends Observable<M> {
        final Uri id;


        Receiver(Uri id) {
            super(new OnSubscribe<M>() {
                @Override
                public void call(Subscriber<? super M> subscriber) {

                }
            });
            this.id = id;
        }

        public Receiver<NxImageMessage> decodeImages(DecoderConfig c) {
            // FIXME
            return null;
        }


        public static final class DecoderConfig {
            int maxWidth;
            int maxHeight;
        }
    }



    interface CacheController {
        public void setSize(int bytes);
        public void trim(float f);
    }




    final class MessageStatus {
        static enum CompletionStatus {
            ACKED,
            CANCELED,
            PENDING
        }

        public final Uri id;
        public final CompletionStatus completionStatus;

        // TODO id, path, size, transfer stats

        MessageStatus(Uri id, CompletionStatus completionStatus) {
            this.id = id;
            this.completionStatus = completionStatus;
        }
    }

    final class Bind {
        public final Uri id;
        public final boolean open;

        Bind(Uri id, boolean open) {
            this.id = id;
            this.open = open;
        }
    }

    final class ReceivePath {
        public final Uri p;
        public final boolean open;

        ReceivePath(Uri p, boolean open) {
            this.p = p;
            this.open = open;
        }
    }




    enum Link {
        WIFI,
        CELL
    }

    final class ConnectivityEvent {
        public final Link link;
        public final boolean connected;

        ConnectivityEvent(Link link, boolean connected) {
            this.link = link;
            this.connected = connected;
        }
    }



    interface TransportFactory {
        Transport create(Link link, String host, int port) throws IOException;
    }



    interface Transport {
        void send(byte[] frame) throws IOException;
        rx.Observable<byte[]> receive();

        void open() throws IOException;
        void close() throws IOException;
    }


}
