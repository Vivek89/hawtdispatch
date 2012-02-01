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
package org.fusesource.hawtdispatch.transport;

import org.fusesource.hawtdispatch.CustomDispatchSource;
import org.fusesource.hawtdispatch.Dispatch;
import org.fusesource.hawtdispatch.DispatchQueue;
import org.fusesource.hawtdispatch.EventAggregators;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class PipeTransportServer implements TransportServer {

    protected String connectURI;
    protected TransportServerListener listener;
    protected String name;
    protected boolean marshal;
    protected final AtomicInteger connectionCounter = new AtomicInteger();
    DispatchQueue dispatchQueue;

    private CustomDispatchSource<PipeTransport,LinkedList<PipeTransport>> acceptSource;


    public String getBoundAddress() {
        return connectURI;
    }

    public InetSocketAddress getSocketAddress() {
        return null;
    }

    public DispatchQueue getDispatchQueue() {
        return dispatchQueue;
    }

    public void setDispatchQueue(DispatchQueue queue) {
        dispatchQueue = queue;
    }

    public void suspend() {
        acceptSource.suspend();
    }

    public void resume() {
        acceptSource.resume();
    }

    public void setTransportServerListener(TransportServerListener listener) {
        this.listener = listener;
    }

    public void start() throws Exception {
        start(null);
    }
    public void start(Runnable onCompleted) throws Exception {
        acceptSource = Dispatch.createSource(EventAggregators.<PipeTransport>linkedList(), dispatchQueue);
        acceptSource.setEventHandler(new Runnable() {
            public void run() {
                LinkedList<PipeTransport> transports = acceptSource.getData();
                for (PipeTransport transport : transports) {
                    try {
                        listener.onAccept(transport);
                    } catch (Exception e) {
                        listener.onAcceptError(e);
                    }
                }
            }
        });
        acceptSource.resume();
        if( onCompleted!=null ) {
            dispatchQueue.execute(onCompleted);
        }
    }

    public void stop() throws Exception {
        stop(null);
    }
    public void stop(Runnable onCompleted) throws Exception {
        PipeTransportRegistry.unbind(this);
        acceptSource.setCancelHandler(onCompleted);
        acceptSource.cancel();
    }

    public void setConnectURI(String connectURI) {
        this.connectURI = connectURI;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public PipeTransport connect() {
        int connectionId = connectionCounter.incrementAndGet();
        String remoteAddress = connectURI.toString() + "#" + connectionId;
        assert this.listener != null : "Server does not have an accept listener";

        PipeTransport clientTransport = createClientTransport();
        PipeTransport serverTransport = createServerTransport();
        clientTransport.peer = serverTransport;
        serverTransport.peer = clientTransport;

        clientTransport.setRemoteAddress(remoteAddress);
        serverTransport.setRemoteAddress(remoteAddress);

        serverTransport.setMarshal(marshal);
        this.acceptSource.merge(serverTransport);
        return clientTransport;
    }

    protected PipeTransport createClientTransport() {
        return new PipeTransport(this);
    }
    
    protected PipeTransport createServerTransport() {
        return new PipeTransport(this);
    }

    public boolean isMarshal() {
        return marshal;
    }

    public void setMarshal(boolean marshal) {
        this.marshal = marshal;
    }
}
