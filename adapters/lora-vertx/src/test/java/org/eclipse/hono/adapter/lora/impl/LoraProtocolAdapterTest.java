/*******************************************************************************
 * Copyright (c) 2019, 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.adapter.lora.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.adapter.lora.LoraConstants;
import org.eclipse.hono.adapter.lora.LoraMessage;
import org.eclipse.hono.adapter.lora.LoraMessageType;
import org.eclipse.hono.adapter.lora.LoraMetaData;
import org.eclipse.hono.adapter.lora.LoraProtocolAdapterProperties;
import org.eclipse.hono.adapter.lora.UplinkLoraMessage;
import org.eclipse.hono.adapter.lora.providers.LoraProvider;
import org.eclipse.hono.adapter.lora.providers.LoraProviderMalformedPayloadException;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.DownstreamSender;
import org.eclipse.hono.client.DownstreamSenderFactory;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.RegistrationClient;
import org.eclipse.hono.client.RegistrationClientFactory;
import org.eclipse.hono.client.TenantClient;
import org.eclipse.hono.client.TenantClientFactory;
import org.eclipse.hono.service.auth.DeviceUser;
import org.eclipse.hono.service.http.HttpContext;
import org.eclipse.hono.service.http.TracingHandler;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.TenantObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.Tracer.SpanBuilder;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.proton.ProtonDelivery;

/**
 * Verifies behavior of {@link LoraProtocolAdapter}.
 */
public class LoraProtocolAdapterTest {

    private static final int TEST_FUNCTION_PORT = 2;
    private static final String TEST_TENANT_ID = "myTenant";
    private static final String TEST_GATEWAY_ID = "myLoraGateway";
    private static final String TEST_DEVICE_ID = "0102030405060708";
    private static final byte[] TEST_PAYLOAD = "bumxlux".getBytes(StandardCharsets.UTF_8);
    private static final String TEST_PROVIDER = "bumlux";

    private static TenantClientFactory tenantClientFactory;
    private static RegistrationClientFactory registrationClientFactory;
    private static DownstreamSenderFactory downstreamSenderFactory;

    private LoraProtocolAdapter adapter;
    private DownstreamSender telemetrySender;
    private DownstreamSender eventSender;
    private Tracer tracer;
    private Span currentSpan;

    /**
     * Creates the client factories for the registry services.
     */
    @SuppressWarnings("unchecked")
    @BeforeAll
    public static void createServiceClientFactories() {
        tenantClientFactory = mock(TenantClientFactory.class);
        when(tenantClientFactory.connect()).thenReturn(Future.succeededFuture(mock(HonoConnection.class)));
        doAnswer(invocation -> {
            final Handler<AsyncResult<Void>> shutdownHandler = invocation.getArgument(0);
            shutdownHandler.handle(Future.succeededFuture());
            return null;
        }).when(tenantClientFactory).disconnect(any(Handler.class));

        registrationClientFactory = mock(RegistrationClientFactory.class);
        when(registrationClientFactory.connect()).thenReturn(Future.succeededFuture(mock(HonoConnection.class)));
        doAnswer(invocation -> {
            final Handler<AsyncResult<Void>> shutdownHandler = invocation.getArgument(0);
            shutdownHandler.handle(Future.succeededFuture());
            return null;
        }).when(registrationClientFactory).disconnect(any(Handler.class));

        downstreamSenderFactory = mock(DownstreamSenderFactory.class);
        when(downstreamSenderFactory.connect()).thenReturn(Future.succeededFuture(mock(HonoConnection.class)));
        doAnswer(invocation -> {
            final Handler<AsyncResult<Void>> shutdownHandler = invocation.getArgument(0);
            shutdownHandler.handle(Future.succeededFuture());
            return null;
        }).when(downstreamSenderFactory).disconnect(any(Handler.class));
    }

    /**
     * Sets up the fixture.
     */
    @BeforeEach
    public void setUp() {

        final TenantClient tenantClient = mock(TenantClient.class);
        doAnswer(invocation -> {
            return Future.succeededFuture(TenantObject.from(invocation.getArgument(0), true));
        }).when(tenantClient).get(anyString(), (SpanContext) any());
        doAnswer(invocation -> {
            return Future.succeededFuture(TenantObject.from(invocation.getArgument(0), true));
        }).when(tenantClient).get(anyString());
        when(tenantClientFactory.getOrCreateTenantClient()).thenReturn(Future.succeededFuture(tenantClient));

        final RegistrationClient regClient = mock(RegistrationClient.class);
        when(regClient.assertRegistration(anyString(), any(), (SpanContext) any())).thenReturn(Future.succeededFuture(new JsonObject()));
        when(registrationClientFactory.getOrCreateRegistrationClient(anyString())).thenReturn(Future.succeededFuture(regClient));

        telemetrySender = mock(DownstreamSender.class);
        when(telemetrySender.send(any(Message.class), (SpanContext) any())).thenReturn(Future.succeededFuture(mock(ProtonDelivery.class)));
        when(telemetrySender.sendAndWaitForOutcome(any(Message.class), (SpanContext) any())).thenReturn(
                Future.succeededFuture(mock(ProtonDelivery.class)));
        when(downstreamSenderFactory.getOrCreateTelemetrySender(anyString())).thenReturn(Future.succeededFuture(telemetrySender));

        eventSender = mock(DownstreamSender.class);
        when(eventSender.send(any(Message.class), (SpanContext) any())).thenThrow(new UnsupportedOperationException());
        when(eventSender.sendAndWaitForOutcome(any(Message.class), (SpanContext) any())).thenReturn(Future.succeededFuture(mock(ProtonDelivery.class)));
        when(downstreamSenderFactory.getOrCreateEventSender(anyString())).thenReturn(Future.succeededFuture(eventSender));

        currentSpan = mock(Span.class);
        when(currentSpan.context()).thenReturn(mock(SpanContext.class));

        final SpanBuilder spanBuilder = mock(SpanBuilder.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(spanBuilder.start()).thenReturn(currentSpan);

        tracer = mock(Tracer.class);
        when(tracer.buildSpan(anyString())).thenReturn(spanBuilder);

        adapter = new LoraProtocolAdapter();
        adapter.setConfig(new LoraProtocolAdapterProperties());
        adapter.setTenantClientFactory(tenantClientFactory);
        adapter.setRegistrationClientFactory(registrationClientFactory);
        adapter.setDownstreamSenderFactory(downstreamSenderFactory);
        adapter.setTracer(tracer);
    }

    /**
     * Verifies that an uplink message is routed to a provider correctly.
     */
    @Test
    public void handleProviderRouteSuccessfullyForUplinkMessage() {

        final LoraProvider providerMock = getLoraProviderMock();
        final HttpContext httpContext = newHttpContext();
        final HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.getHeader(eq(Constants.HEADER_QOS_LEVEL))).thenReturn(null);
        when(httpContext.request()).thenReturn(request);

        adapter.handleProviderRoute(httpContext, providerMock);

        verify(httpContext.getRoutingContext()).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);

        final ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(telemetrySender).send(message.capture(), any(SpanContext.class));
        assertThat(MessageHelper.getPayload(message.getValue())).isEqualTo(Buffer.buffer(TEST_PAYLOAD));
        assertThat(message.getValue().getContentType()).isEqualTo(LoraConstants.CONTENT_TYPE_LORA_BASE + providerMock.getProviderName());
        final ApplicationProperties props = message.getValue().getApplicationProperties();
        assertThat(MessageHelper.getApplicationProperty(props, LoraConstants.APP_PROPERTY_FUNCTION_PORT, Integer.class))
            .isEqualTo(TEST_FUNCTION_PORT);
        final String metaData = MessageHelper.getApplicationProperty(props, LoraConstants.APP_PROPERTY_META_DATA, String.class);
        assertThat(metaData).isNotNull();
        final JsonObject metaDataJson = new JsonObject(metaData);
        assertThat(metaDataJson.getInteger(LoraConstants.APP_PROPERTY_FUNCTION_PORT)).isEqualTo(TEST_FUNCTION_PORT);
        verify(httpContext.getRoutingContext().response()).setStatusCode(HttpResponseStatus.ACCEPTED.code());
        verify(currentSpan).finish();
    }

    /**
     * Verifies that an options request is routed to a provider correctly.
     */
    @Test
    public void handleProviderRouteSuccessfullyForOptionsRequest() {
        final HttpContext httpContext = newHttpContext();

        adapter.handleOptionsRoute(httpContext.getRoutingContext());

        verify(httpContext.getRoutingContext().response()).setStatusCode(HttpResponseStatus.OK.code());
        verify(currentSpan).finish();
    }

    /**
     * Verifies that the provider route discards join messages.
     */
    @Test
    public void handleProviderRouteDiscardsJoinMessages() {
        final LoraMessage message = mock(LoraMessage.class);
        when(message.getType()).thenReturn(LoraMessageType.JOIN);
        final LoraProvider providerMock = getLoraProviderMock(message);
        final HttpContext httpContext = newHttpContext();

        adapter.handleProviderRoute(httpContext, providerMock);

        verify(httpContext.getRoutingContext()).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verify(httpContext.getRoutingContext().response()).setStatusCode(HttpResponseStatus.ACCEPTED.code());
        verify(currentSpan).finish();
    }

    /**
     * Verifies that the provider route discards downlink messages.
     */
    @Test
    public void handleProviderRouteDiscardsDownlinkMessages() {
        final LoraMessage message = mock(LoraMessage.class);
        when(message.getType()).thenReturn(LoraMessageType.DOWNLINK);
        final LoraProvider providerMock = getLoraProviderMock(message);
        final HttpContext httpContext = newHttpContext();

        adapter.handleProviderRoute(httpContext, providerMock);

        verify(httpContext.getRoutingContext()).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verify(httpContext.response()).setStatusCode(HttpResponseStatus.ACCEPTED.code());
        verify(currentSpan).finish();
    }

    /**
     * Verifies that the provider route discards other messages.
     */
    @Test
    public void handleProviderRouteDiscardsOtherMessages() {
        final LoraMessage message = mock(LoraMessage.class);
        when(message.getType()).thenReturn(LoraMessageType.UNKNOWN);
        final LoraProvider providerMock = getLoraProviderMock(message);
        final HttpContext httpContext = newHttpContext();

        adapter.handleProviderRoute(httpContext, providerMock);

        verify(httpContext.getRoutingContext()).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verify(httpContext.response()).setStatusCode(HttpResponseStatus.ACCEPTED.code());
        verify(currentSpan).finish();
    }

    /**
     * Verifies that the provider route rejects invalid gateway credentials with unauthorized.
     */
    @Test
    public void handleProviderRouteCausesUnauthorizedForInvalidGatewayCredentials() {
        final LoraProvider providerMock = getLoraProviderMock();
        final HttpContext httpContext = newHttpContext();
        when(httpContext.getRoutingContext().user()).thenReturn(null);

        adapter.handleProviderRoute(httpContext, providerMock);

        verify(httpContext.getRoutingContext()).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verifyUnauthorized(httpContext.getRoutingContext());
        verify(currentSpan).finish();
    }

    /**
     * Verifies that the provider route rejects a request if the request body cannot
     * be parsed.
     */
    @Test
    public void handleProviderRouteCausesBadRequestForFailureToParseBody() {

        final LoraProvider providerMock = getLoraProviderMock();
        when(providerMock.getMessage(any(RoutingContext.class))).thenThrow(new LoraProviderMalformedPayloadException("no device ID"));
        final HttpContext httpContext = newHttpContext();

        adapter.handleProviderRoute(httpContext, providerMock);

        verify(httpContext.getRoutingContext()).put(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER, TEST_PROVIDER);
        verify(telemetrySender, never()).send(any(Message.class), any(SpanContext.class));
        verify(telemetrySender, never()).sendAndWaitForOutcome(any(Message.class), any(SpanContext.class));
        verifyBadRequest(httpContext.getRoutingContext());
        verify(currentSpan).finish();
    }

    /**
     * Verifies that the provider name is added to the message when using customized downstream message.
     */
    @Test
    public void customizeDownstreamMessageAddsProviderNameToMessage() {
        final HttpContext httpContext = newHttpContext();
        final Message messageMock = mock(Message.class);

        adapter.customizeDownstreamMessage(messageMock, httpContext);

        verify(messageMock).setApplicationProperties(argThat(properties -> TEST_PROVIDER
                .equals(properties.getValue().get(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER))));
    }

    private LoraProvider getLoraProviderMock() {
        final UplinkLoraMessage message = new UplinkLoraMessage(TEST_DEVICE_ID);
        message.setPayload(Buffer.buffer(TEST_PAYLOAD));
        return getLoraProviderMock(message);
    }

    private LoraProvider getLoraProviderMock(final LoraMessage message) {
        final LoraProvider provider = mock(LoraProvider.class);
        when(provider.getProviderName()).thenReturn(TEST_PROVIDER);
        when(provider.pathPrefixes()).thenReturn(Set.of("/bumlux"));
        when(provider.getMessage(any(RoutingContext.class))).thenReturn(message);

        return provider;
    }

    private HttpContext newHttpContext() {

        final LoraMetaData metaData = new LoraMetaData();
        metaData.setFunctionPort(TEST_FUNCTION_PORT);

        final RoutingContext context = mock(RoutingContext.class);
        when(context.getBody()).thenReturn(Buffer.buffer());
        when(context.user()).thenReturn(new DeviceUser(TEST_TENANT_ID, TEST_GATEWAY_ID));
        when(context.response()).thenReturn(mock(HttpServerResponse.class));
        when(context.get(LoraConstants.APP_PROPERTY_ORIG_LORA_PROVIDER)).thenReturn(TEST_PROVIDER);
        when(context.get(LoraConstants.APP_PROPERTY_META_DATA)).thenReturn(metaData);
        final Span parentSpan = mock(Span.class);
        when(parentSpan.context()).thenReturn(mock(SpanContext.class));
        when(context.get(TracingHandler.CURRENT_SPAN)).thenReturn(parentSpan);

        return HttpContext.from(context);
    }

    private void verifyUnauthorized(final RoutingContext routingContextMock) {
        verifyErrorCode(routingContextMock, HttpResponseStatus.UNAUTHORIZED);
    }

    private void verifyBadRequest(final RoutingContext routingContextMock) {
        verifyErrorCode(routingContextMock, HttpResponseStatus.BAD_REQUEST);
    }

    private void verifyErrorCode(final RoutingContext routingContextMock, final HttpResponseStatus expectedStatusCode) {
        final ArgumentCaptor<ClientErrorException> failCaptor = ArgumentCaptor.forClass(ClientErrorException.class);
        verify(routingContextMock).fail(failCaptor.capture());
        assertEquals(expectedStatusCode.code(), failCaptor.getValue().getErrorCode());
    }
}
