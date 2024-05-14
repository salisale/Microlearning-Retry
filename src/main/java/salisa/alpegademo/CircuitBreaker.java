package salisa.alpegademo;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import salisa.alpegademo.exception.TemporaryFailureException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.text.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.Random;

public class CircuitBreaker {
    private static CircuitBreakerRegistry registry;

    public static void main(String[] args) {
        // Create a custom configuration for a CircuitBreaker
        var circuitBreakerConfig = CircuitBreakerConfig.custom()

                .slidingWindowType(SlidingWindowType.COUNT_BASED) // or TIME_BASED
                .slidingWindowSize(10) // last 10 calls - if TIME_BASED, window size = last 10 seconds

                // Configure Failure
                .failureRateThreshold(30) // trip open if > 30% fails
                .recordExceptions(TemporaryFailureException.class) // included exceptions
                .ignoreExceptions(ParseException.class, // excluded exceptions
                                  IllegalStateException.class,
                                  IOException.class) // if not explicitly included here, will consider a failure


                // Configure Slow call
                .slowCallRateThreshold(50) // trip open if > 50% is slow
                .slowCallDurationThreshold(Duration.ofSeconds(1)) // slow if > 1s

                // Configure Total Ban Duration
                .waitDurationInOpenState(Duration.ofSeconds(2)) // let's impose 2s of total ban

                // Configure Transition
                .permittedNumberOfCallsInHalfOpenState(10) // allow 10 calls in risky situation
                .build();

       registry = CircuitBreakerRegistry.of(circuitBreakerConfig);

       testGetCountriesWithCircuitBreaker();
    }

    public static void testGetCountriesWithCircuitBreaker() {
        var circuitBreaker = registry.circuitBreaker("CountryService");

        for (int i = 0; i < 20; i++) {
            try {
                var response = circuitBreaker.executeCheckedSupplier(CircuitBreaker::getCountries);
                System.out.println(response);
            } catch (Throwable t) {
                System.out.println(t);
            }
        }
    }

    public static CloseableHttpResponse getCountries() throws TemporaryFailureException, IOException {
            var reqConfig = RequestConfig.custom()
                .setConnectTimeout(2000)
                .setConnectionRequestTimeout(2000)
                .setSocketTimeout(2000).build();

        //************* let's throw exception 50% of the time **********************/
        if (new Random().nextInt(2) == 1) throw new TemporaryFailureException();
        //**************************************************************************/

        return httpGetHelper("https://restcountries.com/v3.1/all", reqConfig);

    }

    private static CloseableHttpResponse httpGetHelper(String URL, RequestConfig reqConfig)
                                                    throws TemporaryFailureException, IOException {
        final HttpGet request = new HttpGet(URL);
        try (CloseableHttpClient client = HttpClientBuilder.create().setDefaultRequestConfig(reqConfig).build()) {
            var response = client.execute(request);
            var statusCode = response.getStatusLine().getStatusCode();
            if (List.of(408, 429, 500, 502, 503, 504).contains(statusCode)) {
                throw new TemporaryFailureException();
            }
            return response;
        } catch (NoHttpResponseException | ConnectTimeoutException
                 | HttpTimeoutException | SocketTimeoutException e) {
            throw new TemporaryFailureException();
        }
    }



}