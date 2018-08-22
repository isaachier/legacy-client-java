/*
 * Copyright (c) 2016, Uber Technologies, Inc
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

package com.uber.jaeger.propagation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.jaeger.Span;
import com.uber.jaeger.filters.jaxrs2.ClientFilter;
import com.uber.jaeger.filters.jaxrs2.ServerRequestCarrier;
import com.uber.jaeger.reporters.InMemoryReporter;
import com.uber.jaeger.samplers.ConstSampler;
import io.jaegertracing.internal.metrics.InMemoryMetricsFactory;
import io.jaegertracing.internal.propagation.TextMapCodec;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FilterIntegrationTest {
  private JerseyServer server;
  private Client client;
  private Tracer tracer;
  private InMemoryReporter reporter;
  private InMemoryMetricsFactory metricsFactory;
  public static final String BAGGAGE_KEY = "a-big-metal-door";
  private ObjectMapper mapper = new ObjectMapper();

  // http://www.syfy.com/darkmatter/videos/a-big-metal-door
  public static final String BAGGAGE_VALUE = "I-Keep-It-Locked-Always";

  @Before
  public void setUp() throws Exception {
    metricsFactory = new InMemoryMetricsFactory();
    reporter = new InMemoryReporter();
    tracer =
        new com.uber.jaeger.Tracer.Builder("some-op-name")
            .withReporter(reporter)
            .withSampler(new ConstSampler(true))
            .withMetricsFactory(metricsFactory)
            .build();

    // start the server
    server = new JerseyServer(tracer);
    server.start();
    // create the client
    client =
        ClientBuilder.newClient()
            .register(new ClientFilter(tracer))
            .register(JacksonFeature.class);
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
  }

  @Test
  public void testJerseyClientReceivesSpan() throws Exception {
    WebTarget target = client.target(server.BASE_URI).path("jersey").path("hop1");

    Span span = (Span) tracer.buildSpan("root-span").startManual();
    span.setBaggageItem(BAGGAGE_KEY, BAGGAGE_VALUE);
    tracer.scopeManager().activate(span, false);

    Response resp = target.request(MediaType.APPLICATION_JSON_TYPE).get();

    String responseStr = resp.readEntity(String.class);
    CallTreeNode callTree = mapper.readValue(responseStr, CallTreeNode.class);

    String strContext = TextMapCodec.contextAsString(span.context());
    String traceId = strContext.substring(0, strContext.indexOf(':'));
    boolean isSampled = true;

    assertEquals(6, reporter.getSpans().size());
    assertTrue(callTree.validateTraceIds(traceId, isSampled));

    assertEquals(
        3L, metricsFactory.getCounter("jaeger:traces", "sampled=y,state=joined"));
    assertEquals(
        6L,
        metricsFactory.getCounter("jaeger:finished_spans", ""));
    assertEquals(
        1L, metricsFactory.getCounter("jaeger:traces", "sampled=y,state=started"));
    assertEquals(
        7L, metricsFactory.getCounter("jaeger:started_spans", "sampled=y"));
  }

  /*
   * This test exists because opentracing's convention around missing tracer
   * state headers may change to stop supporting the automatic creation of
   * building a span.
   */
  @Test
  public void testExtractorReturnsNullWhenTracerStateHeaderIsMissing() {
    ContainerRequestContext reqContext = mock(ContainerRequestContext.class);
    given(reqContext.getHeaders()).willReturn(new MultivaluedHashMap<String, String>());
    ServerRequestCarrier carrier = new ServerRequestCarrier(reqContext);
    SpanContext spanCtx = tracer.extract(Format.Builtin.HTTP_HEADERS, carrier);
    assertNull(spanCtx);
  }
}
