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

package io.openshift.booster.messaging;

import io.reactivex.Completable;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.proton.ProtonClient;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;
import io.vertx.reactivex.CompletableHelper;
import io.vertx.reactivex.config.ConfigRetriever;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.impl.AsyncResultCompletable;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import io.vertx.reactivex.ext.web.handler.StaticHandler;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.message.Message;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class Frontend extends AbstractVerticle {
  private static final Logger LOGGER = LoggerFactory.getLogger(Frontend.class);
  private static final String ID = "frontend-vertx-" + UUID.randomUUID()
    .toString().substring(0, 4);

  private ProtonSender requestSender;
  private ProtonReceiver responseReceiver;

  private final AtomicInteger requestSequence = new AtomicInteger(0);
  private final Queue<Message> requestMessages = new ConcurrentLinkedQueue<>();
  private final Data data = new Data();

  @Override
  public void start(Future<Void> future) {
    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());
    router.post("/api/send-request").handler(this::handleSendRequest);
    router.get("/api/receive-response").handler(this::handleReceiveResponse);
    router.get("/api/data").handler(this::handleGetData);
    router.get("/health").handler(rc -> rc.response().end("OK"));
    router.get("/*").handler(StaticHandler.create());

    ConfigRetriever.create(vertx).rxGetConfig()
      .flatMapCompletable(json -> {
        String amqpHost = json.getString("MESSAGING_SERVICE_HOST", "localhost");
        int amqpPort = json.getInteger("MESSAGING_SERVICE_PORT", 5672);
        //String amqpUser = json.getString("MESSAGING_SERVICE_USER", "work-queue");
        String amqpUser = json.getString("MESSAGING_SERVICE_USER", "");
        // String amqpPassword = json.getString("MESSAGING_SERVICE_PASSWORD", "work-queue");
        String amqpPassword = json.getString("MESSAGING_SERVICE_PASSWORD", "");

        String httpHost = json.getString("HTTP_HOST", "0.0.0.0");
        int httpPort = json.getInteger("HTTP_PORT", 8080);

        // AMQP
        ProtonClient client = ProtonClient.create(vertx.getDelegate());
        Future<Void> connected = Future.future();
        client.connect(amqpHost, amqpPort, amqpUser, amqpPassword, result -> {
          if (result.failed()) {
            LOGGER.error("MESSAGING_SERVICE_HOST " + amqpHost);
            LOGGER.error("MESSAGING_SERVICE_PORT " + amqpPort);
            connected.fail(result.cause());
          } else {
            ProtonConnection conn = result.result();
            conn.setContainer(ID);
            conn.open();

            sendRequests(conn);
            receiveWorkerUpdates(conn);
            pruneStaleWorkers();
            connected.complete();
          }
        });

        Completable brokerConnected = new AsyncResultCompletable(connected::setHandler);
        Completable serverStarted = vertx.createHttpServer()
          .requestHandler(router::accept)
          .rxListen(httpPort, httpHost)
          .toCompletable();

        return brokerConnected.andThen(serverStarted);
      })
      .subscribe(CompletableHelper.toObserver(future));
  }

  private void sendRequests(ProtonConnection conn) {
    requestSender = conn.createSender("work-requests");

    // Using a null address and setting the source dynamic tells
    // the remote peer to generate the reply address.
    responseReceiver = conn.createReceiver(null);
    Source source = (Source) responseReceiver.getSource();
    source.setDynamic(true);

    responseReceiver.openHandler(result -> requestSender.sendQueueDrainHandler(s -> doSendRequests()));

    responseReceiver.handler((delivery, message) -> {
      Map props = message.getApplicationProperties().getValue();
      String workerId = (String) props.get("workerId");
      String cloudId = (String) props.get("AMQ_LOCATION_KEY");
      String requestId = (String) message.getCorrelationId();
      String text = (String) ((AmqpValue) message.getBody()).getValue();

      /*
          trim down to the relevant substring      
      */

      int lastIndex = workerId.lastIndexOf("-");
      String uniquePart = workerId.substring(lastIndex + 1);
      // LOGGER.info("ONPREM: " + uniquePart);
      Response response = new Response(requestId, uniquePart, cloudId, text);

      data.getResponses().put(response.getRequestId(), response);

      LOGGER.info("{0}: Received {1}", ID, response);
    });

    requestSender.open();
    responseReceiver.open();
  }

  private void doSendRequests() {
    if (responseReceiver == null) {
      return;
    }

    if (responseReceiver.getRemoteSource().getAddress() == null) {
      return;
    }

    while (!requestSender.sendQueueFull()) {
      Message message = requestMessages.poll();

      if (message == null) {
        break;
      }

      message.setReplyTo(responseReceiver.getRemoteSource().getAddress());

      requestSender.send(message);

      LOGGER.info("{0}: Sent {1}", ID, message);
    }
  }

  private void receiveWorkerUpdates(ProtonConnection conn) {
    ProtonReceiver receiver = conn.createReceiver("worker-updates");

    receiver.handler((delivery, message) -> {
      Map props = message.getApplicationProperties().getValue();
      String workerId = (String) props.get("workerId");
      String cloud = (String) props.get("AMQ_LOCATION_KEY");
      long timestamp = (long) props.get("timestamp");
      long requestsProcessed = (long) props.get("requestsProcessed");
      long processingErrors = (long) props.get("processingErrors");

      WorkerUpdate update = new WorkerUpdate(workerId, cloud, timestamp, requestsProcessed,
        processingErrors);

      data.getWorkers().put(update.getWorkerId(), update);
    });

    receiver.open();
  }

  private void handleSendRequest(RoutingContext rc) {
    String json = rc.getBodyAsString();
    String requestId = ID + "/" + requestSequence.incrementAndGet();
    Request request = Json.decodeValue(json, Request.class);
    Map<String, Object> props = new HashMap<>();
    props.put("uppercase", request.isUppercase());
    props.put("reverse", request.isReverse());
    
    Message message = Message.Factory.create();
    message.setMessageId(requestId);
    message.setAddress("work-requests");
    message.setBody(new AmqpValue(request.getText()));
    message.setApplicationProperties(new ApplicationProperties(props));

    requestMessages.add(message);

    data.getRequestIds().add(requestId);

    doSendRequests();

    rc.response().setStatusCode(202).end(requestId);
  }

  private void handleReceiveResponse(RoutingContext rc) {
    String value = rc.request().getParam("request");

    if (value == null) {
      rc.fail(500);
      return;
    }

    Response response = data.getResponses().get(value);

    if (response == null) {
      rc.response().setStatusCode(404).end();
      return;
    }

    rc.response()
      .setStatusCode(200)
      .putHeader("Content-Type", "application/json; charset=utf-8")
      .end(Json.encodePrettily(response));
  }

  private void handleGetData(RoutingContext rc) {
    rc.response()
      .putHeader("Content-Type", "application/json; charset=utf-8")
      .end(Json.encodePrettily(data));
  }

  private void pruneStaleWorkers() {
    vertx.setPeriodic(5000, timer -> {
      LOGGER.debug("{0}: Pruning stale workers", ID);

      Map<String, WorkerUpdate> workers = data.getWorkers();
      long now = System.currentTimeMillis();

      for (Map.Entry<String, WorkerUpdate> entry : workers.entrySet()) {
        String workerId = entry.getKey();
        WorkerUpdate update = entry.getValue();

        if (now - update.getTimestamp() > 10 * 1000) {
          workers.remove(workerId);
          LOGGER.info("{0}: Pruned {1}", ID, workerId);
        }
      }
    });
  }
}
