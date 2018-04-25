/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.connector.rest.swagger;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OAuthRefreshTokenProcessor implements Processor {

    /**
     * The number of milliseconds we try to refresh the access token before it
     * expires.
     */
    private static final long AHEAD_OF_TIME_REFRESH_MILIS = 60 * 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = LoggerFactory.getLogger(OAuthRefreshTokenProcessor.class);

    final String expiresInOverride = System.getenv().get("AUTHTOKEN_EXPIRES_IN_OVERRIDE");

    final AtomicReference<String> lastRefreshTokenTried = new AtomicReference<>(null);

    //Always refresh on (re)start
    final AtomicReference<Boolean> isFirstTime = new AtomicReference<>(Boolean.TRUE);

    protected final SwaggerConnectorComponent component;

    public OAuthRefreshTokenProcessor(final SwaggerConnectorComponent component) {
        this.component = component;
    }

    @Override
    public void process(final Exchange exchange) throws Exception {

        if ((isFirstTime.get() || component.getAccessTokenExpiresAt() - AHEAD_OF_TIME_REFRESH_MILIS < now())
            && component.getRefreshToken() != null) {
            tryToRefreshAccessToken();
            isFirstTime.set(Boolean.FALSE);
        }

    }

    CloseableHttpClient createHttpClient() {
        return HttpClientBuilder.create().build();
    }

    HttpUriRequest createHttpRequest() {
        final String tokenEndpoint = component.getTokenEndpoint();
        final RequestBuilder builder = RequestBuilder.post(tokenEndpoint);

        if (component.isAuthorizeUsingParameters()) {
            builder.addParameter("client_id", component.getClientId())//
                .addParameter("client_secret", component.getClientSecret());
        }

        builder.addParameter("refresh_token", component.getRefreshToken())//
            .addParameter("grant_type", "refresh_token");

        return builder.build();
    }

    long now() {
        return System.currentTimeMillis();
    }

    void processRefreshTokenResponse(final HttpEntity entity) throws IOException, JsonProcessingException {
        final JsonNode body = JSON.readTree(entity.getContent());

        final JsonNode accessToken = body.get("access_token");
        if (accessToken != null && !accessToken.isNull() && !accessToken.asText().isEmpty()) {
            component.setAccessToken(accessToken.asText());
            LOG.info("Successful access token refresh");

            Long expiresInSeconds = null;
            if (expiresInOverride != null) {
                expiresInSeconds = Long.valueOf(expiresInOverride);
            } else {
                final JsonNode expiresIn = body.get("expires_in");
                if (expiresIn != null && !expiresIn.isNull() && !expiresIn.asText().isEmpty()) {
                    expiresInSeconds = expiresIn.asLong();
                }
            }
            if (expiresInSeconds != null) {
                component.setAccessTokenExpiresAt(now() + expiresInSeconds * 1000);
            }

            final JsonNode refreshToken = body.get("refresh_token");
            if (refreshToken != null && !refreshToken.isNull() && !refreshToken.asText().isEmpty()) {
                component.setRefreshToken(refreshToken.asText());
            }
        }
    }

    void tryToRefreshAccessToken() {
        final String currentRefreshToken = component.getRefreshToken();
        lastRefreshTokenTried.getAndUpdate(last -> {
            if (last != null && last.equals(currentRefreshToken)) {
                return last;
            }

            return null;
        });

        if (!lastRefreshTokenTried.compareAndSet(null, currentRefreshToken)) {
            LOG.info("Already tried to refresh the access token with the current refresh token");
            return;
        }

        LOG.info("Trying to refresh the OAuth2 access token");

        try (final CloseableHttpClient client = createHttpClient()) {
            final HttpUriRequest request = createHttpRequest();

            try (final CloseableHttpResponse response = client.execute(request)) {
                final StatusLine statusLine = response.getStatusLine();
                if (statusLine.getStatusCode() != HttpStatus.SC_OK) {
                    final HttpEntity entity = response.getEntity();
                    LOG.error("Unable to refresh the access token, received status: `{}`, response: `{}`", statusLine,
                        EntityUtils.toString(entity));
                    return;
                }

                final HttpEntity entity = response.getEntity();
                processRefreshTokenResponse(entity);
            }
        } catch (final IOException e) {
            LOG.error("Unable to refresh the access token", e);
        }
    }
}
