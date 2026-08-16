package simulations;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.UUID;

public class RegistrationSimulation extends Simulation {

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl("http://localhost:8080")
        .acceptHeader("application/json")
        .contentTypeHeader("application/json")
        .header("Authorization", "Bearer perf-test-token");

    // QA-01: full registration lifecycle — create → submit → begin-examination → approve
    // 5 users ramped over 30 s; each gets a unique company name to avoid name-clash errors
    private final ScenarioBuilder fullRegistration = scenario("full-registration")
        .exec(session -> session.set("companyName", "Perf Co " + UUID.randomUUID()))
        .exec(http("create-application")
            .post("/api/v1/registration-applications")
            .body(StringBody("{}"))
            .check(status().is(201))
            .check(jsonPath("$.application-id").saveAs("applicationId")))
        .pause(Duration.ofMillis(200))
        .exec(http("submit-application")
            .post("/api/v1/registration-applications/#{applicationId}/submit")
            .body(StringBody(session ->
                "{" +
                "\"company-name\":\"" + session.getString("companyName") + "\"," +
                "\"proposed-directors\":[" +
                  "{\"id\":\"d-1\",\"name\":\"Jane Smith\",\"natural-person\":true,\"identity-verified\":true}," +
                  "{\"id\":\"d-2\",\"name\":\"Alice Jones\",\"natural-person\":true,\"identity-verified\":true}" +
                "]," +
                "\"registered-office-address\":{" +
                  "\"address-line-1\":\"1 Main St\",\"city\":\"Dublin\",\"country\":\"IE\"" +
                "}}"
            ))
            .check(status().is(200)))
        .pause(Duration.ofMillis(200))
        .exec(http("begin-examination")
            .post("/api/v1/registration-applications/#{applicationId}/begin-examination")
            .body(StringBody("{}"))
            .check(status().is(200)))
        .pause(Duration.ofMillis(200))
        .exec(http("approve-application")
            .post("/api/v1/registration-applications/#{applicationId}/approve")
            .body(StringBody("{}"))
            .check(status().is(200)));

    // QA-02: browse the public register — search by name fragment
    // 10 users ramped over 30 s
    private final ScenarioBuilder browseRegister = scenario("browse-register")
        .exec(http("search-register")
            .get("/api/v1/register?name=Perf")
            .check(status().is(200)));

    {
        setUp(
            fullRegistration.injectOpen(rampUsers(5).during(Duration.ofSeconds(30))),
            browseRegister.injectOpen(rampUsers(10).during(Duration.ofSeconds(30)))
        )
        .protocols(httpProtocol)
        .assertions(
            global().responseTime().percentile3().lt(1000),
            global().responseTime().percentile4().lt(2000),
            global().successfulRequests().percent().gt(99.0)
        );
    }
}
