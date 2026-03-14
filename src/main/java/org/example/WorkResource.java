package org.example;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jakarta.annotation.PostConstruct;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/work")
@Tag(name = "Work", description = "API для нагрузочного тестирования с rate limiting")
public class WorkResource {

  static List<String> syncList = Collections.synchronizedList(new ArrayList<>());
  int apiLimit;
  int timeout;
  private final MeterRegistry meterRegistry;

  public WorkResource(
      MeterRegistry meterRegistry,
      @ConfigProperty(name = "app.api.limit") int apiLimit,
      @ConfigProperty(name = "app.api.timeout") int timeout) {
    this.meterRegistry = meterRegistry;
    this.apiLimit = apiLimit;
    this.timeout = timeout;
  }

  @PostConstruct
  void registerMetrics() {
    Gauge.builder("work_active_requests", syncList, List::size)
        .description("Number of active work requests in progress")
        .register(meterRegistry);
  }

  @GET
  @Produces(MediaType.TEXT_PLAIN)
  @Operation(
      summary = "Выполнить работу",
      description =
          "Обрабатывает запрос с задержкой. При превышении лимита запросов возвращает 429")
  @APIResponse(responseCode = "429", description = "Превышен лимит запросов (Too Many Requests)")
  @APIResponse(responseCode = "200", description = "Успешное выполнение задачи ")
  public Uni<Response> doWork() {
    if (syncList.size() > this.apiLimit) {
      return Uni.createFrom()
          .item(Response.status(429).entity("Too many requests - rate limit exceeded\n").build());
    }
    syncList.add("0");
    return Uni.createFrom()
        .item("OK\n")
        .onItem()
        .delayIt()
        .by(Duration.ofMillis(timeout))
        .onItem()
        .transform(
            result -> {
              syncList.removeFirst();
              return Response.ok(result).build();
            });
  }

  @GET
  @Path("/status")
  @Produces(MediaType.TEXT_PLAIN)
  @Operation(
      summary = "Получить статус",
      description = "Возвращает текущее количество активных запросов")
  public Uni<Integer> status() {
    return Uni.createFrom().item(syncList.size());
  }
}
