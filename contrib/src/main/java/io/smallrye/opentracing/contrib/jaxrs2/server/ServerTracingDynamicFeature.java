/*
 * Copyright 2016-2020 The OpenTracing Authors
 * Copied from https://github.com/opentracing-contrib/java-jaxrs
 * Intended only for Jakarta namespace migration
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.smallrye.opentracing.contrib.jaxrs2.server;

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.ws.rs.Priorities;
import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.FeatureContext;

import org.eclipse.microprofile.opentracing.Traced;

import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import io.smallrye.opentracing.contrib.jaxrs2.serialization.InterceptorSpanDecorator;
import io.smallrye.opentracing.contrib.jaxrs2.server.OperationNameProvider.WildcardOperationName;

/**
 * This class has to be registered as JAX-RS provider to enable tracing of server requests. It also
 * requires {@link SpanFinishingFilter} for correct functionality. Spans are created in JAX-RS filter
 * and finished in servlet filter.
 *
 * @author Pavol Loffay
 */
public class ServerTracingDynamicFeature implements DynamicFeature {
    private static final Logger log = Logger.getLogger(
            ServerTracingDynamicFeature.class.getName());

    private final Builder builder;

    /**
     * When using this constructor application has to call {@link GlobalTracer#register} to register
     * tracer instance. Ideally it should be called in {@link javax.servlet.ServletContextListener}.
     *
     * For a custom configuration use {@link Builder#build()}.
     */
    public ServerTracingDynamicFeature() {
        this(new Builder(GlobalTracer.get()));
    }

    private ServerTracingDynamicFeature(Builder builder) {
        this.builder = builder;
    }

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        // TODO why it is called twice for the same endpoint
        if (!tracingDisabled(resourceInfo)) {
            log(resourceInfo);
            context.register(new ServerTracingFilter(
                    builder.tracer,
                    operationName(resourceInfo),
                    builder.spanDecorators,
                    builder.operationNameBuilder.build(resourceInfo.getResourceClass(), resourceInfo.getResourceMethod()),
                    builder.skipPattern != null ? Pattern.compile(builder.skipPattern) : null,
                    builder.joinExistingActiveSpan),
                    builder.priority);

            if (builder.traceSerialization) {
                context.register(new ServerTracingInterceptor(builder.tracer,
                        builder.serializationSpanDecorators), builder.serializationPriority);
            }
        }
    }

    private void log(ResourceInfo resourceInfo) {
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Registering tracing on %s#%s...",
                    resourceInfo.getResourceClass().getCanonicalName(),
                    resourceInfo.getResourceMethod().getName()));
        }
    }

    protected Traced closestTracedAnnotation(ResourceInfo resourceInfo) {
        Traced tracedAnnotation = resourceInfo.getResourceMethod().getAnnotation(Traced.class);
        if (tracedAnnotation == null) {
            tracedAnnotation = resourceInfo.getResourceClass().getAnnotation(Traced.class);
        }

        return tracedAnnotation;
    }

    protected boolean tracingDisabled(ResourceInfo resourceInfo) {
        Traced traced = closestTracedAnnotation(resourceInfo);
        return traced == null ? !builder.allTraced : !traced.value();
    }

    protected String operationName(ResourceInfo resourceInfo) {
        Traced traced = closestTracedAnnotation(resourceInfo);
        return traced != null && !traced.operationName().isEmpty() ? traced.operationName() : null;
    }

    /**
     * Builder for creating JAX-RS dynamic feature for tracing server requests.
     *
     * By default span's operation name is HTTP method and span is decorated with
     * {@link ServerSpanDecorator#STANDARD_TAGS} which adds standard tags.
     * If you want to set different span name provide custom span decorator {@link ServerSpanDecorator}.
     */
    public static class Builder {
        private final Tracer tracer;
        private boolean allTraced;
        private List<ServerSpanDecorator> spanDecorators;
        private List<InterceptorSpanDecorator> serializationSpanDecorators;
        private int priority;
        private int serializationPriority;
        private OperationNameProvider.Builder operationNameBuilder;
        private boolean traceSerialization;
        private String skipPattern;
        private boolean joinExistingActiveSpan;

        public Builder(Tracer tracer) {
            this.tracer = tracer;
            this.spanDecorators = Collections.singletonList(ServerSpanDecorator.STANDARD_TAGS);
            this.serializationSpanDecorators = Collections.singletonList(InterceptorSpanDecorator.STANDARD_TAGS);
            // by default do not use Priorities.AUTHENTICATION due to security concerns
            this.priority = Priorities.HEADER_DECORATOR;
            this.serializationPriority = Priorities.ENTITY_CODER;
            this.allTraced = true;
            this.operationNameBuilder = WildcardOperationName.newBuilder();
            this.traceSerialization = true;
            this.joinExistingActiveSpan = false;
        }

        /**
         * Only resources annotated with {@link Traced} will be traced.
         * 
         * @return builder
         */
        public Builder withTraceNothing() {
            allTraced = false;
            return this;
        }

        /**
         * Set span decorators.
         * 
         * @param spanDecorators span decorator
         * @return builder
         */
        public Builder withDecorators(List<ServerSpanDecorator> spanDecorators) {
            this.spanDecorators = spanDecorators;
            return this;
        }

        /**
         * Set serialization span decorators.
         * 
         * @return builder
         */
        public Builder withSerializationDecorators(List<InterceptorSpanDecorator> spanDecorators) {
            this.serializationSpanDecorators = spanDecorators;
            return this;
        }

        /**
         * @param priority the overriding priority for the registered component.
         *        Default is {@link Priorities#HEADER_DECORATOR}
         * @return builder
         *
         * @see Priorities
         */
        public Builder withPriority(int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * @param serializationPriority the overriding priority for the registered component.
         *        Default is {@link Priorities#ENTITY_CODER}
         * @return builder
         *
         * @see Priorities
         */
        public Builder withSerializationPriority(int serializationPriority) {
            this.serializationPriority = serializationPriority;
            return this;
        }

        /**
         * @param builder the builder for operation name provider
         * @return
         */
        public Builder withOperationNameProvider(OperationNameProvider.Builder builder) {
            this.operationNameBuilder = builder;
            return this;
        }

        /**
         * @param traceSerialization whether to trace serialization
         * @return builder
         */
        public Builder withTraceSerialization(boolean traceSerialization) {
            this.traceSerialization = traceSerialization;
            return this;
        }

        /**
         * @param skipPattern skip pattern e.g. /health|/status
         * @return builder
         */
        public Builder withSkipPattern(String skipPattern) {
            this.skipPattern = skipPattern;
            return this;
        }

        /**
         * @param joinExistingActiveSpan If true, any active span on the on the current thread will
         *        be used as a parent span. If false, parent span will be
         *        extracted from HTTP headers.
         *        This feature can be used when chaining spans from lower
         *        instrumentation layers e.g. servlet instrumentation.
         *        Default is false.
         * @return builder
         */
        public Builder withJoinExistingActiveSpan(boolean joinExistingActiveSpan) {
            this.joinExistingActiveSpan = joinExistingActiveSpan;
            return this;
        }

        /**
         * @return server tracing dynamic feature. This feature should be manually registered to
         *         {@link javax.ws.rs.core.Application}
         */
        public ServerTracingDynamicFeature build() {
            return new ServerTracingDynamicFeature(this);
        }
    }
}
