/**
 * Copyright (c) 2016 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.telemetry.impl;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.telemetry.SenderFactory;
import org.eclipse.hono.telemetry.TelemetryConstants;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.ResourceIdentifier;
import org.junit.Test;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonHelper;
import io.vertx.proton.ProtonSender;

/**
 * 
 *
 */
public class ForwardingTelemetryAdapterTest {

    /**
     * 
     */
    private static final String TELEMETRY_MSG_CONTENT = "hello";
    private static final String CLIENT_ID = "protocol_adapter";
    private static final String DEVICE_ID = "myDevice";

    @Test
    public void testProcessTelemetryDataForwardsMessageToSender() throws InterruptedException {

        // GIVEN an adapter with a connection to a downstream container
        final CountDownLatch latch = new CountDownLatch(1);
        ProtonSender sender = mock(ProtonSender.class);
        when(sender.isOpen()).thenReturn(Boolean.TRUE);
        when(sender.send(any(byte[].class), any(Message.class))).then(invocation -> {
            latch.countDown();
            return null;
        });
        ForwardingTelemetryAdapter adapter = new ForwardingTelemetryAdapter(newMockSenderFactory(sender));
        adapter.addSender(CLIENT_ID, sender);

        // WHEN processing a telemetry message
        Message msg = ProtonHelper.message(TELEMETRY_MSG_CONTENT);
        MessageHelper.addDeviceId(msg, DEVICE_ID);
        adapter.processTelemetryData(msg, CLIENT_ID);

        // THEN the message has been delivered to the downstream container
        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testClientAttachedResumesClientOnSuccess() throws InterruptedException {
        final CountDownLatch errorMessageSent = new CountDownLatch(1);
        final EventBus eventBus = mock(EventBus.class);
        when(eventBus.send(
                TelemetryConstants.EVENT_BUS_ADDRESS_TELEMETRY_FLOW_CONTROL,
                TelemetryConstants.getFlowControlMsg(CLIENT_ID, false)))
            .then(invocation -> {
                errorMessageSent.countDown();
                return eventBus;
            });
        final Vertx vertx = mock(Vertx.class);
        final Context ctx = mock(Context.class);
        when(vertx.eventBus()).thenReturn(eventBus);

        final ProtonSender sender = newMockSender();
        when(sender.sendQueueFull()).thenReturn(false);

        final ProtonConnection con = mock(ProtonConnection.class);
        final SenderFactory senderFactory = newMockSenderFactory(sender);

        // GIVEN an adapter with a connection to the downstream container
        ForwardingTelemetryAdapter adapter = new ForwardingTelemetryAdapter(senderFactory);
        adapter.init(vertx, ctx);
        adapter.setDownstreamConnection(con);

        // WHEN a client wants to attach to Hono for uploading telemetry data
        final ResourceIdentifier targetAddress = ResourceIdentifier.from(TelemetryConstants.NODE_ADDRESS_TELEMETRY_PREFIX, "myTenant", null);
        adapter.processLinkAttachedMessage(CLIENT_ID, targetAddress.toString(), TelemetryConstants.EVENT_BUS_ADDRESS_TELEMETRY_FLOW_CONTROL);

        // THEN assert that an error message has been sent via event bus
        assertTrue(errorMessageSent.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testClientAttachedClosesLinkIfDownstreamConnectionIsBroken() {
        Vertx vertx = mock(Vertx.class);
        Context ctx = mock(Context.class);
        EventBus eventBus = mock(EventBus.class);
        when(vertx.eventBus()).thenReturn(eventBus);

        // GIVEN an adapter without connection to the downstream container
        ForwardingTelemetryAdapter adapter = new ForwardingTelemetryAdapter(newMockSenderFactory(null));
        adapter.init(vertx, ctx);

        // WHEN a client wants to attach to Hono for uploading telemetry data
        final ResourceIdentifier targetAddress = ResourceIdentifier.from(TelemetryConstants.NODE_ADDRESS_TELEMETRY_PREFIX, "myTenant", null);
        adapter.processLinkAttachedMessage(CLIENT_ID, targetAddress.toString(), TelemetryConstants.EVENT_BUS_ADDRESS_TELEMETRY_FLOW_CONTROL);

        // THEN assert that an error message has been sent via event bus
        verify(eventBus).send(TelemetryConstants.EVENT_BUS_ADDRESS_TELEMETRY_FLOW_CONTROL, TelemetryConstants.getErrorMessage(CLIENT_ID, true));
    }

    @SuppressWarnings("unchecked")
    private ProtonSender newMockSender() {
        ProtonSender sender = mock(ProtonSender.class);
        when(sender.isOpen()).thenReturn(Boolean.TRUE);
        when(sender.openHandler(any(Handler.class))).thenReturn(sender);
        return sender;
    }

    private SenderFactory newMockSenderFactory(final ProtonSender senderToCreate) {
        return (connection, address, resultHandler) -> resultHandler.complete(senderToCreate);
    }
}
