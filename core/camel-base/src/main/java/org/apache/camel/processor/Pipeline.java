/*
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
package org.apache.camel.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExtendedCamelContext;
import org.apache.camel.Navigate;
import org.apache.camel.Processor;
import org.apache.camel.Traceable;
import org.apache.camel.spi.IdAware;
import org.apache.camel.spi.ReactiveExecutor;
import org.apache.camel.spi.RouteIdAware;
import org.apache.camel.support.AsyncProcessorConverterHelper;
import org.apache.camel.support.AsyncProcessorSupport;
import org.apache.camel.support.ExchangeHelper;
import org.apache.camel.support.service.ServiceHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static org.apache.camel.processor.PipelineHelper.continueProcessing;

/**
 * Creates a Pipeline pattern where the output of the previous step is sent as
 * input to the next step, reusing the same message exchanges
 */
public class Pipeline extends AsyncProcessorSupport implements Navigate<Processor>, Traceable, IdAware, RouteIdAware {

    private static final Logger LOG = LoggerFactory.getLogger(Pipeline.class);

    private final CamelContext camelContext;
    private final ReactiveExecutor reactiveExecutor;
    private List<AsyncProcessor> processors;
    private String id;
    private String routeId;

    public Pipeline(CamelContext camelContext, Collection<Processor> processors) {
        this.camelContext = camelContext;
        this.reactiveExecutor = camelContext.adapt(ExtendedCamelContext.class).getReactiveExecutor();
        this.processors = processors.stream().map(AsyncProcessorConverterHelper::convert).collect(Collectors.toList());
    }

    public static Processor newInstance(CamelContext camelContext, List<Processor> processors) {
        if (processors.isEmpty()) {
            return null;
        } else if (processors.size() == 1) {
            return processors.get(0);
        }
        return new Pipeline(camelContext, processors);
    }

    public static Processor newInstance(final CamelContext camelContext, final Processor... processors) {
        if (processors == null || processors.length == 0) {
            return null;
        } else if (processors.length == 1) {
            return processors[0];
        }

        final List<Processor> toBeProcessed = new ArrayList<>(processors.length);
        for (Processor processor : processors) {
            if (processor != null) {
                toBeProcessed.add(processor);
            }
        }

        return new Pipeline(camelContext, toBeProcessed);
    }

    @Override
    public boolean process(Exchange exchange, AsyncCallback callback) {
        if (exchange.isTransacted()) {
            reactiveExecutor.scheduleSync(() -> Pipeline.this.doProcess(exchange, callback, processors, new AtomicInteger(), true));
        } else {
            reactiveExecutor.scheduleMain(() -> Pipeline.this.doProcess(exchange, callback, processors, new AtomicInteger(), true));
        }
        return false;
    }

    protected void doProcess(Exchange exchange, AsyncCallback callback, List<AsyncProcessor> processors, AtomicInteger index, boolean first) {
        // optimize to use an atomic index counter for tracking how long we are in the processors list (uses less memory than iterator on array list)

        if (continueRouting(processors, index, exchange)
                && (first || continueProcessing(exchange, "so breaking out of pipeline", LOG))) {

            // prepare for next run
            ExchangeHelper.prepareOutToIn(exchange);

            // get the next processor
            AsyncProcessor processor = processors.get(index.getAndIncrement());

            processor.process(exchange, doneSync ->
                    reactiveExecutor.schedule(() -> doProcess(exchange, callback, processors, index, false)));
        } else {
            ExchangeHelper.copyResults(exchange, exchange);

            // logging nextExchange as it contains the exchange that might have altered the payload and since
            // we are logging the completion if will be confusing if we log the original instead
            // we could also consider logging the original and the nextExchange then we have *before* and *after* snapshots
            if (LOG.isTraceEnabled()) {
                LOG.trace("Processing complete for exchangeId: {} >>> {}", exchange.getExchangeId(), exchange);
            }

            reactiveExecutor.schedule(callback);
        }
    }

    protected boolean continueRouting(List<AsyncProcessor> list, AtomicInteger index, Exchange exchange) {
        Object stop = exchange.getProperty(Exchange.ROUTE_STOP);
        if (stop != null) {
            boolean doStop = exchange.getContext().getTypeConverter().convertTo(Boolean.class, stop);
            if (doStop) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ExchangeId: {} is marked to stop routing: {}", exchange.getExchangeId(), exchange);
                }
                return false;
            }
        }
        // continue if there are more processors to route
        boolean answer = index.get() < list.size();
        if (LOG.isTraceEnabled()) {
            LOG.trace("ExchangeId: {} should continue routing: {}", exchange.getExchangeId(), answer);
        }
        return answer;
    }

    @Override
    protected void doStart() throws Exception {
        ServiceHelper.startService(processors);
    }

    @Override
    protected void doStop() throws Exception {
        ServiceHelper.stopService(processors);
    }

    @Override
    public String toString() {
        return id;
    }

    @SuppressWarnings("unchecked")
    public List<Processor> getProcessors() {
        return (List) processors;
    }

    @Override
    public String getTraceLabel() {
        return "pipeline";
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getRouteId() {
        return routeId;
    }

    @Override
    public void setRouteId(String routeId) {
        this.routeId = routeId;
    }

    @Override
    public List<Processor> next() {
        if (!hasNext()) {
            return null;
        }
        return new ArrayList<>(processors);
    }

    @Override
    public boolean hasNext() {
        return processors != null && !processors.isEmpty();
    }
}
