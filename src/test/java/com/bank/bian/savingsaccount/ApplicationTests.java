package com.bank.bian.savingsaccount;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Boot + API smoke test against the real savings domain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTests {

    static final String CR = "/v1/savings-account-facility-fulfillment-arrangement";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    String url(String path) { return "http://localhost:" + port + path; }

    @Test
    void savingsJourney_depositAccrueCapitalize() {
        var opened = rest.postForEntity(url(CR + "/initiate"),
                Map.of("customerReference", "C-API-SA", "currency", "INR",
                        "interestRateBp", 100, "minBalanceMinor", 0),
                Map.class);
        assertThat(opened.getStatusCode().value()).isEqualTo(201);
        String id = (String) opened.getBody().get("accountId");

        rest.postForEntity(url(CR + "/" + id + "/payments/deposit"),
                Map.of("amountMinor", 365_000, "reference", "seed"), Map.class);
        rest.postForEntity(url(CR + "/" + id + "/interest/accrue"), null, Map.class);

        var capitalized = rest.postForEntity(url(CR + "/" + id + "/interest/capitalize"), null, Map.class);
        assertThat(capitalized.getStatusCode().value()).isEqualTo(201);
        assertThat(((Number) capitalized.getBody().get("amountMinor")).longValue()).isEqualTo(10);

        var balance = rest.getForObject(url(CR + "/" + id + "/balance"), Map.class);
        assertThat(((Number) balance.get("balanceMinor")).longValue()).isEqualTo(365_010);

        // savings has no overdraft: a withdrawal beyond the balance → 409
        var breach = rest.postForEntity(url(CR + "/" + id + "/payments/withdraw"),
                Map.of("amountMinor", 999_999, "reference", "too-much"), Map.class);
        assertThat(breach.getStatusCode().value()).isEqualTo(409);
        assertThat(breach.getBody().get("code")).isEqualTo("MIN_BALANCE_BREACH");
    }
}
