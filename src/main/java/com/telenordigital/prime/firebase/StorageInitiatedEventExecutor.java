package com.telenordigital.prime.firebase;

import com.telenordigital.prime.events.PurchaseRequest;
import com.telenordigital.prime.events.PurchaseRequestListener;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class StorageInitiatedEventExecutor {
    private ExecutorService executor;
    private final Object monitor = new Object();

    private final Set<PurchaseRequestListener> purchaseRequestListeners;

    public StorageInitiatedEventExecutor() {
        final ThreadFactory tf = new ThreadProducer();
        this.executor = Executors.newCachedThreadPool(tf);
        this.purchaseRequestListeners = new HashSet<>();
    }


    public void addPurchaseRequestListener(PurchaseRequestListener listener) {

        synchronized (monitor) {
            purchaseRequestListeners.add(listener);
        }
    }

    private final class ThreadProducer implements ThreadFactory {

        private final ThreadFactory tf = Executors.defaultThreadFactory();

        @Override
        public Thread newThread(Runnable r) {
            final Thread t = tf.newThread(r);
            t.setName("FbstorageEventHandler");
            return t;
        }
    }

    public void onPurchaseRequest(final PurchaseRequest req) {
        synchronized (monitor) {
            for (final PurchaseRequestListener l : purchaseRequestListeners) {
                applyPurchaseRequestThroughExecutor(req, l);
            }
        }
    }

    private void applyPurchaseRequestThroughExecutor(
            final PurchaseRequest req,
            final PurchaseRequestListener l) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                l.onPurchaseRequest(req);
            }
        });
    }
}
