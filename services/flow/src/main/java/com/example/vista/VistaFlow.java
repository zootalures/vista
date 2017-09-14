package com.example.vista;

import com.example.vista.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fnproject.fn.api.FnConfiguration;
import com.fnproject.fn.api.Headers;
import com.fnproject.fn.api.RuntimeContext;
import com.fnproject.fn.api.flow.*;
import com.github.seratch.jslack.Slack;
import com.github.seratch.jslack.shortcut.Shortcut;
import com.github.seratch.jslack.shortcut.model.ApiToken;
import com.github.seratch.jslack.shortcut.model.ChannelName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class VistaFlow {
    private static final Logger log = LoggerFactory.getLogger(VistaFlow.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
    }

    private static Shortcut slack;

    @FnConfiguration
    public static void configure(RuntimeContext ctx) {

        Slack slack = Slack.getInstance();
        ApiToken slackToken = ApiToken.of(ctx.getConfigurationByKey("SLACK_API_TOKEN").orElseThrow(() -> new RuntimeException("Missing pubnub subscribe key ")));

        VistaFlow.slack = slack.shortcut(slackToken);

    }


    public String handleRequest(ScrapeReq input) throws Exception {

        log.info("Got request {} {}", input.query, input.num);
        postToSlack(String.format("About to start scraping for images from  %s", input.query));


        fnStarted("scraper", "0");
        Flows.currentFlow()
                .invokeFunction("./scraper", HttpMethod.POST, Headers.emptyHeaders(), toJson(input))
                .whenComplete(fnComplete("scraper", "0"))
                .thenCompose((httpResp) -> {
                    ScrapeResp resp = fromJson(httpResp.getBodyAsBytes(), ScrapeResp.class);
                    log.info("Got  {} images", resp.result.size());
                    List<FlowFuture<?>> pendingTasks = new ArrayList<>();

                    resp.result.forEach(scrapeResult -> {
                        log.info("starting detection on {}", scrapeResult.image_url);

                        String id = scrapeResult.id;
                        fnStarted("detect-plates", id);


                        FlowFuture<HttpResponse> platesFuture = Flows.currentFlow().invokeFunction("./detect-plates", HttpMethod.POST, Headers.emptyHeaders(), toJson(new DetectPlateReq(scrapeResult.image_url, "us")))
                                .whenComplete(fnComplete("detect-plates", id));

                        FlowFuture<?> processFuture = platesFuture.thenCompose((platesHttpResp) -> {
                            DetectPlateResp plateResp = fromJson(platesHttpResp.getBodyAsBytes(), DetectPlateResp.class);
                            if (plateResp.got_plate) {
                                log.info("Got plate {} in {}", plateResp.plate, scrapeResult.image_url);

                                postToSlack("Found plate " + plateResp.plate);
                                AlertReq alertReq = new AlertReq(plateResp.plate, scrapeResult.image_url);
                                log.info("Starting alert {}", id);
                                Flow cur = Flows.currentFlow();
                                fnStarted("alert", id);
                                FlowFuture<?> f1 = cur.invokeFunction("./alert", HttpMethod.POST, Headers.emptyHeaders(), toJson(alertReq))
                                        .whenComplete(fnComplete("alert", id));

                                log.info("Starting draw {}", id);
                                fnStarted("draw", id);

                                FlowFuture<?> f2 = cur.invokeFunction("./draw", HttpMethod.POST, Headers.emptyHeaders(), toJson(new DrawReq(id, scrapeResult.image_url, plateResp.rectangles)))
                                        .whenComplete(fnComplete("draw", id));

                                return cur.allOf(f1, f2);
                            } else {
                                log.info("No plates in {}", scrapeResult.image_url);
                                return (FlowFuture) Flows.currentFlow().completedValue(null);
                            }

                        });
                        pendingTasks.add(processFuture);
                    });

                    return Flows.currentFlow().allOf(pendingTasks.toArray(new FlowFuture[pendingTasks.size()]));

                }).whenComplete((t, e) -> {
            postToSlack(String.format("Finished scraping"));

        });

        return "Scanning started ";
    }

    private static void postToSlack(String format) {
        try {
            slack.postAsBot(ChannelName.of("general"), format);
        } catch (Exception pne) {
            log.error("Error sending to slack", pne);
        }

    }


    private static void fnStarted(String type, String id) {
        log.info("Staring {}:{}", type, id);
      //  postToSlack(String.format("Starting fn %s : %s", type, id));

    }


    private static <T> Flows.SerBiConsumer<T, Throwable> fnComplete(String type, String id) {
        return (t, e) -> {
            log.info("Got result from fn {} {}", t, e);
          //  postToSlack(String.format("Completed fn %s : %s: error %s", type, id, e == null ? "No" : "Yes"));

        };
    }

    public static <T> T fromJson(byte[] data, Class<T> type) {
        try {
            return objectMapper.readValue(data, type);
        } catch (IOException e) {
            log.error("Failed to extract value to {} ", type, e);
            throw new RuntimeException(e);
        }
    }

    public static <T> byte[] toJson(T val) {
        try {
            return objectMapper.writeValueAsString(val).getBytes();
        } catch (IOException e) {
            log.error("Failed to wite {} to json ", val, e);
            throw new RuntimeException(e);
        }
    }


}
