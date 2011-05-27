/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.hawtdispatch.internal;

import org.fusesource.hawtdispatch.DispatchQueue;
import org.fusesource.hawtdispatch.Metrics;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class SerialDispatchQueue extends AbstractDispatchObject implements HawtDispatchQueue, Runnable {

    protected volatile String label;

    protected final AtomicBoolean triggered = new AtomicBoolean();
    protected final ConcurrentLinkedQueue<Runnable> externalQueue = new ConcurrentLinkedQueue<Runnable>();
    private final LinkedList<Runnable> localQueue = new LinkedList<Runnable>();
    private final ThreadLocal<Boolean> executing = new ThreadLocal<Boolean>();
    private MetricsCollector metricsCollector = InactiveMetricsCollector.INSTANCE;

    public SerialDispatchQueue(String label) {
        this.label = label;
    }

    public void execute(final Runnable runnable) {
        assert runnable != null;
        enqueue(metricsCollector.track(runnable));
    }

    private void enqueue(Runnable runnable) {
        // We can take a shortcut...
        if( executing.get()!=null ) {
            localQueue.add(runnable);
        } else {
            externalQueue.add(runnable);
            triggerExecution();
        }
    }

    public void run() {
        HawtDispatchQueue original = HawtDispatcher.CURRENT_QUEUE.get();
        HawtDispatcher.CURRENT_QUEUE.set(this);
        executing.set(Boolean.TRUE);
        try {
            Runnable runnable;
            while( (runnable = externalQueue.poll())!=null ) {
                localQueue.add(runnable);
            }
            while(true) {
                if( isSuspended() ) {
                    return;
                }
                runnable = localQueue.poll();
                if( runnable==null ) {
                    return;
                }
                try {
                    runnable.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } finally {
            executing.remove();
            HawtDispatcher.CURRENT_QUEUE.set(original);
            triggered.set(false);
            if( !externalQueue.isEmpty() || !localQueue.isEmpty()) {
                triggerExecution();
            }
        }
    }

    protected void triggerExecution() {
        if( triggered.compareAndSet(false, true) ) {
            getTargetQueue().execute(this);
        }
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public boolean isExecuting() {
        return executing.get()!=null;
    }

    public void assertExecuting() {
        assert isExecuting() : getDispatcher().assertMessage();
    }


    @Override
    protected void onStartup() {
        triggerExecution();
    }

    @Override
    protected void onResume() {
        triggerExecution();
    }

    public QueueType getQueueType() {
        return QueueType.SERIAL_QUEUE;
    }

    public void executeAfter(long delay, TimeUnit unit, Runnable runnable) {
        getDispatcher().timerThread.addRelative(runnable, this, delay, unit);
    }


    public DispatchQueue createQueue(String label) {
        DispatchQueue rc = getDispatcher().createQueue(label);
        rc.setTargetQueue(this);
        return rc;
    }

    public HawtDispatcher getDispatcher() {
        HawtDispatchQueue target = getTargetQueue();
        if (target ==null ) {
            throw new UnsupportedOperationException();
        }
        return target.getDispatcher();
    }

    public SerialDispatchQueue isSerialDispatchQueue() {
        return this;
    }

    public ThreadDispatchQueue isThreadDispatchQueue() {
        return null;
    }

    public GlobalDispatchQueue isGlobalDispatchQueue() {
        return null;
    }

    public void profile(boolean on) {
        if( !on && metricsCollector==InactiveMetricsCollector.INSTANCE )
            return;

        if( on ) {
            metricsCollector = new ActiveMetricsCollector(this);
            getDispatcher().track(this);
        } else {
//            getDispatcher().untrack(this);
            metricsCollector = InactiveMetricsCollector.INSTANCE;
        }
    }

    public Metrics metrics() {
        return metricsCollector.metrics();
    }

    private int drains() {
        return getDispatcher().drains;
    }



    @Override
    public String toString() {
        if( label == null ) {
            return "serial queue";
        } else {
            return "serial queue { label: \""+label+"\" }";
        }
    }
}
